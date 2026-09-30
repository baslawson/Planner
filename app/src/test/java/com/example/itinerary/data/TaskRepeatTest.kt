package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TaskRepeatTest {
    @Test fun undatedReminderFollowsNextDueDateForLateAndEarlyCompletion() {
        val today = LocalDate.of(2026, 9, 26)
        val zone = ZoneId.of("Australia/Perth")
        for (rule in TaskRepeat.entries.filter { it != TaskRepeat.NONE }) {
            for (reminderDay in listOf(today.minusDays(40), today.plusDays(40))) {
                // The newer kinds carry their value: every 3 days, Mondays and Thursdays, the first Monday.
                val stored = mapOf("EVERY_N_DAYS" to "EVERY_N_DAYS:3", "EVERY_N_WEEKS" to "EVERY_N_WEEKS:3", "EVERY_N_MONTHS" to "EVERY_N_MONTHS:3",
                    "DAYS_OF_WEEK" to "DAYS_OF_WEEK:MON,THU", "MONTHLY_WEEKDAY" to "MONTHLY_WEEKDAY:1,MON")[rule.name] ?: rule.name
                val task = PlannerTask(title = "Undated", repeat = stored, repeatDays = 3,
                    reminderAt = reminderDay.atTime(9, 15).atZone(zone).toInstant().toEpochMilli())
                val next = task.nextOccurrence(today, zone)!!
                assertEquals(rule.name, next.dueDate!!.atTime(9, 15).atZone(zone).toInstant().toEpochMilli(), next.reminderAt)
            }
        }
    }
    @Test fun undatedReminderKeepsLocalTimeAcrossDstTransitions() {
        val zone = ZoneId.of("Europe/London")
        for (day in listOf(LocalDate.of(2026, 3, 28), LocalDate.of(2026, 10, 24))) {
            val task = PlannerTask(title = "Undated", repeat = "DAILY",
                reminderAt = day.minusDays(10).atTime(9, 0).atZone(zone).toInstant().toEpochMilli())
            assertEquals(day.plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), task.nextOccurrence(day, zone)!!.reminderAt)
        }
    }
    @Test fun monthlyRepeatKeepsJanuaryAnchorAcrossFebruary() {
        val january = PlannerTask(title = "Monthly", dueDate = LocalDate.of(2028, 1, 31), repeat = "MONTHLY")
        val february = january.nextOccurrence(LocalDate.of(2028, 1, 31))!!
        assertEquals(LocalDate.of(2028, 2, 29), february.dueDate)
        assertEquals(LocalDate.of(2028, 3, 31), february.nextOccurrence(LocalDate.of(2028, 2, 29))!!.dueDate)
    }
    // 1 Oct bug hunt #3: "every few months" comes back to the 31st after a shorter month, as Monthly does.
    @Test fun everyFewMonthsKeepsTheAnchorAcrossShortMonths() {
        val january = PlannerTask(title = "Quarterly", dueDate = LocalDate.of(2027, 1, 31), repeat = "EVERY_N_MONTHS:3")
        val april = january.nextOccurrence(LocalDate.of(2027, 1, 31))!!
        assertEquals(LocalDate.of(2027, 4, 30), april.dueDate)
        assertEquals(31, april.repeatAnchorDay)
        assertEquals(LocalDate.of(2027, 7, 31), april.nextOccurrence(LocalDate.of(2027, 4, 30))!!.dueDate)
        // A task on an ordinary day is unchanged; events' own series still step from their start.
        assertEquals(LocalDate.of(2027, 7, 15), PlannerTask(title = "Mid", dueDate = LocalDate.of(2027, 4, 15), repeat = "EVERY_N_MONTHS:3")
            .nextOccurrence(LocalDate.of(2027, 4, 15))!!.dueDate)
        assertEquals(LocalDate.of(2027, 4, 30), RepeatRule.everyMonths(3).nextAfter(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 1, 31)))
    }
    @Test fun lateWeeklyCompletionSkipsMissedDatesAndKeepsWeekday() {
        val task = PlannerTask(title = "Weekly", dueDate = LocalDate.of(2026, 9, 1), repeat = "WEEKLY")
        assertEquals(LocalDate.of(2026, 9, 29), task.nextOccurrence(LocalDate.of(2026, 9, 26))!!.dueDate)
    }
    @Test fun completionBasedRepeatUsesCompletionDateAndResetsChecklist() {
        val task = PlannerTask(title = "Maintenance", dueDate = LocalDate.of(2026, 1, 1), repeat = "AFTER_COMPLETION", repeatDays = 10,
            done = true, checklist = listOf(ChecklistEntry(text = "Step", done = true)))
        val next = task.nextOccurrence(LocalDate.of(2026, 9, 26))!!
        assertEquals(LocalDate.of(2026, 10, 6), next.dueDate)
        assertFalse(next.done); assertFalse(next.checklist.single().done); assertNotEquals(task.id, next.id)
    }
    @Test fun reminderKeepsLocalClockAcrossDaylightSaving() {
        val zone = ZoneId.of("Europe/London")
        val due = LocalDate.of(2026, 3, 28)
        val task = PlannerTask(title = "Daily", dueDate = due, repeat = "DAILY", reminderAt = due.atTime(9, 0).atZone(zone).toInstant().toEpochMilli())
        assertEquals(due.plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), task.nextOccurrence(due, zone)!!.reminderAt)
    }
    @Test fun nonRepeatingHasNoSuccessorAndUndatedStartsFromToday() {
        val task = PlannerTask(title = "Once")
        assertNull(task.nextOccurrence())
        assertEquals(LocalDate.of(2026, 10, 3), task.copy(repeat = "WEEKLY").nextOccurrence(LocalDate.of(2026, 9, 26))!!.dueDate)
    }
    @Test fun fortnightlyRepeatKeepsFourteenDayScheduleAfterLateCompletion() {
        val task = PlannerTask(title = "Fortnightly", dueDate = LocalDate.of(2026, 9, 1), repeat = "FORTNIGHTLY")
        assertEquals(LocalDate.of(2026, 9, 15), task.nextOccurrence(LocalDate.of(2026, 9, 1))!!.dueDate)
        assertEquals(LocalDate.of(2026, 9, 29), task.nextOccurrence(LocalDate.of(2026, 9, 26))!!.dueDate)
        assertEquals(LocalDate.of(2026, 10, 13), task.nextOccurrence(LocalDate.of(2026, 9, 29))!!.dueDate)
    }
    @Test fun yearlyRepeatKeepsLeapDayAnchorAcrossNonLeapYears() {
        var task = PlannerTask(title = "Yearly", dueDate = LocalDate.of(2024, 2, 29), repeat = "YEARLY")
        for (year in 2025..2028) {
            task = task.nextOccurrence(task.dueDate!!)!!
            assertEquals(LocalDate.of(year, 2, if (year==2028) 29 else 28), task.dueDate)
            assertEquals(29, task.repeatAnchorDay)
        }
    }
    @Test fun yearlyRepeatSkipsMissedYearsAndAdvancesFromScheduledDateWhenEarly() {
        val task = PlannerTask(title = "Yearly", dueDate = LocalDate.of(2020, 9, 30), repeat = "YEARLY")
        assertEquals(LocalDate.of(2026, 9, 30), task.nextOccurrence(LocalDate.of(2026, 9, 26))!!.dueDate)
        assertEquals(LocalDate.of(2027, 9, 30), task.nextOccurrence(LocalDate.of(2026, 9, 30))!!.dueDate)
        assertEquals(LocalDate.of(2021, 9, 30), task.nextOccurrence(LocalDate.of(2020, 9, 1))!!.dueDate)
    }
    @Test fun undatedNewRepeatsStartFromCompletionAndYearlyReminderKeepsClock() {
        val today = LocalDate.of(2026, 9, 26)
        assertEquals(today.plusDays(14), PlannerTask(title="Undated",repeat="FORTNIGHTLY").nextOccurrence(today)!!.dueDate)
        assertEquals(today.plusYears(1), PlannerTask(title="Undated",repeat="YEARLY").nextOccurrence(today)!!.dueDate)
        val zone=ZoneId.of("Australia/Perth")
        val task=PlannerTask(title="Yearly",dueDate=today,repeat="YEARLY",reminderAt=today.atTime(9,0).atZone(zone).toInstant().toEpochMilli())
        assertEquals(today.plusYears(1).atTime(9,0).atZone(zone).toInstant().toEpochMilli(), task.nextOccurrence(today,zone)!!.reminderAt)
    }
}
