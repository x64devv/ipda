package ipda.broker

import ipda.detect.TestCandles.h1
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimBrokerTest {

    private val spread = 0.00010 // 1 pip round trip, for easy arithmetic
    private fun broker() = SimBroker(mapOf("EURUSD" to spread))

    private fun sellIntent(stop: Double, tp: Double) = OrderIntent(
        symbol = "EURUSD", side = Side.SELL, volumeLots = 1.0,
        stopLoss = stop, takeProfit = tp, decisionTime = Instant.parse("2026-01-05T00:00:00Z"),
    )

    @Test
    fun `entry fills at next candle open and target exit nets spread`() {
        val b = broker()
        assertTrue(b.submit(sellIntent(stop = 1.2050, tp = 1.1900)).accepted)

        b.onCandle(h1(1, 1.2000, 1.2010, 1.1990, 1.1995)) // entry fills at 1.2000
        assertTrue(b.hasExposure("EURUSD"))
        b.onCandle(h1(2, 1.1995, 1.2000, 1.1890, 1.1895)) // low crosses tp

        val t = b.closedTrades.single()
        assertEquals(1.2000, t.entryPrice)
        assertEquals(SimBroker.ExitReason.TARGET, t.reason)
        assertEquals(1.1900, t.exitPrice)
        assertEquals(0.0100, t.grossMove, 1e-9)          // sell 1.2000 -> 1.1900
        assertEquals(0.0100 - spread, t.netMove, 1e-9)
        assertEquals((0.0100 - spread) / 0.0050, t.rMultiple, 1e-9) // risk = 1.2050-1.2000
        assertFalse(b.hasExposure("EURUSD"))
    }

    @Test
    fun `stop-first when one candle touches both stop and target`() {
        val b = broker()
        b.submit(sellIntent(stop = 1.2050, tp = 1.1950))
        b.onCandle(h1(1, 1.2000, 1.2005, 1.1995, 1.2000)) // entry 1.2000
        // Monster candle spans both bracket sides:
        b.onCandle(h1(2, 1.2000, 1.2060, 1.1940, 1.1950))

        val t = b.closedTrades.single()
        assertEquals(SimBroker.ExitReason.STOP, t.reason)
        assertEquals(1.2050, t.exitPrice)
        assertTrue(t.netMove < 0)
    }

    @Test
    fun `stop gapped through exits at the worse open`() {
        val b = broker()
        b.submit(sellIntent(stop = 1.2050, tp = 1.1900))
        b.onCandle(h1(1, 1.2000, 1.2005, 1.1995, 1.2000)) // entry 1.2000
        b.onCandle(h1(2, 1.2080, 1.2090, 1.2070, 1.2085)) // opens beyond the stop

        val t = b.closedTrades.single()
        assertEquals(SimBroker.ExitReason.STOP, t.reason)
        assertEquals(1.2080, t.exitPrice) // open, not the stop level
    }

    @Test
    fun `entry same candle exit - stop-first applies immediately`() {
        val b = broker()
        b.submit(sellIntent(stop = 1.2010, tp = 1.1990))
        // Fill at open 1.2000; the same candle's high crosses the tight stop.
        b.onCandle(h1(1, 1.2000, 1.2015, 1.1985, 1.1990))
        val t = b.closedTrades.single()
        assertEquals(SimBroker.ExitReason.STOP, t.reason)
    }

    @Test
    fun `one exposure per symbol - second intent rejected until flat`() {
        val b = broker()
        assertTrue(b.submit(sellIntent(stop = 1.2050, tp = 1.1900)).accepted)
        assertFalse(b.submit(sellIntent(stop = 1.2050, tp = 1.1900)).accepted)
        b.onCandle(h1(1, 1.2000, 1.2005, 1.1995, 1.2000)) // now open
        assertFalse(b.submit(sellIntent(stop = 1.2050, tp = 1.1900)).accepted)
    }

    @Test
    fun `invalid bracket rejected`() {
        val b = broker()
        // SELL requires stop above target.
        assertFalse(b.submit(sellIntent(stop = 1.1900, tp = 1.2050)).accepted)
    }

    @Test
    fun `end of data flattens at last close`() {
        val b = broker()
        b.submit(sellIntent(stop = 1.2050, tp = 1.1800))
        val last = h1(1, 1.2000, 1.2005, 1.1995, 1.1998)
        b.onCandle(last)
        b.closeAll(mapOf("EURUSD" to last))
        val t = b.closedTrades.single()
        assertEquals(SimBroker.ExitReason.END_OF_DATA, t.reason)
        assertEquals(1.1998, t.exitPrice)
    }
}
