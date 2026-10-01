package com.example.itinerary.data

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

// Import calendar file (step 2 of calendar sync): a whole .ics export from Google, Outlook, Apple and the like, or a
// single invitation, read into rows the user ticks before anything is saved. The events become ordinary Planner events,
// a one-time copy. Repeating events become a Planner series (up to 365 dates, reaching at most 12 months ahead); the
// common repeat patterns are worked out here (see IcsRepeat), an unusual one keeps its first date with a note.
// Forgiving like calendar sync: an event that can't be read is skipped and counted, a cancelled one is left out.
object CalendarFileImport {
    const val MAX_BYTES = 10 * 1024 * 1024
    const val MAX_EVENTS = 2000
    const val MAX_SAVED = 10_000 // events saved in one import, counting every date of a series
    const val MAX_SERIES = 365
    const val MONTHS_AHEAD = 12L

    // One row of the list: a single event ([dates] has one date) or a repeating one with every date it falls on.
    // [item] is the event on its first date; the others are the same event moved to each date.
    data class Entry(val id: Int, val item: ItineraryItem, val dates: List<LocalDate>, val repeat: RepeatRule, val note: String? = null) {
        val repeating: Boolean get() = dates.size > 1

        // The dates that would be imported: from today on (an occurrence still under way counts), or all of them with
        // past events included; the latest 365 when there are more.
        fun datesFor(today: LocalDate, includePast: Boolean): List<LocalDate> {
            val span = item.endDate?.let { ChronoUnit.DAYS.between(item.date, it) } ?: 0
            return (if (includePast) dates else dates.filter { !it.plusDays(span).isBefore(today) }).takeLast(MAX_SERIES)
        }

        fun past(today: LocalDate): Boolean = datesFor(today, includePast = false).isEmpty()
    }

    class Result(val entries: List<Entry>, val skipped: Int)

    private class Row(val item: ItineraryItem, val dates: List<LocalDate>, val repeat: RepeatRule, val note: String?)

    // One event read from a file with every date it falls on, on the phone's clock.
    private class Expanded(val timings: List<Ics.Timing>, val title: String, val location: String, val notes: String,
                           val repeat: IcsRepeat?, val repeatStart: LocalDate, val note: String?)

    fun read(text: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): Result {
        val lines = checkedLines(text)
        require(lines.none { it.equals("METHOD:CANCEL", true) }) { "This is a cancellation, not a new appointment." }
        val (expanded, skipped) = expandAll(lines, zone, today.plusMonths(MONTHS_AHEAD), from = null)
        val entries = expanded.flatMap(::rows).map { Entry(0, it.item, it.dates, it.repeat, it.note) }
            .sortedWith(compareBy<Entry> { it.item.date }.thenBy { it.item.startTime }.thenBy { it.item.title })
            .mapIndexed { index, entry -> entry.copy(id = index) }
        require(entries.isNotEmpty() || skipped > 0) { "No events found in this file." }
        require(entries.size <= MAX_EVENTS) { "This file has ${entries.size} events; import at most $MAX_EVENTS at once." }
        require(entries.isNotEmpty()) { "None of the events in this file could be read." }
        return Result(entries, skipped)
    }

    // A subscribed calendar (step 4): the dates between [from] and [until] as outside events, plus the calendar's own
    // name and colour when the file has them. An empty window is fine; a file that isn't a calendar is not.
    class Window(val events: List<OutsideEvent>, val skipped: Int, val name: String?, val color: Int?)

    fun window(text: String, zone: ZoneId, from: LocalDate, until: LocalDate): Window {
        val lines = checkedLines(text)
        val (expanded, skipped) = expandAll(lines, zone, until, from)
        val events = expanded.flatMap { e ->
            e.timings.filter { !(it.endDate ?: it.date).isBefore(from) && !it.date.isAfter(until) }.map { t ->
                OutsideEvent(sourceId = 0, date = t.date, startTime = t.startTime, durationMinutes = t.durationMinutes, endDate = t.endDate,
                    timedStart = t.timedStart, timedEnd = t.timedEnd, title = e.title, location = e.location, notes = e.notes)
            }
        }.distinct()
        // Calendar-wide properties come before the first event.
        val header = lines.takeWhile { !it.equals("BEGIN:VEVENT", true) }.mapNotNull { runCatching { Ics.property(it) }.getOrNull() }
        val name = header.firstOrNull { it.name == "X-WR-CALNAME" }?.value?.let(Ics::unescape)?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
        val color = header.firstOrNull { it.name == "X-APPLE-CALENDAR-COLOR" || it.name == "COLOR" }?.value?.trim()
            ?.let { Regex("#([0-9A-Fa-f]{6})([0-9A-Fa-f]{2})?").matchEntire(it) }?.let { (0xFF000000L or it.groupValues[1].toLong(16)).toInt() }
        return Window(events, skipped, name, color)
    }

    private fun checkedLines(text: String): List<String> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "This calendar file is too large (maximum 10 MB)." }
        val lines = Ics.lines(text)
        require(lines.firstOrNull()?.uppercase() == "BEGIN:VCALENDAR") { "This isn't a calendar file." }
        return lines
    }

    // Every event in the file with its dates up to [limit] (and from [from], when given), and how many couldn't be read.
    private fun expandAll(lines: List<String>, zone: ZoneId, limit: LocalDate, from: LocalDate?): Pair<List<Expanded>, Int> {
        val events = Ics.events(lines, 50_000, "This calendar file has too many events.", unfinished = "This calendar file is incomplete.")
        var skipped = 0
        fun one(props: List<Ics.Property>, name: String) = props.firstOrNull { it.name == name }
        // Moved or cancelled single dates of a repeating event: the same UID with a RECURRENCE-ID.
        // One whose repeating event isn't in the file is just an event of its own.
        val (candidates, others) = events.partition { one(it, "RECURRENCE-ID") != null && one(it, "UID") != null }
        val masterUids = others.mapNotNull { one(it, "UID")?.value }.toSet()
        val (overrides, orphans) = candidates.partition { one(it, "UID")!!.value in masterUids }
        val replaced = overrides.groupBy { one(it, "UID")!!.value }
        val result = mutableListOf<Expanded>()
        for (props in others + orphans) {
            try {
                if (one(props, "STATUS")?.value?.uppercase() == "CANCELLED") continue
                val moved = one(props, "UID")?.let { replaced[it.value] }.orEmpty()
                expand(props, moved, zone, limit, from)?.let { result += it }
                moved.forEach { override ->
                    if (one(override, "STATUS")?.value?.uppercase() != "CANCELLED") expand(override, emptyList(), zone, limit, from)?.let { result += it }
                }
            } catch (_: Exception) { skipped++ }
        }
        return result to skipped
    }

    private fun expand(props: List<Ics.Property>, moved: List<List<Ics.Property>>, zone: ZoneId, limit: LocalDate, from: LocalDate?): Expanded? {
        fun one(name: String) = props.firstOrNull { it.name == name }
        val start = requireNotNull(one("DTSTART"))
        val end = one("DTEND"); val duration = one("DURATION")
        val allDay = Ics.isDate(start)
        val ownZone = if (allDay || start.value.endsWith("Z")) zone else start.params["TZID"]?.let(Ics::zone) ?: zone
        val first: ZonedDateTime = if (allDay) Ics.date(start).atStartOfDay(zone) else strictTime(start, zone)
        val firstLocal = first.withZoneSameInstant(ownZone).toLocalDateTime()
        // An all-day event longer than Planner holds is cut to MultiDay.MAX_DAYS (a one-time copy, or a read-only one in a
        // subscribed calendar: nothing is written back); an import says so.
        val fullDays = if (allDay) Ics.allDayDays(first.toLocalDate(), end, duration) else 0
        val days = fullDays.coerceAtMost(MultiDay.MAX_DAYS.toLong())
        val length: Duration? = if (allDay) null else when {
            end != null -> Duration.between(first, strictTime(end, zone)).also { require(!it.isNegative) }
            duration != null -> Ics.duration(duration.value)
            else -> null
        }
        // The dates on the event's own clock. A window starts a little early: the phone's date can differ by a day, and a
        // multi-day date that began before the window still covers it.
        var note: String? = null
        val rule = one("RRULE")?.value
        val repeat = rule?.let { IcsRepeat.parse(it, ownZone, firstLocal.toLocalDate()) }
        if (rule != null && repeat == null) note = "Repeats in a way Planner can't copy, so only the first date is imported."
        if (fullDays > MultiDay.MAX_DAYS) note = listOfNotNull(note, "Lasts $fullDays days; Planner imports the first ${MultiDay.MAX_DAYS}.").joinToString(" ")
        val keepFrom = from?.minusDays(days.coerceAtLeast(1) + 1) ?: firstLocal.toLocalDate()
        val dates = (repeat?.dates(firstLocal.toLocalDate(), firstLocal.toLocalTime(), limit, from = keepFrom)
            ?: listOf(firstLocal.toLocalDate())).toMutableSet()
        props.filter { it.name == "RDATE" }.flatMap { values(it, ownZone) }.filter { it <= limit }.forEach { dates += it }
        val removed = props.filter { it.name == "EXDATE" }.flatMap { values(it, ownZone) } +
            moved.mapNotNull { it.firstOrNull { p -> p.name == "RECURRENCE-ID" } }.flatMap { values(it, ownZone) }
        dates -= removed.toSet()
        if (dates.isEmpty()) return null
        // Each date on the phone's clock.
        val timings = dates.sorted().map { date ->
            if (allDay) Ics.allDay(date, days) else {
                val begin = date.atTime(firstLocal.toLocalTime()).atZone(ownZone).withZoneSameInstant(zone).truncatedTo(ChronoUnit.MINUTES)
                Ics.timed(begin.toLocalDateTime(), length?.let { begin.plus(it).truncatedTo(ChronoUnit.MINUTES).toLocalDateTime() })
            }
        }
        return Expanded(timings,
            title = one("SUMMARY")?.value?.let(Ics::unescape)?.trim()?.takeIf { it.isNotEmpty() }?.take(500) ?: "Imported event",
            location = one("LOCATION")?.value?.let(Ics::unescape)?.trim().orEmpty().take(2000),
            notes = one("DESCRIPTION")?.value?.let(Ics::unescape)?.trim().orEmpty().take(20_000),
            repeat = repeat, repeatStart = firstLocal.toLocalDate(), note = note)
    }

    // One event (with its repeats) as import rows: usually one, but more when daylight-saving differences between its own
    // time zone and the phone's put some dates at another clock time — a Planner series has one time.
    private fun rows(e: Expanded): List<Row> =
        e.timings.groupBy { listOf(it.startTime, it.durationMinutes, it.endDate?.let { end -> ChronoUnit.DAYS.between(it.date, end) }, it.timedStart, it.timedEnd) }
            .values.map { group ->
                val t = group.first()
                // Planner can't hold a timed event longer than a day: it becomes all-day over the same days, times noted.
                val longNote = t.timedStart?.let { s -> "Originally from ${s} on the first day to ${t.timedEnd} on the last day." }
                val item = ItineraryItem(tripId = 0, date = t.date, startTime = t.startTime, durationMinutes = t.durationMinutes,
                    endDate = t.endDate, title = e.title, location = e.location,
                    notes = listOfNotNull(longNote, e.notes.takeIf { it.isNotEmpty() }).joinToString("\n\n").take(20_000))
                val plannerRule = if (group.size > 1) e.repeat?.plannerRule(e.repeatStart) ?: RepeatRule.NONE else RepeatRule.NONE
                Row(item, group.map { it.date }, plannerRule, e.note)
            }

    // The dates in an EXDATE, RDATE or RECURRENCE-ID (a list of dates or date-times) on the event's own clock.
    private fun values(p: Ics.Property, zone: ZoneId): List<LocalDate> = p.value.split(',').filter { it.isNotBlank() }.map { value ->
        val part = Ics.Property(p.name, p.params, value.trim())
        if (Ics.isDate(part)) Ics.date(part) else strictTime(part, zone).withZoneSameInstant(
            if (value.trim().endsWith("Z")) zone else p.params["TZID"]?.let(Ics::zone) ?: zone).toLocalDate()
    }

    // A date-time in its own zone (unknown zones refuse the event rather than guessing its time).
    private fun strictTime(p: Ics.Property, zone: ZoneId): ZonedDateTime = Ics.time(p, zone, strictGap = false, Ics::zone)

    // The Planner events to save for the ticked rows: a repeating row becomes one series (shared seriesId).
    fun events(entries: List<Entry>, today: LocalDate, includePast: Boolean): List<ItineraryItem> {
        val result = entries.flatMap { entry ->
            val dates = entry.datesFor(today, includePast)
            val series = if (dates.size > 1) UUID.randomUUID().toString() else null
            dates.map { date ->
                entry.item.startingOn(date).copy(seriesId = series, repeatRule = if (series == null) "NONE" else entry.repeat.name)
            }
        }
        require(result.size <= MAX_SAVED) { "That's ${result.size} events; import at most $MAX_SAVED at once. Untick some repeating events." }
        return result
    }

    // Already in Planner: an event with the same title and time on one of the dates.
    fun duplicate(entry: Entry, existing: Set<Triple<String, LocalDate, LocalTime?>>, today: LocalDate, includePast: Boolean): Boolean =
        entry.datesFor(today, includePast).ifEmpty { entry.dates }.any { Triple(entry.item.title, it, entry.item.startTime) in existing }

    // "Select all": every row with a date to import, except the ones already in Planner ([duplicates]).
    fun selectAll(entries: List<Entry>, today: LocalDate, includePast: Boolean, duplicates: Set<Int>): Set<Int> =
        entries.filter { it.id !in duplicates && it.datesFor(today, includePast).isNotEmpty() }.mapTo(HashSet()) { it.id }

    fun existingKeys(items: List<ItineraryItem>): Set<Triple<String, LocalDate, LocalTime?>> =
        items.mapTo(HashSet()) { Triple(it.title, it.date, it.startTime) }
}
