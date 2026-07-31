package ipda.detect

import ipda.config.DisplacementConfig
import ipda.detect.TestCandles.h1
import ipda.model.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisplacementTest {

    /**
     * Scenario: 21 quiet alternating candles ending on a BULL candle (so the
     * bearish burst starts a fresh leg; ATR20 = 0.0007 and warm as of the
     * pre-leg candle; no swings in the warmup by construction), then a
     * 3-candle bearish burst with SIBI FVGs.
     * Expected: exactly one displacement event, fired at the FIRST close where
     * the growing leg passes all gates (idx 22, leg length 2 — the FVG using
     * the pre-leg candle as candle 1 of the triple completes there).
     */
    private fun burstScenario(): DisplacementDetector {
        val det = DisplacementDetector(DisplacementConfig(), swingWing = 2) // defaults: k1=2, b=0.65, m=4, ATR20
        TestCandles.alternatingWarmup(21).forEach { det.onCandle(it) } // idx 20 = bull, close 1.1005
        det.onCandle(h1(21, 1.1005, 1.1007, 1.0970, 1.0972))
        det.onCandle(h1(22, 1.0972, 1.0973, 1.0940, 1.0942))
        det.onCandle(h1(23, 1.0942, 1.0944, 1.0910, 1.0912))
        return det
    }

    @Test
    fun `qualifying burst fires exactly one event at first qualifying close`() {
        val det = burstScenario()
        assertEquals(1, det.displacements.size)
        val event = det.displacements.single()
        assertEquals(22, event.evaluation.endIndex)
        assertEquals(21, event.evaluation.startIndex)
        assertEquals(2, event.evaluation.length)
        assertEquals(Direction.BEARISH, event.evaluation.direction)
    }

    @Test
    fun `continuous values are logged on every leg evaluation - qualifying or not`() {
        val det = burstScenario()
        // 21 one-candle warmup legs + 3 burst closes = 24 evaluations.
        assertEquals(24, det.evaluations.size)

        // Warmup legs: ATR not warm as of leg start -> energy fails, rOverAtr null, still logged.
        val warm = det.evaluations.first()
        assertNull(warm.rOverAtr)
        assertFalse(warm.passEnergy)
        assertFalse(warm.qualifies)

        // First burst close (idx 21): energy+conviction pass, no FVG yet -> C fails.
        val e21 = det.evaluations[21]
        assertEquals(5.286, e21.rOverAtr!!, 0.01)
        assertTrue(e21.passEnergy)
        assertTrue(e21.passConviction)
        assertFalse(e21.passImbalance)
        assertFalse(e21.qualifies)

        // Final burst close (idx 23): leg 21..23 — R = 1.1007-1.0910 over ATR 0.0007.
        val e23 = det.evaluations[23]
        assertEquals(3, e23.length)
        assertEquals(0.0097, e23.range, 1e-9)
        assertEquals(13.857, e23.rOverAtr!!, 0.01)
        assertEquals(0.9588, e23.bodyRatio, 0.001)
        assertEquals(2, e23.fvgCount) // (20,21,22) and (21,22,23)
        assertTrue(e23.qualifies)     // still qualifying — but no second event
    }

    @Test
    fun `no prior confirmed swing - classified as displacement into liquidity`() {
        val det = burstScenario()
        val event = det.displacements.single()
        assertEquals(DisplacementKind.INTO_LIQUIDITY, event.classification.kind)
        assertNull(event.classification.brokenSwing)
    }

    /**
     * MSS scenario: a confirmed swing low at 1.1985 (bar 2, confirmed bar 4),
     * then a 2-candle bearish burst closing through it.
     */
    @Test
    fun `leg closing through prior confirmed swing is MSS displacement`() {
        val det = DisplacementDetector(
            DisplacementConfig(atrPeriod = 3), // small ATR period keeps the scenario short
            swingWing = 2,
        )
        det.onCandle(h1(0, 1.2010, 1.2011, 1.2000, 1.2005))
        det.onCandle(h1(1, 1.2005, 1.2006, 1.1995, 1.2000))
        det.onCandle(h1(2, 1.2000, 1.2001, 1.1985, 1.1990)) // swing low bar
        det.onCandle(h1(3, 1.1990, 1.2001, 1.1990, 1.2000))
        det.onCandle(h1(4, 1.2000, 1.2006, 1.1999, 1.2005)) // swing low confirms here
        det.onCandle(h1(5, 1.2005, 1.2006, 1.1975, 1.1977)) // burst 1 — no FVG yet
        det.onCandle(h1(6, 1.1977, 1.1978, 1.1940, 1.1943)) // burst 2 — SIBI completes, closes through 1.1985

        assertEquals(1, det.displacements.size)
        val event = det.displacements.single()
        assertEquals(6, event.evaluation.endIndex)
        assertEquals(DisplacementKind.MSS, event.classification.kind)
        val broken = event.classification.brokenSwing
        assertNotNull(broken)
        assertEquals(1.1985, broken.price)
        assertEquals(2, broken.index)
    }

    /**
     * Speed cap (Condition D): a 5-candle grind whose FVG only completes on
     * candle 5. With the cap ON (m=4) it must never fire; with the cap OFF the
     * same tape fires at idx 7 — and withinSpeedCap=false is still logged, so
     * the toggle comparison is a pure query (standing rule 2).
     */
    @Test
    fun `speed cap toggle - same tape, gate on blocks, gate off fires and still logs length`() {
        fun run(capEnabled: Boolean): DisplacementDetector {
            val det = DisplacementDetector(
                DisplacementConfig(atrPeriod = 3, speedCapEnabled = capEnabled),
                swingWing = 2,
            )
            TestCandles.alternatingWarmup(3, base = 1.2000).forEach { det.onCandle(it) }
            det.onCandle(h1(3, 1.2005, 1.2006, 1.2001, 1.2002))
            det.onCandle(h1(4, 1.2002, 1.2004, 1.1998, 1.1999))
            det.onCandle(h1(5, 1.1999, 1.2002, 1.1995, 1.1996))
            det.onCandle(h1(6, 1.1996, 1.1998, 1.1990, 1.1993))
            det.onCandle(h1(7, 1.1993, 1.1994, 1.1950, 1.1952)) // SIBI (5,6,7) completes; leg length 5
            return det
        }

        val gated = run(capEnabled = true)
        assertEquals(0, gated.displacements.size)
        val finalEvalGated = gated.evaluations.last()
        assertEquals(5, finalEvalGated.length)
        assertTrue(finalEvalGated.passEnergy && finalEvalGated.passConviction && finalEvalGated.passImbalance)
        assertFalse(finalEvalGated.withinSpeedCap)
        assertFalse(finalEvalGated.qualifies)

        val open = run(capEnabled = false)
        assertEquals(1, open.displacements.size)
        val event = open.displacements.single()
        assertEquals(7, event.evaluation.endIndex)
        assertEquals(5, event.evaluation.length)
        assertFalse(event.evaluation.withinSpeedCap) // logged regardless of toggle
    }

    @Test
    fun `doji terminates a leg and belongs to none`() {
        val det = DisplacementDetector(DisplacementConfig(atrPeriod = 3), swingWing = 2)
        det.onCandle(h1(0, 1.2000, 1.2001, 1.1990, 1.1992)) // bear
        assertNull(det.onCandle(h1(1, 1.1992, 1.1994, 1.1990, 1.1992))) // doji -> no evaluation
        val eval = det.onCandle(h1(2, 1.1992, 1.1993, 1.1980, 1.1982)) // bear again -> NEW leg
        assertNotNull(eval)
        assertEquals(2, eval.startIndex) // not chained to the leg at idx 0
        assertEquals(1, eval.length)
    }

    @Test
    fun `direction flip starts a new leg`() {
        val det = DisplacementDetector(DisplacementConfig(atrPeriod = 3), swingWing = 2)
        det.onCandle(h1(0, 1.2000, 1.2001, 1.1990, 1.1992)) // bear
        det.onCandle(h1(1, 1.1992, 1.2005, 1.1991, 1.2002)) // bull
        val eval = det.onCandle(h1(2, 1.2002, 1.2010, 1.2001, 1.2008)) // bull continues
        assertNotNull(eval)
        assertEquals(Direction.BULLISH, eval.direction)
        assertEquals(1, eval.startIndex)
        assertEquals(2, eval.length)
    }
}
