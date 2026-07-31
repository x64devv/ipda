package ipda.detect

import ipda.config.SessionDef
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure, causal session tagging: Instant -> set of session labels.
 *
 * Killzones are defined in exchange-local time (tz database), so DST is
 * handled by java.time, not by us. A window [start, end) is evaluated on the
 * wall clock of its own zone; start > end means it crosses local midnight.
 *
 * Candles are tagged by their OPEN time (the convention must be applied
 * identically in backtest and live — it is, because this is the only tagger).
 */
class SessionTagger(sessions: List<SessionDef>) {

    private data class Window(
        val name: String,
        val zone: ZoneId,
        val start: LocalTime,
        val end: LocalTime,
    ) {
        fun contains(t: Instant): Boolean {
            val local = ZonedDateTime.ofInstant(t, zone).toLocalTime()
            return if (start <= end) {
                // Same-day window, [start, end)
                local >= start && local < end
            } else {
                // Crosses local midnight, e.g. 20:00 -> 00:00 or 22:00 -> 02:00
                local >= start || local < end
            }
        }
    }

    private val windows: List<Window> = sessions.map {
        Window(
            name = it.name,
            zone = ZoneId.of(it.zone),
            start = LocalTime.parse(it.start),
            end = LocalTime.parse(it.end),
        )
    }

    /** All session labels active at instant [t]. Pure and deterministic. */
    fun tag(t: Instant): Set<String> =
        windows.filter { it.contains(t) }.map { it.name }.toSet()
}

/**
 * Daily boundary derivation — ICT NY-midnight convention. We derive our own
 * trading-day identity from UTC timestamps instead of trusting platform daily
 * bars, whose alignment is broker/server dependent. Identical in backtest and
 * live by construction.
 */
class DailyBoundary(zoneId: String = "America/New_York") {
    private val zone: ZoneId = ZoneId.of(zoneId)

    /** The trading-day date (local calendar date in the boundary zone) containing [t]. */
    fun tradingDay(t: Instant): LocalDate =
        ZonedDateTime.ofInstant(t, zone).toLocalDate()

    /** The instant of the daily open (local midnight in the boundary zone) for [t]'s trading day. */
    fun dailyOpen(t: Instant): Instant =
        tradingDay(t).atStartOfDay(zone).toInstant()
}
