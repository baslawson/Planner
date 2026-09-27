package com.example.itinerary.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** RFC 5545: one saved occurrence, with UTC timed values and exclusive all-day end. */
object CalendarExport {
    private val timestamp = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    fun encode(item: ItineraryItem, uid: String, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String {
        require(item.date.year in 1..9998) { "Calendar export supports years 1–9998" }
        require(uid.matches(Regex("[A-Za-z0-9@._-]+")))
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Planner//Event Export//EN", "CALSCALE:GREGORIAN",
            "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:${timestamp.format(now)}")
        val time = item.startTime
        if (time == null) {
            lines += "DTSTART;VALUE=DATE:${item.date.format(DateTimeFormatter.BASIC_ISO_DATE)}"
            lines += "DTEND;VALUE=DATE:${item.date.plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE)}"
        } else {
            val start = item.date.atTime(time).atZone(zone)
            lines += "DTSTART:${timestamp.format(start)}"
            item.durationMinutes?.takeIf { it > 0 }?.let { lines += "DTEND:${timestamp.format(start.plusMinutes(it.toLong()))}" }
        }
        lines += "SUMMARY:${escape(item.title)}"
        if (item.location.isNotBlank()) lines += "LOCATION:${escape(item.location)}"
        if (item.notes.isNotBlank()) lines += "DESCRIPTION:${escape(item.notes)}"
        lines += listOf("END:VEVENT", "END:VCALENDAR")
        return lines.joinToString("\r\n", postfix = "\r\n") { fold(it) }
    }
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\r\n", "\n").replace("\r", "\n")
        .replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")
        .filter { it == '\t' || it.code >= 32 && it.code != 127 }
    private fun fold(line: String): String = buildString {
        var bytes = 0
        line.codePoints().forEach { cp ->
            val character = String(Character.toChars(cp))
            val size = character.toByteArray(Charsets.UTF_8).size
            if (bytes + size > 75) { append("\r\n "); bytes = 1 }
            append(character); bytes += size
        }
    }
}
