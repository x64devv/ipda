package ipda.detect

import ipda.model.SwingPoint

/**
 * Dealing range + premium/discount (v1 interpretation).
 *
 * The dealing range is spanned by the most recent CONFIRMED swing high and
 * swing low (normalized so low < high — in a strong trend the latest swing
 * low can sit above the latest swing high's price; min/max keeps the range
 * well-formed). Equilibrium = midpoint. Price above equilibrium = PREMIUM,
 * below = DISCOUNT.
 *
 * Causal by construction: it only consumes confirmed swings, so the range a
 * strategy sees at close t is exactly the range known at close t.
 */
class DealingRangeTracker(private val swings: SwingDetector) {

    enum class Zone { PREMIUM, DISCOUNT, EQUILIBRIUM }

    data class Range(
        val low: Double,
        val high: Double,
        val fromSwing: SwingPoint,
        val toSwing: SwingPoint,
    ) {
        val equilibrium: Double get() = (low + high) / 2.0
        val size: Double get() = high - low

        fun classify(price: Double): Zone = when {
            price > equilibrium -> Zone.PREMIUM
            price < equilibrium -> Zone.DISCOUNT
            else -> Zone.EQUILIBRIUM
        }

        /** 0.0 at range low → 1.0 at range high (continuous value — log it). */
        fun position(price: Double): Double =
            if (size > 0) (price - low) / size else 0.5
    }

    /** Current dealing range, or null until one swing of each type has confirmed. */
    fun current(): Range? {
        val h = swings.lastConfirmedHigh ?: return null
        val l = swings.lastConfirmedLow ?: return null
        return Range(
            low = minOf(l.price, h.price),
            high = maxOf(l.price, h.price),
            fromSwing = if (l.confirmedAtIndex <= h.confirmedAtIndex) l else h,
            toSwing = if (l.confirmedAtIndex <= h.confirmedAtIndex) h else l,
        )
    }
}
