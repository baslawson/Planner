package com.example.itinerary.data

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// Calendar sync step 6 (two-way): event files in the calendar Planner keeps in sync with.
object ServerEvents {
    // [item] is the event as a Planner event, or null when Planner can't hold it exactly (a repeating event, a timed one
    // longer than a day, several events in one file, a cancelled or unreadable one, one outside the years a calendar file
    // can hold): those stay read-only.
    class Parsed(val uid: String?, val item: ItineraryItem?)

    fun parse(text: String, zone: ZoneId): Parsed = runCatching {
        val events = Ics.events(Ics.lines(text), 50, "Too many events.")
        val uid = events.firstNotNullOfOrNull { props -> props.firstOrNull { it.name == "UID" }?.value?.trim() }
        if (events.size != 1) return Parsed(uid, null)
        val props = events.single()
        fun one(name: String) = props.firstOrNull { it.name == name }
        if (props.any { it.name in setOf("RRULE", "RDATE", "EXDATE", "RECURRENCE-ID") }) return Parsed(uid, null)
        if (one("STATUS")?.value?.uppercase() == "CANCELLED") return Parsed(uid, null)
        val start = one("DTSTART") ?: return Parsed(uid, null)
        val end = one("DTEND"); val duration = one("DURATION")
        val timing = if (Ics.isDate(start)) Ics.date(start).let { Ics.allDay(it, Ics.allDayLength(it, end, duration)) } else {
            // An unknown time zone makes the event read-only rather than guessing its time.
            val begin = Ics.time(start, zone, strictGap = false) { ZoneId.of(it) }.withZoneSameInstant(zone).truncatedTo(ChronoUnit.MINUTES)
            val finish = when {
                end != null -> Ics.time(end, zone, strictGap = false) { ZoneId.of(it) }.withZoneSameInstant(zone).truncatedTo(ChronoUnit.MINUTES)
                duration != null -> begin.plus(Ics.duration(duration.value))
                else -> null
            }
            Ics.timed(begin.toLocalDateTime(), finish?.toLocalDateTime())
        }
        if (timing.timedStart != null) return Parsed(uid, null)
        Parsed(uid, ItineraryItem(tripId = 0, date = timing.date, startTime = timing.startTime, durationMinutes = timing.durationMinutes,
            endDate = timing.endDate, title = one("SUMMARY")?.value?.let(Ics::unescape)?.trim()?.takeIf { it.isNotEmpty() }?.take(500) ?: "(No title)",
            location = one("LOCATION")?.value?.let(Ics::unescape)?.trim().orEmpty().take(2000),
            notes = one("DESCRIPTION")?.value?.let(Ics::unescape)?.trim().orEmpty().take(20_000)).takeIf(CalendarExport::exportable))
    }.getOrElse { Parsed(null, null) }

    // The fields two-way sync carries from Nextcloud into a Planner event; everything Planner-only (checklist, reminders,
    // attachments, category, colour…) stays as it is.
    fun apply(item: ItineraryItem, server: ItineraryItem): ItineraryItem = item.copy(date = server.date, startTime = server.startTime,
        durationMinutes = server.durationMinutes, endDate = server.endDate, title = server.title, location = server.location, notes = server.notes)

    // [original] with only the properties Planner manages (CalendarExport.MANAGED) replaced by [item]'s, plus a fresh
    // DTSTAMP/LAST-MODIFIED and a higher SEQUENCE. Everything else — attendees, alarms, categories, time zones, unknown
    // properties — is kept exactly as it was, lines and folding included.
    fun patch(original: String, item: ItineraryItem, zone: ZoneId, now: Instant): String {
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
                        (CalendarExport.managed(item, zone) + listOf("DTSTAMP:${CalendarExport.stamp(now)}",
                            "LAST-MODIFIED:${CalendarExport.stamp(now)}", "SEQUENCE:${sequence + 1}")).forEach { out += CalendarExport.fold(it) }
                        done = true
                    }
                    stack.removeLastOrNull(); out += text
                }
                inEvent && n in CalendarExport.MANAGED + setOf("DTSTAMP", "LAST-MODIFIED") -> Unit
                inEvent && n == "SEQUENCE" -> sequence = block.first().substringAfter(':').trim().toIntOrNull() ?: 0
                else -> out += text
            }
        }
        require(done) { "No event in this file" }
        return out.joinToString("\r\n", postfix = "\r\n")
    }
}
