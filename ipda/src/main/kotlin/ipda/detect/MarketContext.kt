package ipda.detect

import ipda.model.Candle
import ipda.model.SwingPoint
import ipda.model.SwingType
import java.time.Instant

/**
 * Per-symbol market context for the v1 layers (settled 29 Jul 2026, brief
 * D1–D3): liquidity-sweep tracking (layer B), dealing-range premium/discount
 * (layer C), and the counterfactual levels logged on every signal (D2/D3).
 *
 * Owns its own SwingDetector (same wing config as displacement's internal
 * one — deterministic on the same input, so sharing is unnecessary), feeding
 * a LiquidityPoolDetector and a DealingRangeTracker.
 *
 * Sweep definition (v1 interpretation, documented):
 *  - POOL sweep: as defined by LiquidityPoolDetector — a candle trades
 *    STRICTLY through the cluster extreme of a pool formed at an earlier close.
 *  - SWING sweep: a candle trades strictly through the price of an individual
 *    CONFIRMED swing (the "prior swing extreme" clause of layer B). A swing is
 *    individually sweepable only from the close AFTER its confirmation close
 *    (strict fractals guarantee wing candles cannot pre-sweep it); each swing
 *    sweeps at most once. Swings that joined a pool remain individually
 *    tracked — only the most recent sweep per side is retained, so the
 *    duplication is harmless.
 *
 * Only the MOST RECENT sweep per side (HIGH = buyside taken, LOW = sellside
 * taken) is retained: layer B's gate is "was the opposing side swept within
 * the last N candles", and the continuous value candlesSince* is logged on
 * every signal so N stays a query, not a re-run (standing rule 2).
 *
 * Causal by construction: everything here consumes completed candles and
 * confirmed swings only.
 */
class MarketContext(
    swingWing: Int,
    equalLevelTolerance: Double,
) {
    private val swings = SwingDetector(swingWing)
    private val poolDetector = LiquidityPoolDetector(swings, equalLevelTolerance)
    private val rangeTracker = DealingRangeTracker(swings)

    /** A recorded liquidity sweep. HIGH = buyside taken, LOW = sellside taken. */
    data class Sweep(
        val type: SwingType,
        /** Bar index (0-based within this context's stream) of the sweeping candle. */
        val index: Int,
        val time: Instant,
        /** The swept level (pool cluster extreme or swing price). */
        val level: Double,
    )

    private var index = -1

    var lastHighSweep: Sweep? = null
        private set
    var lastLowSweep: Sweep? = null
        private set

    /** Confirmed swings not yet individually swept. */
    private val unsweptSwings = ArrayList<SwingPoint>()

    /** Feed one completed bias-TF candle, AFTER the candle is final. */
    fun onCandle(candle: Candle) {
        index++
        val newlyConfirmed = swings.onCandle(candle)
        val changedPools = poolDetector.onCandle(candle, newlyConfirmed)

        for (pool in changedPools) {
            if (pool.swept && pool.sweptAt == candle.openTime) {
                record(Sweep(pool.type, index, candle.openTime, pool.level))
            }
        }

        val it = unsweptSwings.iterator()
        while (it.hasNext()) {
            val s = it.next()
            val sweptNow = when (s.type) {
                SwingType.HIGH -> candle.high > s.price
                SwingType.LOW -> candle.low < s.price
            }
            if (sweptNow) {
                record(Sweep(s.type, index, candle.openTime, s.price))
                it.remove()
            }
        }
        // Added AFTER the sweep check: a swing confirmed at this close cannot
        // be swept by this same candle.
        unsweptSwings.addAll(newlyConfirmed)
    }

    private fun record(sweep: Sweep) = when (sweep.type) {
        SwingType.HIGH -> lastHighSweep = sweep
        SwingType.LOW -> lastLowSweep = sweep
    }

    /** Candles elapsed since [sweep] (0 = swept by the current candle). */
    fun candlesSince(sweep: Sweep?): Int? = sweep?.let { index - it.index }

    /** Current dealing range, or null before one swing of each type confirmed. */
    fun dealingRange(): DealingRangeTracker.Range? = rangeTracker.current()

    /**
     * Nearest ACTIVE (unswept) opposing pool for a draw-on-liquidity target
     * (D3 counterfactual, logged not traded): for a short from [price] the
     * nearest LOW pool strictly below; for a long the nearest HIGH pool
     * strictly above.
     */
    fun nearestDrawPool(price: Double, forShort: Boolean): LiquidityPoolDetector.Pool? =
        if (forShort) {
            poolDetector.active.filter { it.type == SwingType.LOW && it.level < price }
                .maxByOrNull { it.level }
        } else {
            poolDetector.active.filter { it.type == SwingType.HIGH && it.level > price }
                .minByOrNull { it.level }
        }
}
