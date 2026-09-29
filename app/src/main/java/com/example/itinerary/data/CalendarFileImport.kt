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

    fun read(text: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): Result {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "This calendar file is too large (maximum 10 MB)." }
        val lines = Ics.lines(text)
        require(lines.firstOrNull()?.uppercase() == "BEGIN:VCALENDAR") { "This isn't a calendar file." }
        require(lines.none { it.equals("METHOD:CANCEL", true) }) { "This is a cancellation, not a new appointment." }
        val events = Ics.events(lines, 50_000, "This calendar file has too many events.", unfinished = "This calendar file is incomplete.")
        val limit = today.plusMonths(MONTHS_AHEAD)
        var skipped = 0
        fun one(props: List<Ics.Property>, name: String) = props.firstOrNull { it.name == name }
        // Moved or cancelled single dates of a repeating event: the same UID with a RECURRENCE-ID.
        // One whose repeating event isn't in the file is just an event of its own.
        val (candidates, others) = events.partition { one(it, "RECURRENCE-ID") != null && one(it, "UID") != null }
        val masterUids = others.mapNotNull { one(it, "UID")?.value }.toSet()
        val (overrides, orphans) = candidates.partition { one(it, "UID")!!.value in masterUids }
        val masters = others + orphans
        val replaced = overrides.groupBy { one(it, "UID")!!.value }
        val rows = mutableListOf<Row>()
        for (props in masters) {
            try {
                if (one(props, "STATUS")?.value?.uppercase() == "CANCELLED") continue
                val moved = one(props, "UID")?.let { replaced[it.value] }.orEmpty()
                rows += occurrences(props, moved, zone, limit)
                moved.forEach { override ->
                    if (one(override, "STATUS")?.value?.uppercase() != "CANCELLED") rows += occurrences(override, emptyList(), zone, limit)
                }
            } catch (_: Exception) { skipped++ }
        }
        val entries = rows.map { Entry(0, it.item, it.dates, it.repeat, it.note) }
            .sortedWith(compareBy<Entry> { it.item.date }.thenBy { it.item.startTime }.thenBy { it.item.title })
            .mapIndexed { index, entry -> entry.copy(id = index) }
        require(entries.isNotEmpty() || skipped > 0) { "No events found in this file." }
        require(entries.size <= MAX_EVENTS) { "This file has ${entries.size} events; import at most $MAX_EVENTS at once." }
        require(entries.isNotEmpty()) { "None of the events in this file could be read." }
        return Result(entries, skipped)
    }

    // One event (with its repeats) as rows: usually one, but more when daylight-saving differences between its own time
    // zone and the phone's put some dates at another clock time — a Planner series has one time.
    private fun occurrences(props: List<Ics.Property>, moved: List<List<Ics.Property>>, zone: ZoneId, limit: LocalDate): List<Row> {
        fun one(name: String) = props.firstOrNull { it.name == name }
        val start = requireNotNull(one("DTSTART"))
        val end = one("DTEND"); val duration = one("DURATION")
        val allDay = Ics.isDate(start)
        val ownZone = if (allDay || start.value.endsWith("Z")) zone else start.params["TZID"]?.let { ZoneId.of(it) } ?: zone
        val first: ZonedDateTime = if (allDay) Ics.date(start).atStartOfDay(zone) else strictTime(start, zone)
        val firstLocal = first.withZoneSameInstant(ownZone).toLocalDateTime()
        val days = if (allDay) Ics.allDayLength(first.toLocalDate(), end, duration) else 0
        val length: Duration? = if (allDay) null else when {
            end != null -> Duration.between(first, strictTime(end, zone)).also { require(!it.isNegative) }
            duration != null -> Ics.duration(duration.value)
            else -> null
        }
        // The dates on the event's own clock.
        var note: String? = null
        val rule = one("RRULE")?.value
        val repeat = rule?.let { IcsRepeat.parse(it, ownZone, firstLocal.toLocalDate()) }
        if (rule != null && repeat == null) note = "Repeats in a way Planner can't copy, so only the first date is imported."
        val dates = (repeat?.dates(firstLocal.toLocalDate(), firstLocal.toLocalTime(), limit) ?: listOf(firstLocal.toLocalDate())).toMutableSet()
        props.filter { it.name == "RDATE" }.flatMap { values(it, ownZone) }.filter { it <= limit }.forEach { dates += it }
        val removed = props.filter { it.name == "EXDATE" }.flatMap { values(it, ownZone) } +
            moved.mapNotNull { it.firstOrNull { p -> p.name == "RECURRENCE-ID" } }.flatMap { values(it, ownZone) }
        dates -= removed.toSet()
        if (dates.isEmpty()) return emptyList()
        // Each date on the phone's clock: the Planner event it becomes.
        val timings = dates.sorted().map { date ->
            if (allDay) Ics.allDay(date, days) else {
                val begin = date.atTime(firstLocal.toLocalTime()).atZone(ownZone).withZoneSameInstant(zone).truncatedTo(ChronoUnit.MINUTES)
                Ics.timed(begin.toLocalDateTime(), length?.let { begin.plus(it).truncatedTo(ChronoUnit.MINUTES).toLocalDateTime() })
            }
        }
        val title = one("SUMMARY")?.value?.let(Ics::unescape)?.trim()?.takeIf { it.isNotEmpty() }?.take(500) ?: "Imported event"
        val location = one("LOCATION")?.value?.let(Ics::unescape)?.trim().orEmpty().take(2000)
        val notes = one("DESCRIPTION")?.value?.let(Ics::unescape)?.trim().orEmpty().take(20_000)
        return timings.groupBy { listOf(it.startTime, it.durationMinutes, it.endDate?.let { e -> ChronoUnit.DAYS.between(it.date, e) }, it.timedStart, it.timedEnd) }
            .values.map { group ->
                val t = group.first()
                // Planner can't hold a timed event longer than a day: it becomes all-day over the same days, times noted.
                val longNote = t.timedStart?.let { s -> "Originally from ${s} on the first day to ${t.timedEnd} on the last day." }
                val item = ItineraryItem(tripId = 0, date = t.date, startTime = t.startTime, durationMinutes = t.durationMinutes,
                    endDate = t.endDate, title = title, location = location,
                    notes = listOfNotNull(longNote, notes.takeIf { it.isNotEmpty() }).joinToString("\n\n").take(20_000))
                val plannerRule = if (group.size > 1) repeat?.plannerRule(firstLocal.toLocalDate()) ?: RepeatRule.NONE else RepeatRule.NONE
                Row(item, group.map { it.date }, plannerRule, note)
            }
    }

    // The dates in an EXDATE, RDATE or RECURRENCE-ID (a list of dates or date-times) on the event's own clock.
    private fun values(p: Ics.Property, zone: ZoneId): List<LocalDate> = p.value.split(',').filter { it.isNotBlank() }.map { value ->
        val part = Ics.Property(p.name, p.params, value.trim())
        if (Ics.isDate(part)) Ics.date(part) else strictTime(part, zone).withZoneSameInstant(
            if (value.trim().endsWith("Z")) zone else p.params["TZID"]?.let { ZoneId.of(it) } ?: zone).toLocalDate()
    }

    // A date-time in its own zone (unknown zones refuse the event rather than guessing its time).
    private fun strictTime(p: Ics.Property, zone: ZoneId): ZonedDateTime = Ics.time(p, zone, strictGap = false) { ZoneId.of(it) }

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

    fun existingKeys(items: List<ItineraryItem>): Set<Triple<String, LocalDate, LocalTime?>> =
        items.mapTo(HashSet()) { Triple(it.title, it.date, it.startTime) }
}
