package ipda.feed

import ipda.model.Candle
import ipda.model.Timeframe

/**
 * Replays snapshot candles as one deterministic, canonically-ordered stream
 * (§9.2). The engine downstream consumes only the [Feed] interface, so it
 * cannot tell replay from live — the live adapter's contract is to emit the
 * exact same order.
 *
 * Ordering = [canonicalOrder]: close time, then higher timeframe first (bias
 * state must be current before entry-TF logic runs), then symbol ascending.
 */
class FeedReplayer(
    series: Map<Pair<String, Timeframe>, List<Candle>>,
) : Feed {

    private val ordered: List<Candle> = series.values
        .flatten()
        .sortedWith(canonicalOrder)

    val size: Int get() = ordered.size

    override fun run(handler: (Candle) -> Unit) {
        for (candle in ordered) handler(candle)
    }
}
