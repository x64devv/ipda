package ipda.detect

import ipda.config.DisplacementConfig
import ipda.model.Candle
import ipda.model.Direction
import ipda.model.Fvg
import ipda.model.FvgKind
import ipda.model.SwingPoint
import ipda.model.SwingType
import java.time.Instant
import kotlin.math.abs

/**
 * Displacement per discussion notes §7 — a property of a LEG, not a candle.
 *
 * Leg (v1): maximal run of consecutive same-direction closes (candle body
 * direction, close vs open). An exact doji (close == open) terminates the
 * current leg and belongs to no leg. Known v1 failure mode (documented in §7):
 * one small counter-candle splits a genuine displacement — first planned
 * refinement is tolerating one inside candle mid-run.
 *
 * Range (spec-literal): bearish leg i..j: R = high(i) - low(j); bullish
 * mirrored (R = high(j) - low(i)).
 *
 * Qualifying gates (binary), evaluated causally at EVERY close while a leg is
 * growing — the leg-so-far i..j is a candidate at each close j:
 *   A energy:     R >= k1 * ATR(period) with ATR as of candle i-1 (the leg
 *                 must not inflate its own denominator; unwarm ATR => fail A)
 *   B conviction: |close(j) - open(i)| / R >= bodyRatio
 *   C imbalance:  leg contains >= 1 FVG in leg direction (BISI for bullish,
 *                 SIBI for bearish). Attribution rule (v1, documented): the
 *                 FVG's middle candle lies within the leg and its third candle
 *                 closed within the leg; the first candle of the triple MAY be
 *                 the candle immediately before the leg started.
 *   D speed:      leg length <= m candles. Toggleable, ON by default; length
 *                 is logged regardless so the toggle comparison is a query.
 *
 * Standing rule 2: continuous values (rOverAtr, bodyRatio, fvgCount, length)
 * are logged on EVERY evaluation, qualifying or not.
 *
 * Classification (separate from detection, §7): at the qualifying close, if
 * the leg has closed through the most recent CONFIRMED opposite swing point
 * (confirmed as of this close, swing located before the leg started) => MSS
 * displacement; otherwise displacement into liquidity.
 *
 * A DisplacementEvent is emitted at the FIRST close where the growing leg
 * qualifies (once per leg). Later closes keep evaluating and logging — a leg
 * that qualified at length 3 and grew past the speed cap at length 5 shows
 * that in the evaluation log.
 */
class DisplacementDetector(
    private val cfg: DisplacementConfig,
    swingWing: Int,
) {
    private val atr = AtrCalculator(cfg.atrPeriod, cfg.atrMethod)
    private val fvgs = FvgDetector()
    private val swings = SwingDetector(swingWing)

    private var index = -1
    private var legStart = -1
    private var legStartTime: Instant? = null
    private var legDirection: Direction = Direction.NONE
    private var legCandles = ArrayList<Candle>()
    private var legAlreadyFired = false

    private val _evaluations = ArrayList<LegEvaluation>()
    private val _displacements = ArrayList<DisplacementEvent>()

    /** Every leg evaluation ever made (the sweep-as-query substrate). */
    val evaluations: List<LegEvaluation> get() = _evaluations
    /** Qualifying displacement events, one per qualifying leg. */
    val displacements: List<DisplacementEvent> get() = _displacements

    /**
     * Feed one completed candle. Internal processing order (fixed, documented):
     * ATR -> FVG -> swings -> leg logic. Returns the evaluation of the current
     * leg-so-far at this close, or null if no leg is active (doji / first bar).
     */
    fun onCandle(candle: Candle): LegEvaluation? {
        index++
        atr.onCandle(candle)
        fvgs.onCandle(candle)
        swings.onCandle(candle)

        val dir = candle.direction
        if (dir == Direction.NONE) {
            resetLeg()
            return null
        }
        if (dir != legDirection || legStart < 0) {
            legStart = index
            legStartTime = candle.openTime
            legDirection = dir
            legCandles = ArrayList()
            legAlreadyFired = false
        }
        legCandles.add(candle)

        val eval = evaluate(candle)
        _evaluations.add(eval)

        if (eval.qualifies && !legAlreadyFired) {
            legAlreadyFired = true
            val wantedKind = if (legDirection == Direction.BEARISH) FvgKind.SIBI else FvgKind.BISI
            _displacements.add(
                DisplacementEvent(
                    evaluation = eval,
                    classification = classify(candle),
                    // The leg-attributed directional FVGs (same predicate as
                    // fvgCount) — captured at fire time for the M15
                    // retracement-entry layer and its counterfactual levels.
                    legFvgs = fvgs.all.filter { f ->
                        f.kind == wantedKind && f.middleIndex >= legStart && f.thirdIndex <= index
                    },
                )
            )
        }
        return eval
    }

    private fun resetLeg() {
        legStart = -1
        legStartTime = null
        legDirection = Direction.NONE
        legCandles = ArrayList()
        legAlreadyFired = false
    }

    private fun evaluate(last: Candle): LegEvaluation {
        val first = legCandles.first()
        val length = legCandles.size
        val bearish = legDirection == Direction.BEARISH

        // Spec-literal range: high of first / low of last for bearish, mirrored.
        val range = if (bearish) first.high - last.low else last.high - first.low

        val atrAtStart = atr.valueAsOf(legStart - 1)
        val rOverAtr = if (atrAtStart != null && atrAtStart > 0.0) range / atrAtStart else null
        val bodyRatio = if (range > 0.0) abs(last.close - first.open) / range else 0.0

        val wantedKind = if (bearish) FvgKind.SIBI else FvgKind.BISI
        val fvgCount = fvgs.all.count { f ->
            f.kind == wantedKind && f.middleIndex >= legStart && f.thirdIndex <= index
        }

        val passEnergy = rOverAtr != null && rOverAtr >= cfg.k1
        val passConviction = range > 0.0 && bodyRatio >= cfg.bodyRatio
        val passImbalance = fvgCount >= 1
        val withinSpeedCap = length <= cfg.speedCapCandles
        val passSpeed = !cfg.speedCapEnabled || withinSpeedCap

        return LegEvaluation(
            symbol = last.symbol,
            timeframe = last.timeframe,
            direction = legDirection,
            startIndex = legStart,
            endIndex = index,
            startTime = legStartTime!!,
            endTime = last.openTime,
            closeTime = last.closeTime,
            length = length,
            range = range,
            extremeHigh = if (bearish) first.high else last.high,
            extremeLow = if (bearish) last.low else first.low,
            lastClose = last.close,
            atrAtStart = atrAtStart,
            rOverAtr = rOverAtr,
            bodyRatio = bodyRatio,
            fvgCount = fvgCount,
            passEnergy = passEnergy,
            passConviction = passConviction,
            passImbalance = passImbalance,
            withinSpeedCap = withinSpeedCap,
            speedCapEnabled = cfg.speedCapEnabled,
            qualifies = passEnergy && passConviction && passImbalance && passSpeed,
        )
    }

    private fun classify(last: Candle): DisplacementClassification {
        val swing: SwingPoint? = if (legDirection == Direction.BEARISH)
            swings.lastConfirmedLow else swings.lastConfirmedHigh
        // Swing must sit before the leg started — a leg cannot MSS-break a
        // structure point that only exists because of the leg itself.
        val valid = swing != null && swing.index < legStart
        val broke = valid && when (swing!!.type) {
            SwingType.LOW -> last.close < swing.price
            SwingType.HIGH -> last.close > swing.price
        }
        return if (broke) {
            DisplacementClassification(DisplacementKind.MSS, swing)
        } else {
            DisplacementClassification(DisplacementKind.INTO_LIQUIDITY, null)
        }
    }
}

/** Continuous values logged on every leg evaluation (standing rule 2). */
data class LegEvaluation(
    val symbol: String,
    val timeframe: ipda.model.Timeframe,
    val direction: Direction,
    val startIndex: Int,
    val endIndex: Int,
    val startTime: Instant,
    val endTime: Instant,
    val closeTime: Instant,
    val length: Int,
    val range: Double,
    /** The two price ends that define R (spec-literal): bearish high(i)/low(j), bullish high(j)/low(i). */
    val extremeHigh: Double,
    val extremeLow: Double,
    /** close(j) at evaluation time — the price the signal acts from. */
    val lastClose: Double,
    val atrAtStart: Double?,
    val rOverAtr: Double?,
    val bodyRatio: Double,
    val fvgCount: Int,
    val passEnergy: Boolean,
    val passConviction: Boolean,
    val passImbalance: Boolean,
    val withinSpeedCap: Boolean,
    val speedCapEnabled: Boolean,
    val qualifies: Boolean,
)

enum class DisplacementKind { MSS, INTO_LIQUIDITY }

data class DisplacementClassification(
    val kind: DisplacementKind,
    /** The swing point broken, when kind == MSS. */
    val brokenSwing: SwingPoint?,
)

data class DisplacementEvent(
    val evaluation: LegEvaluation,
    val classification: DisplacementClassification,
    /** Leg-attributed directional FVGs as of the qualifying close (fvgCount's list form). */
    val legFvgs: List<Fvg> = emptyList(),
)
