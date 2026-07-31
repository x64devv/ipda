package ipda.tools

import ipda.config.ConfigLoader
import ipda.ctrader.TrendbarMapper.RawBar
import ipda.data.SnapshotStore
import ipda.model.Timeframe
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * SYNTHETIC snapshot generator — pipeline dry-run only.
 *
 *   ./gradlew synth --args="--days 180 --seed 42"
 *
 * Writes a deterministic (seeded) random-walk H1 series with injected
 * displacement-like bursts, plus an M15 series decomposed from each H1 candle
 * (same OHLC envelope), for the configured instruments. Snapshot source is
 * marked "synthetic" — these bars mean NOTHING about markets; they exist so
 * the fetch→snapshot→backtest plumbing can be exercised before the cTrader
 * token lands. Never mix synthetic and real snapshots in an analysis.
 */
fun main(args: Array<String>) {
    val days = argValue(args, "--days")?.toInt() ?: 180
    val seed = argValue(args, "--seed")?.toLong() ?: 42L
    val dbPath = Path.of(argValue(args, "--db") ?: "data/snapshots.db")

    val cfg = ConfigLoader.load(Path.of("config/ipda-config.json")).config
    val start = Instant.parse("2026-01-05T00:00:00Z")
    val series = LinkedHashMap<Pair<String, Timeframe>, List<RawBar>>()

    for ((s, instrument) in cfg.instruments.withIndex()) {
        val rng = Random(seed + s)
        val h1 = generateH1(rng, start, days * 24, base1e5 = if (s == 0) 110_000L else 127_000L)
        series[instrument to Timeframe.H1] = h1
        series[instrument to Timeframe.M15] = decomposeM15(rng, h1)
    }

    SnapshotStore(dbPath).use { store ->
        val info = store.writeSnapshot(source = "synthetic:seed=$seed,days=$days", series = series)
        println("SYNTHETIC snapshot ${if (info.alreadyExisted) "(already existed)" else "written"}:")
        println("  id:       ${info.id}")
        println("  checksum: ${info.checksum}")
        println("  bars:     ${info.barCount}")
        println("  db:       ${dbPath.toAbsolutePath()}")
        println()
        println("Run the baseline against it with:")
        println("  ./gradlew backtest --args=\"--snapshot ${info.id}\"")
    }
}

private fun generateH1(rng: Random, start: Instant, bars: Int, base1e5: Long): List<RawBar> {
    val out = ArrayList<RawBar>(bars)
    var price = base1e5
    var t = start
    var burstLeft = 0
    var burstDir = 0
    for (i in 0 until bars) {
        // Skip weekends (Sat/Sun UTC) like an FX feed would.
        while (t.atZoneUtc().dayOfWeek.value >= 6) t += Duration.ofHours(1)

        if (burstLeft == 0 && rng.nextDouble() < 0.02) { // occasional strong directional burst
            burstLeft = 2 + rng.nextInt(3)
            burstDir = if (rng.nextBoolean()) 1 else -1
        }
        val drift = if (burstLeft > 0) burstDir * (25 + rng.nextInt(20)) else rng.nextInt(-8, 9)
        if (burstLeft > 0) burstLeft--

        val open = price
        val close = open + drift + rng.nextInt(-3, 4)
        val wiggle = 3 + rng.nextInt(6)
        val high = max(open, close) + wiggle
        val low = min(open, close) - wiggle
        out.add(RawBar(t.toEpochMilli(), open, high, low, close, volume = (100 + rng.nextInt(900)).toLong()))
        price = close
        t += Duration.ofHours(1)
    }
    return out
}

/** Split each H1 bar into 4 M15 bars sharing its OHLC envelope exactly. */
private fun decomposeM15(rng: Random, h1: List<RawBar>): List<RawBar> {
    val out = ArrayList<RawBar>(h1.size * 4)
    for (bar in h1) {
        val opens = LongArray(4)
        val closes = LongArray(4)
        opens[0] = bar.open1e5
        for (k in 0 until 3) {
            val f = (k + 1) / 4.0
            val mid = bar.open1e5 + ((bar.close1e5 - bar.open1e5) * f).roundToLong()
            closes[k] = (mid + rng.nextInt(-4, 5)).coerceIn(bar.low1e5, bar.high1e5)
            opens[k + 1] = closes[k]
        }
        closes[3] = bar.close1e5
        val hiSlot = rng.nextInt(4)
        val loSlot = (hiSlot + 1 + rng.nextInt(3)) % 4
        for (k in 0 until 4) {
            var hi = max(opens[k], closes[k])
            var lo = min(opens[k], closes[k])
            if (k == hiSlot) hi = bar.high1e5
            if (k == loSlot) lo = bar.low1e5
            out.add(
                RawBar(
                    openTimeMs = bar.openTimeMs + k * 15 * 60_000L,
                    open1e5 = opens[k], high1e5 = hi, low1e5 = lo, close1e5 = closes[k],
                    volume = bar.volume / 4,
                )
            )
        }
    }
    return out
}

private fun Instant.atZoneUtc() = java.time.ZonedDateTime.ofInstant(this, java.time.ZoneOffset.UTC)

private fun argValue(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}
