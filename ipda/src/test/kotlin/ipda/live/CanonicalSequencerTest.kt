package ipda.live

import ipda.model.Candle
import ipda.model.Timeframe
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanonicalSequencerTest {

    private var now = 0L
    private val clock = { now }

    private fun candle(symbol: String, tf: Timeframe, closeTime: Instant): Candle {
        val open = closeTime.minus(tf.duration)
        return Candle(symbol, tf, open, 1.0, 1.2, 0.9, 1.1)
    }

    private val close = Instant.parse("2026-07-29T14:00:00Z")

    @Test
    fun `shared close flushes together in canonical order despite jitter`() {
        val seq = CanonicalSequencer(settleMs = 2000, clock = clock)
        // Wire jitter: M15s and one H1 arrive scattered, GBPUSD H1 last.
        now = 0; assertTrue(seq.add(candle("EURUSD", Timeframe.M15, close)).isEmpty())
        now = 300; assertTrue(seq.add(candle("GBPUSD", Timeframe.M15, close)).isEmpty())
        now = 700; assertTrue(seq.add(candle("EURUSD", Timeframe.H1, close)).isEmpty())
        now = 1200; assertTrue(seq.add(candle("GBPUSD", Timeframe.H1, close)).isEmpty())

        now = 1999
        assertTrue(seq.drainReady().isEmpty())
        now = 2000
        val out = seq.drainReady()
        assertEquals(
            listOf(
                "EURUSD" to Timeframe.H1, "GBPUSD" to Timeframe.H1,
                "EURUSD" to Timeframe.M15, "GBPUSD" to Timeframe.M15,
            ),
            out.map { it.symbol to it.timeframe },
        )
    }

    @Test
    fun `younger bucket never flushes past an older one still settling`() {
        val seq = CanonicalSequencer(settleMs = 2000, clock = clock)
        val later = close.plusSeconds(3600)
        now = 0; seq.add(candle("EURUSD", Timeframe.H1, later))     // deadline 2000
        now = 1500; seq.add(candle("GBPUSD", Timeframe.H1, close))  // older close, deadline 3500
        now = 2500
        assertTrue(seq.drainReady().isEmpty()) // older bucket not ready → younger held too
        now = 3500
        val out = seq.drainReady()
        assertEquals(listOf(close, later), out.map { it.closeTime })
    }

    @Test
    fun `late straggler is emitted immediately and reported`() {
        var late: Candle? = null
        val seq = CanonicalSequencer(settleMs = 1000, clock = clock, onLateCandle = { late = it })
        now = 0; seq.add(candle("EURUSD", Timeframe.H1, close))
        now = 1000; assertEquals(1, seq.drainReady().size)
        val straggler = candle("GBPUSD", Timeframe.H1, close)
        val immediate = seq.add(straggler)
        assertEquals(listOf(straggler), immediate)
        assertEquals(straggler, late)
        assertEquals(0, seq.pendingCount)
    }
}
