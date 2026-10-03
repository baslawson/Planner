package com.example.itinerary.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** RFC 5545: one saved occurrence, with UTC timed values and exclusive all-day end. */
object CalendarExport {
    private val timestamp = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    // The years a calendar file holds (encode and managed refuse others).
    fun exportable(item: ItineraryItem) = item.date.year in 1..9998

    // Compiled once: encode runs for every event of a send pass (fingerprints).
    internal val UID = Regex("[A-Za-z0-9@._-]+")

    fun encode(item: ItineraryItem, uid: String, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String {
        require(exportable(item)) { "Calendar export supports years 1–9998" }
        require(uid.matches(UID))
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Planner//Event Export//EN", "CALSCALE:GREGORIAN",
            "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:${timestamp.format(now)}")
        lines += managed(item, zone)
        lines += listOf("END:VEVENT", "END:VCALENDAR")
        return lines.joinToString("\r\n", postfix = "\r\n") { fold(it) }
    }

    // The properties Planner manages in an event (unfolded): its dates or times, title, place and notes. Two-way sync
    // (step 6) replaces only these in a file that has more.
    val MANAGED = setOf("DTSTART", "DTEND", "DURATION", "SUMMARY", "LOCATION", "DESCRIPTION")

    fun managed(item: ItineraryItem, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        require(exportable(item)) { "Calendar export supports years 1–9998" }
        val lines = mutableListOf<String>()
        val time = item.startTime
        if (time == null) {
            lines += "DTSTART;VALUE=DATE:${item.date.format(DateTimeFormatter.BASIC_ISO_DATE)}"
            // The end is exclusive: the day after the last day (a multi-day event's end date).
            lines += "DTEND;VALUE=DATE:${item.lastDay.plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE)}"
        } else {
            val start = item.date.atTime(time)
            lines += "DTSTART:${timestamp.format(start.atZone(zone))}"
            // Planner's minutes are wall-clock minutes (Ics.timed): the end is that clock time, across a daylight-saving change too.
            item.durationMinutes?.takeIf { it > 0 }?.let { lines += "DTEND:${timestamp.format(start.plusMinutes(it.toLong()).atZone(zone))}" }
        }
        lines += "SUMMARY:${escape(item.title)}"
        if (item.location.isNotBlank()) lines += "LOCATION:${escape(item.location)}"
        if (item.notes.isNotBlank()) lines += "DESCRIPTION:${escape(item.notes)}"
        return lines
    }

    fun stamp(now: Instant): String = timestamp.format(now)
    // In one pass, the same as replacing \, CR LF, CR, LF, ";" and "," in that order and then dropping control characters
    // other than tab. Fingerprints are stored, so this must not change by a byte (CalendarExportSpeedTest).
    internal fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '\\' -> out.append("\\\\")
                c == '\r' -> { out.append("\\n"); if (i + 1 < value.length && value[i + 1] == '\n') i++ }
                c == '\n' -> out.append("\\n")
                c == ';' -> out.append("\\;")
                c == ',' -> out.append("\\,")
                c == '\t' || c.code >= 32 && c.code != 127 -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    // Lines of at most 75 bytes (UTF-8), never splitting a character. Most lines are short ASCII and come back as they
    // are; the others count bytes per code point without making a String for each.
    fun fold(line: String): String {
        if (line.length <= 75 && line.all { it.code < 0x80 }) return line
        val out = StringBuilder(line.length + line.length / 64 * 3)
        var bytes = 0
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val size = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                // A lone surrogate: counted as the encoder counts it, as before.
                cp in 0xD800..0xDFFF -> String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > 75) { out.append("\r\n "); bytes = 1 }
            out.appendCodePoint(cp); bytes += size
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    // The first 16 bytes of [text]'s SHA-256 as 32 lowercase hex digits: the sync fingerprints' hash.
    internal fun shortHash(text: String): String {
        val digest = sha256.get()!!.digest(text.toByteArray())
        val out = CharArray(32)
        for (i in 0 until 16) {
            val b = digest[i].toInt()
            out[2 * i] = HEX[(b shr 4) and 0xF]; out[2 * i + 1] = HEX[b and 0xF]
        }
        return String(out)
    }
    private val HEX = "0123456789abcdef".toCharArray()
    // One digest per thread (digest() resets it): getInstance looks the provider up each time.
    private val sha256 = ThreadLocal.withInitial { java.security.MessageDigest.getInstance("SHA-256") }
}
