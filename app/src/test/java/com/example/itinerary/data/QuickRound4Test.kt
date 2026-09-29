package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// Parser round 4 (probe list qa/parser-probe/probe-round4.txt): phrasings Quick entry read wrongly or refused.
class QuickRound4Test {
    private val today = LocalDate.of(2026, 9, 30) // a Wednesday
    private val now = LocalDateTime.of(2026, 9, 30, 10, 0)
    private fun parse(text: String) = QuickEntry.parse(text, today, now = now, dayFirst = true)
    // Read without an error; a question the user answers (morning or afternoon, what time) is fine.
    private fun ok(text: String) = parse(text).also { assertTrue("$text: ${it.error}", it.error == null || it.clarificationOnly) }
    private fun day(month: Int, day: Int) = LocalDate.of(2026, month, day)

    @Test fun lunchtimeIsATimeNotAPlace() {
        ok("Lunch with Kim at lunchtime Friday").let {
            assertEquals("Lunch with Kim", it.title); assertEquals("", it.location); assertEquals(day(10, 2), it.date)
            assertNull(it.time); assertNotNull(it.timePrompt)
        }
        ok("Lunch with Kim at lunchtime Friday 12:30pm").let { assertEquals(LocalTime.of(12, 30), it.time); assertNull(it.timePrompt) }
        assertNotNull(parse("Lunch at lunchtime Friday 6pm").error) // not lunchtime
    }

    @Test fun aLengthRightAfterATime() {
        ok("Webinar Tuesday 1pm 1.5 hours").let { assertEquals("Webinar", it.title); assertEquals(90, it.durationMinutes); assertEquals(LocalTime.of(13, 0), it.time) }
        ok("Call with Jo tomorrow 3pm 45 mins").let { assertEquals("Call with Jo", it.title); assertEquals(45, it.durationMinutes) }
        assertEquals(60, ok("Call 3pm, 1 hour").durationMinutes)
        assertEquals(105, ok("Meeting 3pm for 1h 45 mins").durationMinutes)
        // Not after a time: an ordinary title.
        ok("Buy 2 hours of parking").let { assertEquals("Buy 2 hours of parking", it.title); assertNull(it.durationMinutes) }
    }

    @Test fun betweenTwoTimes() {
        ok("Workshop between 2 and 4 Thursday").let {
            assertEquals("Workshop", it.title); assertEquals(day(10, 1), it.date); assertTrue(it.ambiguousTime)
            assertEquals(listOf(LocalTime.of(2, 0), LocalTime.of(14, 0)), it.timeChoices); assertEquals(120, it.durationMinutes)
        }
        ok("Workshop between 2pm and 4pm Thursday").let { assertEquals(LocalTime.of(14, 0), it.time); assertEquals(120, it.durationMinutes) }
    }

    @Test fun rangesWithoutAmPmOfferTheChoice() {
        ok("Study 2 till 4 tomorrow").let {
            assertEquals("Study", it.title); assertEquals(day(10, 1), it.date)
            assertEquals(listOf(LocalTime.of(2, 0), LocalTime.of(14, 0)), it.timeChoices); assertEquals(120, it.durationMinutes)
        }
        ok("Lunch 12 till 1").let { assertEquals(listOf(LocalTime.MIDNIGHT, LocalTime.NOON), it.timeChoices); assertEquals(60, it.durationMinutes) }
        ok("Parent-teacher interviews Thursday 4:30-6").let {
            assertEquals("Parent-teacher interviews", it.title); assertEquals(listOf(LocalTime.of(4, 30), LocalTime.of(16, 30)), it.timeChoices)
            assertEquals(90, it.durationMinutes)
        }
        // A part of the day settles it.
        ok("Nap tomorrow afternoon 2 till 4").let { assertEquals(LocalTime.of(14, 0), it.time); assertEquals(120, it.durationMinutes); assertFalse(it.ambiguousTime) }
        // Unchanged: am/pm on one end, and 24-hour ranges.
        ok("Gym Monday 6-7am").let { assertEquals(LocalTime.of(6, 0), it.time); assertEquals(60, it.durationMinutes) }
        ok("Standup 14:00-14:15 tomorrow").let { assertEquals(LocalTime.of(14, 0), it.time); assertEquals(15, it.durationMinutes) }
        ok("Class 9.00-10.30 Monday").let { assertEquals(LocalTime.of(9, 0), it.time); assertEquals(90, it.durationMinutes); assertFalse(it.ambiguousTime) }
    }

    @Test fun annuallyAndQuarterly() {
        ok("Insurance renewal annually 1 March").let {
            assertEquals("Insurance renewal", it.title); assertEquals(RepeatRule.YEARLY, it.repeat); assertEquals(LocalDate.of(2027, 3, 1), it.date)
        }
        assertEquals(RepeatRule.everyMonths(3), ok("Rates quarterly").repeat)
        ok("Car service every 3 months").let { assertEquals("Car service", it.title); assertEquals(RepeatRule.everyMonths(3), it.repeat) }
        assertEquals(RepeatRule.everyMonths(2), ok("Haircut every other month").repeat)
        assertNotNull(parse("Haircut every 30 months").error)
    }

    @Test fun repeatsUntilADate() {
        ok("Piano every Monday until Christmas").let {
            assertEquals("Piano", it.title); assertEquals(RepeatRule.WEEKLY, it.repeat); assertEquals(day(10, 5), it.date)
            assertEquals(12, it.repeatCount); assertTrue(it.repeatCountSpecified) // 5 Oct … 21 Dec
        }
        assertEquals(9, ok("Piano every Monday until 30 November").repeatCount)
        assertEquals(3, ok("Gym every weekday until Friday").repeatCount) // Wed, Thu, Fri
        assertEquals(4, ok("Swim every day until 3 Oct").repeatCount)
        assertNotNull(parse("Piano every Monday until today").error) // ends before it starts
        assertNotNull(parse("Piano every Monday until Christmas for 3 times").error) // two ends
    }

    @Test fun withAReminder() {
        ok("Doctor tomorrow 11am with a reminder").let { assertEquals("Doctor", it.title); assertEquals(0, it.reminderMinutes) }
        ok("Dentist 3pm, and remind me").let { assertEquals("Dentist", it.title); assertEquals(0, it.reminderMinutes) }
        assertNotNull(parse("Dentist 3pm with a reminder remind me 1 hour before").error)
        ok("Reminder app review").let { assertEquals("Reminder app review", it.title); assertNull(it.reminderMinutes) }
    }

    @Test fun noLeftoverWords() {
        ok("Visit gran this coming Monday").let { assertEquals("Visit gran", it.title); assertEquals(day(10, 5), it.date) }
        ok("Call bank first thing tomorrow").let { assertEquals("Call bank", it.title); assertEquals(day(10, 1), it.date); assertNotNull(it.timePrompt) }
        assertEquals(LocalTime.of(8, 0), ok("Call bank first thing tomorrow 8am").time)
        ok("Emails before noon").let { assertEquals("Emails", it.title); assertEquals(LocalTime.NOON, it.time) }
        ok("Pay bill before Friday").let { assertEquals("Pay bill", it.title); assertEquals(day(10, 2), it.date) }
    }

    @Test fun weeksWeekendsAndMonths() {
        ok("Camping next weekend").let { assertEquals("Camping", it.title); assertEquals(day(10, 10), it.date) }
        ok("Review goals end of next week").let { assertEquals("Review goals", it.title); assertEquals(day(10, 9), it.date) }
        assertEquals(day(10, 2), ok("Review goals end of this week").date)
        assertEquals(day(10, 2), ok("Report by the end of the week").date)
        ok("Budget meeting start of next month").let { assertEquals("Budget meeting", it.title); assertEquals(day(10, 1), it.date) }
        assertEquals(day(10, 1), ok("Budget meeting beginning of next month").date)
        ok("Catch up early next week").let { assertEquals("Catch up", it.title); assertEquals(day(10, 5), it.date) }
        assertEquals(day(10, 31), ok("Invoices end of next month").date)
        // Still asks: a whole week or month without a day.
        assertNotNull(parse("Review next week").error); assertNotNull(parse("Review next month").error)
    }

    @Test fun everyMorningOrEvening() {
        ok("Walk every morning").let { assertEquals("Walk", it.title); assertEquals(RepeatRule.DAILY, it.repeat); assertNotNull(it.timePrompt) }
        ok("Journal every evening at 9pm").let { assertEquals("Journal", it.title); assertEquals(RepeatRule.DAILY, it.repeat); assertEquals(LocalTime.of(21, 0), it.time) }
        assertNotNull(parse("Stretch every evening at 9am").error)
        assertNotNull(parse("Walk every morning every Monday").error)
    }

    @Test fun weekdayOfTheMonth() {
        ok("Book club last Thursday of the month").let {
            assertEquals("Book club", it.title); assertEquals(RepeatRule.monthlyOn(RepeatRule.LAST, THURSDAY), it.repeat); assertEquals(day(10, 29), it.date)
        }
        assertEquals(RepeatRule.monthlyOn(1, MONDAY), ok("Board meeting first Monday of the month").repeat)
    }

    @Test fun everyFewMonthsInTheModel() {
        val rule = RepeatRule.everyMonths(3)
        assertEquals("EVERY_N_MONTHS:3", rule.name); assertEquals(rule, RepeatRule.parse("EVERY_N_MONTHS:3"))
        assertEquals("Every 3 months", rule.label)
        assertNull(RepeatRule.parse("EVERY_N_MONTHS:25")); assertNull(RepeatRule.parse("EVERY_N_MONTHS:1"))
        // From the start date each time, so a 31st stays the last day of shorter months.
        assertEquals(listOf(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 4, 30), LocalDate.of(2027, 7, 31)), rule.dates(LocalDate.of(2027, 1, 31), 3))
        assertEquals(LocalDate.of(2027, 4, 30), rule.nextAfter(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 10)))
        assertEquals("Every 3 months", TaskRepeat.label("EVERY_N_MONTHS:3", 0))
        assertTrue(TaskRepeat.valid("EVERY_N_MONTHS:3")); assertFalse(TaskRepeat.valid("EVERY_N_MONTHS:99"))
        val task = PlannerTask(title = "Car service", repeat = "EVERY_N_MONTHS:3", dueDate = LocalDate.of(2026, 9, 15))
        assertEquals(LocalDate.of(2026, 12, 15), task.nextOccurrence(LocalDate.of(2026, 9, 30))!!.dueDate)
        // Calendar files: FREQ=MONTHLY;INTERVAL=3 becomes every 3 months.
        val file = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:c\r\nDTSTART:20261015T090000\r\nRRULE:FREQ=MONTHLY;INTERVAL=3;COUNT=3\r\nSUMMARY:Service\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        assertEquals(RepeatRule.everyMonths(3), CalendarFileImport.read(file, java.time.ZoneOffset.UTC, today).entries.single().repeat)
    }

    @Test fun unchanged() {
        ok("Buy milk").let { assertEquals("Buy milk", it.title); assertFalse(it.dateSpecified) }
        ok("Holiday for 2 weeks").let { assertEquals("Holiday", it.title); assertEquals(today.plusDays(13), it.endDate) }
        assertEquals(RepeatRule.MONTHLY, ok("Mortgage every month on the 15th").repeat)
        assertNotNull(parse("Payday every second Friday").error)
        assertNotNull(parse("Groceries after work").timePrompt)
        assertTrue(ok("Meet Alex at 10 tmrw at the library").ambiguousTime)
    }
}
