package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// Two-way sync (step 6): what counts as a change, and which files on Nextcloud are Planner's own events already after
// reconnecting. The sync itself is exercised end to end in the instrumented CalendarTwoWayTest.
class CalendarSyncRulesTest {
    private val day = LocalDate.of(2026, 10, 5)
    private fun event(id: Long, title: String, time: Int? = 9, date: LocalDate = day, minutes: Int? = 60, notes: String = "") =
        ItineraryItem(id = id, tripId = 0, date = date, startTime = time?.let { LocalTime.of(it, 0) }, durationMinutes = minutes, title = title, notes = notes)
    private fun planner(n: Int) = "planner-0000000$n-aaaa-bbbb-cccc-dddddddddddd@planner"

    @Test fun aNewTimeZoneAloneChangesNoFingerprint() {
        val item = event(1, "Dentist")
        val perth = ZoneId.of("Australia/Perth"); val london = ZoneId.of("Europe/London")
        // The old way moved with the phone's zone, so travelling looked like an edit of every timed event.
        assertNotEquals(CalendarSync.zonedFingerprint(item, perth), CalendarSync.zonedFingerprint(item, london))
        // Now it doesn't; a real edit still does.
        assertEquals(CalendarSync.fingerprint(item), CalendarSync.fingerprint(item.copy()))
        assertNotEquals(CalendarSync.fingerprint(item), CalendarSync.fingerprint(item.copy(startTime = LocalTime.of(10, 0))))
        assertNotEquals(CalendarSync.fingerprint(item), CalendarSync.fingerprint(item.copy(notes = "Bring the card")))
        // An all-day event never depended on the zone: its old fingerprint still matches anywhere.
        val allDay = event(2, "Holiday", time = null, minutes = null)
        assertTrue(CalendarSync.inSync(CalendarSync.zonedFingerprint(allDay, perth), allDay, london))
    }

    @Test fun onlyARowFromBeforeTheUpdateMatchesTheOldWays() {
        val perth = ZoneId.of("Australia/Perth")
        val item = event(1, "Dentist") // 09:00
        val stored = CalendarSync.fingerprint(item)
        assertTrue(CalendarSync.inSync(stored, item, perth))
        // Moved later by exactly the phone's UTC offset: 17:00 in Perth is 09:00 UTC, but it is an edit.
        assertFalse(CalendarSync.inSync(stored, item.copy(startTime = LocalTime.of(17, 0)), perth))
        // New fingerprints tell themselves apart from the old ones (worked out in UTC by 0.0.9, in the phone's zone before).
        assertNotEquals(CalendarSync.zonedFingerprint(item, ZoneOffset.UTC), stored)
        assertTrue(CalendarSync.inSync(CalendarSync.zonedFingerprint(item, ZoneOffset.UTC), item, perth))
        assertTrue(CalendarSync.inSync(CalendarSync.zonedFingerprint(item, perth), item, perth))
        assertFalse(CalendarSync.inSync(CalendarSync.zonedFingerprint(item, perth), item.copy(notes = "Bring the card"), perth))
    }

    @Test fun aLostReplyForAnEventDeletedSinceIsStillDeletedThere() {
        val row = SentEvent(id = 5, itemId = 1, account = "a", calendar = "/c/", uid = planner(1), fingerprint = "x", problem = SentEvent.PENDING)
        val dentist = event(1, "Dentist")
        val file = ServerFile("/c/${planner(1)}.ics", "\"e1\"", CalendarExport.encode(dentist, planner(1), ZoneOffset.UTC, Instant.EPOCH))
        // Deleted in Planner: the row stays pending with the file's version, so send deletes exactly that file.
        val gone = CalendarSync.adopted(row, file, null, ZoneOffset.UTC)
        assertEquals(SentEvent.PENDING, gone.problem)
        assertEquals("\"e1\"", gone.etag)
        // Still in Planner: the file counts as synced.
        val kept = CalendarSync.adopted(row, file, dentist, ZoneOffset.UTC)
        assertNull(kept.problem); assertEquals("\"e1\"", kept.etag); assertEquals(CalendarSync.fingerprint(dentist), kept.fingerprint)
        // A file Planner can't read is left for the next pull.
        assertEquals(SentEvent.CHANGED, CalendarSync.adopted(row, file.copy(data = "garbage"), dentist, ZoneOffset.UTC).problem)
    }

    @Test fun eventsBeforeThePullWindowAreLookedUpAfterReconnecting() {
        val from = LocalDate.of(2026, 7, 1); val until = LocalDate.of(2027, 10, 31)
        val longAgo = event(1, "Long ago", date = LocalDate.of(2026, 3, 5)); val far = event(2, "Far", date = LocalDate.of(2028, 1, 1)); val soon = event(3, "Soon")
        assertEquals(listOf(until.plusDays(1) to until.plusYears(100)), CalendarSync.linkRanges(listOf(soon, far), from, until, first = true))
        // The first pull since the calendar was chosen also reads the files before the window, to link past events.
        assertEquals(listOf(from.minusYears(100) to from), CalendarSync.linkRanges(listOf(longAgo, soon), from, until, first = true))
        assertTrue(CalendarSync.linkRanges(listOf(longAgo, soon), from, until, first = false).isEmpty())
        assertTrue(CalendarSync.linkRanges(listOf(soon), from, until, first = true).isEmpty())
    }

    @Test fun anEventOutsideYearsOneTo9998IsNeitherSentNorCompared() {
        val far = event(1, "Far", date = LocalDate.of(9999, 1, 5))
        assertFalse(CalendarSync.sendable(far))
        assertFalse(CalendarSync.inSync(CalendarSync.fingerprint(event(1, "Far")), far, ZoneOffset.UTC))
        assertTrue(CalendarSync.sendable(event(1, "Near", date = LocalDate.of(9998, 12, 31))))
    }

    @Test fun plannersOwnFilesAreRecognised() {
        assertTrue(CalendarSync.isPlannerUid(planner(1)))
        listOf(null, "web-1", "planner-1", "abc@planner", "x-planner-1@planner.example").forEach { assertFalse("$it", CalendarSync.isPlannerUid(it)) }
    }

    @Test fun filesAreLinkedToTheSameEventsNotCopied() {
        val events = listOf(event(1, "Dentist"), event(2, "Gym", time = 18), event(3, "Gym", time = 18), event(4, "Holiday", time = null, minutes = null))
        // Exact matches, Planner's file or not; two identical events each get one of two identical files.
        val files = listOf(Triple("/a.ics", planner(1), event(0, "Dentist")), Triple("/b.ics", "web-7", event(0, "Gym", time = 18)),
            Triple("/c.ics", planner(2), event(0, "Gym", time = 18)), Triple("/d.ics", "web-8", event(0, " Holiday ", time = null, minutes = null)),
            Triple("/e.ics", "web-9", event(0, "Something new")))
        assertEquals(mapOf("/a.ics" to 1L, "/b.ics" to 2L, "/c.ics" to 3L, "/d.ics" to 4L), CalendarSync.relink(files, events))
        // A different length or day is not the same event.
        assertTrue(CalendarSync.relink(listOf(Triple("/a.ics", "web-1", event(0, "Dentist", minutes = 30))), events.take(1)).isEmpty())
        assertTrue(CalendarSync.relink(listOf(Triple("/a.ics", "web-1", event(0, "Dentist", date = day.plusDays(1)))), events.take(1)).isEmpty())
    }

    @Test fun plannersOwnFileEditedOnOneSideIsStillLinked() {
        val dentist = event(1, "Dentist"); val lunch = event(2, "Lunch", time = 12)
        // Moved on Nextcloud while disconnected: its title finds it; renamed: its date and time do.
        assertEquals(mapOf("/a.ics" to 1L), CalendarSync.relink(listOf(Triple("/a.ics", planner(1), event(0, "Dentist", time = 15))), listOf(dentist, lunch)))
        assertEquals(mapOf("/b.ics" to 2L), CalendarSync.relink(listOf(Triple("/b.ics", planner(2), event(0, "Lunch with Sam", time = 12))), listOf(dentist, lunch)))
        // Someone else's file is only linked when it is exactly the same event.
        assertTrue(CalendarSync.relink(listOf(Triple("/a.ics", "web-1", event(0, "Dentist", time = 15))), listOf(dentist)).isEmpty())
        // Unsure (two events could be it): left alone.
        assertTrue(CalendarSync.relink(listOf(Triple("/a.ics", planner(1), event(0, "Dentist", time = 15))), listOf(dentist, event(3, "Dentist", time = 11))).isEmpty())
        // An exact match elsewhere is never taken by a looser one.
        assertEquals(mapOf("/x.ics" to 1L), CalendarSync.relink(listOf(Triple("/y.ics", planner(3), event(0, "Dentist", time = 15)),
            Triple("/x.ics", "web-1", event(0, "Dentist"))), listOf(dentist)))
    }
}
