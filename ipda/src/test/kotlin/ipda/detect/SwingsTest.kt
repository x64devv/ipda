package ipda.detect

import ipda.detect.TestCandles.h1
import ipda.model.SwingType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwingsTest {

    @Test
    fun `swing low confirms exactly wing bars later - causality`() {
        val det = SwingDetector(wing = 2)
        // Lows: 1.2000, 1.1995, 1.1985 (swing), 1.1995, 1.1999
        val candles = listOf(
            h1(0, 1.2010, 1.2011, 1.2000, 1.2005),
            h1(1, 1.2005, 1.2006, 1.1995, 1.2000),
            h1(2, 1.2000, 1.2001, 1.1985, 1.1990),
            h1(3, 1.1990, 1.2001, 1.1990, 1.2000),
            h1(4, 1.2000, 1.2006, 1.1999, 1.2005),
        )

        val emissions = candles.map { det.onCandle(it) }

        // Nothing may confirm before bar 4 (= 2 + wing).
        assertTrue(emissions.take(4).all { it.isEmpty() })
        val confirmed = emissions[4]
        assertEquals(1, confirmed.size)
        val swing = confirmed.single()
        assertEquals(SwingType.LOW, swing.type)
        assertEquals(2, swing.index)
        assertEquals(1.1985, swing.price)
        assertEquals(4, swing.confirmedAtIndex)
        assertEquals(swing, det.lastConfirmedLow)
    }

    @Test
    fun `swing high mirrored`() {
        val det = SwingDetector(wing = 2)
        val candles = listOf(
            h1(0, 1.2000, 1.2005, 1.1999, 1.2004),
            h1(1, 1.2004, 1.2010, 1.2000, 1.2008),
            h1(2, 1.2008, 1.2025, 1.2005, 1.2010),
            h1(3, 1.2010, 1.2012, 1.2000, 1.2002),
            h1(4, 1.2002, 1.2004, 1.1995, 1.1998),
        )
        candles.forEach { det.onCandle(it) }
        val swing = det.lastConfirmedHigh!!
        assertEquals(SwingType.HIGH, swing.type)
        assertEquals(2, swing.index)
        assertEquals(1.2025, swing.price)
        assertEquals(4, swing.confirmedAtIndex)
    }

    @Test
    fun `equal extremes do not form a swing - strict fractal`() {
        val det = SwingDetector(wing = 2)
        // Bar 2's high equals bar 3's high -> not strictly greater -> no swing high.
        val candles = listOf(
            h1(0, 1.2000, 1.2005, 1.1999, 1.2004),
            h1(1, 1.2004, 1.2010, 1.2000, 1.2008),
            h1(2, 1.2008, 1.2025, 1.2005, 1.2010),
            h1(3, 1.2010, 1.2025, 1.2000, 1.2002),
            h1(4, 1.2002, 1.2004, 1.1995, 1.1998),
            h1(5, 1.1998, 1.2000, 1.1990, 1.1992),
        )
        candles.forEach { det.onCandle(it) }
        assertEquals(null, det.lastConfirmedHigh)
    }

    @Test
    fun `uniform alternating candles produce no swings`() {
        val det = SwingDetector(wing = 2)
        TestCandles.alternatingWarmup(20).forEach { det.onCandle(it) }
        assertEquals(0, det.confirmed.size)
    }
}
