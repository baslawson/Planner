package com.example.itinerary.data

import com.example.itinerary.reminders.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TaskNoteRingTest {
    @Test fun rebootSnapshotKeepsNewFlagsAndReadsOlderSnapshots() {
        val alarms = listOf(LockedAlarm.Task("task", 2_000_000_000_000, "Task", true), LockedAlarm.Note("note", 2_000_000_000_000, true))
        val snapshot = LockedSnapshot("HOUR_24", alarms)
        assertEquals(snapshot, LockedAlarmCodec.decode(LockedAlarmCodec.encode(snapshot)))
        val older = "planner-locked-alarms\t1\tHOUR_24\nt\ttask\t2000000000000\tTask\nn\tnote\t2000000000000\n"
        assertEquals(listOf(LockedAlarm.Task("task", 2_000_000_000_000, "Task"), LockedAlarm.Note("note", 2_000_000_000_000)), LockedAlarmCodec.decode(older)!!.alarms)
    }
    @Test fun recurrenceAndNoteMergeKeepTheChoiceWithoutHidingConflicts() {
        val day = LocalDate.of(2026, 10, 5)
        val task = PlannerTask(title = "Repeat", dueDate = day, repeat = "DAILY", ringUntilDismissed = true)
        assertTrue(task.nextOccurrence(day, zone = ZoneId.of("UTC"))!!.ringUntilDismissed)
        val base = PlannerNote(title = "Note", reminderAt = 1000)
        val merged = mergeNotes(base, base.copy(ringUntilDismissed = true), base.copy(content = "Other words"))!!
        assertTrue(merged.ringUntilDismissed); assertEquals("Other words", merged.content)
        assertNull(mergeNotes(base, base.copy(ringUntilDismissed = true), base.copy(reminderAt = 2000)))
    }
}
