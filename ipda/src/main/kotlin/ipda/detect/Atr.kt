package ipda.detect

import ipda.config.AtrMethod
import ipda.model.Candle
import kotlin.math.abs
import kotlin.math.max

/**
 * Streaming ATR. True range of bar t (given previous close):
 *   TR = max(high-low, |high-prevClose|, |low-prevClose|)
 * First bar's TR = high-low.
 *
 * SMA method: simple mean of the last [period] TRs (transparent, v1 default).
 * WILDER: classic smoothing ATR_t = (ATR_{t-1}*(p-1) + TR_t) / p, seeded with
 * the SMA of the first p TRs.
 *
 * value(afterIndex = i) is the ATR computed from candles 0..i ONLY — this is
 * what Condition A means by "ATR as of candle i-1": the leg must not inflate
 * its own denominator.
 */
class AtrCalculator(
    private val period: Int,
    private val method: AtrMethod = AtrMethod.SMA,
) {
    init {
        require(period >= 1) { "ATR period must be >= 1" }
    }

    private var prevClose: Double? = null
    private val trs = ArrayList<Double>()
    /** atrByIndex[i] = ATR as of candle i (null until warm). */
    private val atrByIndex = ArrayList<Double?>()
    private var wilder: Double? = null

    fun onCandle(candle: Candle) {
        val pc = prevClose
        val tr = if (pc == null) {
            candle.high - candle.low
        } else {
            max(candle.high - candle.low, max(abs(candle.high - pc), abs(candle.low - pc)))
        }
        prevClose = candle.close
        trs.add(tr)

        val atr: Double? = when {
            trs.size < period -> null
            method == AtrMethod.SMA ->
                trs.subList(trs.size - period, trs.size).sum() / period
            else -> { // WILDER
                val w = wilder
                val next = if (w == null) trs.sum() / period // seed: SMA of first p TRs
                else (w * (period - 1) + tr) / period
                wilder = next
                next
            }
        }
        atrByIndex.add(atr)
    }

    /**
     * ATR using candles up to and including [index]. Null while warming up
     * (fewer than [period] candles seen by then) or if index < 0.
     */
    fun valueAsOf(index: Int): Double? =
        if (index < 0 || index >= atrByIndex.size) null else atrByIndex[index]
}
