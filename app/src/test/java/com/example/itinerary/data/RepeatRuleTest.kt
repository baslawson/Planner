package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek.*
import java.time.LocalDate
import java.time.ZoneId

class RepeatRuleTest {
    private val monday = LocalDate.of(2026, 9, 28)

    @Test fun storedNamesRoundTripAndOldNamesStillRead() {
        for (rule in RepeatRule.entries + listOf(RepeatRule.everyDays(3), RepeatRule.everyWeeks(5), RepeatRule.onDays(setOf(FRIDAY, MONDAY)),
            RepeatRule.monthlyOn(1, MONDAY), RepeatRule.monthlyOn(RepeatRule.LAST, FRIDAY)))
            assertEquals(rule, RepeatRule.parse(rule.name))
        assertEquals(RepeatRule.WEEKLY, RepeatRule.valueOf("WEEKLY"))
        assertEquals("DAYS_OF_WEEK:MON,FRI", RepeatRule.onDays(setOf(FRIDAY, MONDAY)).name)
        assertEquals("MONTHLY_WEEKDAY:LAST,FRI", RepeatRule.monthlyOn(RepeatRule.LAST, FRIDAY).name)
        for (bad in listOf("EVERY_N_DAYS:1", "EVERY_N_DAYS:366", "EVERY_N_WEEKS:53", "DAYS_OF_WEEK:", "MONTHLY_WEEKDAY:5,MON",
            "MONTHLY_WEEKDAY:1", "WEEKLY:3", "FOO", "", "EVERY_N_DAYS:x"))
            assertNull(bad, RepeatRule.parse(bad))
        // While being edited an incomplete rule is kept, but it is not valid.
        assertFalse(RepeatRule.parse("EVERY_N_DAYS:0", complete = false)!!.valid)
    }

    @Test fun labels() {
        assertEquals("Every 3 days", RepeatRule.everyDays(3).label)
        assertEquals("Every 3 weeks", RepeatRule.everyWeeks(3).label)
        assertEquals("Every Mon, Wed, Fri", RepeatRule.onDays(setOf(WEDNESDAY, FRIDAY, MONDAY)).label)
        assertEquals("Monthly on the first Monday", RepeatRule.monthlyOn(1, MONDAY).label)
        assertEquals("Monthly on the last Friday", RepeatRule.monthlyOn(RepeatRule.LAST, FRIDAY).label)
    }

    @Test fun dates() {
        assertEquals(listOf(monday, monday.plusDays(3), monday.plusDays(6)), RepeatRule.everyDays(3).dates(monday, 3))
        assertEquals(listOf(monday, monday.plusWeeks(3)), RepeatRule.everyWeeks(3).dates(monday, 2))
        val tuesday = monday.plusDays(1)
        assertEquals(listOf(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5)),
            RepeatRule.onDays(setOf(MONDAY, WEDNESDAY, FRIDAY)).dates(tuesday, 3))
        assertEquals(listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 12, 7)),
            RepeatRule.monthlyOn(1, MONDAY).dates(monday, 3))
        assertEquals(listOf(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 27), LocalDate.of(2026, 12, 25)),
            RepeatRule.monthlyOn(RepeatRule.LAST, FRIDAY).dates(monday, 3))
        // A start that is itself the right weekday of the month is kept.
        assertEquals(LocalDate.of(2026, 10, 5), RepeatRule.monthlyOn(1, MONDAY).dates(LocalDate.of(2026, 10, 5), 1).single())
        assertTrue(RepeatRule.monthlyOn(1, MONDAY).fits(LocalDate.of(2026, 10, 5)))
        assertFalse(RepeatRule.monthlyOn(1, MONDAY).fits(LocalDate.of(2026, 10, 12)))
        assertEquals(RepeatRule.monthlyOn(RepeatRule.LAST, WEDNESDAY), RepeatRule.monthlyLike(LocalDate.of(2026, 9, 30)))
        assertEquals(RepeatRule.monthlyOn(4, MONDAY), RepeatRule.monthlyLike(LocalDate.of(2026, 9, 28)))
    }

    @Test fun tasksMoveToTheNextDateOfTheNewerRules() {
        val zone = ZoneId.of("Australia/Perth")
        fun next(repeat: String, due: LocalDate, today: LocalDate) = PlannerTask(title = "T", dueDate = due, repeat = repeat).nextOccurrence(today, zone)!!.dueDate
        assertEquals(LocalDate.of(2026, 10, 1), next("EVERY_N_DAYS:3", monday, monday))
        // Completed late: the next date on the same three-day rhythm after today.
        assertEquals(LocalDate.of(2026, 10, 7), next("EVERY_N_DAYS:3", monday, LocalDate.of(2026, 10, 5)))
        assertEquals(monday.plusWeeks(3), next("EVERY_N_WEEKS:3", monday, monday))
        assertEquals(LocalDate.of(2026, 10, 1), next("DAYS_OF_WEEK:MON,THU", monday, monday))
        assertEquals(LocalDate.of(2026, 11, 2), next("MONTHLY_WEEKDAY:1,MON", LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5)))
    }

    @Test fun taskValidationAcceptsCompleteNewerRulesOnly() {
        for (ok in listOf("NONE", "WEEKLY", "AFTER_COMPLETION", "EVERY_N_DAYS:3", "DAYS_OF_WEEK:MON,WED", "MONTHLY_WEEKDAY:LAST,FRI"))
            assertTrue(ok, TaskRepeat.valid(ok))
        for (bad in listOf("EVERY_N_DAYS", "EVERY_N_DAYS:1", "WEEKLY:2", "AFTER_COMPLETION:3", "FOO"))
            assertFalse(bad, TaskRepeat.valid(bad))
        assertEquals("Every 3 days", TaskRepeat.label("EVERY_N_DAYS:3", 7))
        assertEquals("Days after completion (5)", TaskRepeat.label("AFTER_COMPLETION", 5))
    }
}
