package ipda.backtest

import ipda.config.BaselineStrategyConfig
import ipda.config.EntryMode
import ipda.broker.EntryType
import ipda.broker.OrderIntent
import ipda.broker.Side
import ipda.detect.DealingRangeTracker
import ipda.detect.DisplacementEvent
import ipda.detect.DisplacementKind
import ipda.detect.SessionTagger
import ipda.model.Candle
import ipda.model.Direction
import ipda.model.Fvg

/**
 * Per-signal context view, computed by the engine at the signal close from
 * the per-symbol MarketContext (detectors update before strategy — engine
 * order is fixed). Everything here is causal as of the signal close.
 */
data class SignalContext(
    /** Candles since the last buyside sweep (HIGH side taken); null = never. */
    val candlesSinceHighSweep: Int?,
    /** Candles since the last sellside sweep (LOW side taken); null = never. */
    val candlesSinceLowSweep: Int?,
    /** Swept level backing the high-side sweep (counterfactual stop for shorts). */
    val lastHighSweepLevel: Double?,
    /** Swept level backing the low-side sweep (counterfactual stop for longs). */
    val lastLowSweepLevel: Double?,
    /** Dealing range at signal close; null until both swing types confirmed. */
    val dealingRange: DealingRangeTracker.Range?,
    /** Session labels of the signal candle's open time. */
    val sessions: Set<String>,
    /** Nearest active opposing pool level in trade direction (draw target), null if none. */
    val drawTargetLevel: Double?,
)

/**
 * Strategy seam. v0/v1: decisions happen only at bias-timeframe closes, driven
 * by the displacement detector plus per-symbol market context. The interface
 * will widen when the M15 entry refinement layer lands; the engine treats it
 * as a black box either way.
 */
interface Strategy {
    /**
     * Called at every bias-TF candle close, AFTER detectors updated.
     * [newEvent] is non-null iff this close produced a new displacement event;
     * [context] is the market-context view at this close (non-null whenever
     * [newEvent] is). Return an order intent or null.
     */
    fun onBiasCandle(candle: Candle, newEvent: DisplacementEvent?, context: SignalContext?): OrderIntent?
}

/**
 * BASELINE strategy — v0 core (deliberately crude, per §9.1) plus the v1
 * context layers settled 29 Jul 2026 (strategy-design-brief D1), each
 * config-gated OFF by default so A -> B -> B+C are measured as deltas:
 *
 *  v0 core:
 *  - Act only on new qualifying displacement events (optionally MSS-only).
 *  - Optional killzone filter on the signal candle's OPEN time.
 *  - Direction = leg direction. Stop = the far end of the leg. Target =
 *    rMultiple × risk from the signal close.
 *
 *  Layer B (requireSweep): an opposing liquidity sweep (pool or prior
 *  confirmed swing extreme) must have occurred within sweepLookbackCandles —
 *  buyside taken before a short, sellside before a long.
 *
 *  Layer C (premiumDiscountOnly): shorts only from PREMIUM, longs only from
 *  DISCOUNT at the signal close. EQUILIBRIUM (or no range yet) takes nothing.
 *
 * Stop stays at the leg extreme and target stays fixed-R by decision D2/D3;
 * the sweep-extreme stop and draw-on-liquidity target are logged as
 * counterfactuals by the engine, never traded here.
 */
class BaselineDisplacementStrategy(
    private val cfg: BaselineStrategyConfig,
    private val sessions: SessionTagger,
    private val volumeLots: Double = 1.0,
) : Strategy {

    override fun onBiasCandle(candle: Candle, newEvent: DisplacementEvent?, context: SignalContext?): OrderIntent? {
        val event = newEvent ?: return null
        if (cfg.requireMss && event.classification.kind != DisplacementKind.MSS) return null
        if (cfg.killzoneOnly && sessions.tag(candle.openTime).none { it in cfg.killzones }) return null

        val eval = event.evaluation
        val bearish = eval.direction == Direction.BEARISH
        if (eval.direction == Direction.NONE) return null

        if (cfg.requireSweep) {
            val since = if (bearish) context?.candlesSinceHighSweep else context?.candlesSinceLowSweep
            if (since == null || since > cfg.sweepLookbackCandles) return null
        }
        if (cfg.premiumDiscountOnly) {
            val zone = context?.dealingRange?.classify(eval.lastClose) ?: return null
            val wanted = if (bearish) DealingRangeTracker.Zone.PREMIUM else DealingRangeTracker.Zone.DISCOUNT
            if (zone != wanted) return null
        }

        val signalClose = eval.lastClose
        val stop = if (bearish) eval.extremeHigh else eval.extremeLow

        return when (cfg.entryMode) {
            EntryMode.MARKET_NEXT_OPEN -> {
                val risk = if (bearish) stop - signalClose else signalClose - stop
                if (risk <= 0) return null
                OrderIntent(
                    symbol = eval.symbol,
                    side = if (bearish) Side.SELL else Side.BUY,
                    volumeLots = volumeLots,
                    stopLoss = stop,
                    takeProfit = if (bearish) signalClose - cfg.rMultiple * risk
                                 else signalClose + cfg.rMultiple * risk,
                    decisionTime = eval.closeTime,
                    decisionPrice = signalClose,
                )
            }
            EntryMode.FVG_MIDPOINT_LIMIT -> {
                // Limit at the midpoint of the leg FVG NEAREST the signal
                // close (settled 29 Jul 2026): bullish -> highest midpoint,
                // bearish -> lowest. Condition C guarantees >=1 leg FVG on
                // every qualifying leg; guard anyway.
                val level = chosenEntryLevel(event) ?: return null
                // The limit must be a genuine retracement: between stop and
                // signal close. Pathological shapes (midpoint at/past the
                // close, or at/beyond the stop) are skipped, not clamped.
                val validRetracement =
                    if (bearish) level > signalClose && level < stop
                    else level < signalClose && level > stop
                if (!validRetracement) return null

                if (cfg.pdAtEntry) {
                    val zone = context?.dealingRange?.classify(level) ?: return null
                    val wanted = if (bearish) DealingRangeTracker.Zone.PREMIUM
                                 else DealingRangeTracker.Zone.DISCOUNT
                    if (zone != wanted) return null
                }

                val risk = if (bearish) stop - level else level - stop
                if (risk <= 0) return null
                OrderIntent(
                    symbol = eval.symbol,
                    side = if (bearish) Side.SELL else Side.BUY,
                    volumeLots = volumeLots,
                    stopLoss = stop,
                    takeProfit = if (bearish) level - cfg.rMultiple * risk
                                 else level + cfg.rMultiple * risk,
                    decisionTime = eval.closeTime,
                    entryType = EntryType.LIMIT,
                    limitPrice = level,
                    expiryCandles = cfg.entryExpiryCandles,
                    decisionPrice = signalClose,
                )
            }
        }
    }

    companion object {
        val Fvg.midpoint: Double get() = (gapLow + gapHigh) / 2.0

        /** The traded level: midpoint of the leg FVG nearest the signal close. */
        fun chosenEntryLevel(event: DisplacementEvent): Double? {
            val fvgs = event.legFvgs
            if (fvgs.isEmpty()) return null
            return when (event.evaluation.direction) {
                Direction.BULLISH -> fvgs.maxOf { it.midpoint }
                Direction.BEARISH -> fvgs.minOf { it.midpoint }
                Direction.NONE -> null
            }
        }

        /** Counterfactual: near edge of the chosen (nearest) FVG. */
        fun nearEdgeLevel(event: DisplacementEvent): Double? {
            val fvgs = event.legFvgs
            if (fvgs.isEmpty()) return null
            return when (event.evaluation.direction) {
                Direction.BULLISH -> fvgs.maxByOrNull { it.midpoint }!!.gapHigh
                Direction.BEARISH -> fvgs.minByOrNull { it.midpoint }!!.gapLow
                Direction.NONE -> null
            }
        }

        /** Counterfactual: midpoint of the DEEPEST leg FVG (nearest leg origin). */
        fun deepestLevel(event: DisplacementEvent): Double? {
            val fvgs = event.legFvgs
            if (fvgs.isEmpty()) return null
            return when (event.evaluation.direction) {
                Direction.BULLISH -> fvgs.minOf { it.midpoint }
                Direction.BEARISH -> fvgs.maxOf { it.midpoint }
                Direction.NONE -> null
            }
        }

        /** Counterfactual: 50% retracement of the displacement leg. */
        fun halfLegLevel(event: DisplacementEvent): Double =
            (event.evaluation.extremeHigh + event.evaluation.extremeLow) / 2.0
    }
}
