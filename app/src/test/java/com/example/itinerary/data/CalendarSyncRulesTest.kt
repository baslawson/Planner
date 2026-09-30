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
        assertEquals(CalendarSync.zonedFingerprint(item, ZoneOffset.UTC), CalendarSync.fingerprint(item))
        assertNotEquals(CalendarSync.fingerprint(item), CalendarSync.fingerprint(item.copy(startTime = LocalTime.of(10, 0))))
        assertNotEquals(CalendarSync.fingerprint(item), CalendarSync.fingerprint(item.copy(notes = "Bring the card")))
        // An all-day event never depended on the zone: its old fingerprint is the new one.
        val allDay = event(2, "Holiday", time = null, minutes = null)
        assertEquals(CalendarSync.zonedFingerprint(allDay, perth), CalendarSync.fingerprint(allDay))
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
