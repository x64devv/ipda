package ipda.data

import ipda.config.ConfigLoader
import ipda.ctrader.TrendbarMapper
import ipda.ctrader.TrendbarMapper.RawBar
import ipda.model.Candle
import ipda.model.Timeframe
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/**
 * Checksummed, versioned candle snapshots in SQLite (§8.4).
 *
 * Reproducibility rules:
 *  - Prices are stored as exact 1/100000 integer units — floating point only
 *    appears at load time, deterministically.
 *  - The snapshot id IS a content hash: "snap-" + first 12 hex chars of the
 *    SHA-256 over the canonical row serialization (sorted by symbol,
 *    timeframe, open time). Same data ⇒ same id, byte-identical re-fetch is
 *    detectable, and every backtest run records the id it ran against.
 *  - Snapshots are immutable once written; writing identical content is a
 *    no-op that returns the existing id.
 */
class SnapshotStore(private val dbPath: Path) : AutoCloseable {

    private val conn: Connection

    init {
        dbPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        conn = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        conn.createStatement().use { st ->
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS snapshot(
                    id TEXT PRIMARY KEY,
                    created_utc TEXT NOT NULL,
                    source TEXT NOT NULL,
                    checksum TEXT NOT NULL,
                    bar_count INTEGER NOT NULL
                )"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS candle(
                    snapshot_id TEXT NOT NULL,
                    symbol TEXT NOT NULL,
                    timeframe TEXT NOT NULL,
                    open_time_ms INTEGER NOT NULL,
                    open_1e5 INTEGER NOT NULL,
                    high_1e5 INTEGER NOT NULL,
                    low_1e5 INTEGER NOT NULL,
                    close_1e5 INTEGER NOT NULL,
                    volume INTEGER NOT NULL,
                    PRIMARY KEY(snapshot_id, symbol, timeframe, open_time_ms)
                )"""
            )
        }
    }

    data class SnapshotInfo(val id: String, val checksum: String, val barCount: Int, val alreadyExisted: Boolean)

    /**
     * Write one immutable snapshot. [series] maps (symbol, timeframe) to its
     * bars; rows are canonically ordered before hashing so insertion order
     * never affects identity.
     */
    fun writeSnapshot(
        source: String,
        series: Map<Pair<String, Timeframe>, List<RawBar>>,
        createdUtc: Instant = Instant.now(),
    ): SnapshotInfo {
        val rows = series.entries
            .flatMap { (key, bars) ->
                val (symbol, tf) = key
                bars.map { Row(symbol, tf.name, it) }
            }
            .sortedWith(compareBy({ it.symbol }, { it.tf }, { it.bar.openTimeMs }))

        val checksum = ConfigLoader.sha256Hex(
            rows.joinToString("\n") { r ->
                "${r.symbol}|${r.tf}|${r.bar.openTimeMs}|${r.bar.open1e5}|${r.bar.high1e5}|${r.bar.low1e5}|${r.bar.close1e5}|${r.bar.volume}"
            }.toByteArray(Charsets.UTF_8)
        )
        val id = "snap-" + checksum.take(12)

        val exists = conn.prepareStatement("SELECT 1 FROM snapshot WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().next()
        }
        if (exists) return SnapshotInfo(id, checksum, rows.size, alreadyExisted = true)

        conn.autoCommit = false
        try {
            conn.prepareStatement(
                "INSERT INTO snapshot(id, created_utc, source, checksum, bar_count) VALUES(?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, createdUtc.toString())
                ps.setString(3, source)
                ps.setString(4, checksum)
                ps.setInt(5, rows.size)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO candle(snapshot_id, symbol, timeframe, open_time_ms, open_1e5, high_1e5, low_1e5, close_1e5, volume) VALUES(?,?,?,?,?,?,?,?,?)"
            ).use { ps ->
                for (r in rows) {
                    ps.setString(1, id)
                    ps.setString(2, r.symbol)
                    ps.setString(3, r.tf)
                    ps.setLong(4, r.bar.openTimeMs)
                    ps.setLong(5, r.bar.open1e5)
                    ps.setLong(6, r.bar.high1e5)
                    ps.setLong(7, r.bar.low1e5)
                    ps.setLong(8, r.bar.close1e5)
                    ps.setLong(9, r.bar.volume)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
        return SnapshotInfo(id, checksum, rows.size, alreadyExisted = false)
    }

    /** Recompute the checksum of a stored snapshot and compare — corruption check. */
    fun verify(snapshotId: String): Boolean {
        val stored = conn.prepareStatement("SELECT checksum FROM snapshot WHERE id = ?").use { ps ->
            ps.setString(1, snapshotId)
            ps.executeQuery().let { rs -> if (rs.next()) rs.getString(1) else null }
        } ?: return false
        val sb = StringBuilder()
        var first = true
        conn.prepareStatement(
            "SELECT symbol, timeframe, open_time_ms, open_1e5, high_1e5, low_1e5, close_1e5, volume FROM candle WHERE snapshot_id = ? ORDER BY symbol, timeframe, open_time_ms"
        ).use { ps ->
            ps.setString(1, snapshotId)
            val rs = ps.executeQuery()
            while (rs.next()) {
                if (!first) sb.append('\n')
                first = false
                sb.append(rs.getString(1)).append('|').append(rs.getString(2)).append('|')
                    .append(rs.getLong(3)).append('|').append(rs.getLong(4)).append('|')
                    .append(rs.getLong(5)).append('|').append(rs.getLong(6)).append('|')
                    .append(rs.getLong(7)).append('|').append(rs.getLong(8))
            }
        }
        return ConfigLoader.sha256Hex(sb.toString().toByteArray(Charsets.UTF_8)) == stored
    }

    fun loadCandles(snapshotId: String, symbol: String, tf: Timeframe): List<Candle> {
        val out = ArrayList<Candle>()
        conn.prepareStatement(
            "SELECT open_time_ms, open_1e5, high_1e5, low_1e5, close_1e5, volume FROM candle WHERE snapshot_id = ? AND symbol = ? AND timeframe = ? ORDER BY open_time_ms"
        ).use { ps ->
            ps.setString(1, snapshotId)
            ps.setString(2, symbol)
            ps.setString(3, tf.name)
            val rs = ps.executeQuery()
            while (rs.next()) {
                out.add(
                    TrendbarMapper.toCandle(
                        RawBar(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)),
                        symbol, tf,
                    )
                )
            }
        }
        return out
    }

    fun listSnapshots(): List<Triple<String, String, Int>> {
        val out = ArrayList<Triple<String, String, Int>>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT id, created_utc, bar_count FROM snapshot ORDER BY created_utc")
            while (rs.next()) out.add(Triple(rs.getString(1), rs.getString(2), rs.getInt(3)))
        }
        return out
    }

    override fun close() = conn.close()

    private data class Row(val symbol: String, val tf: String, val bar: RawBar)
}
