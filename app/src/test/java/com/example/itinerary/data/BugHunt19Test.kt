package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// Bug hunt 19 (7 Oct 2026): the Planner fixes that can be checked without a phone.
class BugHunt19Test {
    private val today = LocalDate.of(2026, 10, 7)
    private val zone = ZoneId.of("Australia/Perth")
    private fun parse(text: String) = QuickEntry.parse(text, today, now = LocalDateTime.of(2026, 10, 7, 10, 0), dayFirst = true, zone = zone)

    // P4: Quote puts "> " in front of the line as it is, and takes it off again; it was taken for a bullet.
    @Test fun quoteHoldsTheLineAndTogglesOff() {
        assertEquals("> - milk", Markdown.prefixLines("- milk", 0, 0, "> ").text)
        assertEquals("> - [ ] milk", Markdown.prefixLines("- [ ] milk", 0, 0, "> ").text)
        assertEquals("> # Title", Markdown.prefixLines("# Title", 0, 0, "> ").text)
        assertEquals("- milk", Markdown.prefixLines("> - milk", 0, 0, "> ").text)
        assertEquals("a\nb", Markdown.prefixLines("> a\n>b", 0, 7, "> ").text)
        assertEquals("> a\n> > b", Markdown.prefixLines("a\n> b", 0, 5, "> ").text)
        // The cursor stays on the same text.
        assertEquals(4, Markdown.prefixLines("milk", 2, 2, "> ").start)
    }

    // P5: an end that gives more than 365 is refused, not cut short at 365.
    @Test fun aRepeatEndPastTheLimitIsRefused() {
        assertNotNull(parse("Gym daily until 31/12/2027").error)
        assertNotNull(parse("Gym daily for 2 years").error)
        assertEquals(365, occurrencesWhile(RepeatRule.DAILY, today, 0) { it <= today.plusDays(364) })
        assertEquals(366, occurrencesWhile(RepeatRule.DAILY, today, 0) { it <= today.plusDays(365) })
        assertEquals(12, occurrencesWhile(RepeatRule.MONTHLY, LocalDate.of(2026, 1, 31), 0) { it.year == 2026 })
        // Within the limit as before.
        assertEquals(53, parse("Bins weekly until 6/10/2027").repeatCount)
    }

    // P6: a monthly or yearly series made into a task keeps its month day, so the 31st doesn't drift to the 28th.
    @Test fun aSeriesMadeIntoATaskKeepsItsMonthDay() {
        val occurrence = ItineraryItem(tripId = 0, date = LocalDate.of(2027, 2, 28), startTime = null, title = "Rent", repeatRule = "MONTHLY")
        val task = TaskEventConversion.toTask(occurrence, emptyList(), emptyList(), wholeSeries = true, zone = zone,
            seriesStart = LocalDate.of(2027, 1, 31)).result
        assertEquals(31, task.repeatAnchorDay)
        assertEquals(LocalDate.of(2027, 3, 31), task.nextOccurrence(LocalDate.of(2027, 2, 28), zone)!!.dueDate)
        assertEquals(29, TaskEventConversion.monthDay("YEARLY", LocalDate.of(2028, 2, 29)))
        assertEquals(0, TaskEventConversion.monthDay("WEEKLY", LocalDate.of(2027, 1, 31)))
        // Just this occurrence: no repeat, no month day.
        assertEquals(0, TaskEventConversion.toTask(occurrence, emptyList(), emptyList(), wholeSeries = false, zone = zone,
            seriesStart = LocalDate.of(2027, 1, 31)).result.repeatAnchorDay)
    }

    // "Christmas Eve at midnight" is the end of Christmas Eve, as "Friday midnight" is the end of Friday.
    @Test fun midnightOnAHolidayIsTheEndOfThatDay() {
        parse("Countdown Christmas Eve at midnight").let {
            assertNull(it.error); assertEquals(LocalDate.of(2026, 12, 25), it.date); assertEquals(LocalTime.MIDNIGHT, it.time)
        }
    }

    // An all-day series whose UNTIL is a UTC date-time keeps its last day west of UTC too.
    @Test fun anAllDaySeriesKeepsItsLastDayWestOfUtc() {
        val start = LocalDate.of(2026, 12, 28)
        val newYork = ZoneId.of("America/New_York")
        val dates = IcsRepeat.parse("FREQ=DAILY;UNTIL=20261231T000000Z", newYork, start, allDay = true)!!
            .dates(start, LocalTime.MIDNIGHT, LocalDate.of(2027, 2, 1), allDay = true)
        assertEquals(LocalDate.of(2026, 12, 31), dates.last())
    }
}
