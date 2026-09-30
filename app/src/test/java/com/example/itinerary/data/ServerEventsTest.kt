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
}
