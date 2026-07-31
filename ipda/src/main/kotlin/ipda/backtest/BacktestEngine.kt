package ipda.backtest

import ipda.broker.SimBroker
import ipda.config.ConfigLoader
import ipda.config.IpdaConfig
import ipda.detect.LegEvaluation
import ipda.feed.Feed
import ipda.log.EventLog
import ipda.model.Candle

/** Bump on behaviour-relevant code changes — third leg of run identity. */
const val CODE_VERSION = "0.4.0"

/**
 * Event-driven backtest engine (§9.2). Consumes ONLY the [Feed] seam — it has
 * no idea whether candles come from a snapshot replay or a live adapter.
 *
 * Per-candle processing order (fixed, documented):
 *  1. Broker first: pending entries fill at this candle's open, exits resolve
 *     against this candle's range (stop-first).
 *  2. Detectors update at the close.
 *  3. Strategy decides at the close; any intent is submitted for the NEXT
 *     candle's open.
 * Non-bias-timeframe candles pass through untouched in v1 (multi-TF aware
 * stream; H1-only baseline logic).
 *
 * The decision path itself lives in [DecisionPipeline] (extracted 29 Jul 2026
 * for the live demo loop): replay and live run the same detector → context →
 * strategy → submit code, differing only in the broker behind the seam.
 *
 * Run identity = (config hash, snapshot id, code version) — stamped into
 * every run artifact.
 */
class BacktestEngine(
    cfg: IpdaConfig,
    private val configHash: String,
    private val snapshotId: String,
    strategy: Strategy,
    private val eventLog: EventLog,
) {
    val broker = SimBroker(
        spreadBySymbol = cfg.instruments.associateWith { cfg.execution.spreadPrice(it) },
    )

    private val executionTimeframe = cfg.execution.executionTimeframe
    private val biasTimeframe = cfg.displacement.timeframe
    private val pipeline = DecisionPipeline(cfg, strategy, eventLog) { intent -> broker.submit(intent) }
    private val lastCandle = HashMap<String, Candle>()

    val runId: String =
        "run-" + ConfigLoader.sha256Hex("$configHash|$snapshotId|$CODE_VERSION".toByteArray()).take(12)

    private var cancelsSeen = 0

    fun run(feed: Feed): BacktestResult {
        var candles = 0L
        feed.run { candle ->
            candles++
            // Execution-TF candles drive the broker (fills + exits). When the
            // execution TF equals the bias TF this reproduces the v0 order
            // exactly (broker first, then detectors/strategy at the close).
            // With finer execution, the HTF-first feed order plus the broker's
            // openTime >= decisionTime eligibility guard keeps everything
            // causal: a same-close M15 candle arrives after the H1 decision
            // but covers earlier price action, so it cannot fill it.
            if (candle.timeframe == executionTimeframe) {
                lastCandle[candle.symbol] = candle
                broker.onCandle(candle)
                logNewCancels()
            }
            if (candle.timeframe == biasTimeframe) {
                pipeline.onBiasCandle(candle)
            }
        }
        broker.closeAll(lastCandle)
        return BacktestResult(
            runId = runId,
            configHash = configHash,
            snapshotId = snapshotId,
            codeVersion = CODE_VERSION,
            candlesProcessed = candles,
            trades = broker.closedTrades,
        )
    }

    private fun logNewCancels() {
        val all = broker.cancelledOrders
        while (cancelsSeen < all.size) {
            val c = all[cancelsSeen++]
            eventLog.append(
                "order_cancelled",
                """{"symbol":"${c.symbol}","side":"${c.side}","decisionTime":"${c.decisionTime}","limit":${c.limitPrice},"cancelTime":"${c.cancelTime}","reason":"${c.reason}","candlesLived":${c.candlesLived}}"""
            )
        }
    }
}

data class BacktestResult(
    val runId: String,
    val configHash: String,
    val snapshotId: String,
    val codeVersion: String,
    val candlesProcessed: Long,
    val trades: List<SimBroker.ClosedTrade>,
)

/** Compact JSONL payload — every leg evaluation, qualifying or not (rule 2). */
fun LegEvaluation.toJson(): String = buildString {
    append("{")
    append("\"symbol\":\"").append(symbol).append("\",")
    append("\"tf\":\"").append(timeframe).append("\",")
    append("\"dir\":\"").append(direction).append("\",")
    append("\"start\":\"").append(startTime).append("\",")
    append("\"closeTime\":\"").append(closeTime).append("\",")
    append("\"len\":").append(length).append(",")
    append("\"range\":").append(range).append(",")
    append("\"rOverAtr\":").append(rOverAtr ?: "null").append(",")
    append("\"bodyRatio\":").append(bodyRatio).append(",")
    append("\"fvgCount\":").append(fvgCount).append(",")
    append("\"withinSpeedCap\":").append(withinSpeedCap).append(",")
    append("\"qualifies\":").append(qualifies)
    append("}")
}

/** Aggregate stats over closed trades — the headline numbers of a run. */
data class Stats(
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val winRate: Double,
    val netMoveTotal: Double,
    val avgR: Double,
    val profitFactor: Double,
) {
    companion object {
        fun of(trades: List<SimBroker.ClosedTrade>): Stats {
            val wins = trades.count { it.netMove > 0 }
            val losses = trades.count { it.netMove <= 0 }
            val grossWin = trades.filter { it.netMove > 0 }.sumOf { it.netMove }
            val grossLoss = -trades.filter { it.netMove < 0 }.sumOf { it.netMove }
            return Stats(
                trades = trades.size,
                wins = wins,
                losses = losses,
                winRate = if (trades.isNotEmpty()) wins.toDouble() / trades.size else 0.0,
                netMoveTotal = trades.sumOf { it.netMove },
                avgR = if (trades.isNotEmpty()) trades.sumOf { it.rMultiple } / trades.size else 0.0,
                profitFactor = if (grossLoss > 0) grossWin / grossLoss else if (grossWin > 0) Double.POSITIVE_INFINITY else 0.0,
            )
        }
    }
}
