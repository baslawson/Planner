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
    // A task's ⋮ "Due tomorrow": the reminder moves by the same days (its clock time kept across daylight saving); an
    // undated task keeps its reminder; a moved reminder ends a snooze.
    @Test fun dueTomorrowMovesTheReminderWithTheDate() {
        val sydney = ZoneId.of("Australia/Sydney")
        val today = LocalDate.of(2026, 10, 5)
        fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(sydney).toInstant().toEpochMilli()
        val late = PlannerTask(title = "Late", dueDate = LocalDate.of(2026, 10, 1), reminderAt = at(LocalDate.of(2026, 9, 30), 9),
            snoozedUntil = at(today, 12))
        // Moved at 8 am, so today's 9 am is still ahead.
        val moved = late.dueTomorrow(today, at(today, 8), sydney)
        assertEquals(LocalDate.of(2026, 10, 6), moved.dueDate)
        // Five days on, across the 4 October clock change: still 9 am, the day before it's due.
        assertEquals(at(LocalDate.of(2026, 10, 5), 9), moved.reminderAt)
        assertNull(moved.snoozedUntil)
        val undated = PlannerTask(title = "Some day", reminderAt = at(today, 15), snoozedUntil = at(today, 16))
        val dated = undated.dueTomorrow(today, at(today, 8), sydney)
        assertEquals(LocalDate.of(2026, 10, 6), dated.dueDate)
        assertEquals(undated.reminderAt, dated.reminderAt); assertEquals(undated.snoozedUntil, dated.snoozedUntil)
        assertEquals(late.copy(dueDate = moved.dueDate, reminderAt = moved.reminderAt, snoozedUntil = null), moved)
    }
    // Review T1: "Due tomorrow" never leaves a reminder in the past (it would never ring) or after the new due date.
    @Test fun dueTomorrowNeverLeavesAPastOrLateReminder() {
        val sydney = ZoneId.of("Australia/Sydney")
        val today = LocalDate.of(2026, 10, 5)
        fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(sydney).toInstant().toEpochMilli()
        val now = at(today, 10)
        // Due on the 10th, reminder the day before at 9: brought forward 4 days it would be today 9 am, already past.
        val early = PlannerTask(title = "Early", dueDate = LocalDate.of(2026, 10, 10), reminderAt = at(LocalDate.of(2026, 10, 9), 9))
        assertEquals(at(today.plusDays(1), 9), early.dueTomorrow(today, now, sydney).reminderAt)
        // The same for a late task whose moved reminder (today 9 am) has just passed.
        val late = PlannerTask(title = "Late", dueDate = LocalDate.of(2026, 10, 1), reminderAt = at(LocalDate.of(2026, 9, 30), 9))
        assertEquals(at(today.plusDays(1), 9), late.dueTomorrow(today, now, sydney).reminderAt)
        // An overdue task reminded this afternoon: moved 5 days it would come after the new due date, so it stays,
        // snooze and all.
        val overdue = PlannerTask(title = "Overdue", dueDate = LocalDate.of(2026, 10, 1), reminderAt = at(today, 15),
            snoozedUntil = at(today, 16))
        val kept = overdue.dueTomorrow(today, now, sydney)
        assertEquals(overdue.reminderAt, kept.reminderAt); assertEquals(overdue.snoozedUntil, kept.snoozedUntil)
        for (task in listOf(early, late, overdue)) {
            val reminder = task.dueTomorrow(today, now, sydney).reminderAt!!
            assertTrue(task.title, reminder > now && reminder < at(today.plusDays(2), 0))
        }
    }
    // Review T4: like changing the date in the editor, "Due tomorrow" resets the day a monthly repeat keeps to.
    @Test fun dueTomorrowResetsTheMonthlyAnchorDay() {
        val successor = PlannerTask(title = "Rent", dueDate = LocalDate.of(2027, 4, 30), repeat = "MONTHLY", repeatAnchorDay = 31)
        val moved = successor.dueTomorrow(LocalDate.of(2027, 4, 14), 0L)
        assertEquals(0, moved.repeatAnchorDay)
        assertEquals(LocalDate.of(2027, 5, 15), moved.nextOccurrence(LocalDate.of(2027, 4, 15))!!.dueDate)
        // Already due tomorrow: nothing changes.
        val tomorrow = successor.copy(dueDate = LocalDate.of(2027, 4, 15))
        assertEquals(tomorrow, tomorrow.dueTomorrow(LocalDate.of(2027, 4, 14), 0L))
    }
    // Review R3: a repeat's next reminder at the same offset may already have passed (a "day before" reminder done after
    // that time): it goes to the next time its clock time comes round, no later than the new due date.
    @Test fun nextReminderIsNeverAlreadyPast() {
        val zone = ZoneId.of("Australia/Perth")
        val today = LocalDate.of(2026, 10, 5)
        fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val daily = PlannerTask(title = "Pills", dueDate = today, repeat = "DAILY", reminderAt = at(today.minusDays(1), 9))
        // Done at 10: tomorrow's "day before" reminder would be today 9 am.
        assertEquals(at(today.plusDays(1), 9), daily.nextOccurrence(today, zone, at(today, 10))!!.reminderAt)
        // Done at 8: today 9 am is still ahead.
        assertEquals(at(today, 9), daily.nextOccurrence(today, zone, at(today, 8))!!.reminderAt)
        // A weekly one keeps its day-before reminder when that is still ahead.
        assertEquals(at(today.plusDays(6), 9), daily.copy(repeat = "WEEKLY").nextOccurrence(today, zone, at(today, 10))!!.reminderAt)
    }
    // Review R5: task reminders follow the phone's clock, as event reminders do. After a time-zone change a reminder
    // still ahead keeps its local date and time; one already gone off is not brought back, and one not yet gone off is
    // not lost when its clock time has already passed in the new zone.
    @Test fun taskRemindersKeepTheirClockTimeInANewTimeZone() {
        val perth = ZoneId.of("Australia/Perth"); val sydney = ZoneId.of("Australia/Sydney")
        val day = LocalDate.of(2026, 10, 7)
        fun at(hour: Int, zone: ZoneId) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val task = PlannerTask(title = "Call", dueDate = day, reminderAt = at(9, perth))
        // East, at 6 am Perth time (9 am Sydney): 9 am in Sydney has just passed, so the old alarm stays and still rings.
        val east = task.inTimeZone(perth, sydney, at(6, perth) + 60_000)
        assertEquals(at(9, sydney), east.reminderAt); assertEquals(at(9, perth), east.activeReminderAt)
        // East the evening before: 9 am Sydney time.
        val early = task.inTimeZone(perth, sydney, at(9, perth) - 86_400_000)
        assertEquals(at(9, sydney), early.reminderAt); assertEquals(at(9, sydney), early.activeReminderAt)
        // The way back: 9 am in Perth again, unless it has already gone off.
        assertEquals(at(9, perth), early.inTimeZone(sydney, perth, at(9, perth) - 86_400_000).activeReminderAt)
        val rung = PlannerTask(title = "Call", dueDate = day, reminderAt = at(9, sydney))
        val west = rung.inTimeZone(sydney, perth, at(9, sydney) + 60_000)
        assertEquals(at(9, perth), west.reminderAt)
        assertTrue(west.activeReminderAt!! <= at(9, sydney) + 60_000)
        // A snooze is a fixed time, as an event's is; a repeat then follows the new clock time.
        val snoozed = task.copy(snoozedUntil = at(12, perth)).inTimeZone(perth, sydney, at(9, perth) - 86_400_000)
        assertEquals(at(12, perth), snoozed.activeReminderAt)
        assertEquals(day.plusDays(1).atTime(9, 0).atZone(sydney).toInstant().toEpochMilli(),
            snoozed.copy(repeat = "DAILY").nextOccurrence(day, sydney, at(9, perth) - 86_400_000)!!.reminderAt)
        // No reminder, or the same zone: unchanged.
        assertEquals(task.copy(reminderAt = null), task.copy(reminderAt = null).inTimeZone(perth, sydney, 0L))
        assertEquals(task, task.inTimeZone(perth, perth, 0L))
    }
    // R-L1: a reminder that has rung and whose new clock time has passed too keeps the time it rang at, so Done (or
    // Snooze) on its notification, which carries that time, still acts on the task (Repository.actOnTaskReminder).
    @Test fun aRungReminderKeepsItsTimeForItsNotificationAfterATimeZoneChange() {
        val perth = ZoneId.of("Australia/Perth"); val sydney = ZoneId.of("Australia/Sydney")
        val day = LocalDate.of(2026, 10, 7)
        fun at(hour: Int, zone: ZoneId) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val task = PlannerTask(title = "Call", dueDate = day, reminderAt = at(9, perth), repeat = "DAILY")
        // Rang at 9 am in Perth; at 3 pm the phone moves to Sydney, where 9 am has passed as well.
        val east = task.inTimeZone(perth, sydney, at(15, perth))
        assertEquals(at(9, perth), east.activeReminderAt)
        assertNull(east.snoozedAt(at(15, perth)))
        // The next occurrence follows the new clock time.
        assertEquals(day.plusDays(1).atTime(9, 0).atZone(sydney).toInstant().toEpochMilli(),
            east.nextOccurrence(day, sydney, at(15, perth))!!.reminderAt)
        // The same going west.
        val rung = task.copy(reminderAt = at(9, sydney))
        assertEquals(at(9, sydney), rung.inTimeZone(sydney, perth, at(20, sydney)).activeReminderAt)
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
