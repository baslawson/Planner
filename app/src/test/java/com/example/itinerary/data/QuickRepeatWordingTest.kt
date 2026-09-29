package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek.*
import java.time.*

/** Repeats that need the newer rules: every few days or weeks, chosen weekdays, a weekday of the month. */
class QuickRepeatWordingTest {
    private val today = LocalDate.of(2026, 9, 28) // a Monday
    private fun parse(text: String) = QuickEntry.parse(text, today, dayFirst = true)
    private fun ok(text: String) = parse(text).also { assertNull(text, it.error) }

    @Test fun everyFewDaysOrWeeks() {
        ok("Swim every other day").let { assertEquals("Swim", it.title); assertEquals(RepeatRule.everyDays(2), it.repeat); assertEquals(today, it.date) }
        assertEquals(RepeatRule.everyDays(3), ok("Water plants every 3 days").repeat)
        assertEquals(RepeatRule.everyDays(3), ok("Water plants every three days").repeat)
        assertEquals("Water plants", ok("Water plants every 3 days").title)
        assertEquals(RepeatRule.everyWeeks(3), ok("Review every 3 weeks").repeat)
        assertEquals(RepeatRule.everyWeeks(6), ok("Haircut every six weeks").repeat)
        // Unchanged: two weeks is fortnightly, one day is daily.
        assertEquals(RepeatRule.FORTNIGHTLY, ok("Review every 2 weeks").repeat)
        assertEquals(RepeatRule.DAILY, ok("Review every day").repeat)
        // Parser round 4: every few months.
        assertEquals(RepeatRule.everyMonths(2), ok("Review every other month").repeat)
        assertEquals(RepeatRule.everyMonths(3), ok("Car service every 3 months").repeat)
        assertEquals(RepeatRule.MONTHLY, ok("Review every 1 months").repeat)
    }

    @Test fun chosenWeekdays() {
        val mwf = RepeatRule.onDays(setOf(MONDAY, WEDNESDAY, FRIDAY))
        for (text in listOf("Gym every Mon, Wed and Fri 6am", "Gym Mon Wed Fri 6am", "Gym every Monday, Wednesday & Friday 6am",
            "Gym Mon/Wed/Fri 6am", "Gym Mondays, Wednesdays and Fridays 6am", "Gym on Mon, Wed, Fri at 6am")) {
            val r = ok(text)
            assertEquals(text, "Gym", r.title); assertEquals(text, mwf, r.repeat)
            assertEquals(text, today, r.date); assertEquals(text, LocalTime.of(6, 0), r.time)
        }
        ok("Class every monday and wednesday 6pm").let { assertEquals("Class", it.title); assertEquals(RepeatRule.onDays(setOf(MONDAY, WEDNESDAY)), it.repeat) }
        // The first date is the first chosen day on or after today.
        assertEquals(LocalDate.of(2026, 9, 29), ok("Gym Tue and Thu").date)
        // With a period: two weeks of three days a week.
        assertEquals(6, ok("Gym Mon Wed Fri for 2 weeks").repeatCount)
        // "or" is not a list of days.
        assertNotNull(parse("Meeting Monday or Tuesday").error)
        // A task keeps the rule.
        assertEquals("DAYS_OF_WEEK:MON,WED,FRI", ok("Gym Mon Wed Fri").quickTask().repeat)
    }

    @Test fun weekdayOfTheMonth() {
        ok("Board meeting first Monday of every month").let {
            assertEquals("Board meeting", it.title); assertEquals(RepeatRule.monthlyOn(1, MONDAY), it.repeat)
            assertEquals(LocalDate.of(2026, 10, 5), it.date)
        }
        ok("Book club last Friday of each month 7pm").let {
            assertEquals("Book club", it.title); assertEquals(RepeatRule.monthlyOn(RepeatRule.LAST, FRIDAY), it.repeat)
            assertEquals(LocalDate.of(2026, 10, 30), it.date); assertEquals(LocalTime.of(19, 0), it.time)
        }
        assertEquals(RepeatRule.monthlyOn(1, MONDAY), ok("Rent every first Monday").repeat)
        assertEquals(RepeatRule.monthlyOn(3, THURSDAY), ok("Pay day on the 3rd Thursday of every month").repeat)
        assertEquals(RepeatRule.monthlyOn(2, TUESDAY), ok("Book club 2nd Tuesday of every month").repeat)
        // "every 2nd Tuesday" could mean every other Tuesday: ask.
        val unclear = parse("Book club every 2nd Tuesday")
        assertTrue(unclear.error!!.contains("every other Tuesday"))
        assertNotNull(parse("Book club every second Tuesday").error)
        // Parser round 4 (user approved): "of the month" reads as every month, like "of every month".
        assertEquals(RepeatRule.monthlyOn(1, MONDAY), ok("Board meeting first Monday of the month").repeat)
    }
}
