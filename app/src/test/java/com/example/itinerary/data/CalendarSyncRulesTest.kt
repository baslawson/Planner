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
        val until = LocalDate.of(2027, 10, 31)
        val longAgo = event(1, "Long ago", date = LocalDate.of(2026, 3, 5)); val far = event(2, "Far", date = LocalDate.of(2028, 1, 1)); val soon = event(3, "Soon")
        assertEquals(listOf(until.plusDays(1) to until.plusYears(100)), CalendarSync.linkRanges(listOf(soon, far), until))
        assertTrue(CalendarSync.linkRanges(listOf(longAgo, soon), until).isEmpty())
        // A past one is looked for when it is sent (edited since it was noted), around its own days only.
        assertEquals(listOf(LocalDate.of(2026, 3, 4) to LocalDate.of(2026, 3, 7)), CalendarSync.lookupRanges(listOf(longAgo)))
        // Near ones share one lookup; far apart ones don't make it span the years between.
        val trip = event(4, "Trip", date = LocalDate.of(2026, 3, 20)).copy(endDate = LocalDate.of(2026, 3, 25)); val older = event(5, "Older", date = LocalDate.of(2019, 6, 1))
        assertEquals(listOf(LocalDate.of(2019, 5, 31) to LocalDate.of(2019, 6, 3), LocalDate.of(2026, 3, 4) to LocalDate.of(2026, 3, 27)),
            CalendarSync.lookupRanges(listOf(trip, longAgo, older)))
    }

    @Test fun anEditedPastEventLinkedWhenSentIsAnUpdateUnlessChangedThereToo() {
        val noted = event(1, "Long ago", date = LocalDate.of(2026, 3, 5))
        val row = SentEvent(id = 7, itemId = 1, account = "a", calendar = "/c/", uid = null, fingerprint = CalendarSync.fingerprint(noted))
        val file = ServerFile("/c/${planner(1)}.ics", "\"e1\"", CalendarExport.encode(noted, planner(1), ZoneOffset.UTC, Instant.EPOCH))
        val edited = noted.copy(location = "Room 3")
        // Nextcloud still has it as noted: linked, and Planner's edit is then sent as an update of that file.
        val linked = CalendarSync.linkedForSend(row, file.href, planner(1), file, noted, edited, ZoneOffset.UTC)
        assertEquals(7L, linked.id); assertEquals(planner(1), linked.uid); assertEquals(file.href, linked.href); assertEquals("\"e1\"", linked.etag)
        assertNull(linked.problem); assertEquals(row.fingerprint, linked.fingerprint); assertNotEquals(CalendarSync.fingerprint(edited), linked.fingerprint)
        // Already as Planner has it: nothing to send.
        assertEquals(CalendarSync.fingerprint(edited), CalendarSync.linkedForSend(row, file.href, planner(1), file, edited, edited, ZoneOffset.UTC).fingerprint)
        // Changed on Nextcloud too: the user chooses.
        val both = CalendarSync.linkedForSend(row, file.href, planner(1), file, noted.copy(startTime = LocalTime.of(15, 0)), edited, ZoneOffset.UTC)
        assertEquals(SentEvent.CONFLICT, both.problem); assertEquals(file.data, both.conflict)
    }

    @Test fun anEventOutsideYearsOneTo9998IsNeitherSentNorCompared() {
        val far = event(1, "Far", date = LocalDate.of(9999, 1, 5))
        assertFalse(CalendarSync.sendable(far))
        assertFalse(CalendarSync.inSync(CalendarSync.fingerprint(event(1, "Far")), far, ZoneOffset.UTC))
        assertTrue(CalendarSync.sendable(event(1, "Near", date = LocalDate.of(9998, 12, 31))))
    }

    @Test fun theFirstPullNeverReadsTheWholeHistory() {
        // However many past events were noted when the calendar was chosen, no pull reads the files before its window.
        val from = LocalDate.of(2026, 7, 1); val until = LocalDate.of(2027, 10, 31)
        val past = (1..500L).map { event(it, "Past $it", date = from.minusDays(it)) }
        assertTrue(CalendarSync.linkRanges(past + event(999, "Soon"), until).isEmpty())
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

    // D1: an edited past occurrence of a series is looked up with its neighbours' files in range (a day either side, near
    // ranges merged). It finds its own file or none, never a neighbour's (that was a false "changed in both places").
    private val gym = (1..5L).map { event(it, "Gym", time = 7, date = day.plusDays(it)) }
    private val gymFiles = gym.map { Triple("/c/${planner(it.id.toInt())}.ics", planner(it.id.toInt()), it.copy(id = 0)) }
    private val gymNoted = gym.associate { it.id to CalendarSync.fingerprint(it) }
    private fun edited(files: List<Triple<String, String?, ItineraryItem>>, edited: ItineraryItem, noted: Map<Long, String> = gymNoted) =
        CalendarSync.editedFiles(files, listOf(edited), gym.filter { it.id != edited.id }, noted, ZoneOffset.UTC)

    @Test fun anEditedOccurrenceOfASeriesFindsItsOwnFileNotANeighbours() {
        val own = gymFiles[2].first
        // Moved to 8:00: its file is the one exactly as it was noted.
        assertEquals(mapOf(own to 3L), edited(gymFiles, gym[2].copy(startTime = LocalTime.of(8, 0))))
        // Renamed, or moved onto the next day's slot: still its own file; the neighbours keep theirs.
        assertEquals(mapOf(own to 3L), edited(gymFiles, gym[2].copy(title = "Gym with Sam")))
        assertEquals(mapOf(own to 3L), edited(gymFiles, gym[2].copy(date = day.plusDays(4))))
        // Its own file gone from Nextcloud: none (sent as a new file), whatever else is there.
        assertTrue(edited(gymFiles - gymFiles[2], gym[2].copy(startTime = LocalTime.of(8, 0))).isEmpty())
        assertTrue(edited(gymFiles - gymFiles[2], gym[2].copy(date = day.plusDays(4))).isEmpty())
    }

    @Test fun anEditedPastEventIsOnlyLinkedToAnExactMatch() {
        val moved = gym[2].copy(startTime = LocalTime.of(8, 0))
        // Changed the same way on Nextcloud: exactly the event as it is now, so linked (nothing to send).
        val same = gymFiles.toMutableList().also { it[2] = it[2].copy(third = moved.copy(id = 0)) }
        assertEquals(mapOf(gymFiles[2].first to 3L), edited(same, moved))
        // Changed differently on Nextcloud: neither as noted nor as now, so no link (no guessing by title or time).
        val other = gymFiles.toMutableList().also { it[2] = it[2].copy(third = gym[2].copy(id = 0, startTime = LocalTime.of(9, 0))) }
        assertTrue(edited(other, moved).isEmpty())
        // A lone event: the looser matches of the pull's relink are not used here either.
        assertTrue(CalendarSync.editedFiles(listOf(Triple("/a.ics", planner(1), event(0, "Dentist", time = 15))), listOf(event(1, "Dentist", time = 11)),
            emptyList(), mapOf(1L to CalendarSync.fingerprint(event(1, "Dentist"))), ZoneOffset.UTC).isEmpty())
        // A row noted before fingerprints had their prefix (worked out in the phone's zone) still finds its file.
        val perth = ZoneId.of("Australia/Perth")
        assertEquals(mapOf(gymFiles[2].first to 3L), CalendarSync.editedFiles(gymFiles, listOf(moved), gym - gym[2],
            gymNoted + (3L to CalendarSync.zonedFingerprint(gym[2], perth)), perth))
    }

    // E6: a change while a pass is sending never cancels it (its write would reach Nextcloud unrecorded); another pass
    // follows. While waiting, a change restarts the wait as before.
    @Test fun aChangeDuringASendQueuesAnotherPassInsteadOfCancellingIt() {
        val debounce = SendDebounce()
        assertTrue(debounce.request()) // idle: start the wait
        assertTrue(debounce.request()) // still waiting: restart it
        debounce.started()
        assertFalse(debounce.request()) // sending: not cancelled…
        assertFalse(debounce.request())
        assertTrue(debounce.finished()) // …one more pass afterwards, however many changes came in
        assertTrue(debounce.request())
        debounce.started()
        assertFalse(debounce.finished()) // nothing came in: done
        assertTrue(debounce.request())
        // A change that came during the wait is sent by that same pass.
        debounce.started(); assertFalse(debounce.finished())
    }

    // E6 safety net: a file at a version Planner didn't record that already holds Planner's event as it is now (its own
    // write, unrecorded) is taken as synced, not a change there or a conflict. Anything else still is.
    @Test fun aFileAlreadyHoldingPlannersEventIsTakenAsSynced() {
        val utc = ZoneOffset.UTC
        val before = event(1, "Dentist")
        val now = before.copy(title = "Dentist (moved)", startTime = LocalTime.of(11, 0), checklist = listOf(ChecklistEntry("c", "Only in Planner", false)))
        val row = SentEvent(id = 5, itemId = 1, account = "a", calendar = "/c/", uid = "u1", href = "/c/u1.ics", etag = "\"e1\"",
            ics = "old", fingerprint = CalendarSync.fingerprint(before), problem = SentEvent.CHANGED, conflict = "x")
        // Planner's latest version, with the file's own extras (an attendee): taken, whatever the row said.
        val written = ServerEvents.patch(CalendarExport.encode(before, "u1", utc, Instant.EPOCH).replace("END:VEVENT", "ATTENDEE:mailto:a@example.com\r\nEND:VEVENT"),
            now, utc, Instant.EPOCH)
        val taken = CalendarSync.alreadyThere(row, ServerFile("/c/u1.ics", "\"e2\"", written), now, utc)!!
        assertEquals("\"e2\"", taken.etag); assertEquals(written, taken.ics); assertEquals(CalendarSync.fingerprint(now), taken.fingerprint)
        assertNull(taken.problem); assertNull(taken.conflict)
        // Changed there to something else, or an earlier version of Planner's: still compared.
        assertNull(CalendarSync.alreadyThere(row, ServerFile("/c/u1.ics", "\"e2\"", CalendarExport.encode(now.copy(title = "Web"), "u1", utc, Instant.EPOCH)), now, utc))
        assertNull(CalendarSync.alreadyThere(row, ServerFile("/c/u1.ics", "\"e2\"", CalendarExport.encode(before, "u1", utc, Instant.EPOCH)), now, utc))
        // A file Planner can't hold isn't taken.
        assertNull(CalendarSync.alreadyThere(row, ServerFile("/c/u1.ics", "\"e2\"", "garbage"), now, utc))
    }
}
