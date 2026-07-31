package ipda.ctrader

import com.xtrader.protocol.openapi.v2.model.ProtoOATrendbar
import com.xtrader.protocol.openapi.v2.model.ProtoOATrendbarPeriod
import ipda.model.Timeframe
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrendbarMapperTest {

    // 2026-01-05T10:00:00Z in minutes = 1767607200s / 60
    private val openMinutes = (1767607200L / 60).toInt()

    private fun bar(): ProtoOATrendbar = ProtoOATrendbar.newBuilder()
        .setVolume(1234)
        .setPeriod(ProtoOATrendbarPeriod.H1)
        .setLow(109_500)          // 1.09500
        .setDeltaOpen(120)        // open  = 1.09620
        .setDeltaHigh(400)        // high  = 1.09900
        .setDeltaClose(250)       // close = 1.09750
        .setUtcTimestampInMinutes(openMinutes)
        .build()

    @Test
    fun `wire deltas and 1e5 price units map to correct candle`() {
        val raw = TrendbarMapper.toRaw(bar())
        assertEquals(1767607200_000L, raw.openTimeMs)
        assertEquals(109_620, raw.open1e5)
        assertEquals(109_900, raw.high1e5)
        assertEquals(109_500, raw.low1e5)
        assertEquals(109_750, raw.close1e5)
        assertEquals(1234, raw.volume)

        val candle = TrendbarMapper.toCandle(raw, "EURUSD", Timeframe.H1)
        assertEquals(Instant.parse("2026-01-05T10:00:00Z"), candle.openTime)
        assertEquals(1.09620, candle.open, 1e-9)
        assertEquals(1.09900, candle.high, 1e-9)
        assertEquals(1.09500, candle.low, 1e-9)
        assertEquals(1.09750, candle.close, 1e-9)
        assertEquals(1234, candle.volume)
    }

    @Test
    fun `completed-bar rule - close time must not be in the future`() {
        val raw = TrendbarMapper.toRaw(bar()) // H1 bar opening 10:00
        // At 10:59:59 the bar is still forming.
        assertFalse(TrendbarMapper.isComplete(raw, Timeframe.H1, Instant.parse("2026-01-05T10:59:59Z")))
        // At exactly 11:00:00 it is complete.
        assertTrue(TrendbarMapper.isComplete(raw, Timeframe.H1, Instant.parse("2026-01-05T11:00:00Z")))
        // Same open time interpreted as M15 completes at 10:15.
        assertTrue(TrendbarMapper.isComplete(raw, Timeframe.M15, Instant.parse("2026-01-05T10:15:00Z")))
    }

    @Test
    fun `timeframe to wire period mapping`() {
        assertEquals(ProtoOATrendbarPeriod.M15, TrendbarMapper.toPeriod(Timeframe.M15))
        assertEquals(ProtoOATrendbarPeriod.H1, TrendbarMapper.toPeriod(Timeframe.H1))
    }
}
