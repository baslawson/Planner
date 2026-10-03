package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.random.Random

// Optimization 3 Oct (DA-1): fingerprints are stored, so the faster escape, fold and hash must give exactly what the
// code before them gave. The old code is kept here as it was and compared on many generated events and tasks; the
// timing for 3,000 events is printed (not asserted: it depends on the machine).
class CalendarExportSpeedTest {
    // ---- The code as it was before 3 Oct ----
    private val oldTimestamp = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private fun oldEscape(value: String) = value.replace("\\", "\\\\").replace("\r\n", "\n").replace("\r", "\n")
        .replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")
        .filter { it == '\t' || it.code >= 32 && it.code != 127 }
    private fun oldFold(line: String): String = buildString {
        var bytes = 0
        line.codePoints().forEach { cp ->
            val character = String(Character.toChars(cp))
            val size = character.toByteArray(Charsets.UTF_8).size
            if (bytes + size > 75) { append("\r\n "); bytes = 1 }
            append(character); bytes += size
        }
    }
    private fun oldManaged(item: ItineraryItem, zone: ZoneId): List<String> {
        val lines = mutableListOf<String>()
        val time = item.startTime
        if (time == null) {
            lines += "DTSTART;VALUE=DATE:${item.date.format(DateTimeFormatter.BASIC_ISO_DATE)}"
            lines += "DTEND;VALUE=DATE:${item.lastDay.plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE)}"
        } else {
            val start = item.date.atTime(time)
            lines += "DTSTART:${oldTimestamp.format(start.atZone(zone))}"
            item.durationMinutes?.takeIf { it > 0 }?.let { lines += "DTEND:${oldTimestamp.format(start.plusMinutes(it.toLong()).atZone(zone))}" }
        }
        lines += "SUMMARY:${oldEscape(item.title)}"
        if (item.location.isNotBlank()) lines += "LOCATION:${oldEscape(item.location)}"
        if (item.notes.isNotBlank()) lines += "DESCRIPTION:${oldEscape(item.notes)}"
        return lines
    }
    private fun oldEncode(item: ItineraryItem, uid: String, zone: ZoneId, now: Instant): String {
        require(uid.matches(Regex("[A-Za-z0-9@._-]+")))
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Planner//Event Export//EN", "CALSCALE:GREGORIAN",
            "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:${oldTimestamp.format(now)}")
        lines += oldManaged(item, zone)
        lines += listOf("END:VEVENT", "END:VCALENDAR")
        return lines.joinToString("\r\n", postfix = "\r\n") { oldFold(it) }
    }
    private fun oldHash(text: String) =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    private fun oldFingerprint(item: ItineraryItem) = "u1:" + oldHash(oldEncode(item, "planner", ZoneOffset.UTC, Instant.EPOCH))
    private fun oldTaskFingerprint(f: ServerTasks.Fields) = "t1:" + oldHash(
        listOf(f.title.trim(), f.notes.trim(), f.dueDate?.toString().orEmpty(), f.priority.name, f.done.toString()).joinToString("\u0000"))

    // ---- Generated input: every kind of character escape and fold treat differently ----
    private val pieces = listOf("a", "Z", "0", " ", "\t", "\r", "\n", "\r\n", "\\", ";", ",", ":", "\u0000", "\u0007", "\u001f", "\u007f",
        "é", "ß", "ü", "€", "中", "日本", "😀", "👍🏽", "\uD83D", "\uDE00", " ", " ", "﻿", "ﬀ", "abcdefghij", "Dentist ")
    private fun text(random: Random, max: Int) = buildString { repeat(random.nextInt(max + 1)) { append(pieces[random.nextInt(pieces.size)]) } }
    private fun item(random: Random, id: Long): ItineraryItem {
        val year = listOf(1, 2, 1969, 1970, 2026, 2038, 9998)[random.nextInt(7)]
        val date = LocalDate.of(year, 1 + random.nextInt(12), 1 + random.nextInt(28))
        val timed = random.nextInt(3) != 0
        return ItineraryItem(id = id, tripId = 0, date = date,
            startTime = if (timed) LocalTime.of(random.nextInt(24), random.nextInt(60)) else null,
            durationMinutes = if (timed) listOf(null, 0, 1, 60, 1440)[random.nextInt(5)] else null,
            endDate = if (!timed && random.nextBoolean() && year < 9998) date.plusDays(random.nextLong(1, 5)) else null,
            title = text(random, if (random.nextInt(10) == 0) 120 else 8),
            location = if (random.nextBoolean()) text(random, 20) else "",
            notes = if (random.nextInt(4) == 0) text(random, 1500) else text(random, 30))
    }

    @Test fun escapeAndFoldAreUnchanged() {
        val random = Random(20261003)
        repeat(20_000) {
            val s = text(random, if (it % 10 == 0) 400 else 40)
            assertEquals(oldEscape(s), CalendarExport.escape(s))
            assertEquals(oldFold(s), CalendarExport.fold(s))
            val line = "DESCRIPTION:" + CalendarExport.escape(s)
            assertEquals(oldFold(line), CalendarExport.fold(line))
        }
        // Exactly at the fold: 75 ASCII bytes stay on one line, 76 don't; a character never splits.
        for (n in 70..80) for (tail in listOf("", "é", "中", "😀")) {
            val s = "x".repeat(n) + tail
            assertEquals(oldFold(s), CalendarExport.fold(s))
        }
    }

    @Test fun encodeAndFingerprintsAreByteIdentical() {
        val random = Random(5)
        val zones = listOf(ZoneOffset.UTC, ZoneId.of("Australia/Perth"), ZoneId.of("Europe/London"), ZoneId.of("America/St_Johns"))
        repeat(5_000) { n ->
            val item = item(random, n + 1L)
            val zone = zones[n % zones.size]
            val now = Instant.ofEpochSecond(random.nextLong(0, 250_000_000_000L))
            assertEquals(oldEncode(item, "planner-$n@planner", zone, now), CalendarExport.encode(item, "planner-$n@planner", zone, now))
            assertEquals(oldFingerprint(item), CalendarSync.fingerprint(item))
            assertEquals(oldHash(oldEncode(item, "planner", zone, Instant.EPOCH)), CalendarSync.zonedFingerprint(item, zone))
            val fields = ServerTasks.Fields(item.title, item.notes, item.date.takeIf { n % 3 != 0 },
                TaskPriority.entries[n % TaskPriority.entries.size], n % 2 == 0)
            assertEquals(oldTaskFingerprint(fields), ServerTasks.fingerprint(fields))
        }
    }

    @Test fun printTheTimeFor3000Events() {
        val random = Random(3000)
        val items = List(3_000) { item(random, it + 1L) }
        fun time(block: () -> Unit): Long { val start = System.nanoTime(); block(); return (System.nanoTime() - start) / 1_000_000 }
        var sink = 0
        // Warm up both, then take the best of five for each.
        repeat(3) { items.forEach { sink += oldFingerprint(it).length + CalendarSync.fingerprint(it).length } }
        val before = (1..5).minOf { time { items.forEach { sink += oldFingerprint(it).length } } }
        val after = (1..5).minOf { time { items.forEach { sink += CalendarSync.fingerprint(it).length } } }
        println("Fingerprinting 3,000 events (JVM, best of 5): before $before ms, after $after ms ($sink)")
    }
}
