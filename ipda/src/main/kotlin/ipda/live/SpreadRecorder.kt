package ipda.live

import java.util.concurrent.ConcurrentHashMap

/**
 * Live bid/ask book-keeping for standing rule 5 (demo fills get a slippage
 * haircut before being believed — this records the real numbers that replace
 * the fixed-pips spread assumption).
 *
 * The spot subscription delivers both sides; the latest quote per symbol is
 * kept here and sampled at three moments:
 *  - DECISION: logged with every live order submission,
 *  - FILL: logged with every execution event,
 *  - CLOSE: logged at every execution-TF candle close (regular time series
 *    of spread through the sessions).
 * Aggregates (count/mean/max spread) feed the live summary.
 */
class SpreadRecorder {

    data class Quote(val bid: Double, val ask: Double, val timestampMs: Long) {
        val spread: Double get() = ask - bid
    }

    private class Agg {
        var count: Long = 0
        var sumSpread: Double = 0.0
        var maxSpread: Double = 0.0
    }

    private val last = ConcurrentHashMap<String, Quote>()
    private val aggregates = ConcurrentHashMap<String, Agg>()

    /** Update from a spot event (prices already de-scaled to real units). */
    fun onQuote(symbol: String, bid: Double?, ask: Double?, timestampMs: Long) {
        val prev = last[symbol]
        // Spot events may carry only one side; merge with the previous quote.
        val b = bid ?: prev?.bid ?: return
        val a = ask ?: prev?.ask ?: return
        last[symbol] = Quote(b, a, timestampMs)
    }

    fun quote(symbol: String): Quote? = last[symbol]

    /** Sample for the regular close-time series; also feeds the aggregates. */
    fun sampleAtClose(symbol: String): Quote? {
        val q = last[symbol] ?: return null
        val agg = aggregates.getOrPut(symbol) { Agg() }
        synchronized(agg) {
            agg.count++
            agg.sumSpread += q.spread
            if (q.spread > agg.maxSpread) agg.maxSpread = q.spread
        }
        return q
    }

    data class SpreadStats(val samples: Long, val meanSpread: Double, val maxSpread: Double)

    fun stats(): Map<String, SpreadStats> =
        aggregates.entries.associate { (symbol, agg) ->
            synchronized(agg) {
                symbol to SpreadStats(
                    samples = agg.count,
                    meanSpread = if (agg.count > 0) agg.sumSpread / agg.count else 0.0,
                    maxSpread = agg.maxSpread,
                )
            }
        }
}
