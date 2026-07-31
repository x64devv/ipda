package ipda.live

import ipda.feed.canonicalOrder
import ipda.model.Candle
import java.time.Instant
import java.util.TreeMap

/**
 * Enforces the canonical feed order on live emissions despite wire jitter
 * (contract in `ipda.feed`: close time → HTF first → symbol ascending).
 *
 * Completed candles are grouped into buckets by CLOSE time. A bucket flushes
 * [settleMs] after its first arrival — long enough for all series sharing
 * that close (H1 + M15, both symbols) to arrive, short enough that decision
 * latency stays bounded. Buckets flush strictly in close-time order.
 *
 * A candle whose close time is at/before the last flushed close (a straggler
 * beyond the settle window) is emitted IMMEDIATELY and reported through
 * [onLateCandle] — engine state is per-symbol so a cross-symbol ordering
 * violation is observable in the log but cannot corrupt decisions.
 */
class CanonicalSequencer(
    private val settleMs: Long,
    private val clock: () -> Long,
    private val onLateCandle: (Candle) -> Unit = {},
) {
    private class Bucket(val deadlineMs: Long) {
        val candles = ArrayList<Candle>()
    }

    private val buckets = TreeMap<Instant, Bucket>()
    private var lastFlushedClose: Instant? = null

    /**
     * Add a completed candle. Usually returns empty (the candle waits for its
     * bucket); returns the candle itself when it is a late straggler that
     * must be emitted immediately to avoid holding the stream hostage.
     */
    fun add(candle: Candle): List<Candle> {
        val flushed = lastFlushedClose
        if (flushed != null && candle.closeTime <= flushed) {
            onLateCandle(candle)
            return listOf(candle)
        }
        buckets.getOrPut(candle.closeTime) { Bucket(clock() + settleMs) }.candles.add(candle)
        return emptyList()
    }

    /**
     * Flush every bucket whose settle deadline has passed, oldest close
     * first, each internally sorted canonically. A younger bucket never
     * flushes past an older one still settling.
     */
    fun drainReady(): List<Candle> {
        val out = ArrayList<Candle>()
        val now = clock()
        while (buckets.isNotEmpty()) {
            val (close, bucket) = buckets.firstEntry()
            if (now < bucket.deadlineMs) break
            out.addAll(bucket.candles.sortedWith(canonicalOrder))
            lastFlushedClose = close
            buckets.remove(close)
        }
        return out
    }

    /** Earliest pending deadline, for pump-loop pacing; null when empty. */
    fun nextDeadlineMs(): Long? = buckets.firstEntry()?.value?.deadlineMs

    val pendingCount: Int get() = buckets.values.sumOf { it.candles.size }
}
