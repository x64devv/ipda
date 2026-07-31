package ipda.detect

import ipda.detect.TestCandles.h1
import ipda.model.Candle
import ipda.model.SwingType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiquidityPoolsTest {

    private class Rig(tolerance: Double) {
        val swings = SwingDetector(wing = 2)
        val pools = LiquidityPoolDetector(swings, tolerance)
        fun feed(candle: Candle) = pools.onCandle(candle, swings.onCandle(candle))
    }

    // Two swing highs 0.0002 apart (1.2010 @ bar2, 1.2012 @ bar6), then a run through them.
    private val tape = listOf(
        h1(0, 1.2000, 1.2002, 1.1995, 1.2001),
        h1(1, 1.2001, 1.2005, 1.1998, 1.2003),
        h1(2, 1.2003, 1.2010, 1.2000, 1.2005), // swing high 1.2010
        h1(3, 1.2005, 1.2006, 1.1999, 1.2000),
        h1(4, 1.2000, 1.2004, 1.1996, 1.1998), // confirms swing @2
        h1(5, 1.1998, 1.2006, 1.1997, 1.2004),
        h1(6, 1.2004, 1.2012, 1.2001, 1.2007), // swing high 1.2012
        h1(7, 1.2007, 1.2008, 1.2000, 1.2002),
        h1(8, 1.2002, 1.2003, 1.1998, 1.2000), // confirms swing @6 -> pool forms
        h1(9, 1.2000, 1.2015, 1.1999, 1.2013), // trades through 1.2012 -> sweep
    )

    @Test
    fun `equal highs form a pool at the cluster extreme and get swept`() {
        val rig = Rig(tolerance = 0.0005)
        val events = tape.map { rig.feed(it) }

        // Pool forms when the SECOND swing confirms (bar 8), not before.
        assertTrue(events.take(8).all { changes -> changes.none { it is LiquidityPoolDetector.Pool } || changes.isEmpty() })
        val formed = events[8].single()
        assertEquals(SwingType.HIGH, formed.type)
        assertEquals(1.2012, formed.level) // cluster MAX — where the stops rest
        assertEquals(2, formed.members.size)
        assertEquals(false, formed.swept)

        // Swept by bar 9 (high 1.2015 > 1.2012).
        val swept = events[9].single()
        assertTrue(swept.swept)
        assertEquals(tape[9].openTime, swept.sweptAt)
        assertEquals(0, rig.pools.active.size)
        assertEquals(1, rig.pools.pools.size)
    }

    @Test
    fun `swings outside tolerance never cluster`() {
        val rig = Rig(tolerance = 0.0001) // tighter than the 0.0002 gap
        tape.forEach { rig.feed(it) }
        assertEquals(0, rig.pools.pools.size)
    }

    @Test
    fun `touch below the cluster extreme is not a sweep`() {
        val rig = Rig(tolerance = 0.0005)
        tape.take(9).forEach { rig.feed(it) }
        // High exactly AT the level: not strictly through -> still active.
        val changes = rig.feed(h1(9, 1.2000, 1.2012, 1.1999, 1.2005))
        assertTrue(changes.isEmpty())
        assertEquals(1, rig.pools.active.size)
        assertNull(rig.pools.pools.single().sweptAt)
    }
}
