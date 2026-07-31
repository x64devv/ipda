package ipda.detect

import ipda.detect.TestCandles.h1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarketContextTest {

    /**
     * Tape: swing high 1.2010 @ bar2 (confirmed bar4), swept by bar6.
     * Then swing low 1.1980 @ bar7 (confirmed bar9), swept by bar11.
     */
    private val tape = listOf(
        h1(0, 1.2000, 1.2002, 1.1995, 1.2001),
        h1(1, 1.2001, 1.2005, 1.1998, 1.2003),
        h1(2, 1.2003, 1.2010, 1.2000, 1.2005), // swing high 1.2010
        h1(3, 1.2005, 1.2006, 1.1999, 1.2000),
        h1(4, 1.2000, 1.2004, 1.1996, 1.1998), // confirms swing @2
        h1(5, 1.1998, 1.2006, 1.1997, 1.2004),
        h1(6, 1.2004, 1.2013, 1.2001, 1.2007), // high 1.2013 > 1.2010 -> HIGH sweep at index 6
        h1(7, 1.2007, 1.2008, 1.1980, 1.1985), // swing low 1.1980
        h1(8, 1.1985, 1.1995, 1.1984, 1.1990),
        h1(9, 1.1990, 1.2000, 1.1988, 1.1998), // confirms swing @7
        h1(10, 1.1998, 1.2003, 1.1990, 1.1995),
        h1(11, 1.1995, 1.1996, 1.1975, 1.1978), // low 1.1975 < 1.1980 -> LOW sweep at index 11
    )

    private fun ctx(upTo: Int): MarketContext {
        val c = MarketContext(swingWing = 2, equalLevelTolerance = 0.0001)
        tape.take(upTo + 1).forEach { c.onCandle(it) }
        return c
    }

    @Test
    fun `individual confirmed swing sweeps are recorded with level and age`() {
        val atSweep = ctx(6)
        val high = atSweep.lastHighSweep
        assertNotNull(high)
        assertEquals(1.2010, high.level)
        assertEquals(6, high.index)
        assertEquals(0, atSweep.candlesSince(high))
        assertNull(atSweep.lastLowSweep)

        val later = ctx(11)
        assertEquals(5, later.candlesSince(later.lastHighSweep)) // 11 - 6
        val low = later.lastLowSweep
        assertNotNull(low)
        assertEquals(1.1980, low.level)
        assertEquals(0, later.candlesSince(low))
    }

    @Test
    fun `no sweep is recorded before confirmation or before the level trades through`() {
        // Bar 5 high 1.2006 stays under 1.2010: swing confirmed but not swept.
        val c = ctx(5)
        assertNull(c.lastHighSweep)
        assertNull(c.lastLowSweep)
    }

    @Test
    fun `swing confirmed at this close cannot be swept by the same candle`() {
        // Construct: swing high at bar2, wing candles below; bar4 confirms it
        // AND trades above its price. The strict fractal makes this shape
        // possible only when bar4's high exceeds bar2's — then bar2 is no
        // swing (bar4 within wing). So instead verify directly: after bar4
        // (confirmation candle, high 1.2004 < 1.2010) nothing is swept, and
        // the sweep only lands when a LATER candle crosses.
        assertNull(ctx(4).lastHighSweep)
        assertNotNull(ctx(6).lastHighSweep)
    }

    @Test
    fun `dealing range and draw pool queries are causal snapshots`() {
        // At bar 9 the confirmed low is 1.1980 (bar 7) and the most recent
        // confirmed HIGH is bar 6's 1.2013 (its 1.2013 top is itself a strict
        // fractal, confirmed by bar 8) — not the older 1.2010.
        val c = ctx(9)
        val range = c.dealingRange()
        assertNotNull(range)
        assertEquals(1.1980, range.low)
        assertEquals(1.2013, range.high)
        assertEquals(DealingRangeTracker.Zone.PREMIUM, range.classify(1.2000))
        assertEquals(DealingRangeTracker.Zone.DISCOUNT, range.classify(1.1990))
        assertTrue(range.position(1.2000) > 0.5)

        // No pools on this tape (no equal extremes within tolerance).
        assertNull(c.nearestDrawPool(1.2000, forShort = true))
        assertNull(c.nearestDrawPool(1.2000, forShort = false))
    }
}
