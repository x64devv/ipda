package ipda.detect

import ipda.model.Candle
import ipda.model.Fvg
import ipda.model.FvgKind

/**
 * Streaming 3-candle FVG detector.
 *
 * BISI (bullish): low(candle3) > high(candle1) — gap [high(c1), low(c3)].
 * SIBI (bearish): high(candle3) < low(candle1) — gap [high(c3), low(c1)].
 *
 * Causality: detected at the close of candle 3; the middle candle is the
 * displacement candle. Strict inequality — a zero-size touch is not a gap.
 */
class FvgDetector {

    private var c1: Candle? = null
    private var c2: Candle? = null
    private var nextIndex = 0

    private val _all = ArrayList<Fvg>()
    /** All FVGs detected so far, in detection order. */
    val all: List<Fvg> get() = _all

    /** Feed one completed candle; returns the FVG completed at this close, if any. */
    fun onCandle(candle: Candle): Fvg? {
        val a = c1
        val b = c2
        val j = nextIndex
        nextIndex++
        c1 = b
        c2 = candle
        if (a == null || b == null) return null

        val fvg = when {
            candle.low > a.high -> Fvg(
                kind = FvgKind.BISI,
                firstIndex = j - 2, middleIndex = j - 1, thirdIndex = j,
                gapLow = a.high, gapHigh = candle.low,
                detectedAtTime = candle.openTime,
            )
            candle.high < a.low -> Fvg(
                kind = FvgKind.SIBI,
                firstIndex = j - 2, middleIndex = j - 1, thirdIndex = j,
                gapLow = candle.high, gapHigh = a.low,
                detectedAtTime = candle.openTime,
            )
            else -> null
        }
        if (fvg != null) _all.add(fvg)
        return fvg
    }
}
