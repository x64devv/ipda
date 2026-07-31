package ipda.data

import ipda.ctrader.TrendbarMapper.RawBar
import ipda.model.Timeframe
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SnapshotStoreTest {

    private fun sampleBars(offset: Long = 0): List<RawBar> = (0 until 5L).map { i ->
        RawBar(
            openTimeMs = 1767607200_000L + i * 3_600_000L,
            open1e5 = 109_500 + i * 10 + offset,
            high1e5 = 109_900 + i * 10 + offset,
            low1e5 = 109_400 + i * 10 + offset,
            close1e5 = 109_700 + i * 10 + offset,
            volume = 100 + i,
        )
    }

    @Test
    fun `snapshot id is a content hash - same data same id across stores and insertion orders`() {
        val dir = createTempDirectory("snap")
        val seriesA = linkedMapOf(
            ("EURUSD" to Timeframe.H1) to sampleBars(),
            ("GBPUSD" to Timeframe.H1) to sampleBars(offset = 5000),
        )
        // Same content, reversed insertion order.
        val seriesB = linkedMapOf(
            ("GBPUSD" to Timeframe.H1) to sampleBars(offset = 5000),
            ("EURUSD" to Timeframe.H1) to sampleBars(),
        )
        val t = Instant.parse("2026-07-26T12:00:00Z")

        val infoA = SnapshotStore(dir.resolve("a.db")).use { it.writeSnapshot("test", seriesA, t) }
        val infoB = SnapshotStore(dir.resolve("b.db")).use { it.writeSnapshot("test", seriesB, t) }

        assertEquals(infoA.id, infoB.id)
        assertEquals(infoA.checksum, infoB.checksum)
        assertEquals(10, infoA.barCount)
    }

    @Test
    fun `different data yields different id - and rewrite of identical content is a no-op`() {
        val dir = createTempDirectory("snap")
        SnapshotStore(dir.resolve("s.db")).use { store ->
            val info1 = store.writeSnapshot("test", mapOf(("EURUSD" to Timeframe.H1) to sampleBars()))
            val again = store.writeSnapshot("test", mapOf(("EURUSD" to Timeframe.H1) to sampleBars()))
            assertTrue(again.alreadyExisted)
            assertEquals(info1.id, again.id)

            val info2 = store.writeSnapshot("test", mapOf(("EURUSD" to Timeframe.H1) to sampleBars(offset = 1)))
            assertNotEquals(info1.id, info2.id)
            assertEquals(2, store.listSnapshots().size)
        }
    }

    @Test
    fun `round-trip - loaded candles match written bars exactly and verify passes`() {
        val dir = createTempDirectory("snap")
        SnapshotStore(dir.resolve("s.db")).use { store ->
            val bars = sampleBars()
            val info = store.writeSnapshot("test", mapOf(("EURUSD" to Timeframe.H1) to bars))
            assertTrue(store.verify(info.id))

            val candles = store.loadCandles(info.id, "EURUSD", Timeframe.H1)
            assertEquals(5, candles.size)
            assertEquals(Instant.ofEpochMilli(bars[0].openTimeMs), candles[0].openTime)
            assertEquals(bars[0].open1e5.toDouble() / 100_000, candles[0].open, 0.0)
            assertEquals(bars[4].close1e5.toDouble() / 100_000, candles[4].close, 0.0)
            assertEquals(bars[2].volume, candles[2].volume)
            // Empty result for a series that isn't in the snapshot.
            assertEquals(0, store.loadCandles(info.id, "GBPUSD", Timeframe.H1).size)
        }
    }
}
