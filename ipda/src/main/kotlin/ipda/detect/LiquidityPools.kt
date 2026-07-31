package ipda.detect

import ipda.model.Candle
import ipda.model.SwingPoint
import ipda.model.SwingType
import java.time.Instant

/**
 * Equal highs / equal lows → resting liquidity pools (v1 interpretation).
 *
 * A pool forms when ≥2 CONFIRMED same-type swings sit within [tolerance]
 * price units of each other (tolerance is config — equal highs are never
 * exactly equal). Pool level = the cluster extreme (max for highs, min for
 * lows): that is where the stops actually rest.
 *
 * Sweep: a later candle trading THROUGH the level (strictly beyond) marks the
 * pool swept at that candle's close — causal, like everything else. Swept
 * pools stay in the record (sweeps are signals) but stop accepting members.
 *
 * All state transitions happen inside onCandle, in a fixed order:
 * (1) swings confirmed by this candle join/form pools, (2) this candle's range
 * sweeps. A pool formed by a swing confirmed at this close cannot also be
 * swept by this same candle (its wing candles are already inside the range —
 * sweeping applies to candles after formation).
 */
class LiquidityPoolDetector(
    private val swings: SwingDetector,
    private val tolerance: Double,
) {
    data class Pool(
        val type: SwingType,
        /** Cluster extreme — max for equal highs, min for equal lows. */
        val level: Double,
        val members: List<SwingPoint>,
        val formedAt: Instant,
        val sweptAt: Instant? = null,
    ) {
        val swept: Boolean get() = sweptAt != null
    }

    private val _pools = ArrayList<Pool>()
    val pools: List<Pool> get() = _pools
    val active: List<Pool> get() = _pools.filter { !it.swept }

    /** Unclustered confirmed swings waiting for an equal partner. */
    private val loneSwings = ArrayList<SwingPoint>()

    /**
     * Feed the candle AFTER [swings].onCandle was called for it (the detector
     * does not own the swing detector so callers can share one). Returns pools
     * newly formed or newly swept at this close.
     */
    fun onCandle(candle: Candle, newlyConfirmed: List<SwingPoint>): List<Pool> {
        val changed = ArrayList<Pool>()

        for (swing in newlyConfirmed) {
            changed.addAll(absorb(swing, candle.openTime))
        }

        // Sweep check against this candle's range.
        for (i in _pools.indices) {
            val pool = _pools[i]
            if (pool.swept) continue
            val sweptNow = when (pool.type) {
                SwingType.HIGH -> candle.high > pool.level
                SwingType.LOW -> candle.low < pool.level
            }
            if (sweptNow && candle.openTime > pool.formedAt) {
                val updated = pool.copy(sweptAt = candle.openTime)
                _pools[i] = updated
                changed.add(updated)
            }
        }
        return changed
    }

    private fun absorb(swing: SwingPoint, now: Instant): List<Pool> {
        // Try to join an ACTIVE pool of the same type first.
        val poolIdx = _pools.indexOfFirst {
            !it.swept && it.type == swing.type && distanceToCluster(it.members, swing) <= tolerance
        }
        if (poolIdx >= 0) {
            val pool = _pools[poolIdx]
            val members = pool.members + swing
            val updated = pool.copy(
                level = clusterLevel(swing.type, members),
                members = members,
            )
            _pools[poolIdx] = updated
            return listOf(updated)
        }
        // Else try to pair with a lone swing.
        val mateIdx = loneSwings.indexOfFirst {
            it.type == swing.type && kotlin.math.abs(it.price - swing.price) <= tolerance
        }
        if (mateIdx >= 0) {
            val mate = loneSwings.removeAt(mateIdx)
            val members = listOf(mate, swing)
            val pool = Pool(
                type = swing.type,
                level = clusterLevel(swing.type, members),
                members = members,
                formedAt = now,
            )
            _pools.add(pool)
            return listOf(pool)
        }
        loneSwings.add(swing)
        return emptyList()
    }

    private fun distanceToCluster(members: List<SwingPoint>, swing: SwingPoint): Double =
        members.minOf { kotlin.math.abs(it.price - swing.price) }

    private fun clusterLevel(type: SwingType, members: List<SwingPoint>): Double = when (type) {
        SwingType.HIGH -> members.maxOf { it.price }
        SwingType.LOW -> members.minOf { it.price }
    }
}
