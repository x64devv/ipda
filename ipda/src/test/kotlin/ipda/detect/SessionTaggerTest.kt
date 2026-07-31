package ipda.detect

import ipda.config.defaultSessionTable
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The DST-transition coverage required by the handoff: 2026 has US DST from
 * 8 Mar and UK DST from 29 Mar, so 8–29 Mar is the "split" window where the
 * London/NY offset is 4h instead of 5h. A fixed-UTC session table would be
 * wrong in exactly these tests.
 */
class SessionTaggerTest {

    private val tagger = SessionTagger(defaultSessionTable())

    // --- Winter (both zones on standard time) ---

    @Test
    fun `winter - London KZ 0700-1000 local equals UTC`() {
        assertTrue("LONDON_KZ" in tagger.tag(Instant.parse("2026-01-14T07:30:00Z")))
        assertFalse("LONDON_KZ" in tagger.tag(Instant.parse("2026-01-14T06:30:00Z")))
        assertFalse("LONDON_KZ" in tagger.tag(Instant.parse("2026-01-14T10:00:00Z"))) // end exclusive
    }

    @Test
    fun `winter - NY KZ 0700-1000 local equals 1200-1500 UTC`() {
        assertTrue("NY_KZ" in tagger.tag(Instant.parse("2026-01-14T12:30:00Z")))
        assertFalse("NY_KZ" in tagger.tag(Instant.parse("2026-01-14T11:30:00Z")))
    }

    // --- Summer (both zones on DST) ---

    @Test
    fun `summer - London KZ shifts to 0600-0900 UTC`() {
        assertTrue("LONDON_KZ" in tagger.tag(Instant.parse("2026-04-15T06:30:00Z")))
        assertFalse("LONDON_KZ" in tagger.tag(Instant.parse("2026-04-15T09:30:00Z"))) // would be KZ in winter
    }

    @Test
    fun `summer - NY KZ shifts to 1100-1400 UTC`() {
        assertTrue("NY_KZ" in tagger.tag(Instant.parse("2026-04-15T11:30:00Z")))
        assertFalse("NY_KZ" in tagger.tag(Instant.parse("2026-04-15T14:30:00Z")))
    }

    // --- The split window: US on DST (from 8 Mar 2026), UK not yet (29 Mar 2026) ---

    @Test
    fun `split window - London still on GMT, NY already on EDT`() {
        // 16 Mar 2026: London KZ still 07:00-10:00 UTC...
        assertTrue("LONDON_KZ" in tagger.tag(Instant.parse("2026-03-16T07:30:00Z")))
        assertFalse("LONDON_KZ" in tagger.tag(Instant.parse("2026-03-16T06:30:00Z")))
        // ...but NY KZ is already 11:00-14:00 UTC.
        assertTrue("NY_KZ" in tagger.tag(Instant.parse("2026-03-16T11:30:00Z")))
        assertFalse("NY_KZ" in tagger.tag(Instant.parse("2026-03-16T14:30:00Z")))
    }

    // --- Midnight-crossing window ---

    @Test
    fun `Asia session crosses NY midnight`() {
        // 01:30 UTC on 15 Jan = 20:30 (14 Jan) New York -> inside 20:00-00:00.
        assertTrue("ASIA" in tagger.tag(Instant.parse("2026-01-15T01:30:00Z")))
        // 05:30 UTC = 00:30 NY -> outside (end exclusive at local midnight).
        assertFalse("ASIA" in tagger.tag(Instant.parse("2026-01-15T05:30:00Z")))
    }

    @Test
    fun `tagging is a pure function - same instant same labels`() {
        val t = Instant.parse("2026-03-16T07:30:00Z")
        assertEquals(tagger.tag(t), tagger.tag(t))
    }
}

class DailyBoundaryTest {

    private val boundary = DailyBoundary("America/New_York")

    @Test
    fun `trading day rolls at NY midnight, not UTC midnight`() {
        // 01:30 UTC on 15 Jan is still 14 Jan in New York (20:30 EST).
        assertEquals("2026-01-14", boundary.tradingDay(Instant.parse("2026-01-15T01:30:00Z")).toString())
        // 05:30 UTC on 15 Jan is 00:30 EST -> 15 Jan.
        assertEquals("2026-01-15", boundary.tradingDay(Instant.parse("2026-01-15T05:30:00Z")).toString())
    }

    @Test
    fun `daily open is NY local midnight expressed in UTC - and follows DST`() {
        // EST (UTC-5): daily open 05:00 UTC.
        assertEquals(
            Instant.parse("2026-01-15T05:00:00Z"),
            boundary.dailyOpen(Instant.parse("2026-01-15T12:00:00Z")),
        )
        // EDT (UTC-4): daily open 04:00 UTC.
        assertEquals(
            Instant.parse("2026-04-15T04:00:00Z"),
            boundary.dailyOpen(Instant.parse("2026-04-15T12:00:00Z")),
        )
    }
}
