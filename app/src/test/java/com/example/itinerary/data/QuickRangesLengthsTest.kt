package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// 1 Oct 2026 review: time ranges without am/pm, backwards hour and day ranges, lengths such as "1 hour 30".
class QuickRangesLengthsTest {
    private val today = LocalDate.of(2026, 10, 1) // a Thursday
    private fun parse(text: String) = QuickEntry.parse(text, today, now = LocalDateTime.of(2026, 10, 1, 10, 7), dayFirst = true, zone = ZoneId.of("Australia/Sydney"))
    private fun ok(text: String) = parse(text).also { assertTrue("$text: ${it.error}", it.error == null || it.clarificationOnly) }
    private fun day(month: Int, day: Int, year: Int = 2026) = LocalDate.of(year, month, day)
    private fun at(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)
    private val morningOrAfternoon = "Morning or afternoon? Choose a time below, or type am or pm."
    private fun asks(text: String, first: LocalTime, minutes: Int?) = parse(text).let {
        assertEquals(text, morningOrAfternoon, it.error); assertTrue(text, it.clarificationOnly)
        assertNull(text, it.time); assertEquals(text, listOf(first, first.plusHours(12)), it.timeChoices); assertEquals(text, minutes, it.durationMinutes)
    }

    @Test fun aColonOrDotRangeWithoutAmPmAsks() {
        asks("Meeting 1:00-2:00 tomorrow", at(1), 60)
        asks("Dinner 7:30-9:30 Friday", at(7, 30), 120)
        asks("Dinner 7.30-9.30 Friday", at(7, 30), 120)
        asks("Meeting 9:00-10:30 tomorrow", at(9), 90)
        asks("Lunch 12:30-1:30 tomorrow", at(0, 30), 60)
        // Only one reading: an hour of 0 or above 12, or a leading zero, as for a single time.
        ok("Meeting 13:00-14:00 tomorrow").let { assertEquals(at(13), it.time); assertEquals(60, it.durationMinutes); assertNull(it.error) }
        ok("Shift 9:00-17:30 Monday").let { assertEquals(at(9), it.time); assertEquals(510, it.durationMinutes); assertNull(it.error) }
        ok("Shift 08:00-09:30 Monday").let { assertEquals(at(8), it.time); assertEquals(90, it.durationMinutes); assertNull(it.error) }
        ok("Call 0:30-1:30 tomorrow").let { assertEquals(at(0, 30), it.time); assertNull(it.error) }
        ok("Shift 1000-1200 Monday").let { assertEquals(at(10), it.time); assertEquals(120, it.durationMinutes); assertNull(it.error) }
        ok("Dinner 7:30-9:30pm Friday").let { assertEquals(at(19, 30), it.time); assertNull(it.error) }
        // The working day still needs no question.
        ok("Work 9:00-5:00 Monday").let { assertEquals(at(9), it.time); assertEquals(480, it.durationMinutes); assertNull(it.error) }
        ok("Work 8:30-5 Monday").let { assertEquals(at(8, 30), it.time); assertEquals(510, it.durationMinutes); assertNull(it.error) }
    }

    @Test fun aBackwardsHourRangeIsTheWorkingDayOnlyFromMorningToAfternoon() {
        asks("Party Saturday 8-1", at(8), 300)
        asks("Match 2-1 Saturday", at(2), 660)
        asks("Score 3-1 Saturday", at(3), 600)
        ok("Work 9-5 Monday").let { assertEquals(at(9), it.time); assertEquals(480, it.durationMinutes); assertNull(it.error) }
        ok("Shift Saturday 10-2").let { assertEquals(at(10), it.time); assertEquals(240, it.durationMinutes); assertNull(it.error) }
        ok("Shift Saturday 6-2").let { assertEquals(at(6), it.time); assertEquals(480, it.durationMinutes); assertNull(it.error) }
        ok("Work 9-5 weekdays").let { assertEquals(at(9), it.time); assertNull(it.error) }
        ok("Brunch 11-1 Sunday").let { assertEquals(at(11), it.time); assertEquals(120, it.durationMinutes); assertNull(it.error) }
    }

    @Test fun hoursWithBareMinutesAreALength() {
        ok("Study for 1 hour 30 tomorrow").let { assertEquals("Study", it.title); assertEquals(90, it.durationMinutes); assertNull(it.time) }
        ok("Study for 2 h 45 tomorrow").let { assertEquals("Study", it.title); assertEquals(165, it.durationMinutes) }
        ok("Study for 1h30 tomorrow").let { assertEquals("Study", it.title); assertEquals(90, it.durationMinutes); assertNull(it.time) }
        ok("Study for 1 hour 30 minutes tomorrow").let { assertEquals("Study", it.title); assertEquals(90, it.durationMinutes) }
        ok("Call in 1h 30").let { assertEquals("Call", it.title); assertEquals(day(10, 1), it.date); assertEquals(at(11, 40), it.time) }
        ok("Call in 1h30").let { assertEquals("Call", it.title); assertEquals(at(11, 40), it.time) }
        ok("Call in 1 hour 30").let { assertEquals("Call", it.title); assertEquals(at(11, 40), it.time) }
        // A clock time after a length is still a time.
        ok("Study for 2 hours 10am tomorrow").let { assertEquals(120, it.durationMinutes); assertEquals(at(10), it.time) }
    }

    @Test fun aBackwardsDayRangeInOneMonthIsRefused() {
        for (text in listOf("Trip 28-3 Jan", "Trip Dec 28-3", "Trip 30-2 Nov"))
            assertEquals(text, "End the date range after it starts.", parse(text).error)
        ok("Trip 3-7 Nov").let { assertEquals(day(11, 3), it.date); assertEquals(day(11, 7), it.endDate) }
        ok("Trip 28 Dec - 3 Jan").let { assertEquals(day(12, 28), it.date); assertEquals(day(1, 3, 2027), it.endDate) }
    }

    @Test fun aYearOnTheEndOnlyCanStartTheYearBefore() {
        ok("Trip 28 Dec - 3 Jan 2027").let { assertEquals("Trip", it.title); assertEquals(day(12, 28), it.date); assertEquals(day(1, 3, 2027), it.endDate) }
        ok("Holiday Dec 20 - Jan 5, 2027").let { assertEquals("Holiday", it.title); assertEquals(day(12, 20), it.date); assertEquals(day(1, 5, 2027), it.endDate) }
        ok("Trip 3 Jan - 5 Jan 2027").let { assertEquals(day(1, 3, 2027), it.date); assertEquals(day(1, 5, 2027), it.endDate) }
    }

    @Test fun aRangeUnderWayStaysThisYear() {
        ok("Trip 30 Sep to 4 Oct").let { assertEquals(day(9, 30), it.date); assertEquals(day(10, 4), it.endDate) }
        ok("Trip Sep 30 - Oct 4").let { assertEquals(day(9, 30), it.date); assertEquals(day(10, 4), it.endDate) }
        ok("Trip 30 Sep - 1 Oct").let { assertEquals(day(9, 30), it.date); assertEquals(day(10, 1), it.endDate) }
        // Over before today: next year's.
        ok("Trip 20 Sep - 25 Sep").let { assertEquals(day(9, 20, 2027), it.date); assertEquals(day(9, 25, 2027), it.endDate) }
    }

    @Test fun moreWaysOfWritingALength() {
        mapOf("for 1.25 hours" to 75, "for 1.45 hours" to 87, "for 1hr30" to 90, "for a half hour" to 30,
            "for 2 and a half hours" to 150, "for one and a half hours" to 90, "for .5 hours" to 30, "for half an hour" to 30)
            .forEach { (length, minutes) ->
                ok("Study $length tomorrow").let { assertEquals(length, "Study", it.title); assertEquals(length, minutes, it.durationMinutes) }
            }
        // Not understood: refused rather than left in the title.
        assertEquals("Finish the date, time or duration, or put literal title text in quotes.", parse("Study for 1,5 hours tomorrow").error)
        // Not a length at all: title text, as before.
        ok("Hike for a few hours Saturday").let { assertEquals("Hike for a few hours", it.title) }
    }

    @Test fun aBareHourAfterARepeatAsksMorningOrAfternoon() {
        parse("Gym every Monday 6").let {
            assertEquals(morningOrAfternoon, it.error); assertEquals("Gym", it.title); assertEquals(RepeatRule.WEEKLY, it.repeat)
            assertEquals(day(10, 5), it.date); assertEquals(listOf(at(6), at(18)), it.timeChoices)
        }
        // After "daily" a number may be a count: title text, as before.
        ok("Pills daily 2").let { assertEquals("Pills 2", it.title); assertTrue(it.timeChoices.isEmpty()) }
    }

    @Test fun aTwentyFourHourHourAfterAtNeedsNoQuestion() {
        ok("Call tomorrow at 13").let { assertEquals(at(13), it.time); assertNull(it.error); assertFalse(it.ambiguousTime) }
        ok("Call tomorrow at 0").let { assertEquals(at(0), it.time); assertNull(it.error); assertFalse(it.ambiguousTime) }
        asks("Call tomorrow at 7", at(7), null)
    }

    @Test fun aTimeAfterMidnightIsTheNextDay() {
        ok("Meds 8pm and 2am tomorrow").let {
            assertEquals(day(10, 2), it.date); assertEquals(at(20), it.time); assertEquals(listOf(at(2)), it.extraTimes)
            assertEquals(listOf(day(10, 2) to at(20), day(10, 3) to at(2)), it.eachTime().map { e -> e.date to e.time })
        }
        // Converted from another time zone, each keeps its own day.
        ok("Meds tomorrow 8pm and 2am AWST").let {
            assertEquals(listOf(day(10, 2) to at(22), day(10, 3) to at(4)), it.eachTime().map { e -> e.date to e.time })
        }
        // In order within the day: unchanged.
        ok("Meds 8am and 8pm tomorrow").let {
            assertEquals(listOf(day(10, 2) to at(8), day(10, 2) to at(20)), it.eachTime().map { e -> e.date to e.time })
        }
        ok("Meds 8pm and 8am tomorrow").let {
            assertEquals(listOf(day(10, 2) to at(8), day(10, 2) to at(20)), it.eachTime().map { e -> e.date to e.time })
        }
    }

    @Test fun aTrainOrRowWithASmallNumberCanStartARange() {
        ok("Train 6 - 7pm").let { assertEquals("Train", it.title); assertEquals(at(18), it.time); assertEquals(60, it.durationMinutes) }
        ok("Row 6 - 7pm Friday").let { assertEquals("Row", it.title); assertEquals(at(18), it.time); assertEquals(60, it.durationMinutes) }
        // Still labels: a number that can't be an hour, and the other label words.
        ok("Train 96 till 5pm").let { assertEquals(at(17), it.time); assertNull(it.durationMinutes) }
        ok("Bus 2 - 3pm").let { assertEquals("Bus 2", it.title); assertEquals(at(15), it.time); assertNull(it.durationMinutes) }
        ok("Ferry 12 - 10am Sunday").let { assertEquals("Ferry 12", it.title); assertEquals(at(10), it.time) }
        ok("Gate 5 till 6pm").let { assertEquals(at(18), it.time); assertNull(it.durationMinutes) }
        ok("Train 06 - 7pm").let { assertEquals("Train 06", it.title); assertEquals(at(19), it.time) }
        ok("Train no. 7 - 8am Monday").let { assertEquals("Train no. 7", it.title); assertEquals(at(8), it.time) }
    }
}
