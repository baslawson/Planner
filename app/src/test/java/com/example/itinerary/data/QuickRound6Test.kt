package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// Parser round 6 (probe list qa/parser-probe/probe-round6.txt): phrasings Quick entry read wrongly or refused, with
// Australian wording.
class QuickRound6Test {
    private val today = LocalDate.of(2026, 9, 30) // a Wednesday
    private val now = LocalDateTime.of(2026, 9, 30, 10, 0)
    private val perth = ZoneId.of("Australia/Perth")
    private fun parse(text: String) = QuickEntry.parse(text, today, now = now, dayFirst = true, zone = perth)
    private fun ok(text: String) = parse(text).also { assertTrue("$text: ${it.error}", it.error == null || it.clarificationOnly) }
    private fun day(month: Int, day: Int) = LocalDate.of(2026, month, day)
    private val weekend = RepeatRule.onDays(setOf(SATURDAY, SUNDAY))

    @Test fun noLongerReadWrongly() {
        // The year after a holiday is not a time.
        ok("Picnic Boxing Day 2027").let { assertEquals("Picnic Boxing Day", it.title); assertEquals(LocalDate.of(2027, 12, 26), it.date); assertNull(it.time) }
        ok("Lunch Christmas Day 2026").let { assertEquals(day(12, 25), it.date) }
        // A range without am/pm lasts until the first end after the start.
        ok("Lunch 12:30-1:30 Thursday").let { assertEquals(LocalTime.of(12, 30), it.time); assertEquals(60, it.durationMinutes) }
        assertEquals(120, ok("Meeting 11:30-1:30 Friday").durationMinutes)
        assertEquals(90, ok("Class 9:00-10:30 Monday").durationMinutes)
        assertEquals(180, ok("Shift 22:00-01:00 Friday").durationMinutes) // 24-hour, overnight
        ok("Vet in 3 days' time").let { assertEquals("Vet", it.title); assertEquals(day(10, 3), it.date) }
        assertEquals(day(10, 14), ok("Review in two weeks' time").date)
        ok("Brekkie with Jo Sunday 9").let {
            assertEquals("Brekkie with Jo", it.title); assertEquals(day(10, 4), it.date); assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(21, 0)), it.timeChoices)
        }
        // Only a lone number right after the date: counts and prices stay in the title.
        ok("Dinner Friday 4 people").let { assertEquals("Dinner 4 people", it.title); assertTrue(it.timeChoices.isEmpty()) }
        ok("Gym tmw 6am").let { assertEquals("Gym", it.title); assertEquals(day(10, 1), it.date); assertEquals(LocalTime.of(6, 0), it.time) }
        ok("Meeting 2pm no reminder").let { assertEquals("Meeting", it.title); assertNull(it.reminderMinutes) }
        ok("Remind me to call mum tomorrow no reminder").let { assertEquals("Call mum", it.title); assertNull(it.reminderMinutes) }
        assertNotNull(parse("Meeting 2pm remind me 10 min before no reminder").error)
        assertTrue(parse("Stretch hourly").error!!.startsWith("Planner repeats at most once a day"))
        assertNotNull(parse("Take pills every 8 hours").error)
        assertNotNull(parse("Catch up nxt wk").error) // asks which day, like "next week"
        assertEquals(day(10, 6), ok("Haircut nxt Tues").date)
    }

    @Test fun timeZones() {
        ok("Call 3pm AEST").let { assertEquals("Call", it.title); assertEquals(LocalTime.of(13, 0), it.time) }
        ok("Webinar Friday 9am UTC").let { assertEquals(day(10, 2), it.date); assertEquals(LocalTime.of(17, 0), it.time) }
        // Across midnight the date moves too.
        ok("Call 11pm UTC").let { assertEquals(day(10, 1), it.date); assertEquals(LocalTime.of(7, 0), it.time); assertTrue(it.dateSpecified) }
        assertEquals(LocalTime.of(7, 30), ok("Stream 10am ACDT Monday").time)
        assertNotNull(parse("Call at 3 AEST").error) // am or pm first
        assertNotNull(parse("Standup every day 11pm UTC").error) // would move a repeat's day
        ok("UTC migration Friday").let { assertEquals("UTC migration", it.title) } // not after a time
    }

    @Test fun australianWording() {
        ok("Coffee this arvo").let { assertEquals("Coffee", it.title); assertEquals(today, it.date); assertNotNull(it.timePrompt) }
        ok("Pickup kids arvo").let { assertEquals("Pickup kids", it.title); assertEquals(today, it.date); assertTrue(it.dateSpecified) }
        ok("Footy tomorrow arvo 3pm").let { assertEquals("Footy", it.title); assertEquals(day(10, 1), it.date); assertEquals(LocalTime.of(15, 0), it.time) }
        assertEquals(LocalTime.of(15, 0), ok("Footy Saturday arvo 3").time)
        assertNotNull(parse("Footy tomorrow arvo 9am").error)
        ok("Walk every arvo").let { assertEquals(RepeatRule.DAILY, it.repeat); assertNotNull(it.timePrompt) }
        assertEquals(LocalDate.of(2027, 4, 25), ok("BBQ Anzac Day").date)
        assertEquals(LocalDate.of(2027, 1, 26), ok("Beach Australia Day").date)
        assertEquals(LocalDate.of(2027, 9, 5), ok("Lunch with dad Father's Day").date)
        assertEquals(LocalDate.of(2026, 9, 6), QuickEntry.parse("Lunch Father's Day", LocalDate.of(2026, 9, 1)).date)
        ok("Party NYE 8pm").let { assertEquals(day(12, 31), it.date); assertEquals(LocalTime.of(20, 0), it.time) }
    }

    @Test fun rangesStaysAndWeekdays() {
        ok("Camping Fri-Sun").let { assertEquals("Camping", it.title); assertEquals(day(10, 2), it.date); assertEquals(day(10, 4), it.endDate) }
        // With a clock time, a weekday range is a repeat on those days.
        ok("Work Mon-Fri 9am").let { assertEquals("Work", it.title); assertEquals(RepeatRule.WEEKDAYS, it.repeat); assertEquals(LocalTime.of(9, 0), it.time) }
        ok("Gym Monday to Friday 6am").let { assertEquals(RepeatRule.WEEKDAYS, it.repeat); assertNull(it.endDate) }
        assertEquals(RepeatRule.onDays(setOf(java.time.DayOfWeek.FRIDAY, SATURDAY, SUNDAY)), ok("Markets Fri-Sun 8am").repeat)
        // …but not with next, this or from.
        ok("Camping next Friday to Sunday 6pm").let { assertEquals(RepeatRule.NONE, it.repeat); assertEquals(day(10, 11), it.endDate) }
        ok("Holiday 3 nights from Friday").let { assertEquals("Holiday", it.title); assertEquals(day(10, 2), it.date); assertEquals(day(10, 5), it.endDate) }
        ok("Stay in Bali for 5 nights from 12 Dec").let { assertEquals("Stay in Bali", it.title); assertEquals(day(12, 12), it.date); assertEquals(day(12, 17), it.endDate) }
        assertEquals(day(10, 3), ok("Hotel Friday for 1 night").endDate)
        assertNotNull(parse("Trip 3-5 Oct for 2 nights").error)
    }

    @Test fun repeats() {
        ok("Market every weekend").let { assertEquals("Market", it.title); assertEquals(weekend, it.repeat); assertEquals(day(10, 3), it.date) }
        ok("Swim on weekends 7am").let { assertEquals("Swim", it.title); assertEquals(weekend, it.repeat); assertEquals(LocalTime.of(7, 0), it.time) }
        assertEquals(weekend, ok("Sleep in weekends").repeat)
        ok("Water plants every 3rd day").let { assertEquals("Water plants", it.title); assertEquals(RepeatRule.everyDays(3), it.repeat) }
        assertEquals(RepeatRule.FORTNIGHTLY, ok("Bins every second week").repeat)
        assertEquals(RepeatRule.everyMonths(3), ok("Review every third month").repeat)
        // Unchanged: this weekend is a date.
        ok("Mow lawn this weekend").let { assertEquals(RepeatRule.NONE, it.repeat); assertEquals(day(10, 3), it.date) }
    }

    @Test fun lengthsAndTimes() {
        ok("Flight 1.5h 7am Monday").let { assertEquals("Flight", it.title); assertEquals(90, it.durationMinutes); assertEquals(LocalTime.of(7, 0), it.time) }
        ok("Party tomorrow night 1am").let { assertEquals("Party", it.title); assertEquals(day(10, 2), it.date); assertEquals(LocalTime.of(1, 0), it.time) }
        ok("Deadline midnight tonight").let { assertEquals("Deadline", it.title); assertEquals(today, it.date); assertEquals(LocalTime.of(23, 59), it.time) }
        assertNotNull(parse("Party tomorrow night 9am").error)
        ok("Report EOD").let { assertEquals("Report", it.title); assertEquals(today, it.date); assertEquals(LocalTime.of(17, 0), it.time); assertTrue(it.dateSpecified) }
        ok("Send invoice EOD Friday").let { assertEquals("Send invoice", it.title); assertEquals(day(10, 2), it.date) }
        ok("Submit by COB tomorrow").let { assertEquals("Submit", it.title); assertEquals(day(10, 1), it.date); assertEquals(LocalTime.of(17, 0), it.time) }
        assertEquals("Report", ok("Report due EOD Friday").title)
        assertEquals(LocalTime.of(17, 0), ok("Reply by close of business").time)
        assertNotNull(parse("Report EOD 3pm").error)
    }

    @Test fun moreDates() {
        ok("Dentist 3 days from now").let { assertEquals("Dentist", it.title); assertEquals(day(10, 3), it.date) }
        assertEquals(LocalTime.of(10, 0), ok("Call now").time) // "now" on its own is unchanged
        ok("Friday this week drinks").let { assertEquals("Drinks", it.title); assertEquals(day(10, 2), it.date) }
        assertEquals(day(10, 2), ok("Drinks this week on Friday").date)
        ok("Meeting next month on the 5th").let { assertEquals("Meeting", it.title); assertEquals(day(10, 5), it.date) }
        assertEquals(day(10, 5), ok("Meeting the 5th next month").date)
        ok("Dinner October first").let { assertEquals("Dinner", it.title); assertEquals(day(10, 1), it.date) }
        ok("Party the first of October").let { assertEquals("Party", it.title); assertEquals(day(10, 1), it.date) }
        ok("Review the twenty-first").let { assertEquals("Review", it.title); assertEquals(day(10, 21), it.date) }
        assertEquals(day(10, 21), ok("Review the twenty first at 3pm").date)
        // Ordinary words stay words.
        ok("Watch the first episode").let { assertEquals("Watch the first episode", it.title); assertFalse(it.dateSpecified) }
        ok("Second hand shop Friday").let { assertEquals("Second hand shop", it.title) }
    }

    @Test fun eitherDate() {
        ok("Doctor next Thursday or Friday afternoon").let {
            assertEquals("Doctor", it.title); assertEquals(listOf(day(10, 8), day(10, 9)), it.dateChoices); assertEquals("Which date did you mean?", it.error)
        }
        assertEquals(listOf(day(10, 1), day(10, 2)), ok("Call Thursday or Friday").dateChoices)
        assertEquals(listOf(day(10, 3), day(10, 10)), ok("Party 3 Oct or 10 Oct").dateChoices)
    }

    @Test fun tasksAndReminders() {
        ok("todo buy milk").let { assertEquals("Buy milk", it.title); assertTrue(it.taskHint); assertNull(it.reminderMinutes) }
        ok("Task: pay rent Friday").let { assertEquals("Pay rent", it.title); assertTrue(it.taskHint); assertNull(it.reminderMinutes); assertEquals(day(10, 2), it.date) }
        assertTrue(ok("To-do: tidy garage").taskHint)
        // Only as a prefix, and "task" and "to do" need a colon.
        ok("Task force meeting Friday").let { assertEquals("Task force meeting", it.title); assertFalse(it.taskHint) }
        ok("Review my todo list").let { assertEquals("Review my todo list", it.title); assertFalse(it.taskHint) }
        ok("Flight Sat 6am remind me the night before").let { assertEquals("Flight", it.title); assertEquals(10 * 60, it.reminderMinutes) }
        assertEquals(25 * 60, ok("Show Sat 9pm remind me the evening before").reminderMinutes) // Friday 8pm
    }

    @Test fun unchanged() {
        ok("Camping Friday to Sunday").let { assertEquals(day(10, 2), it.date); assertEquals(day(10, 4), it.endDate) }
        ok("Tennis Sat and Sun 8am").let { assertEquals(weekend, it.repeat) }
        ok("Check oven in an hour").let { assertEquals(LocalTime.of(11, 0), it.time) }
        ok("Launch 10/03").let { assertEquals(LocalDate.of(2027, 3, 10), it.date) }
        ok("Remind me to call mum tomorrow").let { assertEquals(0, it.reminderMinutes); assertTrue(it.taskHint) }
    }
}
