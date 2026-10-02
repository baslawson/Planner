package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// Bug hunt of 2 October (event and task sync, the data layer): the rules that decide what a send does with a file it had
// to fetch, version markers, deletes without a known version, backup uids, and a UTC task due date after travel. The sync
// passes themselves are exercised end to end in the instrumented CalendarTwoWayTest and TaskSyncTest.
class SyncFixesOct2Test {
    private val perth = ZoneId.of("Australia/Perth")
    private val london = ZoneId.of("Europe/London")
    private val stamp = Instant.parse("2026-10-01T10:00:00Z")
    private val calendar = "/remote.php/dav/calendars/u/planner/"
    private val uid = "planner-00000001-aaaa-bbbb-cccc-dddddddddddd@planner"
    private fun event(title: String = "Dentist", notes: String = "") = ItineraryItem(id = 7, tripId = 0, date = LocalDate.of(2026, 10, 5),
        startTime = LocalTime.of(9, 0), durationMinutes = 60, title = title, notes = notes)
    private fun synced(item: ItineraryItem, etag: String? = "\"v1\"", ics: String? = null) = SentEvent(id = 1, itemId = item.id, account = "a",
        calendar = calendar, uid = uid, etag = etag, fingerprint = CalendarSync.fingerprint(item), ics = ics)
    private fun fileOf(item: ItineraryItem, etag: String?, extra: String? = null): ServerFile {
        val text = CalendarExport.encode(item, uid, perth, stamp)
        return ServerFile("$calendar$uid.ics", etag, if (extra == null) text else text.replace("END:VEVENT", "$extra\r\nEND:VEVENT"))
    }

    // E-6: a compressing front end changes the ETag of a GET (Apache: "-gzip", nginx: weak) but not the listed one.
    @Test fun versionMarkersAreComparedAsTheServerListsThem() {
        assertEquals("\"abc\"", NextcloudClient.etag("W/\"abc\""))
        assertEquals("\"abc\"", NextcloudClient.etag("\"abc-gzip\""))
        assertEquals("\"abc\"", NextcloudClient.etag(" W/\"abc-gzip\" "))
        assertEquals("\"abc\"", NextcloudClient.etag("\"abc\""))
        assertEquals("e-foreign", NextcloudClient.etag("e-foreign")) // an unquoted one stays unquoted
        assertEquals("\"-gzip\"", NextcloudClient.etag("\"-gzip\"")) // nothing left: not a suffix
        assertNull(NextcloudClient.etag(null)); assertNull(NextcloudClient.etag("  "))
        assertTrue(NextcloudClient.sameEtag("W/\"abc-gzip\"", "\"abc\""))
        assertTrue(NextcloudClient.sameEtag("abc", "\"abc\""))
        assertFalse(NextcloudClient.sameEtag("\"abc\"", "\"abd\""))
        // Unknown is never the same: a write needs a version to be conditional.
        assertFalse(NextcloudClient.sameEtag(null, null))
        assertFalse(NextcloudClient.sameEtag("\"abc\"", null))
    }

    // E-1: a 403 on one file's write is that file refused, not the whole pass; the message names events.
    @Test fun aRefusedEventIsToldLikeARefusedTask() {
        assertEquals("Nextcloud refused 1 event (HTTP 413). The others were sent; Planner tries again at the next sync.", CalendarSync.refusedMessage(1, 413))
        assertTrue(CalendarSync.refusedMessage(3, 403).startsWith("Nextcloud refused 3 events (HTTP 403)."))
    }

    // E-2 / E-7: what an update does with a file it had to fetch.
    @Test fun aFetchedFileIsSentOnOnlyWhenItStillHasWhatWasSynced() {
        val before = event()
        val edited = before.copy(title = "Dentist (moved room)")
        // The same version, however its marker is written: the update goes onto it, at the listed version.
        fileOf(before, "W/\"v1-gzip\"").let { file ->
            val (row, send) = CalendarSync.fetchedBase(synced(before), file, edited, perth)
            assertTrue(send); assertEquals(file.data, row.ics); assertNull(row.problem)
        }
        // Another app changed only what Planner doesn't manage (an alarm acknowledged): still sent, at the new version.
        fileOf(before, "\"v2\"", extra = "X-MOZ-LASTACK:20261001T090000Z").let { file ->
            val (row, send) = CalendarSync.fetchedBase(synced(before), file, edited, perth)
            assertTrue(send); assertEquals("\"v2\"", row.etag); assertEquals(file.data, row.ics)
        }
        // Nextcloud already has Planner's version (an earlier write whose reply was lost): in sync, nothing to send, and
        // the row isn't marked deleted afterwards.
        fileOf(edited, "\"v3\"").let { file ->
            val (row, send) = CalendarSync.fetchedBase(synced(before), file, edited, perth)
            assertFalse(send); assertNull(row.problem); assertEquals("\"v3\"", row.etag); assertEquals(CalendarSync.fingerprint(edited), row.fingerprint)
        }
        // Changed there in what Planner manages: left for the next pull.
        fileOf(before.copy(title = "Edited on the web"), "\"v4\"").let { file ->
            assertEquals(SentEvent.CHANGED to false, CalendarSync.fetchedBase(synced(before), file, edited, perth).let { it.first.problem to it.second })
        }
        // A row that lost its version (E-7) and a file without one: never written over unconditionally.
        fileOf(before, null).let { file ->
            val (row, send) = CalendarSync.fetchedBase(synced(before, etag = null), file, edited, perth)
            assertFalse(send); assertEquals(SentEvent.CHANGED, row.problem)
        }
        // A row that lost its version but whose file is as synced: sent at the file's version.
        fileOf(before, "\"v5\"").let { file ->
            val (row, send) = CalendarSync.fetchedBase(synced(before, etag = null, ics = file.data), file, edited, perth)
            assertTrue(send); assertEquals("\"v5\"", row.etag)
        }
    }

    // E-7: a delete when the row doesn't know the version.
    @Test fun aDeleteWithoutAKnownVersionUsesTheFetchedOneOnlyWhenSafe() {
        val file = fileOf(event(), "\"v9\"")
        assertEquals("\"v9\"", CalendarSync.deleteVersion(SentEvent.PENDING, null, file)) // Planner's own, its reply lost
        assertEquals("\"v9\"", CalendarSync.deleteVersion(null, file.data, file)) // exactly as last synced
        assertNull(CalendarSync.deleteVersion(null, file.data.replace("Dentist", "Edited"), file)) // changed there
        assertNull(CalendarSync.deleteVersion(null, null, file)) // can't tell
        assertNull(CalendarSync.deleteVersion(SentEvent.PENDING, null, file.copy(etag = null)))
    }

    // E-9: a range too large to read at once is read in halves, down to a single day.
    @Test fun aLargeRangeSplitsInHalves() {
        val start = LocalDate.of(2026, 7, 1); val end = LocalDate.of(2027, 11, 1)
        val halves = CalendarSync.splitRange(start, end)!!
        assertEquals(start, halves[0].first); assertEquals(halves[0].second, halves[1].first); assertEquals(end, halves[1].second)
        assertNotNull(CalendarSync.splitRange(start, start.plusDays(2)))
        assertNull(CalendarSync.splitRange(start, start.plusDays(1)))
    }

    // E-8: rows for files made on Nextcloud keep their own UID through a backup; only a row named after its uid needs
    // Planner's narrow form.
    @Test fun backupRowsKeepAnyStorableUid() {
        val file = "${calendar}abc.ics"
        listOf("urn:uuid:0f0e-11", "{6A3B-77}", "aGVsbG8+/w==", "x".repeat(400), uid).forEach { assertEquals(it, BackupManager.storableUid(it, file)) }
        assertEquals(uid, BackupManager.storableUid(uid, null))
        listOf("urn:uuid:0f0e-11", "{6A3B-77}", "../x", "a/b").forEach { assertNull(it, BackupManager.storableUid(it, null)) }
        listOf("", "x".repeat(1001), "bad\nuid", "tab\tuid", " padded ").forEach { assertNull(it, BackupManager.storableUid(it, file)) }
    }

    // The open T2 remainder: a task whose Nextcloud DUE is a UTC time doesn't move a day after travel and an unrelated edit.
    private fun task(vararg lines: String) = (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Nextcloud Tasks//EN", "BEGIN:VTODO", "UID:abc-1") +
        lines + listOf("END:VTODO", "END:VCALENDAR")).joinToString("\r\n", postfix = "\r\n")

    @Test fun aUtcDueStaysAsWrittenAfterTravelAndAnUnrelatedEdit() {
        val base = task("SUMMARY:Call the bank", "DUE:20261005T170000Z")
        // Pulled in Perth (17:00 UTC is 01:00 on the 6th there).
        val pulled = ServerTasks.apply(PlannerTask(id = "t"), ServerTasks.parse(base, perth).fields!!)
        assertEquals(LocalDate.of(2026, 10, 6), pulled.dueDate)
        val row = SentTask(id = 1, taskId = "t", account = "a", list = calendar, uid = "abc-1", href = "${calendar}abc-1.ics", etag = "\"v1\"",
            fingerprint = ServerTasks.fingerprint(pulled), ics = base)
        // In London (the 5th there), the title only is changed: the due line is written exactly as it was.
        val renamed = pulled.copy(title = "Call the bank about the card")
        val body = ServerTasks.patch(base, renamed, london, stamp, row.fingerprint)
        assertTrue(body, body.contains("DUE:20261005T170000Z"))
        assertTrue(body, body.contains("SUMMARY:Call the bank about the card"))
        // Without the record of what was synced, it would have moved a day (the old behaviour).
        assertTrue(ServerTasks.patch(base, renamed, london, stamp).contains("DUE:20261006T170000Z"))
        // A due date really changed in Planner is still written, on the day chosen.
        val moved = ServerTasks.patch(base, renamed.copy(dueDate = LocalDate.of(2026, 10, 9)), london, stamp, row.fingerprint)
        assertFalse(moved, moved.contains("DUE:20261005T170000Z"))
        assertEquals(LocalDate.of(2026, 10, 9), ServerTasks.parse(moved, london).fields!!.dueDate)
        // That write's reply lost (412 on the retry): Planner's own write is recognised in London too.
        val written = ServerFile("${calendar}abc-1.ics", "\"v2\"", body)
        val there = TaskSync.alreadyThere(row, written, renamed, london)
        assertNotNull(there); assertEquals(ServerTasks.fingerprint(renamed), there!!.fingerprint)
        // Not without the synced text (it can't be told then), nor when the due line is another one.
        assertNull(TaskSync.alreadyThere(row.copy(ics = null), written, renamed, london))
        assertNull(TaskSync.alreadyThere(row, written.copy(data = body.replace("DUE:20261005T170000Z", "DUE:20261004T170000Z")), renamed, london))
        // A pull of a change made there to something else keeps Planner's due day.
        val theirs = task("SUMMARY:Call the bank today", "DUE:20261005T170000Z")
        assertEquals(pulled.dueDate, TaskSync.syncedDue(ServerTasks.parse(theirs, london).fields!!, theirs, row, pulled, london).dueDate)
        assertEquals(LocalDate.of(2026, 10, 5), TaskSync.syncedDue(ServerTasks.parse(theirs, london).fields!!, theirs, row.copy(ics = null), pulled, london).dueDate)
        // A pending file Planner wrote new has a plain date, so adopting it reads the same in any zone.
        val fresh = ServerTasks.encode(renamed, "planner-task-1@planner", stamp)
        val adopted = TaskSync.adopted(row.copy(problem = SentEvent.PENDING), ServerFile("x", "\"v3\"", fresh), renamed, london)
        assertEquals(ServerTasks.fingerprint(renamed), adopted.fingerprint); assertNull(adopted.problem)
    }

    @Test fun theTasksOwnDueLineOnly() {
        assertEquals("DUE:20261005T170000Z", ServerTasks.dueLine(task("SUMMARY:x", "DUE:20261005T17", " 0000Z")))
        assertEquals("DUE;VALUE=DATE:20261005", ServerTasks.dueLine(task("BEGIN:VALARM", "DUE:19990101T000000Z", "END:VALARM", "DUE;VALUE=DATE:20261005")))
        assertNull(ServerTasks.dueLine(task("SUMMARY:x")))
    }

    // E-2 for tasks: a fetched file that still has what was synced is updated, one with Planner's version is in sync.
    @Test fun aFetchedTaskFileIsSentOnOnlyWhenItStillHasWhatWasSynced() {
        val base = task("SUMMARY:Buy milk", "DUE;VALUE=DATE:20261005")
        val before = ServerTasks.apply(PlannerTask(id = "t"), ServerTasks.parse(base, perth).fields!!)
        val row = SentTask(id = 1, taskId = "t", account = "a", list = calendar, uid = "abc-1", etag = "\"v1\"", fingerprint = ServerTasks.fingerprint(before))
        val edited = before.copy(title = "Buy oat milk")
        assertTrue(TaskSync.fetchedBase(row, ServerFile("f", "W/\"v1\"", base), edited, perth).second)
        assertTrue(TaskSync.fetchedBase(row, ServerFile("f", "\"v2\"", base.replace("END:VTODO", "CATEGORIES:Home\r\nEND:VTODO")), edited, perth).second)
        TaskSync.fetchedBase(row, ServerFile("f", "\"v3\"", base.replace("Buy milk", "Buy oat milk")), edited, perth).let { (r, send) -> assertFalse(send); assertNull(r.problem) }
        TaskSync.fetchedBase(row, ServerFile("f", "\"v4\"", base.replace("Buy milk", "Buy bread")), edited, perth).let { (r, send) -> assertFalse(send); assertEquals(SentEvent.CHANGED, r.problem) }
    }
}
