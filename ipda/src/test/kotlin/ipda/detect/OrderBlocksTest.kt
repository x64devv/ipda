package ipda.detect

import ipda.config.DisplacementConfig
import ipda.detect.TestCandles.h1
import ipda.model.Candle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrderBlocksTest {

    /** MSS scenario from DisplacementTest: bearish displacement fires at bar 6, leg starts bar 5. */
    private val tape = listOf(
        h1(0, 1.2010, 1.2011, 1.2000, 1.2005),
        h1(1, 1.2005, 1.2006, 1.1995, 1.2000),
        h1(2, 1.2000, 1.2001, 1.1985, 1.1990),
        h1(3, 1.1990, 1.2001, 1.1990, 1.2000), // bullish
        h1(4, 1.2000, 1.2006, 1.1999, 1.2005), // bullish — LAST bullish before the leg
        h1(5, 1.2005, 1.2006, 1.1975, 1.1977), // leg start
        h1(6, 1.1977, 1.1978, 1.1940, 1.1943), // displacement event fires here
    )

    private class Rig(useBodyOnly: Boolean = false) {
        val displacement = DisplacementDetector(DisplacementConfig(atrPeriod = 3), swingWing = 2)
        val obs = OrderBlockDetector(lookback = 10, useBodyOnly = useBodyOnly)
        var seen = 0
        fun feed(candle: Candle): OrderBlockDetector.OrderBlock? {
            displacement.onCandle(candle)
            val newEvent = if (displacement.displacements.size > seen) {
                seen = displacement.displacements.size
                displacement.displacements.last()
            } else null
            return obs.onCandle(candle, newEvent)
        }
    }

    @Test
    fun `bearish displacement yields OB at last bullish candle before the leg`() {
        val rig = Rig()
        val created = tape.map { rig.feed(it) }

        assertTrue(created.take(6).all { it == null })
        val ob = created[6]
        assertNotNull(ob)
        assertEquals(OrderBlockDetector.Kind.BEARISH_OB, ob.kind)
        assertEquals(4, ob.sourceIndex)         // bar 4, not bar 3
        assertEquals(1.1999, ob.zoneLow)        // bar 4 full range
        assertEquals(1.2006, ob.zoneHigh)
        assertEquals(false, ob.touched)
    }

    @Test
    fun `body-only zone uses open and close`() {
        val rig = Rig(useBodyOnly = true)
        tape.forEach { rig.feed(it) }
        val ob = rig.obs.blocks.single()
        assertEquals(1.2000, ob.zoneLow)  // bar 4 body
        assertEquals(1.2005, ob.zoneHigh)
    }

    @Test
    fun `later candle returning into the zone marks it touched - once`() {
        val rig = Rig()
        tape.forEach { rig.feed(it) }

        rig.feed(h1(7, 1.1943, 1.1990, 1.1940, 1.1985)) // rallies but stays below zone
        assertEquals(false, rig.obs.blocks.single().touched)

        // High 1.2001 enters [1.1999, 1.2006]; low 1.1975 leaves no BISI in the
        // rally leg, so no second displacement (and no second OB) fires.
        rig.feed(h1(8, 1.1985, 1.2001, 1.1975, 1.1987))
        val touched = rig.obs.blocks.single()
        assertTrue(touched.touched)
        assertEquals(h1(8, 1.0, 1.0, 1.0, 1.0).openTime, touched.touchedAt)
        assertEquals(0, rig.obs.untouched.size)
    }

    @Test
    fun `no opposing candle within lookback yields no block`() {
        // 12 straight bearish candles then a qualifying burst: nothing bullish to anchor on.
        val rig = Rig()
        var price = 1.2100
        val candles = ArrayList<Candle>()
        for (i in 0 until 12) {
            candles.add(h1(i, price, price + 0.0002, price - 0.0006, price - 0.0004))
            price -= 0.0004
        }
        candles.forEach { rig.feed(it) }
        // Burst extends the same bearish run; if an event fires, lookback back-scan
        // finds no bullish candle (all bearish from index 0).
        val burst = rig.feed(h1(12, price, price + 0.0001, price - 0.0080, price - 0.0075))
        assertNull(burst)
        assertEquals(0, rig.obs.blocks.size)
    }
}
