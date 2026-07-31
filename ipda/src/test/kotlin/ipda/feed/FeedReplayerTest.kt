package ipda.feed

import ipda.model.Candle
import ipda.model.Timeframe
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedReplayerTest {

    private fun candle(symbol: String, tf: Timeframe, openIso: String): Candle =
        Candle(symbol, tf, Instant.parse(openIso), 1.0, 1.0, 1.0, 1.0)

    @Test
    fun `HTF first at simultaneous closes - H1 before its four M15 children's last close`() {
        val h1 = candle("EURUSD", Timeframe.H1, "2026-01-05T12:00:00Z")      // closes 13:00
        val m15a = candle("EURUSD", Timeframe.M15, "2026-01-05T12:30:00Z")   // closes 12:45
        val m15b = candle("EURUSD", Timeframe.M15, "2026-01-05T12:45:00Z")   // closes 13:00 — ties with H1
        val replayer = FeedReplayer(
            mapOf(
                ("EURUSD" to Timeframe.M15) to listOf(m15a, m15b),
                ("EURUSD" to Timeframe.H1) to listOf(h1),
            )
        )
        val out = ArrayList<Candle>()
        replayer.run { out.add(it) }

        assertEquals(listOf(m15a, h1, m15b), out) // 12:45 close first; at 13:00 H1 precedes M15
    }

    @Test
    fun `same timeframe tie breaks by symbol ascending`() {
        val eu = candle("EURUSD", Timeframe.H1, "2026-01-05T12:00:00Z")
        val gu = candle("GBPUSD", Timeframe.H1, "2026-01-05T12:00:00Z")
        val replayer = FeedReplayer(
            mapOf(
                ("GBPUSD" to Timeframe.H1) to listOf(gu),
                ("EURUSD" to Timeframe.H1) to listOf(eu),
            )
        )
        val out = ArrayList<Candle>()
        replayer.run { out.add(it) }
        assertEquals(listOf(eu, gu), out)
    }

    @Test
    fun `stream is globally ordered by close time`() {
        val series = mapOf(
            ("EURUSD" to Timeframe.H1) to (0..5).map { candle("EURUSD", Timeframe.H1, "2026-01-05T0$it:00:00Z") },
            ("EURUSD" to Timeframe.M15) to (0..3).map { candle("EURUSD", Timeframe.M15, "2026-01-05T02:${"%02d".format(it * 15)}:00Z") },
        )
        val out = ArrayList<Candle>()
        FeedReplayer(series).run { out.add(it) }
        assertEquals(10, out.size)
        assertTrue(out.zipWithNext().all { (a, b) -> !a.closeTime.isAfter(b.closeTime) })
    }
}
