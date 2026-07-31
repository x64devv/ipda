package ipda.backtest

import ipda.broker.OrderAck
import ipda.broker.OrderIntent
import ipda.config.IpdaConfig
import ipda.detect.DisplacementDetector
import ipda.detect.MarketContext
import ipda.detect.SessionTagger
import ipda.log.EventLog
import ipda.model.Candle
import ipda.model.Direction

/**
 * The per-bias-candle decision path, extracted from [BacktestEngine] for the
 * live demo loop milestone so REPLAY AND LIVE RUN THE EXACT SAME CODE:
 * detector update → context update → leg_eval log → displacement/
 * signal_context logs → strategy decision → submit → order_intent log.
 *
 * The only seam is [submit] — SimBroker in replay, the cTrader live adapter
 * in the live loop. Log line formats are frozen; live vs backtest comparison
 * stays a diff, not archaeology.
 */
class DecisionPipeline(
    private val cfg: IpdaConfig,
    private val strategy: Strategy,
    private val eventLog: EventLog,
    private val submit: (OrderIntent) -> OrderAck,
) {
    private val detectors = HashMap<String, DisplacementDetector>()
    private val contexts = HashMap<String, MarketContext>()
    private val displacementCounts = HashMap<String, Int>()
    private val sessions = SessionTagger(cfg.sessions)

    fun onBiasCandle(candle: Candle) {
        val detector = detectors.getOrPut(candle.symbol) {
            DisplacementDetector(cfg.displacement, cfg.swings.wing)
        }
        val context = contexts.getOrPut(candle.symbol) {
            MarketContext(
                swingWing = cfg.swings.wing,
                equalLevelTolerance = cfg.liquidity.equalLevelTolerancePips *
                    (cfg.execution.pipSize[candle.symbol] ?: 0.0001),
            )
        }
        val eval = detector.onCandle(candle)
        context.onCandle(candle)
        if (eval != null) eventLog.append("leg_eval", eval.toJson())

        val seen = displacementCounts.getOrDefault(candle.symbol, 0)
        val newEvent = if (detector.displacements.size > seen) {
            displacementCounts[candle.symbol] = detector.displacements.size
            detector.displacements.last()
        } else null

        var signalContext: SignalContext? = null
        if (newEvent != null) {
            eventLog.append(
                "displacement",
                """{"symbol":"${candle.symbol}","closeTime":"${newEvent.evaluation.closeTime}","kind":"${newEvent.classification.kind}"}"""
            )
            signalContext = buildSignalContext(candle, newEvent.evaluation.direction, newEvent.evaluation.lastClose, context)
            eventLog.append("signal_context", signalContextJson(candle, newEvent, signalContext))
        }

        val intent = strategy.onBiasCandle(candle, newEvent, signalContext) ?: return
        val ack = submit(intent)
        eventLog.append(
            "order_intent",
            """{"symbol":"${intent.symbol}","side":"${intent.side}","decisionTime":"${intent.decisionTime}","stop":${intent.stopLoss},"tp":${intent.takeProfit},"accepted":${ack.accepted}}"""
        )
    }

    /**
     * Context view at the signal close. Continuous values only — the layer
     * gates compare against config, so every gate threshold (N, zone) stays a
     * query over the log (standing rule 2).
     */
    private fun buildSignalContext(
        candle: Candle,
        direction: Direction,
        signalClose: Double,
        context: MarketContext,
    ): SignalContext = SignalContext(
        candlesSinceHighSweep = context.candlesSince(context.lastHighSweep),
        candlesSinceLowSweep = context.candlesSince(context.lastLowSweep),
        lastHighSweepLevel = context.lastHighSweep?.level,
        lastLowSweepLevel = context.lastLowSweep?.level,
        dealingRange = context.dealingRange(),
        sessions = sessions.tag(candle.openTime),
        drawTargetLevel = context.nearestDrawPool(signalClose, forShort = direction == Direction.BEARISH)?.level,
    )

    /**
     * Per-signal JSONL record (decisions D2/D3: counterfactual stop + draw
     * target logged on EVERY signal; killzone membership logged per D4).
     * cfSweepStop = the swept level a sweep-extreme stop would key off;
     * drawTarget/drawImpliedR = the opposite-liquidity target and its implied
     * R given the traded (leg-extreme) stop.
     */
    private fun signalContextJson(candle: Candle, event: ipda.detect.DisplacementEvent, sc: SignalContext): String {
        val eval = event.evaluation
        val bearish = eval.direction == Direction.BEARISH
        val stop = if (bearish) eval.extremeHigh else eval.extremeLow
        val risk = if (bearish) stop - eval.lastClose else eval.lastClose - stop
        val cfSweepStop = if (bearish) sc.lastHighSweepLevel else sc.lastLowSweepLevel
        val drawImpliedR = sc.drawTargetLevel?.let { t ->
            if (risk > 0) (if (bearish) eval.lastClose - t else t - eval.lastClose) / risk else null
        }
        val range = sc.dealingRange
        return buildString {
            append("{")
            append("\"symbol\":\"").append(candle.symbol).append("\",")
            append("\"closeTime\":\"").append(eval.closeTime).append("\",")
            append("\"dir\":\"").append(eval.direction).append("\",")
            append("\"kind\":\"").append(event.classification.kind).append("\",")
            append("\"sinceHighSweep\":").append(sc.candlesSinceHighSweep ?: "null").append(",")
            append("\"sinceLowSweep\":").append(sc.candlesSinceLowSweep ?: "null").append(",")
            append("\"highSweepLevel\":").append(sc.lastHighSweepLevel ?: "null").append(",")
            append("\"lowSweepLevel\":").append(sc.lastLowSweepLevel ?: "null").append(",")
            append("\"rangeLow\":").append(range?.low ?: "null").append(",")
            append("\"rangeHigh\":").append(range?.high ?: "null").append(",")
            append("\"rangePos\":").append(range?.position(eval.lastClose) ?: "null").append(",")
            append("\"zone\":").append(range?.let { "\"${it.classify(eval.lastClose)}\"" } ?: "null").append(",")
            append("\"sessions\":[").append(sc.sessions.sorted().joinToString(",") { "\"$it\"" }).append("],")
            append("\"cfSweepStop\":").append(cfSweepStop ?: "null").append(",")
            append("\"drawTarget\":").append(sc.drawTargetLevel ?: "null").append(",")
            append("\"drawImpliedR\":").append(drawImpliedR ?: "null").append(",")
            // Entry-level candidates (settled 29 Jul 2026): traded level is
            // entryMidpoint under FVG_MIDPOINT_LIMIT; the rest are logged
            // counterfactuals so level choice stays a query.
            append("\"entryMidpoint\":").append(BaselineDisplacementStrategy.chosenEntryLevel(event) ?: "null").append(",")
            append("\"entryNearEdge\":").append(BaselineDisplacementStrategy.nearEdgeLevel(event) ?: "null").append(",")
            append("\"entryDeepest\":").append(BaselineDisplacementStrategy.deepestLevel(event) ?: "null").append(",")
            append("\"entryHalfLeg\":").append(BaselineDisplacementStrategy.halfLegLevel(event)).append(",")
            append("\"legFvgCount\":").append(event.legFvgs.size)
            append("}")
        }
    }
}
