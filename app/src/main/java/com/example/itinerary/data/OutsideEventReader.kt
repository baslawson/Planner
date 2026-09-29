package com.example.itinerary.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// Reads the events a calendar server sends for calendar sync: one VCALENDAR per stored event, with repeats already
// expanded by the server into single dates. Unlike CalendarImport it is forgiving: a broken event is skipped and counted,
// a cancelled one is left out, and over-long text is shortened, so one odd event never hides a whole calendar.
object OutsideEventReader {
    class Result(val events: List<OutsideEvent>, val skipped: Int)

    // Timed events longer than this are shown across their days like an all-day event, with their real times noted.
    private const val MAX_TIMED_MINUTES = 1440L

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
        val title = one("SUMMARY")?.value?.let(Ics::unescape)?.trim()?.takeIf { it.isNotEmpty() }?.take(500) ?: "(No title)"
        val location = one("LOCATION")?.value?.let(Ics::unescape)?.trim().orEmpty().take(2000)
        val notes = one("DESCRIPTION")?.value?.let(Ics::unescape)?.trim().orEmpty().take(20_000)
        if (Ics.isDate(start)) {
            val first = LocalDate.parse(start.value.take(8), DateTimeFormatter.BASIC_ISO_DATE)
            val after = when {
                end != null && Ics.isDate(end) -> LocalDate.parse(end.value.take(8), DateTimeFormatter.BASIC_ISO_DATE)
                duration != null -> first.plusDays(duration(duration.value).toDays())
                else -> first.plusDays(1)
            }
            // The end in the file is the day after the last one. Keep at least one day and at most MultiDay.MAX_DAYS.
            val days = ChronoUnit.DAYS.between(first, after).coerceIn(1, MultiDay.MAX_DAYS.toLong())
            return OutsideEvent(sourceId = 0, date = first, startTime = null,
                endDate = first.plusDays(days - 1).takeIf { days > 1 }, title = title, location = location, notes = notes)
        }
        val begin = time(start, zone)
        val finish = when {
            end != null -> time(end, zone)
            duration != null -> begin.plus(duration(duration.value))
            else -> null
        }
        // Wall-clock minutes, the way Planner shows durations (see CalendarImport).
        val minutes = finish?.let { ChronoUnit.MINUTES.between(begin.toLocalDateTime(), it.toLocalDateTime()) }?.takeIf { it > 0 }
        if (minutes == null || minutes <= MAX_TIMED_MINUTES) {
            return OutsideEvent(sourceId = 0, date = begin.toLocalDate(), startTime = begin.toLocalTime(),
                durationMinutes = minutes?.toInt(), title = title, location = location, notes = notes)
        }
        // Ending exactly at midnight means the day before was the last one it covered.
        val lastDay = finish!!.toLocalDate().let { if (finish.toLocalTime() == LocalTime.MIDNIGHT) it.minusDays(1) else it }
            .coerceAtMost(begin.toLocalDate().plusDays(MultiDay.MAX_DAYS - 1L))
        return OutsideEvent(sourceId = 0, date = begin.toLocalDate(), startTime = null,
            endDate = lastDay.takeIf { it > begin.toLocalDate() }, timedStart = begin.toLocalTime(), timedEnd = finish.toLocalTime(),
            title = title, location = location, notes = notes)
    }

    // iCalendar durations may be in weeks ("P2W"), which java.time.Duration doesn't read.
    private fun duration(value: String): java.time.Duration {
        val weeks = Regex("([+-]?)P([0-9]+)W").matchEntire(value) ?: return java.time.Duration.parse(value)
        val length = java.time.Duration.ofDays(weeks.groupValues[2].toLong() * 7)
        return if (weeks.groupValues[1] == "-") length.negated() else length
    }

    // To the phone's clock, to the minute. An unknown time zone is read as the phone's own rather than dropping the event.
    private fun time(p: Ics.Property, zone: ZoneId): ZonedDateTime =
        Ics.time(p, zone, strictGap = false) { runCatching { ZoneId.of(it) }.getOrDefault(zone) }
            .withZoneSameInstant(zone).truncatedTo(ChronoUnit.MINUTES)
}
