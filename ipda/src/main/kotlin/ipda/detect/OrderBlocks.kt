package ipda.detect

import ipda.model.Candle
import ipda.model.Direction
import java.time.Instant

/**
 * Order blocks (v1 interpretation): the last OPPOSING candle before a
 * qualifying displacement leg — last bullish candle before a bearish
 * displacement (bearish OB), mirrored for bullish.
 *
 * Located when the displacement EVENT fires (once per leg, causal): search
 * backward from the candle before the leg start, skipping dojis, up to
 * [lookback] candles. Zone = the source candle's full range ([useBodyOnly]
 * switches to open/close body — config).
 *
 * Mitigation: the zone is "touched" when a later candle's range overlaps it.
 * v1 tracks the first touch only; refinement (50% mitigation, breaker
 * conversion) comes later if the strategy needs it.
 */
class OrderBlockDetector(
    private val lookback: Int = 10,
    private val useBodyOnly: Boolean = false,
) {
    enum class Kind { BULLISH_OB, BEARISH_OB }

    data class OrderBlock(
        val kind: Kind,
        val zoneLow: Double,
        val zoneHigh: Double,
        val sourceIndex: Int,
        val sourceOpenTime: Instant,
        val createdAt: Instant,
        val touchedAt: Instant? = null,
    ) {
        val touched: Boolean get() = touchedAt != null
    }

    private val history = ArrayList<Candle>()
    private val _blocks = ArrayList<OrderBlock>()
    val blocks: List<OrderBlock> get() = _blocks
    val untouched: List<OrderBlock> get() = _blocks.filter { !it.touched }

    /**
     * Feed every candle (after the displacement detector for the same candle);
     * pass the new displacement event when one fired. Returns the block
     * created at this close, if any.
     */
    fun onCandle(candle: Candle, newEvent: DisplacementEvent?): OrderBlock? {
        // Touch tracking BEFORE adding a block for this close: a block created
        // now cannot be touched by the candle that created it (its zone lies
        // inside the pre-leg structure the leg just left).
        for (i in _blocks.indices) {
            val b = _blocks[i]
            if (b.touched) continue
            if (candle.openTime > b.createdAt && candle.low <= b.zoneHigh && candle.high >= b.zoneLow) {
                _blocks[i] = b.copy(touchedAt = candle.openTime)
            }
        }

        history.add(candle)
        val event = newEvent ?: return null

        val eval = event.evaluation
        val wanted = when (eval.direction) {
            Direction.BEARISH -> Direction.BULLISH
            Direction.BULLISH -> Direction.BEARISH
            Direction.NONE -> return null
        }
        // Search backward from the candle before the leg start.
        var idx = eval.startIndex - 1
        val floor = maxOf(0, eval.startIndex - lookback)
        while (idx >= floor) {
            val c = history[idx]
            if (c.direction == wanted) {
                val (lo, hi) = if (useBodyOnly) {
                    minOf(c.open, c.close) to maxOf(c.open, c.close)
                } else {
                    c.low to c.high
                }
                val block = OrderBlock(
                    kind = if (eval.direction == Direction.BEARISH) Kind.BEARISH_OB else Kind.BULLISH_OB,
                    zoneLow = lo,
                    zoneHigh = hi,
                    sourceIndex = idx,
                    sourceOpenTime = c.openTime,
                    createdAt = candle.openTime,
                )
                _blocks.add(block)
                return block
            }
            idx--
        }
        return null
    }
}
