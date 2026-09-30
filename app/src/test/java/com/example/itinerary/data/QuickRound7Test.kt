package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// Parser round 7 (probe list qa/parser-probe/probe-round7.txt): several times a day, "all afternoon", bare hour ranges
// such as 9-5, and city time zones.
class QuickRound7Test {
    private val today = LocalDate.of(2026, 9, 30) // a Wednesday
    private val now = LocalDateTime.of(2026, 9, 30, 10, 0)
    private val perth = ZoneId.of("Australia/Perth")
    private fun parse(text: String) = QuickEntry.parse(text, today, now = now, dayFirst = true, zone = perth)
    private fun ok(text: String) = parse(text).also { assertTrue("$text: ${it.error}", it.error == null || it.clarificationOnly) }
    private fun refused(text: String) = parse(text).also { assertNotNull(text, it.error); assertFalse(text, it.clarificationOnly) }.error!!
    private fun day(month: Int, day: Int) = LocalDate.of(2026, month, day)
    private fun at(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)

    @Test fun severalTimesADay() {
        ok("Meds every day at 8am and 8pm").let {
            assertEquals("Meds", it.title); assertEquals(RepeatRule.DAILY, it.repeat)
            assertEquals(at(8), it.time); assertEquals(listOf(at(20)), it.extraTimes)
        }
        ok("Tablets 8am, 2pm and 8pm every day").let { assertEquals("Tablets", it.title); assertEquals(at(8), it.time); assertEquals(listOf(at(14), at(20)), it.extraTimes) }
        ok("Feed the cat 7am & 5pm daily").let { assertEquals("Feed the cat", it.title); assertEquals(listOf(at(17)), it.extraTimes) }
        ok("Walk dog 6:30am and 5:30pm every day").let { assertEquals(at(6, 30), it.time); assertEquals(listOf(at(17, 30)), it.extraTimes) }
        ok("Stretch 07:00 and 19:00 weekdays").let { assertEquals(RepeatRule.WEEKDAYS, it.repeat); assertEquals(listOf(at(19)), it.extraTimes) }
        // Written in any order, saved earliest first.
        ok("Meds 8pm and 8am daily").let { assertEquals(at(8), it.time); assertEquals(listOf(at(20)), it.extraTimes) }
        ok("Meds at 8am and at 8pm daily").let { assertEquals("Meds", it.title); assertEquals(listOf(at(20)), it.extraTimes) }
        // Without a repeat: that day, at each time.
        ok("Pills tomorrow 9am and 9pm").let { assertEquals(day(10, 1), it.date); assertEquals(RepeatRule.NONE, it.repeat); assertEquals(listOf(at(21)), it.extraTimes) }
        ok("Call mum 10am and 4pm Saturday").let { assertEquals("Call mum", it.title); assertEquals(day(10, 3), it.date) }
        // A reminder counted back from each event.
        ok("Meds 8am and 8pm remind me 10 min before").let { assertEquals(10, it.reminderMinutes); assertEquals(listOf(at(20)), it.extraTimes) }
    }

    @Test fun timesADayNeedsTheTimes() {
        ok("Insulin twice a day at 7am and 7pm").let {
            assertEquals("Insulin", it.title); assertEquals(RepeatRule.DAILY, it.repeat); assertEquals(at(7), it.time); assertEquals(listOf(at(19)), it.extraTimes)
        }
        ok("Vitamins three times a day at 8am, 1pm and 6pm").let { assertEquals(listOf(at(13), at(18)), it.extraTimes) }
        assertTrue(refused("Meds twice a day").startsWith("Give both times for ‘twice a day’"))
        assertTrue(refused("Meds twice a day at 8am").startsWith("Give both times"))
        assertTrue(refused("Vitamins three times a day at 8am and 6pm").startsWith("Give all three times"))
        // Other counts per period are still refused as before.
        assertTrue(refused("Swim twice a week").startsWith("Planner can't repeat ‘twice a week’"))
    }

    @Test fun severalTimesRefusedWhereUnclear() {
        assertTrue(refused("Check oven at 8 and at 8pm daily").startsWith("Add am or pm to each time"))
        assertTrue(refused("Meds 8am and 8am daily").startsWith("Use different times"))
        assertTrue(refused("Meds 8am and 8pm remind me at 7am").startsWith("With more than one time"))
        // Words between the times make them separate entries, as before.
        assertEquals("Use one start time or one time range.", refused("Lunch 12pm and dinner 7pm"))
        assertTrue(refused("Meds 8am and 8pm all day").isNotEmpty())
        // A single time is unchanged.
        assertTrue(ok("Meds 8am daily").extraTimes.isEmpty())
    }

    @Test fun eachTimeIsItsOwnEvent() {
        val s = ok("Meds every day at 8am and 8pm")
        val parts = s.eachTime()
        assertEquals(listOf(at(8), at(20)), parts.map { it.time })
        assertTrue(parts.all { it.extraTimes.isEmpty() && it.repeat == RepeatRule.DAILY && it.title == "Meds" })
        assertEquals(listOf("tok", "tok-1", "tok-2"), (0..2).map { quickToken("tok", it) })
        ok("Meds 8am daily").let { single -> assertEquals(listOf(single), single.eachTime()) }
        // Choosing a time by hand leaves one time.
        assertTrue(s.corrected(null, "09:00").let { it.time == at(9) && it.extraTimes.isEmpty() })
        assertEquals(listOf(at(20)), s.corrected("2026-10-02", null).extraTimes)
    }

    @Test fun severalTimesCheckedForOverlaps() {
        val s = ok("Meds 8am and 8:30am for 1 hour tomorrow")
        val candidates = s.eachTime().mapIndexed { i, part -> QuickCandidate(quickToken("t", i), part, false) }
        val found = quickConflicts(candidates, emptyList(), emptyList())
        assertEquals(1, found.size); assertTrue(found.single(), "overlaps ‘Meds’" in found.single())
    }

    @Test fun allPartOfTheDay() {
        ok("Study all afternoon Saturday").let { assertEquals("Study", it.title); assertEquals(day(10, 3), it.date); assertEquals(at(13), it.time); assertEquals(240, it.durationMinutes) }
        ok("Gardening all morning Sunday").let { assertEquals(at(9), it.time); assertEquals(180, it.durationMinutes) }
        ok("Party all evening Friday").let { assertEquals(at(18), it.time); assertEquals(180, it.durationMinutes) }
        ok("Study all arvo tomorrow").let { assertEquals("Study", it.title); assertEquals(day(10, 1), it.date); assertEquals(at(13), it.time) }
        ok("Beach all the afternoon Sunday").let { assertEquals("Beach", it.title) }
        ok("Study all afternoon").let { assertEquals(at(13), it.time); assertFalse(it.dateSpecified) }
        assertEquals("Use one time: ‘all afternoon’ or a clock time.", refused("Study all afternoon Saturday 2pm"))
        assertTrue(refused("Study all afternoon for 2 hours").startsWith("Use ‘all afternoon’ or a duration"))
        // "all night" and "all day" are unchanged: the first stays in the title, the second is an all-day event.
        ok("Movie marathon all night Friday").let { assertEquals("Movie marathon all night", it.title); assertNull(it.time) }
        ok("Cleaning all day Saturday").let { assertEquals("Cleaning", it.title); assertNull(it.time) }
    }

    @Test fun bareHourRanges() {
        ok("Work 9-5 weekdays").let { assertEquals("Work", it.title); assertEquals(RepeatRule.WEEKDAYS, it.repeat); assertEquals(at(9), it.time); assertEquals(480, it.durationMinutes) }
        ok("Work 9-5 Mon-Fri").let { assertEquals(RepeatRule.WEEKDAYS, it.repeat); assertEquals(480, it.durationMinutes) }
        ok("Work Monday 9-5").let { assertEquals(day(10, 5), it.date); assertEquals(at(9), it.time); assertNull(it.error) }
        ok("Work tomorrow 9-5").let { assertEquals(day(10, 1), it.date); assertEquals(at(9), it.time) }
        ok("Shift 10-2 Saturday").let { assertEquals(at(10), it.time); assertEquals(240, it.durationMinutes); assertNull(it.error) }
        ok("Shift 10 till 2 Saturday").let { assertEquals(at(10), it.time); assertNull(it.error) }
        ok("Work 9 to 5 tomorrow").let { assertEquals(at(9), it.time); assertEquals(480, it.durationMinutes); assertNull(it.error) }
        // An end after the start, a range from 12, or night work still ask morning or afternoon.
        ok("Class 2-4 Thursday").let { assertEquals(day(10, 1), it.date); assertEquals(listOf(at(2), at(14)), it.timeChoices) }
        ok("Lunch 12-1 Friday").let { assertEquals(listOf(at(0), at(12)), it.timeChoices) }
        ok("Night shift 10-6 Friday").let { assertEquals(listOf(at(10), at(22)), it.timeChoices) }
        // With no day or repeat, 9-5 is still a date.
        ok("Party 9-5").let { assertEquals("Party", it.title); assertEquals(LocalDate.of(2027, 5, 9), it.date); assertNull(it.time) }
    }

    @Test fun placeTimeZones() {
        // The phone is in Perth (UTC+8, no daylight saving). Sydney is UTC+10 until 4 October 2026, then UTC+11.
        ok("Webinar 9am Sydney time").let { assertEquals("Webinar", it.title); assertEquals(at(7), it.time) }
        ok("Webinar 8pm Sydney time every Tuesday").let { assertEquals(day(10, 6), it.date); assertEquals(at(17), it.time) }
        ok("Call 3pm London time tomorrow").let { assertEquals("Call", it.title); assertEquals(at(22), it.time) }
        ok("Meeting 9am New York time Friday").let { assertEquals(at(21), it.time) }
        ok("Chat 6pm UK time Thursday").let { assertEquals(day(10, 2), it.date); assertEquals(at(1), it.time) }
        ok("Zoom 5pm Adelaide time Wednesday").let { assertEquals(at(15, 30), it.time) }
        ok("Call Auckland 8am NZ time Monday").let { assertEquals("Call Auckland", it.title); assertEquals(at(3), it.time) }
        ok("Standup 9am Bali time weekdays").let { assertEquals("Standup", it.title); assertEquals(at(9), it.time) }
        ok("Meds 8am and 8pm Sydney time daily").let { assertEquals(at(6), it.time); assertEquals(listOf(at(18)), it.extraTimes) }
        // Only right after a time; otherwise the words are the title's.
        ok("Webinar Sydney time").let { assertEquals("Webinar Sydney time", it.title) }
        ok("Flight to London time 3pm Friday").let { assertEquals("Flight to London time", it.title); assertEquals(at(15), it.time) }
        assertTrue(refused("Call at 8 Sydney time Friday").startsWith("Add am or pm to the time to use Sydney time"))
        // A repeat whose day would move in the phone's zone is refused, as for AEST.
        assertTrue(refused("Call 11pm LA time every Tuesday").startsWith("In your time zone that is"))
        // The abbreviations still work.
        ok("Call 3pm AEST Friday").let { assertEquals(at(13), it.time) }
    }
}
