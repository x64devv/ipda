package ipda.detect

import ipda.model.Candle
import ipda.model.Timeframe
import java.time.Instant

/** Synthetic candle helpers — detectors must be testable with no network (handoff §4). */
object TestCandles {
    val T0: Instant = Instant.parse("2026-01-05T00:00:00Z")

    fun h1(index: Int, open: Double, high: Double, low: Double, close: Double, symbol: String = "EURUSD"): Candle =
        Candle(
            symbol = symbol,
            timeframe = Timeframe.H1,
            openTime = T0.plusSeconds(3600L * index),
            open = open, high = high, low = low, close = close,
        )

    /**
     * Uniform alternating warmup around [base]: even = bull (+5 pips close),
     * odd = bear (back down). Every candle: high = base+6p, low = base-1p on
     * bulls / mirrored so TR is a constant 7 pips. Opens chain to prior close.
     * Equal highs by design => no strict fractal swings form.
     */
    fun alternatingWarmup(count: Int, base: Double = 1.1000, startIndex: Int = 0): List<Candle> =
        (0 until count).map { k ->
            val i = startIndex + k
            if (k % 2 == 0) h1(i, base, base + 0.0006, base - 0.0001, base + 0.0005)
            else h1(i, base + 0.0005, base + 0.0006, base - 0.0001, base)
        }
}
