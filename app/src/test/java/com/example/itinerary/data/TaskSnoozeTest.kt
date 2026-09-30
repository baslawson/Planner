package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TaskSnoozeTest {
    private val zone = ZoneId.of("Australia/Perth")
    private fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun snoozeDoesNotShiftTheNextReminder() {
        val today = LocalDate.of(2026, 9, 30)
        // Daily task due today at 08:00, snoozed to tomorrow 09:00 and then completed.
        val task = PlannerTask(title = "Pills", dueDate = today, repeat = "DAILY", reminderAt = at(today, 8), snoozedUntil = at(today.plusDays(1), 9))
        assertEquals(at(today.plusDays(1), 9), task.activeReminderAt)
        val next = task.nextOccurrence(today, zone)!!
        assertEquals(today.plusDays(1), next.dueDate)
        assertEquals(at(today.plusDays(1), 8), next.reminderAt)
        assertNull(next.snoozedUntil)
        assertEquals(at(today.plusDays(1), 8), next.activeReminderAt)
    }

    @Test fun undatedTaskFollowsItsBaseReminderToo() {
        val today = LocalDate.of(2026, 9, 30)
        val task = PlannerTask(title = "Water plants", repeat = "WEEKLY", reminderAt = at(today, 7), snoozedUntil = at(today, 19))
        assertEquals(at(today.plusDays(7), 7), task.nextOccurrence(today, zone)!!.reminderAt)
    }

    @Test fun removingTheReminderEndsTheSnooze() {
        assertNull(PlannerTask(title = "Call", reminderAt = null, snoozedUntil = 5_000L).activeReminderAt)
        assertEquals(1_000L, PlannerTask(title = "Call", reminderAt = 1_000L).activeReminderAt)
        assertThrows(IllegalArgumentException::class.java) { Tasks.validate(PlannerTask(title = "Call", snoozedUntil = 0L)) }
    }

    // B17: once the snooze time has passed (e.g. the snoozed notification was swiped away) it is no longer "snoozed".
    @Test fun snoozeCountsOnlyUntilItsTime() {
        val today = LocalDate.of(2026, 9, 30)
        val task = PlannerTask(title = "Pills", reminderAt = at(today, 8), snoozedUntil = at(today, 9))
        assertEquals(at(today, 9), task.snoozedAt(at(today, 8)))
        assertNull(task.snoozedAt(at(today, 9)))
        assertNull(task.snoozedAt(at(today, 10)))
        assertNull(task.copy(reminderAt = null).snoozedAt(at(today, 8)))
    }
}
