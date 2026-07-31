package ipda.model

import java.time.Duration
import java.time.Instant

/**
 * Core market data model.
 *
 * Standing rules (see CLAUDE.md / discussion notes):
 *  - All timestamps are UTC [Instant]s.
 *  - Only COMPLETED candles exist in this system. A [Candle] is immutable and
 *    represents a fully closed bar; detectors are causal by construction.
 */

enum class Timeframe(val duration: Duration) {
    M1(Duration.ofMinutes(1)),
    M5(Duration.ofMinutes(5)),
    M15(Duration.ofMinutes(15)),
    H1(Duration.ofHours(1)),
    H4(Duration.ofHours(4)),
    D1(Duration.ofDays(1));

    /** Higher ordinal = higher timeframe. Used for the HTF-first tie-break. */
    val rank: Int get() = ordinal
}

enum class Direction { BULLISH, BEARISH, NONE }

/**
 * A completed candle. [openTime] is the UTC open of the bar; the bar is known
 * to be complete, so its close time is `openTime + timeframe.duration`.
 */
data class Candle(
    val symbol: String,
    val timeframe: Timeframe,
    val openTime: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long = 0,
) {
    init {
        require(high >= low) { "high < low at $openTime $symbol" }
        require(high >= open && high >= close) { "high not maximal at $openTime $symbol" }
        require(low <= open && low <= close) { "low not minimal at $openTime $symbol" }
    }

    val closeTime: Instant get() = openTime + timeframe.duration

    /** Body direction: close vs open. NONE for exact dojis (close == open). */
    val direction: Direction
        get() = when {
            close > open -> Direction.BULLISH
            close < open -> Direction.BEARISH
            else -> Direction.NONE
        }

    val body: Double get() = kotlin.math.abs(close - open)
    val range: Double get() = high - low
}

enum class SwingType { HIGH, LOW }

/**
 * A confirmed fractal swing point. Causality: the swing sits at [index]
 * (bar whose extreme is the swing) but only becomes known at [confirmedAtIndex]
 * = index + wing, when the last right-wing candle closes. Detectors and
 * strategy code must only act on swings at/after their confirmation bar.
 */
data class SwingPoint(
    val type: SwingType,
    val index: Int,
    val time: Instant,
    val price: Double,
    val confirmedAtIndex: Int,
    val confirmedAtTime: Instant,
)

/** Fair value gap: BISI (bullish) / SIBI (bearish). Detected at close of candle 3. */
enum class FvgKind { BISI, SIBI }

data class Fvg(
    val kind: FvgKind,
    /** Index of the FIRST candle of the 3-candle pattern. */
    val firstIndex: Int,
    /** Index of the middle (displacement) candle. */
    val middleIndex: Int,
    /** Index of the third candle — the bar at whose close the FVG is known. */
    val thirdIndex: Int,
    /** Gap boundaries: low < high always. */
    val gapLow: Double,
    val gapHigh: Double,
    val detectedAtTime: Instant,
) {
    val size: Double get() = gapHigh - gapLow
}
