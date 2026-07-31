package ipda.feed

import ipda.model.Candle

/**
 * The feed abstraction — the seam that makes the engine unable to tell live
 * from replay (§9.2). Everything downstream consumes one time-ordered stream
 * of COMPLETED candles.
 *
 * Canonical ordering contract (both implementations MUST obey it):
 *  1. Events are ordered by candle CLOSE time.
 *  2. At simultaneous closes (e.g. 13:00 completes both an M15 and an H1 bar),
 *     the HIGHER timeframe is emitted FIRST, so bias state is current before
 *     entry-timeframe logic runs. The live adapter buffers and re-emits in
 *     this same order despite real-world jitter.
 *  3. Ties beyond timeframe (multi-instrument) break by symbol, ascending —
 *     arbitrary but fixed and documented.
 *
 * Implementations: FeedReplayer (versioned snapshot walk — next milestone) and
 * the live cTrader adapter (later).
 */
interface Feed {
    /** Deliver candles to [handler] in canonical order until exhausted (replay) or stopped (live). */
    fun run(handler: (Candle) -> Unit)
}

/** The canonical comparator implementing the ordering contract above. */
val canonicalOrder: Comparator<Candle> =
    compareBy<Candle> { it.closeTime }
        .thenByDescending { it.timeframe.rank } // HTF first
        .thenBy { it.symbol }
