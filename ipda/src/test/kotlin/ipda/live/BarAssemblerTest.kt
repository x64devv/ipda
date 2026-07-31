package ipda.live

import ipda.ctrader.TrendbarMapper
import ipda.model.Timeframe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BarAssemblerTest {

    private val h1Ms = Timeframe.H1.duration.toMillis()
    private val t0 = 1_753_776_000_000L // aligned hour

    private fun raw(openMs: Long, low: Long = 110_000, dOpen: Long = 10, dHigh: Long = 50, dClose: Long = 30) =
        TrendbarMapper.RawBar(
            openTimeMs = openMs,
            open1e5 = low + dOpen,
            high1e5 = low + dHigh,
            low1e5 = low,
            close1e5 = low + dClose,
            volume = 1,
        )

    @Test
    fun `roll completes the previous bar with its last seen state`() {
        val a = BarAssembler("EURUSD", Timeframe.H1)
        assertNull(a.onUpdate(raw(t0, dClose = 20)))
        assertNull(a.onUpdate(raw(t0, dClose = 40))) // forming bar updates in place
        val done = a.onUpdate(raw(t0 + h1Ms))        // roll → previous bar complete
        assertEquals(t0, done!!.openTime.toEpochMilli())
        assertEquals((110_000 + 40) / 100_000.0, done.close) // last state won
        assertEquals("EURUSD", done.symbol)
    }

    @Test
    fun `grace completes the forming bar without a roll tick`() {
        val a = BarAssembler("EURUSD", Timeframe.H1)
        a.onUpdate(raw(t0))
        val close = t0 + h1Ms
        assertNull(a.flushDue(nowMs = close + 9_999, graceMs = 10_000))
        val done = a.flushDue(nowMs = close + 10_000, graceMs = 10_000)
        assertEquals(t0, done!!.openTime.toEpochMilli())
        // A late tick for the completed bar is a stale update — dropped.
        assertNull(a.onUpdate(raw(t0, dClose = 45)))
        // The next bar forms normally.
        assertNull(a.onUpdate(raw(t0 + h1Ms)))
        assertEquals(t0 + h1Ms, a.formingOpenMs)
    }

    @Test
    fun `updates at or before the completed watermark are ignored`() {
        val a = BarAssembler("EURUSD", Timeframe.H1)
        a.markCompletedThrough(t0)
        assertNull(a.onUpdate(raw(t0)))            // already emitted by catch-up
        assertNull(a.onUpdate(raw(t0 - h1Ms)))     // older still
        assertNull(a.onUpdate(raw(t0 + h1Ms)))     // new forming bar
        assertEquals(t0 + h1Ms, a.formingOpenMs)
    }

    @Test
    fun `out of order older tick is dropped without completing anything`() {
        val a = BarAssembler("GBPUSD", Timeframe.M15)
        val m15 = Timeframe.M15.duration.toMillis()
        assertNull(a.onUpdate(raw(t0 + m15)))
        assertNull(a.onUpdate(raw(t0))) // older than forming — dropped
        assertEquals(t0 + m15, a.formingOpenMs)
    }
}
