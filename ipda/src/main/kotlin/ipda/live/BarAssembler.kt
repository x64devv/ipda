package ipda.live

import ipda.ctrader.TrendbarMapper
import ipda.model.Candle
import ipda.model.Timeframe

/**
 * Assembles COMPLETED candles for one (symbol, timeframe) series from live
 * trendbar updates (standing rule 1: only completed candles reach the engine).
 *
 * A live trendbar update reflects the forming bar as of the latest tick.
 * Completion is detected two ways:
 *  - ROLL: an update arrives whose open time is later than the forming bar's —
 *    the forming bar's last-seen state IS the finished bar (its final tick
 *    updated it before the roll).
 *  - GRACE: wall clock passes the forming bar's close time plus a grace
 *    period without a roll tick (quiet market, weekend close). Grace covers
 *    clock skew and delivery latency; a tick belonging to the old bar after
 *    grace would be a server anomaly and is dropped (logged upstream).
 *
 * Bars at/before the last completed open are ignored — dedupe across late
 * ticks, resubscribes and reconnects.
 */
class BarAssembler(
    private val symbol: String,
    private val tf: Timeframe,
) {
    private var forming: TrendbarMapper.RawBar? = null
    private var lastCompletedOpenMs: Long = Long.MIN_VALUE

    val formingOpenMs: Long? get() = forming?.openTimeMs

    /** Prime the dedupe watermark (e.g. after a catch-up backfill). */
    fun markCompletedThrough(openTimeMs: Long) {
        if (openTimeMs > lastCompletedOpenMs) lastCompletedOpenMs = openTimeMs
        val f = forming
        if (f != null && f.openTimeMs <= lastCompletedOpenMs) forming = null
    }

    /**
     * Apply one live trendbar update. Returns the COMPLETED candle when this
     * update rolls the series to a new bar, else null.
     */
    fun onUpdate(raw: TrendbarMapper.RawBar): Candle? {
        if (raw.openTimeMs <= lastCompletedOpenMs) return null // stale/duplicate
        val f = forming
        return when {
            f == null -> {
                forming = raw
                null
            }
            raw.openTimeMs == f.openTimeMs -> {
                forming = raw
                null
            }
            raw.openTimeMs > f.openTimeMs -> {
                forming = raw
                complete(f)
            }
            else -> null // older than the forming bar — out-of-order tick, drop
        }
    }

    /**
     * Wall-clock completion: if the forming bar's close time plus [graceMs]
     * has passed as of [nowMs], finalize and return it, else null.
     */
    fun flushDue(nowMs: Long, graceMs: Long): Candle? {
        val f = forming ?: return null
        val closeMs = f.openTimeMs + tf.duration.toMillis()
        if (nowMs < closeMs + graceMs) return null
        forming = null
        return complete(f)
    }

    private fun complete(raw: TrendbarMapper.RawBar): Candle {
        lastCompletedOpenMs = raw.openTimeMs
        return TrendbarMapper.toCandle(raw, symbol, tf)
    }
}
