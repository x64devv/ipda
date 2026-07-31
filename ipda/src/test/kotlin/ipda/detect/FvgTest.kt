package ipda.detect

import ipda.detect.TestCandles.h1
import ipda.model.FvgKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class FvgTest {

    @Test
    fun `BISI - low of candle 3 above high of candle 1`() {
        val det = FvgDetector()
        det.onCandle(h1(0, 1.1000, 1.1010, 1.0995, 1.1005))
        det.onCandle(h1(1, 1.1005, 1.1060, 1.1004, 1.1055)) // displacement candle
        val fvg = det.onCandle(h1(2, 1.1055, 1.1070, 1.1030, 1.1065))

        assertNotNull(fvg)
        assertEquals(FvgKind.BISI, fvg.kind)
        assertEquals(1.1010, fvg.gapLow)  // high(c1)
        assertEquals(1.1030, fvg.gapHigh) // low(c3)
        assertEquals(0, fvg.firstIndex)
        assertEquals(1, fvg.middleIndex)
        assertEquals(2, fvg.thirdIndex)
    }

    @Test
    fun `SIBI - high of candle 3 below low of candle 1`() {
        val det = FvgDetector()
        det.onCandle(h1(0, 1.1000, 1.1005, 1.0990, 1.0995))
        det.onCandle(h1(1, 1.0995, 1.0996, 1.0940, 1.0945))
        val fvg = det.onCandle(h1(2, 1.0945, 1.0960, 1.0920, 1.0925))

        assertNotNull(fvg)
        assertEquals(FvgKind.SIBI, fvg.kind)
        assertEquals(1.0960, fvg.gapLow)  // high(c3)
        assertEquals(1.0990, fvg.gapHigh) // low(c1)
    }

    @Test
    fun `exact touch is not a gap - strict inequality`() {
        val det = FvgDetector()
        det.onCandle(h1(0, 1.1000, 1.1010, 1.0995, 1.1005))
        det.onCandle(h1(1, 1.1005, 1.1050, 1.1004, 1.1045))
        assertNull(det.onCandle(h1(2, 1.1045, 1.1060, 1.1010, 1.1055))) // low == high(c1)
    }

    @Test
    fun `overlapping candles produce no gap`() {
        val det = FvgDetector()
        TestCandles.alternatingWarmup(10).forEach { det.onCandle(it) }
        assertEquals(0, det.all.size)
    }
}
