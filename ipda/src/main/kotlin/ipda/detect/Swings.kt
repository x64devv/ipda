package ipda.detect

import ipda.model.Candle
import ipda.model.SwingPoint
import ipda.model.SwingType

/**
 * Streaming fractal swing detector.
 *
 * Bar i is a swing high iff high(i) is STRICTLY greater than the highs of the
 * `wing` bars on each side (mirrored for lows). Equal extremes do not form a
 * swing — equal highs/lows are a separate liquidity primitive, not a fractal.
 *
 * Causality: the swing at bar i is only KNOWN once bar i+wing closes. onCandle
 * therefore emits swings confirmed BY the candle just processed, never earlier.
 * Strategy code must key off confirmedAtIndex, not index.
 */
class SwingDetector(private val wing: Int) {

    init {
        require(wing >= 1) { "wing must be >= 1" }
    }

    private val buffer = ArrayList<Candle>()
    private var nextIndex = 0

    private val _confirmed = ArrayList<SwingPoint>()
    /** All swings confirmed so far, in confirmation order. */
    val confirmed: List<SwingPoint> get() = _confirmed

    /** Most recent CONFIRMED swing of each type (what MSS classification uses). */
    var lastConfirmedHigh: SwingPoint? = null
        private set
    var lastConfirmedLow: SwingPoint? = null
        private set

    /**
     * Feed one completed candle; returns the swings (0, 1, or 2 — a bar can be
     * both if wings differ, but with symmetric wings at most one high and one
     * low candidate confirm together) newly confirmed at this candle's close.
     */
    fun onCandle(candle: Candle): List<SwingPoint> {
        buffer.add(candle)
        val j = nextIndex // index of the candle just added
        nextIndex++

        val center = j - wing // bar whose swing status is decided by this close
        if (center < wing) return emptyList() // not enough left wing yet

        val out = ArrayList<SwingPoint>(2)
        val c = buffer[center]

        val isHigh = (1..wing).all { k ->
            buffer[center - k].high < c.high && buffer[center + k].high < c.high
        }
        val isLow = (1..wing).all { k ->
            buffer[center - k].low > c.low && buffer[center + k].low > c.low
        }

        if (isHigh) {
            val sp = SwingPoint(SwingType.HIGH, center, c.openTime, c.high, j, candle.openTime)
            _confirmed.add(sp); lastConfirmedHigh = sp; out.add(sp)
        }
        if (isLow) {
            val sp = SwingPoint(SwingType.LOW, center, c.openTime, c.low, j, candle.openTime)
            _confirmed.add(sp); lastConfirmedLow = sp; out.add(sp)
        }
        return out
    }
}
