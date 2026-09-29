package com.example.itinerary.data

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

// Reads the events a calendar server sends for calendar sync: one VCALENDAR per stored event, with repeats already
// expanded by the server into single dates. Forgiving: a broken event is skipped and counted, a cancelled one is left
// out, and over-long text is shortened, so one odd event never hides a whole calendar.
object OutsideEventReader {
    class Result(val events: List<OutsideEvent>, val skipped: Int)

    fun read(calendarData: List<String>, zone: ZoneId): Result {
        var skipped = 0
        val events = calendarData.flatMap { text ->
            val lines = runCatching { Ics.lines(text) }.getOrElse { skipped++; return@flatMap emptyList() }
            val components = runCatching { Ics.events(lines, 10_000, "Too many events.") }.getOrElse { skipped++; return@flatMap emptyList() }
            components.mapNotNull { props ->
                runCatching { event(props, zone) }.getOrElse { skipped++; null }
            }
        }
        return Result(events.distinct(), skipped)
    }

    private fun event(props: List<Ics.Property>, zone: ZoneId): OutsideEvent? {
        fun one(name: String): Ics.Property? = props.firstOrNull { it.name == name }
        if (one("STATUS")?.value?.uppercase() == "CANCELLED") return null
        val start = requireNotNull(one("DTSTART"))
        val end = one("DTEND"); val duration = one("DURATION")
        val timing = if (Ics.isDate(start)) Ics.date(start).let { Ics.allDay(it, Ics.allDayLength(it, end, duration)) } else {
            val begin = time(start, zone)
            val finish = when {
                end != null -> time(end, zone)
                duration != null -> begin.plus(Ics.duration(duration.value))
                else -> null
            }
            Ics.timed(begin.toLocalDateTime(), finish?.toLocalDateTime())
        }
        return OutsideEvent(sourceId = 0, date = timing.date, startTime = timing.startTime, durationMinutes = timing.durationMinutes,
            endDate = timing.endDate, timedStart = timing.timedStart, timedEnd = timing.timedEnd,
            title = one("SUMMARY")?.value?.let(Ics::unescape)?.trim()?.takeIf { it.isNotEmpty() }?.take(500) ?: "(No title)",
            location = one("LOCATION")?.value?.let(Ics::unescape)?.trim().orEmpty().take(2000),
            notes = one("DESCRIPTION")?.value?.let(Ics::unescape)?.trim().orEmpty().take(20_000))
    }

    // To the phone's clock, to the minute. An unknown time zone is read as the phone's own rather than dropping the event.
    private fun time(p: Ics.Property, zone: ZoneId): ZonedDateTime =
        Ics.time(p, zone, strictGap = false) { runCatching { ZoneId.of(it) }.getOrDefault(zone) }
            .withZoneSameInstant(zone).truncatedTo(ChronoUnit.MINUTES)
}
