package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// Parser round 5 (probe list qa/parser-probe/probe-round5.txt): phrasings Quick entry read wrongly or refused.
class QuickRound5Test {
    private val today = LocalDate.of(2026, 9, 30) // a Wednesday
    private val now = LocalDateTime.of(2026, 9, 30, 10, 0)
    private fun parse(text: String) = QuickEntry.parse(text, today, now = now, dayFirst = true)
    private fun ok(text: String) = parse(text).also { assertTrue("$text: ${it.error}", it.error == null || it.clarificationOnly) }
    private fun day(month: Int, day: Int) = LocalDate.of(2026, month, day)

    @Test fun spokenTimesInWords() {
        ok("Meeting at half past four tomorrow").let {
            assertEquals("Meeting", it.title); assertEquals("", it.location); assertEquals(day(10, 1), it.date)
            assertEquals(listOf(LocalTime.of(4, 30), LocalTime.of(16, 30)), it.timeChoices)
        }
        ok("Class at twenty past 10am Monday").let { assertEquals("Class", it.title); assertEquals("", it.location); assertEquals(LocalTime.of(10, 20), it.time) }
        assertEquals(LocalTime.of(18, 45), ok("Dinner at quarter to seven pm").time)
        ok("Call at nine o'clock tomorrow").let { assertEquals("Call", it.title); assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(21, 0)), it.timeChoices) }
        ok("Pickup at 3 sharp Friday").let { assertEquals("Pickup", it.title); assertEquals(listOf(LocalTime.of(3, 0), LocalTime.of(15, 0)), it.timeChoices) }
        assertEquals(LocalTime.of(9, 0), ok("Standup 9am sharp").time)
        // A bare hour word is not a time: "Meet at one" could be a place name.
        ok("Party at Seven Tuesday").let { assertEquals("Seven", it.location); assertNull(it.time) }
    }

    @Test fun moreDates() {
        assertEquals(day(11, 2), ok("Board meeting first Monday in November").date)
        assertEquals(LocalDate.of(2026, 11, 26), ok("Thanksgiving fourth Thursday of November").date)
        assertEquals(LocalDate.of(2027, 9, 6), ok("Fair first Monday of September").date) // past this year: next year
        ok("Exam Monday 19th at 9").let { assertEquals("Exam", it.title); assertEquals(day(10, 19), it.date) }
        ok("Dentist on Friday the 9th").let { assertEquals("Dentist", it.title); assertEquals(day(10, 9), it.date) }
        assertEquals(day(10, 8), ok("Dentist at 10 on Thursday the 8th").date)
        assertEquals(LocalDate.of(2026, 11, 13), ok("Party Friday the 13th").date)
        ok("Follow up a week today").let { assertEquals("Follow up", it.title); assertEquals(day(10, 7), it.date) }
        ok("Coffee a week tomorrow").let { assertEquals("Coffee", it.title); assertEquals(day(10, 8), it.date) }
        ok("Visit tomorrow week").let { assertEquals("Visit", it.title); assertEquals(day(10, 8), it.date) }
        ok("Haircut Monday after next").let { assertEquals("Haircut", it.title); assertEquals(day(10, 12), it.date) }
        assertEquals(day(10, 9), ok("Lunch the Friday after next").date)
        ok("Report last day of October").let { assertEquals("Report", it.title); assertEquals(day(10, 31), it.date) }
        assertEquals(LocalDate.of(2027, 2, 28), ok("Report last day of February").date)
        assertEquals(day(12, 31), ok("Taxes end of the year").date)
        ok("Meeting in 2 weeks on Tuesday").let { assertEquals("Meeting", it.title); assertEquals(day(10, 13), it.date) }
        ok("gym 2morrow 6am").let { assertEquals("gym", it.title); assertEquals(day(10, 1), it.date) }
        ok("Call Sam back later today").let { assertEquals("Call Sam back", it.title); assertEquals(today, it.date); assertTrue(it.dateSpecified) }
    }

    @Test fun holidaysThatMove() {
        ok("Easter egg hunt").let { assertEquals("Easter egg hunt", it.title); assertEquals(LocalDate.of(2027, 3, 28), it.date) }
        assertEquals(LocalDate.of(2027, 3, 26), ok("Market Good Friday").date)
        assertEquals(LocalDate.of(2027, 3, 29), ok("Picnic Easter Monday").date)
        assertEquals(LocalDate.of(2027, 5, 9), ok("Mother's Day lunch").date)
        // Easter in other years (published dates), and on the day itself.
        for ((from, easter) in listOf("2024-01-01" to "2024-03-31", "2025-01-01" to "2025-04-20", "2038-01-01" to "2038-04-25", "2027-03-28" to "2027-03-28"))
            assertEquals(from, LocalDate.parse(easter), QuickEntry.parse("Easter egg hunt", LocalDate.parse(from)).date)
        assertEquals(LocalDate.of(2027, 3, 26), QuickEntry.parse("Market Good Friday", LocalDate.of(2027, 3, 26)).date)
        assertEquals(LocalDate.of(2028, 4, 14), QuickEntry.parse("Market Good Friday", LocalDate.of(2027, 3, 27)).date) // just passed
        assertEquals(180, ok("Swim every day until Easter").repeatCount) // 30 Sep … 28 Mar
    }

    @Test fun repeats() {
        ok("Standup weekly on Tuesdays").let { assertEquals("Standup", it.title); assertEquals(RepeatRule.WEEKLY, it.repeat); assertEquals(day(10, 6), it.date) }
        ok("Payroll biweekly Friday").let { assertEquals("Payroll", it.title); assertEquals(RepeatRule.FORTNIGHTLY, it.repeat); assertEquals(day(10, 2), it.date) }
        ok("Team sync bi-weekly Thursday 10am").let { assertEquals(RepeatRule.FORTNIGHTLY, it.repeat); assertEquals(LocalTime.of(10, 0), it.time) }
        assertEquals(RepeatRule.FORTNIGHTLY, ok("Review every other week on Friday").repeat)
        ok("Bills every month on the last day").let {
            assertEquals("Bills", it.title); assertEquals(RepeatRule.MONTHLY, it.repeat); assertEquals(day(10, 31), it.date)
            assertEquals(LocalDate.of(2026, 11, 30), RepeatRule.MONTHLY.dates(it.date, 2)[1]) // the last day of shorter months
        }
        ok("Walk every weekday morning at 7").let { assertEquals("Walk", it.title); assertEquals(RepeatRule.WEEKDAYS, it.repeat); assertEquals(LocalTime.of(7, 0), it.time) }
        ok("Bins every Tuesday night").let { assertEquals("Bins", it.title); assertEquals(RepeatRule.WEEKLY, it.repeat); assertEquals(day(10, 6), it.date); assertNotNull(it.timePrompt) }
        assertNotNull(parse("Stretch every weekday morning at 7pm").error)
    }

    @Test fun whenARepeatStarts() {
        ok("Lesson every Thursday 4pm starting next week").let {
            assertEquals("Lesson", it.title); assertEquals(RepeatRule.WEEKLY, it.repeat); assertEquals(day(10, 8), it.date); assertEquals(LocalTime.of(16, 0), it.time)
        }
        ok("Course every Tuesday from 6 Oct").let { assertEquals("Course", it.title); assertEquals(day(10, 6), it.date) }
        assertEquals(day(10, 13), ok("Course every Tuesday starting 8 Oct").date) // the first Tuesday from then
        assertNotNull(parse("Course every Tuesday from 6 Oct on Friday").error) // two starts
        assertNotNull(parse("Holiday from next week").error) // not a repeat: still asks for a date
    }

    @Test fun numericEndDates() {
        ok("Piano every Monday until 12/12").let { assertEquals("Piano", it.title); assertEquals(10, it.repeatCount) } // 5 Oct … 7 Dec
        ok("Gym every weekday until 31/10").let { assertEquals(RepeatRule.WEEKDAYS, it.repeat); assertEquals(23, it.repeatCount) }
        // Without a date-format setting, a date that reads both ways asks.
        assertNotNull(QuickEntry.parse("Piano every Monday until 5/12", today, now = now, dayFirst = null).error)
        assertEquals(9, QuickEntry.parse("Piano every Monday until 30/11", today, now = now, dayFirst = null).repeatCount)
    }

    @Test fun notPossibleSaysSo() {
        parse("Book club every 2nd and 4th Tuesday").let { assertTrue(it.error!!.startsWith("Planner repeats on one week of the month")) }
        assertNotNull(parse("Meeting first and third Monday of every month").error)
        parse("Rent twice monthly").let { assertTrue(it.error!!.contains("twice monthly")) }
        assertNotNull(parse("Swim twice a week").error)
    }

    @Test fun remindersWithADayAndATime() {
        ok("Dentist Friday 9am remind me 1 day before at 9am").let { assertEquals("Dentist", it.title); assertEquals(1440, it.reminderMinutes) }
        assertEquals(1440 + 180, ok("Dentist Friday 9am remind me 1 day before at 6am").reminderMinutes)
        assertEquals(2 * 1440 - 540, ok("Flight Saturday 6am remind me 2 days before at 3pm").reminderMinutes)
        assertNotNull(parse("Dentist Friday 9am remind me 2 hours before at 6am").error) // whole days only
        assertNotNull(parse("Dentist Friday 9am remind me 1 day before at 6").error) // am or pm
        ok("Meeting 2pm alarm 30 min before").let { assertEquals("Meeting", it.title); assertEquals(30, it.reminderMinutes) }
        ok("Call 4pm alert me 15m before").let { assertEquals("Call", it.title); assertEquals(15, it.reminderMinutes) }
        ok("Car alarm fitting Friday").let { assertEquals("Car alarm fitting", it.title); assertNull(it.reminderMinutes) }
    }

    @Test fun wordOrderAndLeftovers() {
        ok("Sleep in tomorrow").let { assertEquals("Sleep in", it.title); assertEquals(day(10, 1), it.date) }
        ok("Meeting - Monday - 10am").let { assertEquals("Meeting", it.title); assertEquals(day(10, 5), it.date); assertEquals(LocalTime.of(10, 0), it.time) }
        ok("Call mum (Sunday 5pm)").let { assertEquals("Call mum", it.title); assertEquals(LocalTime.of(17, 0), it.time) }
        ok("Pay electricity bill $120 due Friday").let { assertEquals("Pay electricity bill $120", it.title); assertEquals(day(10, 2), it.date) }
        ok("Movie Friday night 8").let { assertEquals("Movie", it.title); assertEquals(day(10, 2), it.date); assertEquals(LocalTime.of(20, 0), it.time) }
        ok("Drinks tomorrow evening 6-8pm").let { assertEquals(LocalTime.of(18, 0), it.time); assertEquals(120, it.durationMinutes) }
        // A dash in a title stays.
        ok("Check-in - Monday").let { assertEquals("Check-in", it.title) }
        ok("Q3 - budget review Friday").let { assertEquals("Q3 - budget review", it.title) }
        // Still unfinished: nothing after the word.
        assertNotNull(parse("Meeting at").error)
    }

    @Test fun lengthsWrittenFirst() {
        ok("2hr meeting at 3pm tomorrow").let { assertEquals("Meeting", it.title); assertEquals(120, it.durationMinutes); assertEquals(LocalTime.of(15, 0), it.time) }
        ok("30min call 2pm Fri").let { assertEquals("Call", it.title); assertEquals(30, it.durationMinutes) }
        // Only beside a clock time, and only one length.
        ok("2 hours of parking Friday").let { assertEquals("2 hours of parking", it.title); assertNull(it.durationMinutes) }
        assertNotNull(parse("2hr meeting at 3pm for 1 hour").error)
    }

    @Test fun unchanged() {
        ok("Lunch noon-2pm Friday").let { assertEquals(LocalTime.NOON, it.time); assertEquals(120, it.durationMinutes) }
        ok("Holiday 20 Dec - 5 Jan").let { assertEquals("Holiday", it.title); assertEquals(LocalDate.of(2027, 1, 5), it.endDate) }
        ok("Trip next Friday to Sunday").let { assertEquals(day(10, 9), it.date); assertEquals(day(10, 11), it.endDate) }
        assertEquals(RepeatRule.onDays(setOf(MONDAY, WEDNESDAY)), ok("Gym every Mon and Wed 7pm until end of year").repeat)
        assertEquals("Stay overnight", ok("Stay overnight at Kim's Friday").title)
    }
}
