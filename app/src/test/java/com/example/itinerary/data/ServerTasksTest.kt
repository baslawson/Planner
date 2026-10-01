package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Task sync: reading Nextcloud task files, writing Planner's, and changing only what Planner manages.
class ServerTasksTest {
    private val perth = ZoneId.of("Australia/Perth")
    private val now = Instant.parse("2026-10-01T10:00:00Z")
    private fun file(vararg lines: String) = (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Nextcloud Tasks//EN", "BEGIN:VTODO", "UID:abc-1") +
        lines + listOf("END:VTODO", "END:VCALENDAR")).joinToString("\r\n", postfix = "\r\n")
    private fun fields(text: String) = ServerTasks.parse(text, perth).fields

    @Test fun readsTheSyncedFields() {
        val f = fields(file("SUMMARY:Buy milk\\, eggs", "DESCRIPTION:Line one\\nLine two", "DUE;VALUE=DATE:20261005", "PRIORITY:2", "STATUS:NEEDS-ACTION"))!!
        assertEquals("Buy milk, eggs", f.title)
        assertEquals("Line one\nLine two", f.notes)
        assertEquals(LocalDate.of(2026, 10, 5), f.dueDate)
        assertEquals(TaskPriority.HIGH, f.priority)
        assertFalse(f.done)
        assertEquals("abc-1", ServerTasks.parse(file("SUMMARY:x"), perth).uid)
    }

    @Test fun dueTimeCountsOnThePhonesDay() {
        // 20:00 UTC on the 4th is 04:00 on the 5th in Perth.
        assertEquals(LocalDate.of(2026, 10, 5), fields(file("SUMMARY:x", "DUE:20261004T200000Z"))!!.dueDate)
        assertEquals(LocalDate.of(2026, 10, 4), fields(file("SUMMARY:x", "DUE;TZID=Europe/Berlin:20261004T090000"))!!.dueDate)
        assertNull(fields(file("SUMMARY:x"))!!.dueDate)
    }

    // T2: a due time in a zone counts on its own day there, so a move writes exactly the day chosen and travelling never
    // makes an unrelated edit rewrite the due date.
    @Test fun aZonedDueKeepsItsOwnDay() {
        // 23:00 in New York on the 5th is 11:00 on the 6th in Perth: still due on the 5th.
        val ny = file("SUMMARY:x", "DUE;TZID=America/New_York:20261005T230000")
        assertEquals(LocalDate.of(2026, 10, 5), fields(ny)!!.dueDate)
        // Moved to the 8th: the 8th at 23:00 New York, read back as the 8th (it used to drift to the 9th).
        val task = ServerTasks.apply(PlannerTask(id = "t"), fields(ny)!!)
        val moved = ServerTasks.patch(ny, task.copy(dueDate = LocalDate.of(2026, 10, 8)), perth, now)
        assertTrue(moved, moved.contains("DUE;TZID=America/New_York:20261008T230000"))
        assertEquals(LocalDate.of(2026, 10, 8), fields(moved)!!.dueDate)
        // Synced in Perth, the title edited after flying to Honolulu: the due line stays exactly as it was.
        val honolulu = ZoneId.of("Pacific/Honolulu")
        val edited = ServerTasks.patch(ny, task.copy(title = "y"), honolulu, now)
        assertTrue(edited, edited.contains("DUE;TZID=America/New_York:20261005T230000"))
        assertEquals(LocalDate.of(2026, 10, 5), ServerTasks.parse(edited, honolulu).fields!!.dueDate)
        // Floating: its own day too.
        assertEquals(LocalDate.of(2026, 10, 5), fields(file("SUMMARY:x", "DUE:20261005T233000"))!!.dueDate)
    }

    @Test fun priorityBands() {
        fun p(v: String?) = fields(file(*listOfNotNull("SUMMARY:x", v?.let { "PRIORITY:$it" }).toTypedArray()))!!.priority
        assertEquals(TaskPriority.HIGH, p("1")); assertEquals(TaskPriority.HIGH, p("4"))
        assertEquals(TaskPriority.NORMAL, p("5")); assertEquals(TaskPriority.NORMAL, p("0")); assertEquals(TaskPriority.NORMAL, p(null))
        assertEquals(TaskPriority.LOW, p("6")); assertEquals(TaskPriority.LOW, p("9"))
    }

    @Test fun doneByStatusOrElseCompletion() {
        assertTrue(fields(file("SUMMARY:x", "STATUS:COMPLETED"))!!.done)
        assertTrue(fields(file("SUMMARY:x", "COMPLETED:20261001T090000Z"))!!.done)
        assertTrue(fields(file("SUMMARY:x", "PERCENT-COMPLETE:100"))!!.done)
        // Reopened in an app that left the old completion time: STATUS decides.
        assertFalse(fields(file("SUMMARY:x", "STATUS:NEEDS-ACTION", "COMPLETED:20261001T090000Z"))!!.done)
        assertFalse(fields(file("SUMMARY:x", "PERCENT-COMPLETE:40"))!!.done)
    }

    @Test fun whatPlannerCantHoldIsLeftAlone() {
        val repeating = ServerTasks.parse(file("SUMMARY:x", "DUE;VALUE=DATE:20261005", "RRULE:FREQ=WEEKLY"), perth)
        assertNull(repeating.fields); assertTrue(repeating.repeating)
        assertNull(fields(file("SUMMARY:x", "STATUS:CANCELLED")))
        assertNull(fields(file("SUMMARY:" + "a".repeat(501))))
        assertNull(fields(file("SUMMARY:x", "DESCRIPTION:" + "a".repeat(20_001))))
        assertNull(fields("not a calendar"))
        // An event file isn't a task.
        assertNull(fields("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:e\r\nDTSTART;VALUE=DATE:20261005\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"))
        assertFalse(ServerTasks.parse(file("SUMMARY:x", "STATUS:CANCELLED"), perth).repeating)
    }

    @Test fun emptyTitleGetsAName() {
        assertEquals("(No title)", fields(file("DUE;VALUE=DATE:20261005"))!!.title)
    }

    @Test fun alarmsInsideATaskAreNotItsOwnProperties() {
        val f = fields(file("SUMMARY:Pay rent", "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Reminder text", "TRIGGER:-PT15M", "END:VALARM"))!!
        assertEquals("", f.notes)
    }

    @Test fun encodeRoundTrips() {
        val task = PlannerTask(title = "Call Sam; re: lunch", notes = "Ask about Friday,\nand the venue", dueDate = LocalDate.of(2026, 10, 7),
            priority = TaskPriority.LOW, reminderAt = 123, checklist = listOf(ChecklistEntry("a", "one", false)))
        val text = ServerTasks.encode(task, "planner-task-1@planner", now)
        assertTrue(text.contains("BEGIN:VTODO") && text.contains("UID:planner-task-1@planner") && text.contains("DUE;VALUE=DATE:20261007"))
        assertTrue(text.contains("PRIORITY:9") && text.contains("STATUS:NEEDS-ACTION"))
        assertEquals(ServerTasks.fields(task), fields(text))
        val done = ServerTasks.encode(task.copy(done = true, priority = TaskPriority.NORMAL, dueDate = null, notes = ""), "u@planner", now)
        assertTrue(done.contains("STATUS:COMPLETED") && done.contains("COMPLETED:20261001T100000Z") && done.contains("PERCENT-COMPLETE:100"))
        assertFalse(done.contains("PRIORITY") || done.contains("DUE") || done.contains("DESCRIPTION"))
        assertTrue(fields(done)!!.done)
    }

    @Test fun patchChangesOnlyWhatChanged() {
        val original = file("CREATED:20260901T000000Z", "DTSTAMP:20260901T000000Z", "SUMMARY:Pay rent", "PRIORITY:3", "CATEGORIES:Home",
            "X-NEXTCLOUD-THING:keep me", "RELATED-TO;RELTYPE=PARENT:parent-1", "SEQUENCE:4",
            "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT15M", "END:VALARM")
        val task = ServerTasks.apply(PlannerTask(id = "t"), fields(original)!!).copy(title = "Pay rent today")
        val patched = ServerTasks.patch(original, task, perth, now)
        assertTrue(patched.contains("SUMMARY:Pay rent today")); assertFalse(patched.contains("SUMMARY:Pay rent\r\n"))
        // Priority unchanged in Planner's terms: its exact value stays.
        assertTrue(patched.contains("PRIORITY:3"))
        listOf("CATEGORIES:Home", "X-NEXTCLOUD-THING:keep me", "RELATED-TO;RELTYPE=PARENT:parent-1", "TRIGGER:-PT15M", "CREATED:20260901T000000Z")
            .forEach { assertTrue(it, patched.contains(it)) }
        assertTrue(patched.contains("SEQUENCE:5") && patched.contains("LAST-MODIFIED:20261001T100000Z") && patched.contains("DTSTAMP:20261001T100000Z"))
        assertFalse(patched.contains("DTSTAMP:20260901T000000Z"))
        assertEquals(ServerTasks.fields(task), fields(patched))
    }

    @Test fun patchTicksAndUnticks() {
        val original = file("SUMMARY:x", "STATUS:NEEDS-ACTION", "PERCENT-COMPLETE:30")
        val task = ServerTasks.apply(PlannerTask(id = "t"), fields(original)!!)
        val ticked = ServerTasks.patch(original, task.copy(done = true), perth, now)
        assertTrue(ticked.contains("STATUS:COMPLETED") && ticked.contains("COMPLETED:") && ticked.contains("PERCENT-COMPLETE:100"))
        assertFalse(ticked.contains("NEEDS-ACTION") || ticked.contains("PERCENT-COMPLETE:30"))
        val unticked = ServerTasks.patch(ticked, task, perth, now)
        assertTrue(unticked.contains("STATUS:NEEDS-ACTION"))
        assertFalse(unticked.contains("COMPLETED:") || unticked.contains("PERCENT-COMPLETE"))
        assertFalse(fields(unticked)!!.done)
    }

    @Test fun movingTheDueDateKeepsItsTime() {
        val zoned = file("SUMMARY:x", "DUE;TZID=Europe/Berlin:20261005T143000")
        val t1 = ServerTasks.apply(PlannerTask(id = "t"), fields(zoned)!!).copy(dueDate = LocalDate.of(2026, 10, 9))
        assertTrue(ServerTasks.patch(zoned, t1, perth, now).contains("DUE;TZID=Europe/Berlin:20261009T143000"))
        // UTC: the same clock time on the phone, on the new day. 01:30 UTC = 09:30 Perth.
        val utc = file("SUMMARY:x", "DUE:20261005T013000Z")
        val t2 = ServerTasks.apply(PlannerTask(id = "t"), fields(utc)!!).copy(dueDate = LocalDate.of(2026, 10, 9))
        val moved = ServerTasks.patch(utc, t2, perth, now)
        assertTrue(moved, moved.contains("DUE:20261009T013000Z"))
        assertEquals(LocalDate.of(2026, 10, 9), fields(moved)!!.dueDate)
        // A date stays a date; no due date removes it.
        val dated = file("SUMMARY:x", "DUE;VALUE=DATE:20261005")
        val t3 = ServerTasks.apply(PlannerTask(id = "t"), fields(dated)!!)
        assertTrue(ServerTasks.patch(dated, t3.copy(dueDate = LocalDate.of(2026, 11, 1)), perth, now).contains("DUE;VALUE=DATE:20261101"))
        assertFalse(ServerTasks.patch(dated, t3.copy(dueDate = null), perth, now).contains("DUE"))
    }

    @Test fun aStartAfterTheNewDueDateIsDropped() {
        val original = file("SUMMARY:x", "DTSTART;VALUE=DATE:20261003", "DUE;VALUE=DATE:20261010")
        val task = ServerTasks.apply(PlannerTask(id = "t"), fields(original)!!)
        assertTrue(ServerTasks.patch(original, task.copy(dueDate = LocalDate.of(2026, 10, 5)), perth, now).contains("DTSTART;VALUE=DATE:20261003"))
        assertFalse(ServerTasks.patch(original, task.copy(dueDate = LocalDate.of(2026, 10, 1)), perth, now).contains("DTSTART"))
    }

    // T1: the start and the new due date must make a pair Nextcloud accepts: the same kind of value, the start not after
    // the due moment (same-day times count).
    @Test fun aStartThatDoesntGoWithTheNewDueIsDropped() {
        // A timed start and no due: the new due is a date, so the timed start goes.
        val timed = file("SUMMARY:x", "DTSTART:20261005T100000Z")
        val t1 = ServerTasks.apply(PlannerTask(id = "t"), fields(timed)!!).copy(dueDate = LocalDate.of(2026, 10, 9))
        val p1 = ServerTasks.patch(timed, t1, perth, now)
        assertTrue(p1, p1.contains("DUE;VALUE=DATE:20261009")); assertFalse(p1, p1.contains("DTSTART"))
        // Due moved to the start's own day, but earlier in it.
        val sameDay = file("SUMMARY:x", "DTSTART;TZID=Australia/Perth:20261009T180000", "DUE;TZID=Australia/Perth:20261010T090000")
        val t2 = ServerTasks.apply(PlannerTask(id = "t"), fields(sameDay)!!).copy(dueDate = LocalDate.of(2026, 10, 9))
        val p2 = ServerTasks.patch(sameDay, t2, perth, now)
        assertTrue(p2, p2.contains("DUE;TZID=Australia/Perth:20261009T090000")); assertFalse(p2, p2.contains("DTSTART"))
        // A timed start before the timed due stays, and so does one at the very same moment.
        val fine = file("SUMMARY:x", "DTSTART;TZID=Australia/Perth:20261001T080000", "DUE;TZID=Australia/Perth:20261005T090000")
        val t3 = ServerTasks.apply(PlannerTask(id = "t"), fields(fine)!!).copy(dueDate = LocalDate.of(2026, 10, 9))
        assertTrue(ServerTasks.patch(fine, t3, perth, now).contains("DTSTART;TZID=Australia/Perth:20261001T080000"))
        assertTrue(ServerTasks.patch(fine, t3.copy(dueDate = LocalDate.of(2026, 10, 1)), perth, now).contains("DTSTART;TZID=Australia/Perth:20261001T080000"))
        // A date start with a timed due: not the same kind, dropped.
        val mixed = file("SUMMARY:x", "DTSTART;VALUE=DATE:20261001", "DUE:20261005T013000Z")
        val t4 = ServerTasks.apply(PlannerTask(id = "t"), fields(mixed)!!).copy(dueDate = LocalDate.of(2026, 10, 9))
        assertFalse(ServerTasks.patch(mixed, t4, perth, now).contains("DTSTART"))
        // Due date removed: the start stays (a task may have a start alone).
        assertTrue(ServerTasks.patch(fine, t3.copy(dueDate = null), perth, now).contains("DTSTART;TZID=Australia/Perth:20261001T080000"))
    }

    // T1: which write replies refuse that one file (sync goes on with the others) and which stop the pass.
    @Test fun aRefusedFileIsTellableFromAFailedSync() {
        listOf(400, 409, 413, 415, 422).forEach { assertTrue("$it", NextcloudClient.refused(it)) }
        listOf(200, 401, 403, 404, 405, 407, 408, 412, 423, 429, 500, 503, 302).forEach { assertFalse("$it", NextcloudClient.refused(it)) }
        assertEquals("Nextcloud refused 2 tasks (HTTP 415). The others were sent; Planner tries again at the next sync.", TaskSync.refusedMessage(2, 415))
        assertTrue(TaskSync.refusedMessage(1, 400).startsWith("Nextcloud refused 1 task (HTTP 400)."))
    }

    @Test fun foldedLinesSurviveUntouched() {
        val long = "DESCRIPTION:" + "word ".repeat(30).trim()
        val folded = long.chunked(70).let { parts -> parts.first() + parts.drop(1).joinToString("") { "\r\n $it" } }
        val original = file("SUMMARY:x", folded)
        val task = ServerTasks.apply(PlannerTask(id = "t"), fields(original)!!).copy(priority = TaskPriority.HIGH)
        val patched = ServerTasks.patch(original, task, perth, now)
        assertTrue(patched.contains(folded))
        assertEquals(ServerTasks.fields(task), fields(patched))
    }

    @Test fun fingerprintFollowsSyncedFieldsOnly() {
        val task = PlannerTask(id = "t", title = "x", dueDate = LocalDate.of(2026, 10, 5))
        val print = ServerTasks.fingerprint(task)
        assertEquals(print, ServerTasks.fingerprint(task.copy(reminderAt = 5, checklist = listOf(ChecklistEntry("a", "b", true)), repeat = "DAILY")))
        listOf(task.copy(title = "y"), task.copy(notes = "n"), task.copy(dueDate = null), task.copy(priority = TaskPriority.HIGH), task.copy(done = true))
            .forEach { assertNotEquals(print, ServerTasks.fingerprint(it)) }
        assertTrue(ServerTasks.inSync(print, task))
    }

    @Test fun relinkMatchesExactlyThenPlannerFilesByTitle() {
        val a = PlannerTask(id = "a", title = "Milk", dueDate = LocalDate.of(2026, 10, 5))
        val b = PlannerTask(id = "b", title = "Bread")
        val c = PlannerTask(id = "c", title = "Bread")
        fun f(title: String, due: LocalDate? = null) = ServerTasks.Fields(title, "", due, TaskPriority.NORMAL, false)
        val result = TaskSync.relink(listOf(
            Triple("/l/1.ics", "other", f("Milk", LocalDate.of(2026, 10, 5))),
            // Planner's own file, edited on one side: found by title only when one task has it (two Breads: unsure).
            Triple("/l/2.ics", "planner-task-x@planner", f("Bread", LocalDate.of(2026, 1, 1))),
            Triple("/l/3.ics", "someone-else", f("Milk"))), listOf(a, b, c))
        assertEquals(mapOf("/l/1.ics" to "a"), result)
        val single = TaskSync.relink(listOf(Triple("/l/2.ics", "planner-task-x@planner", f("Bread", LocalDate.of(2026, 1, 1)))), listOf(b))
        assertEquals(mapOf("/l/2.ics" to "b"), single)
    }
}
