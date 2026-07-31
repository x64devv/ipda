package ipda.detect

import ipda.config.AtrMethod
import ipda.detect.TestCandles.h1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class AtrTest {

    @Test
    fun `warmup - null until period candles seen`() {
        val atr = AtrCalculator(period = 3)
        val candles = listOf(
            h1(0, 1.1000, 1.1010, 1.1000, 1.1005),
            h1(1, 1.1005, 1.1015, 1.1005, 1.1010),
            h1(2, 1.1010, 1.1020, 1.1010, 1.1015),
        )
        candles.forEach { atr.onCandle(it) }
        assertNull(atr.valueAsOf(0))
        assertNull(atr.valueAsOf(1))
        assertNotNull(atr.valueAsOf(2))
        assertNull(atr.valueAsOf(-1)) // before any data: leg starting at index 0
        assertNull(atr.valueAsOf(99)) // future index unknown
    }

    @Test
    fun `SMA of true ranges - includes gap component`() {
        val atr = AtrCalculator(period = 3, method = AtrMethod.SMA)
        // TRs: bar0 h-l = 0.0010; bar1 h-l = 0.0010 (no gap, open == prev close);
        // bar2 gaps up: high 1.1050, prev close 1.1010 -> TR = max(0.0010, 0.0040, 0.0030) = 0.0040
        atr.onCandle(h1(0, 1.1000, 1.1010, 1.1000, 1.1005))
        atr.onCandle(h1(1, 1.1005, 1.1015, 1.1005, 1.1010))
        atr.onCandle(h1(2, 1.1045, 1.1050, 1.1040, 1.1048))

        assertEquals((0.0010 + 0.0010 + 0.0040) / 3, atr.valueAsOf(2)!!, 1e-9)
    }

    @Test
    fun `valueAsOf(i) never includes later candles - the ATR shift`() {
        val atr = AtrCalculator(period = 3)
        // Three quiet candles, then a monster candle.
        atr.onCandle(h1(0, 1.1000, 1.1010, 1.1000, 1.1005))
        atr.onCandle(h1(1, 1.1005, 1.1015, 1.1005, 1.1010))
        atr.onCandle(h1(2, 1.1010, 1.1020, 1.1010, 1.1015))
        val quiet = atr.valueAsOf(2)!!
        atr.onCandle(h1(3, 1.1015, 1.1215, 1.1015, 1.1210)) // 200-pip burst

        assertEquals(quiet, atr.valueAsOf(2)!!, 0.0) // unchanged by the burst
        assertEquals((0.0010 + 0.0010 + 0.0200) / 3, atr.valueAsOf(3)!!, 1e-9)
    }

    @Test
    fun `Wilder seeds with SMA then smooths`() {
        val atr = AtrCalculator(period = 3, method = AtrMethod.WILDER)
        atr.onCandle(h1(0, 1.1000, 1.1010, 1.1000, 1.1005))
        atr.onCandle(h1(1, 1.1005, 1.1015, 1.1005, 1.1010))
        atr.onCandle(h1(2, 1.1010, 1.1020, 1.1010, 1.1015))
        val seed = atr.valueAsOf(2)!!
        assertEquals(0.0010, seed, 1e-9)

        atr.onCandle(h1(3, 1.1015, 1.1055, 1.1015, 1.1050)) // TR = 0.0040
        assertEquals((seed * 2 + 0.0040) / 3, atr.valueAsOf(3)!!, 1e-9)
    }
}
