package ipda.broker

import ipda.detect.TestCandles.h1
import ipda.model.Candle
import ipda.model.Timeframe
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Limit-entry conventions (M15 refinement, settled 29 Jul 2026):
 * fill AT the limit never better; openTime >= decisionTime eligibility;
 * expiry in eligible candles; cancel when a candle opens at/beyond the stop
 * while unfilled; fill-then-stop-first on the entry candle.
 */
class SimBrokerLimitTest {

    private fun m15(index: Int, open: Double, high: Double, low: Double, close: Double): Candle =
        Candle(
            symbol = "EURUSD", timeframe = Timeframe.M15,
            openTime = Instant.parse("2026-01-05T10:00:00Z").plusSeconds(900L * index),
            open = open, high = high, low = low, close = close,
        )

    private fun broker() = SimBroker(spreadBySymbol = mapOf("EURUSD" to 0.00007))

    /** BUY limit 1.1950, stop 1.1930, tp 1.1990, decided at 10:00. */
    private fun buyLimit(expiry: Int? = 8) = OrderIntent(
        symbol = "EURUSD", side = Side.BUY, volumeLots = 1.0,
        stopLoss = 1.1930, takeProfit = 1.1990,
        decisionTime = Instant.parse("2026-01-05T10:00:00Z"),
        entryType = EntryType.LIMIT, limitPrice = 1.1950, expiryCandles = expiry,
    )

    @Test
    fun `limit fills at the limit price when a candle trades through it`() {
        val b = broker()
        assertTrue(b.submit(buyLimit()).accepted)
        b.onCandle(m15(0, 1.1970, 1.1975, 1.1960, 1.1965)) // no touch
        b.onCandle(m15(1, 1.1965, 1.1968, 1.1945, 1.1955)) // low 1.1945 <= 1.1950 -> fill
        assertTrue(b.hasExposure("EURUSD"))
        b.onCandle(m15(2, 1.1955, 1.1992, 1.1954, 1.1988)) // high >= tp -> TARGET
        val t = b.closedTrades.single()
        assertEquals(1.1950, t.entryPrice) // AT the limit
        assertEquals(m15(1, 1.0, 1.0, 1.0, 1.0).openTime, t.entryTime)
        assertEquals(SimBroker.ExitReason.TARGET, t.reason)
    }

    @Test
    fun `gap through the limit still fills AT the limit - never better`() {
        val b = broker()
        assertTrue(b.submit(buyLimit()).accepted)
        // Opens at 1.1940, below the 1.1950 limit (but above the 1.1930 stop).
        b.onCandle(m15(0, 1.1940, 1.1955, 1.1938, 1.1952))
        assertTrue(b.hasExposure("EURUSD"))
        b.onCandle(m15(1, 1.1952, 1.1992, 1.1950, 1.1990))
        assertEquals(1.1950, b.closedTrades.single().entryPrice) // not 1.1940
    }

    @Test
    fun `candle opening at or beyond the stop cancels the unfilled limit`() {
        val b = broker()
        assertTrue(b.submit(buyLimit()).accepted)
        b.onCandle(m15(0, 1.1925, 1.1955, 1.1920, 1.1950)) // open 1.1925 <= stop 1.1930
        assertFalse(b.hasExposure("EURUSD"))
        val c = b.cancelledOrders.single()
        assertEquals(SimBroker.CancelReason.INVALIDATED, c.reason)
        assertEquals(0, b.closedTrades.size)
    }

    @Test
    fun `fill-then-stop-first when the entry candle reaches the stop`() {
        val b = broker()
        assertTrue(b.submit(buyLimit()).accepted)
        // Opens above the stop, falls through limit AND stop in one candle.
        b.onCandle(m15(0, 1.1960, 1.1962, 1.1925, 1.1928))
        val t = b.closedTrades.single()
        assertEquals(1.1950, t.entryPrice)
        assertEquals(SimBroker.ExitReason.STOP, t.reason)
        assertEquals(1.1930, t.exitPrice) // stop level, not the worse close
        assertTrue(t.rMultiple < -0.9)
    }

    @Test
    fun `unfilled limit expires after N eligible candles`() {
        val b = broker()
        assertTrue(b.submit(buyLimit(expiry = 3)).accepted)
        b.onCandle(m15(0, 1.1970, 1.1975, 1.1960, 1.1965))
        b.onCandle(m15(1, 1.1965, 1.1972, 1.1958, 1.1970))
        assertTrue(b.cancelledOrders.isEmpty())
        b.onCandle(m15(2, 1.1970, 1.1980, 1.1962, 1.1978)) // 3rd eligible no-touch candle
        val c = b.cancelledOrders.single()
        assertEquals(SimBroker.CancelReason.EXPIRED, c.reason)
        assertEquals(3, c.candlesLived)
        assertFalse(b.hasExposure("EURUSD"))
    }

    @Test
    fun `lookahead guard - candles opening before the decision cannot fill or age the order`() {
        val b = broker()
        assertTrue(b.submit(buyLimit(expiry = 2)).accepted)
        // Candle opening BEFORE decision time (09:45) trades through the limit — must be ignored.
        val early = Candle(
            "EURUSD", Timeframe.M15, Instant.parse("2026-01-05T09:45:00Z"),
            1.1955, 1.1958, 1.1940, 1.1948,
        )
        b.onCandle(early)
        assertTrue(b.hasExposure("EURUSD"))       // still pending
        assertTrue(b.cancelledOrders.isEmpty())   // not aged either
        b.onCandle(m15(0, 1.1965, 1.1968, 1.1945, 1.1955)) // eligible -> fill
        assertEquals(0, b.closedTrades.size)
        assertTrue(b.hasExposure("EURUSD"))
    }

    @Test
    fun `sell limit is the mirror image`() {
        val b = broker()
        val intent = OrderIntent(
            symbol = "EURUSD", side = Side.SELL, volumeLots = 1.0,
            stopLoss = 1.2000, takeProfit = 1.1900,
            decisionTime = Instant.parse("2026-01-05T10:00:00Z"),
            entryType = EntryType.LIMIT, limitPrice = 1.1970, expiryCandles = 8,
        )
        assertTrue(b.submit(intent).accepted)
        b.onCandle(m15(0, 1.1950, 1.1975, 1.1945, 1.1960)) // high >= 1.1970 -> fill
        b.onCandle(m15(1, 1.1960, 1.1965, 1.1895, 1.1900)) // low <= tp -> TARGET
        val t = b.closedTrades.single()
        assertEquals(1.1970, t.entryPrice)
        assertEquals(SimBroker.ExitReason.TARGET, t.reason)
    }

    @Test
    fun `limit price outside the bracket is rejected`() {
        val b = broker()
        val bad = buyLimit().copy(limitPrice = 1.1920) // below the stop
        assertFalse(b.submit(bad).accepted)
        val badNoPrice = buyLimit().copy(limitPrice = null)
        assertFalse(b.submit(badNoPrice).accepted)
    }
}
