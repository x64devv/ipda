package ipda.backtest

import ipda.broker.Side
import ipda.config.BaselineStrategyConfig
import ipda.config.defaultSessionTable
import ipda.detect.DealingRangeTracker
import ipda.detect.DisplacementClassification
import ipda.detect.DisplacementEvent
import ipda.detect.DisplacementKind
import ipda.detect.LegEvaluation
import ipda.detect.SessionTagger
import ipda.detect.TestCandles.h1
import ipda.model.Direction
import ipda.model.SwingPoint
import ipda.model.SwingType
import ipda.model.Timeframe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Unit tests for the v1 context gates (layers B and C, settled 29 Jul 2026).
 * The strategy is exercised directly with synthetic events/contexts; the
 * engine-side wiring is covered by BacktestEngineTest.
 */
class BaselineStrategyGatesTest {

    private val sessions = SessionTagger(defaultSessionTable())
    private val candle = h1(50, 1.2000, 1.2001, 1.1940, 1.1943)

    private fun mssEvent(direction: Direction): DisplacementEvent {
        val bearish = direction == Direction.BEARISH
        val eval = LegEvaluation(
            symbol = "EURUSD", timeframe = Timeframe.H1, direction = direction,
            startIndex = 49, endIndex = 50,
            startTime = candle.openTime.minusSeconds(3600),
            endTime = candle.openTime, closeTime = candle.closeTime,
            length = 2, range = 0.0066,
            extremeHigh = if (bearish) 1.2006 else 1.1943,
            extremeLow = if (bearish) 1.1940 else 1.1877,
            lastClose = if (bearish) 1.1943 else 1.1940,
            atrAtStart = 0.0020, rOverAtr = 3.3, bodyRatio = 0.9, fvgCount = 1,
            passEnergy = true, passConviction = true, passImbalance = true,
            withinSpeedCap = true, speedCapEnabled = true, qualifies = true,
        )
        val swing = SwingPoint(
            type = if (bearish) SwingType.LOW else SwingType.HIGH,
            index = 45, time = candle.openTime.minusSeconds(5 * 3600),
            price = if (bearish) 1.1985 else 1.1900,
            confirmedAtIndex = 47, confirmedAtTime = candle.openTime.minusSeconds(3 * 3600),
        )
        return DisplacementEvent(eval, DisplacementClassification(DisplacementKind.MSS, swing))
    }

    private fun context(
        sinceHigh: Int? = null,
        sinceLow: Int? = null,
        range: DealingRangeTracker.Range? = null,
    ) = SignalContext(
        candlesSinceHighSweep = sinceHigh,
        candlesSinceLowSweep = sinceLow,
        lastHighSweepLevel = sinceHigh?.let { 1.2015 },
        lastLowSweepLevel = sinceLow?.let { 1.1930 },
        dealingRange = range,
        sessions = emptySet(),
        drawTargetLevel = null,
    )

    private fun rangeAround(low: Double, high: Double): DealingRangeTracker.Range {
        val sp = SwingPoint(SwingType.LOW, 1, candle.openTime, low, 3, candle.openTime)
        val sp2 = SwingPoint(SwingType.HIGH, 2, candle.openTime, high, 4, candle.openTime)
        return DealingRangeTracker.Range(low = low, high = high, fromSwing = sp, toSwing = sp2)
    }

    // --- Layer B: sweep gate ---

    @Test
    fun `gates off - v0 behaviour unchanged`() {
        val strat = BaselineDisplacementStrategy(BaselineStrategyConfig(), sessions)
        val intent = strat.onBiasCandle(candle, mssEvent(Direction.BEARISH), context())
        assertNotNull(intent)
        assertEquals(Side.SELL, intent.side)
        assertEquals(1.2006, intent.stopLoss)
    }

    @Test
    fun `sweep gate blocks a short with no high-side sweep`() {
        val strat = BaselineDisplacementStrategy(
            BaselineStrategyConfig(requireSweep = true, sweepLookbackCandles = 24), sessions)
        assertNull(strat.onBiasCandle(candle, mssEvent(Direction.BEARISH), context(sinceHigh = null, sinceLow = 3)))
    }

    @Test
    fun `sweep gate blocks a short whose high-side sweep is older than N`() {
        val strat = BaselineDisplacementStrategy(
            BaselineStrategyConfig(requireSweep = true, sweepLookbackCandles = 24), sessions)
        assertNull(strat.onBiasCandle(candle, mssEvent(Direction.BEARISH), context(sinceHigh = 25)))
    }

    @Test
    fun `sweep gate passes a short with a recent buyside sweep - boundary inclusive`() {
        val strat = BaselineDisplacementStrategy(
            BaselineStrategyConfig(requireSweep = true, sweepLookbackCandles = 24), sessions)
        val intent = strat.onBiasCandle(candle, mssEvent(Direction.BEARISH), context(sinceHigh = 24))
        assertNotNull(intent)
        assertEquals(Side.SELL, intent.side)
    }

    @Test
    fun `sweep gate is direction-aware - longs need the sellside taken`() {
        val strat = BaselineDisplacementStrategy(
            BaselineStrategyConfig(requireSweep = true, sweepLookbackCandles = 24), sessions)
        // Only a HIGH sweep on record: long is blocked.
        assertNull(strat.onBiasCandle(candle, mssEvent(Direction.BULLISH), context(sinceHigh = 2)))
        // LOW sweep on record: long passes.
        val intent = strat.onBiasCandle(candle, mssEvent(Direction.BULLISH), context(sinceLow = 2))
        assertNotNull(intent)
        assertEquals(Side.BUY, intent.side)
    }

    // --- Layer C: premium/discount gate ---

    @Test
    fun `premium-discount gate - short allowed from premium only`() {
        val strat = BaselineDisplacementStrategy(
            BaselineStrategyConfig(premiumDiscountOnly = true), sessions)
        // signal close 1.1943; equilibrium of (1.1900, 1.1950) = 1.1925 -> premium
        assertNotNull(strat.onBiasCandle(candle, mssEvent(Direction.BEARISH), context(range = rangeAround(1.1900, 1.1950))))
        // equilibrium of (1.1940, 1.2010) = 1.1975 -> close 1.1943 is discount -> short blocked
        assertNull(strat.onBiasCandle(candle, mssEvent(Direction.BEARISH), context(range = rangeAround(1.1940, 1.2010))))
    }

    @Test
    fun `premium-discount gate - long allowed from discount only`() {
        val strat = BaselineDisplacementStrategy(
            BaselineStrategyConfig(premiumDiscountOnly = true), sessions)
        // long close 1.1940; range (1.1930, 1.2010) eq 1.1970 -> discount -> allowed
        assertNotNull(strat.onBiasCandle(candle, mssEvent(Direction.BULLISH), context(range = rangeAround(1.1930, 1.2010))))
        // range (1.1900, 1.1950) eq 1.1925 -> close 1.1940 is premium -> long blocked
        assertNull(strat.onBiasCandle(candle, mssEvent(Direction.BULLISH), context(range = rangeAround(1.1900, 1.1950))))
    }

    @Test
    fun `premium-discount gate blocks when no dealing range exists yet`() {
        val strat = BaselineDisplacementStrategy(
            BaselineStrategyConfig(premiumDiscountOnly = true), sessions)
        assertNull(strat.onBiasCandle(candle, mssEvent(Direction.BEARISH), context(range = null)))
    }

    @Test
    fun `layers compose - B and C together require both conditions`() {
        val strat = BaselineDisplacementStrategy(
            BaselineStrategyConfig(requireSweep = true, sweepLookbackCandles = 24, premiumDiscountOnly = true),
            sessions)
        val premium = rangeAround(1.1900, 1.1950)
        // Sweep ok, zone ok -> trade.
        assertNotNull(strat.onBiasCandle(candle, mssEvent(Direction.BEARISH), context(sinceHigh = 3, range = premium)))
        // Sweep ok, wrong zone -> blocked.
        assertNull(strat.onBiasCandle(candle, mssEvent(Direction.BEARISH), context(sinceHigh = 3, range = rangeAround(1.1940, 1.2010))))
        // Zone ok, no sweep -> blocked.
        assertNull(strat.onBiasCandle(candle, mssEvent(Direction.BEARISH), context(range = premium)))
    }
}
