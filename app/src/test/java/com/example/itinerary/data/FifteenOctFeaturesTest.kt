package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay

// The 15 Oct features' rules: hour-grid drags, streaks of repeating tasks, birthday reminders.
class FifteenOctFeaturesTest {
    private val day = LocalDate.of(2026, 10, 15)

    @Test fun hourGridDragsGoInQuarterHoursWithinTheDay() {
        // 9:00 dragged 37 minutes later: 9:37 rounds to 9:30; 53 minutes: 9:53 rounds to 10:00; 12 earlier: 8:45.
        assertEquals(570, DayTimeline.movedStart(540, 37f))
        assertEquals(600, DayTimeline.movedStart(540, 53f))
        assertEquals(525, DayTimeline.movedStart(540, -12f))
        // Not before midnight, not later than 23:45.
        assertEquals(0, DayTimeline.movedStart(30, -200f))
        assertEquals(1425, DayTimeline.movedStart(1380, 300f))
        // Stretching the end: to a quarter hour, at least 15 minutes, at most a day; no length counts as the 30 min shown.
        assertEquals(90, DayTimeline.resized(540, 60, 31f))
        assertEquals(15, DayTimeline.resized(540, 60, -200f))
        assertEquals(1440, DayTimeline.resized(540, 60, 5000f))
        assertEquals(45, DayTimeline.resized(540, null, 14f))
        // An end off the grid (9:10 for 50 min, so 10:00) plus 20 minutes: 10:20 rounds to 10:15, so 65 minutes.
        assertEquals(65, DayTimeline.resized(550, 50, 20f))
    }

    @Test fun onlyPlannersTimedEventsOnTheirOwnDayCanBeDragged() {
        val timed = ItineraryItem(id = 5, tripId = 0, date = day, startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "Gym")
        assertTrue(DayTimeline.movable(timed, day))
        // Shown the morning after (started the night before): not from there.
        assertFalse(DayTimeline.movable(timed, day.plusDays(1)))
        assertFalse(DayTimeline.movable(timed.copy(startTime = null), day))
        assertFalse(DayTimeline.movable(timed.copy(endDate = day.plusDays(2)), day))
        // Another calendar's event (negative id) stays put.
        assertFalse(DayTimeline.movable(timed.copy(id = -7), day))
    }

    // An occurrence of a weekly task, due [due], done [late] days after it (null: not done).
    private fun weekly(id: String, due: LocalDate, late: Long?, next: String?) =
        PlannerTask(id = id, title = "Gym", dueDate = due, repeat = "WEEKLY", done = late != null, doneOn = late?.let { due.plusDays(it) }, nextTaskId = next)

    @Test fun aStreakCountsOccurrencesDoneByTheirDueDayInARow() {
        val mon = LocalDate.of(2026, 9, 21)
        // Four weeks done on time (one a day early), the fifth open: 4 in a row on the open one, 4 on the last done one.
        val tasks = listOf(
            weekly("a", mon, 0, "b"), weekly("b", mon.plusWeeks(1), -1, "c"), weekly("c", mon.plusWeeks(2), 0, "d"),
            weekly("d", mon.plusWeeks(3), 0, "e"), weekly("e", mon.plusWeeks(4), null, null))
        val streaks = TaskStreaks.streaks(tasks)
        assertEquals(4, streaks["e"]); assertEquals(4, streaks["d"]); assertEquals(2, streaks["b"])
        // Bug hunt 29 S1: the open one moved (Due tomorrow, a drag, Due on…) keeps what was earned.
        assertEquals(4, TaskStreaks.streaks(tasks.dropLast(1) + weekly("e", mon.plusWeeks(4).plusDays(3), null, null))["e"])
        // S3: a day late breaks the run, for a weekly task as for a daily one.
        val late = listOf(
            weekly("a", mon, 0, "b"), weekly("b", mon.plusWeeks(1), 1, "c"),
            weekly("c", mon.plusWeeks(2), 0, "d"), weekly("d", mon.plusWeeks(3), 0, "e"), weekly("e", mon.plusWeeks(4), null, null))
        assertEquals(2, TaskStreaks.streaks(late)["e"])
        assertNull(TaskStreaks.streaks(late)["b"])
        // An open one in the middle (undone again) ends a run too.
        assertNull(TaskStreaks.streaks(listOf(weekly("a", mon, 0, "b"), weekly("b", mon.plusWeeks(1), null, "c"),
            weekly("c", mon.plusWeeks(2), 0, "d"), weekly("d", mon.plusWeeks(3), null, null)))["d"])
        // One in a row isn't shown; nor is a task that doesn't repeat.
        assertNull(TaskStreaks.streaks(listOf(weekly("a", mon, 0, "b"), weekly("b", mon.plusWeeks(1), null, null)))["b"])
        assertNull(TaskStreaks.streaks(listOf(PlannerTask(id = "x", title = "Once", dueDate = mon, done = true)))["x"])
    }

    @Test fun doneBeforeTheDayWasKeptOrWithNoDueDateCountsAsOnTime() {
        val mon = LocalDate.of(2026, 9, 21)
        // Done before this version (no doneOn), and an undated first occurrence (S6): on time.
        val tasks = listOf(PlannerTask(id = "a", title = "Gym", repeat = "WEEKLY", done = true, doneOn = mon.minusDays(3), nextTaskId = "b"),
            PlannerTask(id = "b", title = "Gym", dueDate = mon, repeat = "WEEKLY", done = true, nextTaskId = "c"),
            PlannerTask(id = "c", title = "Gym", dueDate = mon.plusWeeks(1), repeat = "WEEKLY", nextTaskId = null))
        assertEquals(2, TaskStreaks.streaks(tasks)["c"])
    }

    @Test fun aLongHistoryIsCountedOnceAlongItsChain() {
        // S2: a daily task done every day for ten years: worked out in one pass (this took minutes counted one by one).
        val start = LocalDate.of(2016, 1, 1)
        val tasks = (0 until 3650).map { i ->
            PlannerTask(id = "t$i", title = "Pills", dueDate = start.plusDays(i.toLong()), repeat = "DAILY", done = i < 3649,
                doneOn = if (i < 3649) start.plusDays(i.toLong()) else null, nextTaskId = if (i < 3649) "t${i + 1}" else null)
        }
        val began = System.nanoTime()
        assertEquals(3649, TaskStreaks.streaks(tasks)["t3649"])
        assertTrue("took ${(System.nanoTime() - began) / 1_000_000} ms", System.nanoTime() - began < 2_000_000_000L)
    }

    @Test fun birthdayRemindersComeTheChosenDaysAhead() {
        val sam = 1L to ContactBirthdays.Birthday("Sam", MonthDay.of(10, 18), 1992)
        val ana = 2L to ContactBirthdays.Birthday("Ana", MonthDay.of(10, 15), null)
        val leap = 3L to ContactBirthdays.Birthday("Lee", MonthDay.of(2, 29), 2000)
        assertEquals(listOf(ContactBirthdays.Upcoming(1, "Sam", LocalDate.of(2026, 10, 18), 34)),
            ContactBirthdays.dueIn(listOf(sam, ana, leap), day, 3))
        assertEquals(listOf("Ana"), ContactBirthdays.dueIn(listOf(sam, ana, leap), day, 0).map { it.name })
        assertTrue(ContactBirthdays.dueIn(listOf(sam), day, ContactBirthdays.REMIND_OFF).isEmpty())
        // 29 February: the 28th in other years.
        assertEquals(listOf("Lee"), ContactBirthdays.dueIn(listOf(leap), LocalDate.of(2027, 2, 21), 7).map { it.name })
        assertEquals("Sam's birthday in 3 days (turns 34)", ContactBirthdays.reminderText(ContactBirthdays.dueIn(listOf(sam), day, 3).single(), day))
        assertEquals("Ana's birthday today", ContactBirthdays.reminderText(ContactBirthdays.dueIn(listOf(ana), day, 0).single(), day))
        assertEquals(listOf("Off", "On the day", "1 day before", "3 days before", "1 week before"), ContactBirthdays.REMIND_CHOICES.map(ContactBirthdays::remindLabel))
    }

    @Test fun aBirthdayEventsTitleGivesTheName() {
        assertEquals("Sam", ContactBirthdays.nameOf("Sam's birthday (34)"))
        assertEquals("Mary Jo", ContactBirthdays.nameOf("Mary Jo's birthday"))
        assertNull(ContactBirthdays.nameOf("Dentist"))
    }
}
