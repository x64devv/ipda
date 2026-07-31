package ipda.detect

import ipda.detect.DealingRangeTracker.Zone
import ipda.detect.TestCandles.h1
import ipda.model.SwingType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class DealingRangeTest {

    @Test
    fun `no range until one swing of each type is confirmed`() {
        val swings = SwingDetector(wing = 2)
        val tracker = DealingRangeTracker(swings)

        val candles = listOf(
            h1(0, 1.2000, 1.2005, 1.1999, 1.2004),
            h1(1, 1.2004, 1.2010, 1.2000, 1.2008),
            h1(2, 1.2008, 1.2025, 1.2005, 1.2010), // swing high 1.2025
            h1(3, 1.2010, 1.2012, 1.2000, 1.2002),
            h1(4, 1.2002, 1.2004, 1.1995, 1.1998), // confirms the high — still no low
        )
        candles.forEach { swings.onCandle(it) }
        assertNull(tracker.current()) // high confirmed, low not yet

        // Extend: swing low 1.1975 at bar 5, confirmed at bar 7.
        listOf(
            h1(5, 1.1998, 1.2000, 1.1975, 1.1980),
            h1(6, 1.1980, 1.2002, 1.1978, 1.2000),
            h1(7, 1.2000, 1.2004, 1.1998, 1.2002),
        ).forEach { swings.onCandle(it) }

        val range = tracker.current()
        assertNotNull(range)
        assertEquals(1.1975, range.low)
        assertEquals(1.2025, range.high)
        assertEquals(1.2000, range.equilibrium, 1e-9)
    }

    @Test
    fun `premium discount classification and continuous position`() {
        val range = DealingRangeTracker.Range(
            low = 1.1975, high = 1.2025,
            fromSwing = swingStub(SwingType.LOW, 1.1975),
            toSwing = swingStub(SwingType.HIGH, 1.2025),
        )
        assertEquals(Zone.PREMIUM, range.classify(1.2010))
        assertEquals(Zone.DISCOUNT, range.classify(1.1990))
        assertEquals(Zone.EQUILIBRIUM, range.classify(1.2000))
        assertEquals(0.5, range.position(1.2000), 1e-9)
        assertEquals(1.0, range.position(1.2025), 1e-9)
        assertEquals(0.0, range.position(1.1975), 1e-9)
    }

    private fun swingStub(type: SwingType, price: Double) = ipda.model.SwingPoint(
        type = type, index = 0, time = TestCandles.T0, price = price,
        confirmedAtIndex = 2, confirmedAtTime = TestCandles.T0,
    )
}
