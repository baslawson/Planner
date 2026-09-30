package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// Calendar sync step 6: which Nextcloud event files Planner can edit, and editing one without losing anything else.
class ServerEventsTest {
    private val utc = ZoneOffset.UTC
    private fun file(vararg props: String, extra: String = "") = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Nextcloud//EN\r\n$extra" +
        "BEGIN:VEVENT\r\n${props.joinToString("\r\n")}\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

    @Test fun plainEventsBecomePlannerEventsOthersStayReadOnly() {
        val meeting = ServerEvents.parse(file("UID:m1", "DTSTART:20261005T010000Z", "DTEND:20261005T020000Z", "SUMMARY:Meeting",
            "LOCATION:Room 4", "DESCRIPTION:Agenda\\nitems"), ZoneId.of("Australia/Perth"))
        assertEquals("m1", meeting.uid)
        assertEquals(LocalTime.of(9, 0), meeting.item!!.startTime)
        assertEquals(60, meeting.item!!.durationMinutes)
        assertEquals("Agenda\nitems", meeting.item!!.notes)
        assertNotNull(ServerEvents.parse(file("UID:a", "DTSTART;VALUE=DATE:20261005", "DTEND;VALUE=DATE:20261008", "SUMMARY:Trip"), utc).item!!.endDate)
        // Read-only: repeating, a moved date of one, over a day long, cancelled, unknown zone, several events, broken.
        listOf(file("UID:r", "DTSTART:20261005T010000Z", "RRULE:FREQ=WEEKLY", "SUMMARY:Gym"),
            file("UID:r", "RECURRENCE-ID:20261012T010000Z", "DTSTART:20261013T010000Z", "SUMMARY:Gym"),
            file("UID:l", "DTSTART:20261005T010000Z", "DTEND:20261007T010000Z", "SUMMARY:Course"),
            file("UID:c", "DTSTART:20261005T010000Z", "STATUS:CANCELLED", "SUMMARY:Off"),
            file("UID:z", "DTSTART;TZID=Imaginary/Zone:20261005T090000", "SUMMARY:Where"),
            file("UID:x", "DTSTART:20261005T010000Z", "SUMMARY:One") + "BEGIN:VEVENT\r\nUID:y\r\nDTSTART:20261006T010000Z\r\nEND:VEVENT\r\n",
            "garbage").forEach { assertNull("Must stay read-only: $it", ServerEvents.parse(it, utc).item) }
        assertEquals("r", ServerEvents.parse(file("UID:r", "DTSTART:20261005T010000Z", "RRULE:FREQ=WEEKLY"), utc).uid)
    }

    @Test fun aSemicolonInAQuotedParameterKeepsTheEventEditable() {
        val text = file("UID:q", "DTSTART:20261005T010000Z", "SUMMARY:Lunch", "ATTENDEE;CN=\"Smith; Jane\":mailto:jane@example.com")
        assertEquals("Lunch", ServerEvents.parse(text, utc).item!!.title)
    }

    @Test fun anEventOutsideYearsOneTo9998StaysReadOnly() {
        listOf(file("UID:f", "DTSTART:99990105T090000Z", "SUMMARY:Far"), file("UID:f", "DTSTART;VALUE=DATE:99991231", "SUMMARY:Far"),
            file("UID:f", "DTSTART;VALUE=DATE:00000105", "SUMMARY:Year zero")).forEach {
            val parsed = ServerEvents.parse(it, utc)
            assertEquals("f", parsed.uid); assertNull("Must stay read-only: $it", parsed.item)
        }
        assertNotNull(ServerEvents.parse(file("UID:n", "DTSTART;VALUE=DATE:99981231", "SUMMARY:Near"), utc).item)
    }

    @Test fun patchingChangesOnlyWhatPlannerManages() {
        val original = file("UID:m1", "DTSTAMP:20260101T000000Z", "DTSTART;TZID=Europe/London:20261005T090000", "DTEND;TZID=Europe/London:20261005T100000",
            "SUMMARY:Meeting", "LOCATION:Room 4", "SEQUENCE:3", "ORGANIZER;CN=Sam:mailto:sam@example.com",
            "ATTENDEE;CN=Alex;PARTSTAT=ACCEPTED:mailto:alex@example.com", "CATEGORIES:Work", "X-CUSTOM-THING:keep me",
            "DESCRIPTION:A long description that is folded because it is quite long and goes past the seventy-f\r\n ive octet limit",
            "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Alarm text stays", "TRIGGER:-PT15M", "END:VALARM",
            extra = "BEGIN:VTIMEZONE\r\nTZID:Europe/London\r\nEND:VTIMEZONE\r\n")
        val item = ItineraryItem(tripId = 0, date = LocalDate.of(2026, 10, 6), startTime = LocalTime.of(14, 0), durationMinutes = 30,
            title = "Meeting (moved)", location = "", notes = "New notes")
        val patched = ServerEvents.patch(original, item, utc, Instant.parse("2026-10-01T08:00:00Z"))
        listOf("UID:m1", "ORGANIZER;CN=Sam:mailto:sam@example.com", "ATTENDEE;CN=Alex;PARTSTAT=ACCEPTED:mailto:alex@example.com",
            "CATEGORIES:Work", "X-CUSTOM-THING:keep me", "BEGIN:VALARM", "DESCRIPTION:Alarm text stays", "TRIGGER:-PT15M",
            "BEGIN:VTIMEZONE", "TZID:Europe/London", "PRODID:-//Nextcloud//EN").forEach { assertTrue("Kept: $it", patched.contains(it)) }
        listOf("DTSTART:20261006T140000Z", "DTEND:20261006T143000Z", "SUMMARY:Meeting (moved)", "DESCRIPTION:New notes",
            "SEQUENCE:4", "DTSTAMP:20261001T080000Z", "LAST-MODIFIED:20261001T080000Z").forEach { assertTrue("New: $it", patched.contains(it)) }
        listOf("TZID=Europe/London:20261005T090000", "LOCATION:", "SUMMARY:Meeting\r\n", "long description", "SEQUENCE:3", "DTSTAMP:20260101")
            .forEach { assertFalse("Replaced: $it", patched.contains(it)) }
        // It reads back as the Planner event it was patched from.
        val back = ServerEvents.parse(patched, utc).item!!
        assertEquals(item.copy(tripId = 0), back.copy(tripId = 0))
        assertEquals(1, Regex("BEGIN:VEVENT").findAll(patched).count())
    }

    @Test fun applyingKeepsPlannerOnlyDetails() {
        val mine = ItineraryItem(id = 7, tripId = 3, date = LocalDate.of(2026, 10, 5), startTime = LocalTime.of(9, 0), title = "Mine",
            category = "Health", colorIndex = 4, checklist = listOf(ChecklistEntry("a", "Forms", true)), bufferBeforeMinutes = 10)
        val theirs = ItineraryItem(tripId = 0, date = LocalDate.of(2026, 10, 6), startTime = null, title = "Theirs", location = "Clinic", notes = "n")
        val merged = ServerEvents.apply(mine, theirs)
        assertEquals(listOf(7L, 3L), listOf(merged.id, merged.tripId))
        assertEquals("Health", merged.category); assertEquals(4, merged.colorIndex); assertEquals(mine.checklist, merged.checklist)
        assertEquals(10, merged.bufferBeforeMinutes)
        assertEquals(listOf("Theirs", "Clinic", "n"), listOf(merged.title, merged.location, merged.notes))
        assertEquals(LocalDate.of(2026, 10, 6), merged.date); assertNull(merged.startTime)
    }

    // E9: an all-day event longer than Planner holds (MultiDay.MAX_DAYS) stays read-only instead of being cut short and
    // then written back shorter by a Planner edit. Exactly MAX_DAYS is still editable, by end date or by duration.
    @Test fun anAllDayEventLongerThanPlannerHoldsStaysReadOnly() {
        val max = ServerEvents.parse(file("UID:y", "DTSTART;VALUE=DATE:20260101", "DTEND;VALUE=DATE:20270102", "SUMMARY:Year"), utc).item!!
        assertEquals(MultiDay.MAX_DAYS.toLong(), java.time.temporal.ChronoUnit.DAYS.between(max.date, max.endDate!!) + 1)
        assertNotNull(ServerEvents.parse(file("UID:y", "DTSTART;VALUE=DATE:20260101", "DURATION:P${MultiDay.MAX_DAYS}D", "SUMMARY:Year"), utc).item)
        listOf(file("UID:l", "DTSTART;VALUE=DATE:20260101", "DTEND;VALUE=DATE:20270103", "SUMMARY:Too long"),
            file("UID:l", "DTSTART;VALUE=DATE:20260101", "DTEND;VALUE=DATE:20300101", "SUMMARY:Years"),
            file("UID:l", "DTSTART;VALUE=DATE:20260101", "DURATION:P60W", "SUMMARY:Weeks")).forEach {
            val parsed = ServerEvents.parse(it, utc)
            assertNull("Must stay read-only: $it", parsed.item)
            assertEquals("l", parsed.uid)
        }
    }

    // 1 Oct bug hunt #1: text longer than Planner takes stays read-only instead of being cut short and written back
    // shorter; exactly the limit is still editable.
    @Test fun textLongerThanPlannerTakesStaysReadOnly() {
        fun event(summary: String = "Talk", location: String = "", notes: String = "") = file(*listOfNotNull("UID:t",
            "DTSTART:20261005T010000Z", "SUMMARY:$summary", location.takeIf { it.isNotEmpty() }?.let { "LOCATION:$it" },
            notes.takeIf { it.isNotEmpty() }?.let { "DESCRIPTION:$it" }).toTypedArray())
        assertEquals(20_000, ServerEvents.parse(event(notes = "n".repeat(20_000)), utc).item!!.notes.length)
        assertNotNull(ServerEvents.parse(event(summary = "s".repeat(500), location = "l".repeat(2000)), utc).item)
        listOf(event(notes = "n".repeat(20_001)), event(summary = "s".repeat(501)), event(location = "l".repeat(2001))).forEach {
            val parsed = ServerEvents.parse(it, utc)
            assertNull(parsed.item); assertEquals("t", parsed.uid)
        }
    }

    // An event linked before that fix holds the notes cut short: a title-only edit keeps Nextcloud's full notes.
    @Test fun anEditKeepsTextAnOlderPlannerCutShort() {
        val notes = "x".repeat(20_000) + " IMPORTANT TAIL"
        val original = file("UID:t", "DTSTART:20261005T010000Z", "DTEND:20261005T020000Z", "SUMMARY:Talk", "DESCRIPTION:$notes")
        val held = ItineraryItem(tripId = 0, date = LocalDate.of(2026, 10, 5), startTime = LocalTime.of(1, 0), durationMinutes = 60,
            title = "Talk (moved)", notes = notes.take(20_000))
        val patched = ServerEvents.patch(original, held, utc, Instant.parse("2026-10-01T08:00:00Z"))
        assertTrue(patched.contains("SUMMARY:Talk (moved)"))
        assertTrue(patched.replace("\r\n ", "").contains("DESCRIPTION:$notes\r\n"))
        // Notes edited in Planner are Planner's to write.
        val edited = ServerEvents.patch(original, held.copy(notes = "Short now"), utc, Instant.parse("2026-10-01T08:00:00Z"))
        assertTrue(edited.contains("DESCRIPTION:Short now")); assertFalse(edited.contains("IMPORTANT TAIL"))
    }

    // What Planner didn't change stays as Nextcloud wrote it: its time zone, parameters and escaping included.
    @Test fun patchingKeepsTheLinesOfUnchangedProperties() {
        val original = file("UID:k", "DTSTART;TZID=Europe/London:20261005T090000", "DTEND;TZID=Europe/London:20261005T100000",
            "SUMMARY:Meeting", "LOCATION;ALTREP=\"http://example.com\":Room 4", "DESCRIPTION;LANGUAGE=en:Agenda",
            extra = "BEGIN:VTIMEZONE\r\nTZID:Europe/London\r\nEND:VTIMEZONE\r\n")
        val london = ZoneId.of("Europe/London")
        val item = ServerEvents.parse(original, london).item!!.copy(title = "Meeting (renamed)")
        val patched = ServerEvents.patch(original, item, london, Instant.parse("2026-10-01T08:00:00Z"))
        listOf("DTSTART;TZID=Europe/London:20261005T090000", "DTEND;TZID=Europe/London:20261005T100000",
            "LOCATION;ALTREP=\"http://example.com\":Room 4", "DESCRIPTION;LANGUAGE=en:Agenda", "SUMMARY:Meeting (renamed)")
            .forEach { assertTrue("Kept: $it", patched.contains(it)) }
        assertEquals(1, Regex("DTSTART").findAll(patched).count()); assertEquals(1, Regex("SUMMARY").findAll(patched).count())
        assertEquals(item, ServerEvents.parse(patched, london).item)
        // A file with no title still has none after a notes edit (not "(No title)").
        val untitled = file("UID:u", "DTSTART:20261005T010000Z")
        val noTitle = ServerEvents.patch(untitled, ServerEvents.parse(untitled, utc).item!!.copy(notes = "n"), utc, Instant.EPOCH)
        assertFalse(noTitle.contains("SUMMARY"))
    }

    // 1 Oct bug hunt #2: Planner's minutes are clock minutes, so an event across a daylight-saving change keeps its end.
    @Test fun anEventAcrossADaylightSavingChangeKeepsItsEnd() {
        val sydney = ZoneId.of("Australia/Sydney")
        // Clocks go forward at 02:00 on 4 Oct 2026: 01:30 to 03:30 on the clock is one hour.
        val forward = file("UID:d", "DTSTART:20261003T153000Z", "DTEND:20261003T163000Z", "SUMMARY:Night shift")
        val item = ServerEvents.parse(forward, sydney).item!!
        assertEquals(listOf(LocalTime.of(1, 30), 120), listOf(item.startTime, item.durationMinutes))
        val renamed = ServerEvents.patch(forward, item.copy(title = "Night shift 2"), sydney, Instant.EPOCH)
        assertTrue(renamed.contains("DTEND:20261003T163000Z")); assertFalse(renamed.contains("T173000Z"))
        // Written afresh (moved, or a new file): the end is still that clock time.
        val moved = ServerEvents.patch(forward, item.copy(startTime = LocalTime.of(1, 0)), sydney, Instant.EPOCH)
        assertTrue(moved.contains("DTSTART:20261003T150000Z")); assertTrue(moved.contains("DTEND:20261003T160000Z"))
        assertEquals(item.copy(startTime = LocalTime.of(1, 0)), ServerEvents.parse(moved, sydney).item)
        assertTrue(CalendarExport.encode(item, "d", sydney, Instant.EPOCH).contains("DTEND:20261003T163000Z"))
        // Clocks go back at 03:00 on 5 Apr 2026: 02:30 (before) to 03:00 (after) is 30 clock minutes but 90 real ones; an
        // untouched time keeps the file's own lines, which say which 02:30 was meant.
        val back = file("UID:b", "DTSTART:20260404T153000Z", "DTEND:20260404T170000Z", "SUMMARY:Late")
        val late = ServerEvents.parse(back, sydney).item!!
        val kept = ServerEvents.patch(back, late.copy(notes = "n"), sydney, Instant.EPOCH)
        assertTrue(kept.contains("DTSTART:20260404T153000Z")); assertTrue(kept.contains("DTEND:20260404T170000Z"))
        // Elsewhere nothing changes: Perth on the same night.
        val perth = ZoneId.of("Australia/Perth")
        val plain = ServerEvents.parse(forward, perth).item!!
        assertEquals(60, plain.durationMinutes)
        assertTrue(CalendarExport.encode(plain, "p", perth, Instant.EPOCH).contains("DTEND:20261003T163000Z"))
    }
}
