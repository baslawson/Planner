package com.example.itinerary.data

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// Calendar sync step 6 (two-way): event files in the calendar Planner keeps in sync with.
object ServerEvents {
    // [item] is the event as a Planner event, or null when Planner can't hold it exactly (a repeating event, a timed one
    // longer than a day, an all-day one longer than MultiDay.MAX_DAYS, several events in one file, a cancelled or unreadable one, one outside the years a calendar file
    // can hold, one with a longer title, place or notes than Planner takes): those stay read-only.
    class Parsed(val uid: String?, val item: ItineraryItem?)

    fun parse(text: String, zone: ZoneId): Parsed = read(text, zone, limited = true)

    // [limited]: text longer than Planner holds makes the event read-only; otherwise it's read in full (patch compares).
    private fun read(text: String, zone: ZoneId, limited: Boolean): Parsed = runCatching {
        val events = Ics.events(Ics.lines(text), 50, "Too many events.")
        val uid = events.firstNotNullOfOrNull { props -> props.firstOrNull { it.name == "UID" }?.value?.trim() }
        if (events.size != 1) return Parsed(uid, null)
        val props = events.single()
        fun one(name: String) = props.firstOrNull { it.name == name }
        if (props.any { it.name in setOf("RRULE", "RDATE", "EXDATE", "RECURRENCE-ID") }) return Parsed(uid, null)
        if (one("STATUS")?.value?.uppercase() == "CANCELLED") return Parsed(uid, null)
        val start = one("DTSTART") ?: return Parsed(uid, null)
        val end = one("DTEND"); val duration = one("DURATION")
        val timing = if (Ics.isDate(start)) Ics.date(start).let { first ->
            // Longer than Planner can hold: read-only, never cut short (an edit would write the shorter end back).
            val days = Ics.allDayDays(first, end, duration)
            if (days > MultiDay.MAX_DAYS) return Parsed(uid, null)
            Ics.allDay(first, days)
        } else {
            // An unknown time zone makes the event read-only rather than guessing its time.
            val begin = Ics.time(start, zone, strictGap = false, Ics::zone).withZoneSameInstant(zone).truncatedTo(ChronoUnit.MINUTES)
            val finish = when {
                end != null -> Ics.time(end, zone, strictGap = false, Ics::zone).withZoneSameInstant(zone).truncatedTo(ChronoUnit.MINUTES)
                duration != null -> begin.plus(Ics.duration(duration.value))
                else -> null
            }
            Ics.timed(begin.toLocalDateTime(), finish?.toLocalDateTime())
        }
        if (timing.timedStart != null) return Parsed(uid, null)
        fun text(name: String) = one(name)?.value?.let(Ics::unescape)?.trim().orEmpty()
        val title = text("SUMMARY"); val location = text("LOCATION"); val notes = text("DESCRIPTION")
        // Longer text than Planner holds: read-only, never cut short (an edit would write the shorter text back).
        if (limited && (title.length > MAX_TITLE || location.length > MAX_LOCATION || notes.length > MAX_NOTES)) return Parsed(uid, null)
        Parsed(uid, ItineraryItem(tripId = 0, date = timing.date, startTime = timing.startTime, durationMinutes = timing.durationMinutes,
            endDate = timing.endDate, title = title.ifEmpty { "(No title)" }, location = location, notes = notes)
            .takeIf(CalendarExport::exportable))
    }.getOrElse { Parsed(null, null) }

    // The fields two-way sync carries from Nextcloud into a Planner event; everything Planner-only (checklist, reminders,
    // attachments, category, colour…) stays as it is.
    fun apply(item: ItineraryItem, server: ItineraryItem): ItineraryItem = item.copy(date = server.date, startTime = server.startTime,
        durationMinutes = server.durationMinutes, endDate = server.endDate, title = server.title, location = server.location, notes = server.notes)

    // [original] with only the properties Planner manages (CalendarExport.MANAGED) replaced by [item]'s, plus a fresh
    // DTSTAMP/LAST-MODIFIED and a higher SEQUENCE. Everything else — attendees, alarms, categories, time zones, unknown
    // properties — is kept exactly as it was, lines and folding included. So is a managed property [item] didn't change
    // (the times as a whole, the title, the place, the notes): written as Nextcloud had it, its time zone included.
    // Text an older Planner cut short when it read the file counts as unchanged, so its full length stays.
    fun patch(original: String, item: ItineraryItem, zone: ZoneId, now: Instant): String {
        val before = read(original, zone, limited = false).item
        fun same(theirs: String, mine: String, max: Int) = theirs == mine || theirs.length > max && theirs.take(max) == mine
        val kept = if (before == null) emptySet() else buildSet {
            if (before.date == item.date && before.startTime == item.startTime && before.durationMinutes == item.durationMinutes &&
                before.endDate == item.endDate) addAll(listOf("DTSTART", "DTEND", "DURATION"))
            if (same(before.title, item.title, MAX_TITLE)) add("SUMMARY")
            if (same(before.location, item.location, MAX_LOCATION)) add("LOCATION")
            if (same(before.notes, item.notes, MAX_NOTES)) add("DESCRIPTION")
        }
        val replaced = CalendarExport.MANAGED - kept
        // Logical lines, each with the physical lines it came from.
        val physical = original.removePrefix("﻿").replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val logical = mutableListOf<MutableList<String>>()
        for (line in physical) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && logical.isNotEmpty()) logical.last() += line
            else if (line.isNotEmpty()) logical += mutableListOf(line)
        }
        fun name(block: List<String>) = block.first().substringBefore(':').substringBefore(';').uppercase()
        val out = mutableListOf<String>()
        val stack = ArrayDeque<String>()
        var done = false
        var sequence = 0
        for (block in logical) {
            val text = block.joinToString("\r\n")
            val n = name(block)
            val inEvent = !done && stack.lastOrNull() == "VEVENT"
            when {
                n == "BEGIN" -> { stack.addLast(block.first().substringAfter(':').trim().uppercase()); out += text }
                n == "END" -> {
                    val component = block.first().substringAfter(':').trim().uppercase()
                    if (component == "VEVENT" && !done) {
                        (CalendarExport.managed(item, zone).filter { name(listOf(it)) !in kept } + listOf("DTSTAMP:${CalendarExport.stamp(now)}",
                            "LAST-MODIFIED:${CalendarExport.stamp(now)}", "SEQUENCE:${sequence + 1}")).forEach { out += CalendarExport.fold(it) }
                        done = true
                    }
                    stack.removeLastOrNull(); out += text
                }
                inEvent && n in replaced + setOf("DTSTAMP", "LAST-MODIFIED") -> Unit
                inEvent && n == "SEQUENCE" -> sequence = block.first().substringAfter(':').trim().toIntOrNull() ?: 0
                else -> out += text
            }
        }
        require(done) { "No event in this file" }
        return out.joinToString("\r\n", postfix = "\r\n")
    }

    // The most text Planner takes from a calendar event (as file import and phone calendars do, and as an event holds).
    const val MAX_TITLE = EventText.MAX_TITLE
    const val MAX_LOCATION = EventText.MAX_LOCATION
    const val MAX_NOTES = EventText.MAX_NOTES
}
