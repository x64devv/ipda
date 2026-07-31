package ipda.backtest

import ipda.broker.EntryType
import ipda.broker.Side
import ipda.config.BaselineStrategyConfig
import ipda.config.EntryMode
import ipda.config.defaultSessionTable
import ipda.detect.DealingRangeTracker
import ipda.detect.DisplacementClassification
import ipda.detect.DisplacementEvent
import ipda.detect.DisplacementKind
import ipda.detect.LegEvaluation
import ipda.detect.SessionTagger
import ipda.detect.TestCandles.h1
import ipda.model.Direction
import ipda.model.Fvg
import ipda.model.FvgKind
import ipda.model.SwingPoint
import ipda.model.SwingType
import ipda.model.Timeframe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/** FVG-midpoint limit entry construction + pdAtEntry gate (round 2, 29 Jul 2026). */
class RetracementEntryTest {

    private val sessions = SessionTagger(defaultSessionTable())
    private val candle = h1(50, 1.2000, 1.2001, 1.1940, 1.1943)

    /** Bearish MSS: close 1.1943, leg extremeHigh (stop) 1.2006. */
    private fun bearishEvent(fvgs: List<Fvg>): DisplacementEvent {
        val eval = LegEvaluation(
            symbol = "EURUSD", timeframe = Timeframe.H1, direction = Direction.BEARISH,
            startIndex = 49, endIndex = 50,
            startTime = candle.openTime.minusSeconds(3600),
            endTime = candle.openTime, closeTime = candle.closeTime,
            length = 2, range = 0.0066,
            extremeHigh = 1.2006, extremeLow = 1.1940, lastClose = 1.1943,
            atrAtStart = 0.0020, rOverAtr = 3.3, bodyRatio = 0.9, fvgCount = fvgs.size,
            passEnergy = true, passConviction = true, passImbalance = true,
            withinSpeedCap = true, speedCapEnabled = true, qualifies = true,
        )
        val swing = SwingPoint(SwingType.LOW, 45, candle.openTime.minusSeconds(5 * 3600), 1.1985, 47, candle.openTime.minusSeconds(3 * 3600))
        return DisplacementEvent(eval, DisplacementClassification(DisplacementKind.MSS, swing), fvgs)
    }

    private fun sibi(gapLow: Double, gapHigh: Double) = Fvg(
        kind = FvgKind.SIBI, firstIndex = 48, middleIndex = 49, thirdIndex = 50,
        gapLow = gapLow, gapHigh = gapHigh, detectedAtTime = candle.openTime,
    )

    private fun ctx(range: DealingRangeTracker.Range? = null) = SignalContext(
        candlesSinceHighSweep = null, candlesSinceLowSweep = null,
        lastHighSweepLevel = null, lastLowSweepLevel = null,
        dealingRange = range, sessions = emptySet(), drawTargetLevel = null,
    )

    private fun strat(pdAtEntry: Boolean = false) = BaselineDisplacementStrategy(
        BaselineStrategyConfig(entryMode = EntryMode.FVG_MIDPOINT_LIMIT, entryExpiryCandles = 24, pdAtEntry = pdAtEntry),
        sessions,
    )

    @Test
    fun `limit intent at the midpoint of the FVG nearest the signal close`() {
        // Two SIBIs: midpoints 1.1980 and 1.1996 — bearish nearest-to-close = LOWEST midpoint.
        val event = bearishEvent(listOf(sibi(1.1975, 1.1985), sibi(1.1991, 1.2001)))
        val intent = strat().onBiasCandle(candle, event, ctx())
        assertNotNull(intent)
        assertEquals(EntryType.LIMIT, intent.entryType)
        assertEquals(Side.SELL, intent.side)
        assertEquals(1.1980, intent.limitPrice!!, 1e-9)
        assertEquals(1.2006, intent.stopLoss)
        // risk = 1.2006 - 1.1980 = 0.0026; tp = 1.1980 - 2*0.0026 = 1.1928
        assertEquals(1.1928, intent.takeProfit, 1e-9)
        assertEquals(24, intent.expiryCandles)
    }

    @Test
    fun `no leg FVGs - signal skipped`() {
        assertNull(strat().onBiasCandle(candle, bearishEvent(emptyList()), ctx()))
    }

    @Test
    fun `midpoint outside the retracement window - signal skipped not clamped`() {
        // Midpoint 1.1941 is below the signal close 1.1943 -> not a retracement for a short.
        val event = bearishEvent(listOf(sibi(1.1936, 1.1946)))
        assertNull(strat().onBiasCandle(candle, event, ctx()))
    }

    @Test
    fun `pdAtEntry gates on the zone of the LIMIT level not the close`() {
        val event = bearishEvent(listOf(sibi(1.1975, 1.1985))) // limit 1.1980
        fun range(low: Double, high: Double): DealingRangeTracker.Range {
            val l = SwingPoint(SwingType.LOW, 1, candle.openTime, low, 3, candle.openTime)
            val h = SwingPoint(SwingType.HIGH, 2, candle.openTime, high, 4, candle.openTime)
            return DealingRangeTracker.Range(low, high, l, h)
        }
        // Range (1.1940, 1.2000): eq 1.1970 -> limit 1.1980 in PREMIUM -> short allowed.
        assertNotNull(strat(pdAtEntry = true).onBiasCandle(candle, event, ctx(range(1.1940, 1.2000))))
        // Range (1.1975, 1.2006): eq 1.19905 -> limit 1.1980 in DISCOUNT -> short blocked.
        assertNull(strat(pdAtEntry = true).onBiasCandle(candle, event, ctx(range(1.1975, 1.2006))))
        // No range yet -> blocked.
        assertNull(strat(pdAtEntry = true).onBiasCandle(candle, event, ctx(null)))
    }

    @Test
    fun `market mode is untouched by the new fields`() {
        val strat = BaselineDisplacementStrategy(BaselineStrategyConfig(), sessions)
        val intent = strat.onBiasCandle(candle, bearishEvent(listOf(sibi(1.1975, 1.1985))), ctx())
        assertNotNull(intent)
        assertEquals(EntryType.MARKET, intent.entryType)
        assertNull(intent.limitPrice)
    }

    @Test
    fun `counterfactual level helpers`() {
        val event = bearishEvent(listOf(sibi(1.1975, 1.1985), sibi(1.1991, 1.2001)))
        assertEquals(1.1980, BaselineDisplacementStrategy.chosenEntryLevel(event)!!, 1e-9)
        assertEquals(1.1975, BaselineDisplacementStrategy.nearEdgeLevel(event)!!, 1e-9)  // bearish near edge = gapLow of nearest
        assertEquals(1.1996, BaselineDisplacementStrategy.deepestLevel(event)!!, 1e-9)
        assertEquals((1.2006 + 1.1940) / 2, BaselineDisplacementStrategy.halfLegLevel(event), 1e-9)
    }
}
