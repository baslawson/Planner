package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// Quick entry items left after the bug hunts of 3 Oct 2026 (qa/bughunt-20261003*: Q-N9, Q5-4), as approved on 4 Oct.
class QuickFixesOct4Test {
    private val zone = ZoneId.of("Australia/Sydney")
    private val now = ZonedDateTime.of(2026, 10, 5, 14, 0, 0, 0, zone) // a Monday
    private val today = now.toLocalDate()
    private fun event(text: String, time: String? = null) = QuickInput(text, baseDate = today, timeOverride = time).suggestion(now)
    private fun task(text: String) = QuickInput(text, task = true, baseDate = today).suggestion(now)
    private fun oct(day: Int) = LocalDate.of(2026, 10, day)
    private fun at(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)
    private fun ok(text: String, time: String? = null) = event(text, time).also { assertNull("$text: ${it.quickProblem(false, now)}", it.quickProblem(false, now)) }

    // 1 (Q-N9): "until" a date, with no repeat and no clock time, is one entry over the days from today (or the date given).
    @Test fun untilADateIsOneEntryOverSeveralDays() {
        val range = ok("Holiday 5–9 Oct")
        ok("Holiday until Friday").let {
            assertEquals("Holiday", it.title); assertEquals(range.date, it.date); assertEquals(range.endDate, it.endDate)
            assertNull(it.time); assertTrue(it.dateSpecified)
        }
        ok("Trip until Sunday").let { assertEquals("Trip", it.title); assertEquals(oct(5), it.date); assertEquals(oct(11), it.endDate) }
        ok("Away until the 20th").let { assertEquals("Away", it.title); assertEquals(oct(5), it.date); assertEquals(oct(20), it.endDate) }
        ok("Away until 20 Oct").let { assertEquals(oct(5), it.date); assertEquals(oct(20), it.endDate) }
        // A start date given: from then.
        ok("Away tomorrow until Friday").let { assertEquals("Away", it.title); assertEquals(oct(6), it.date); assertEquals(oct(9), it.endDate) }
        // Until today is just today.
        ok("Home until today").let { assertEquals(oct(5), it.date); assertNull(it.endDate) }
        // As for any multi-day entry, a task asks for an event.
        assertEquals("Tasks use one due date. Choose Event for several days.", task("Holiday until Friday").quickProblem(true, now))
    }

    @Test fun untilKeepsItsOtherMeanings() {
        // A clock time as the end still asks to be finished.
        assertEquals("Finish the date, time or duration, or put literal title text in quotes.", event("Meeting until 3pm").error)
        // A repeat's end.
        ok("Gym every Monday until 26 October").let { assertEquals(4, it.repeatCount); assertNull(it.endDate) }
        // With a clock time the date is the day of it, as before: a multi-day entry has no time.
        ok("Work until Friday 5pm").let { assertEquals(oct(9), it.date); assertEquals(at(17), it.time); assertNull(it.endDate) }
        // A date range.
        ok("Away from Friday to Sunday").let { assertEquals(oct(9), it.date); assertEquals(oct(11), it.endDate) }
        // An end before the start.
        assertNotNull(event("Away until yesterday").error)
    }

    // 2 (Q-N9): "until midnight" ends the event at midnight; its start is asked, as for "tonight".
    @Test fun untilMidnightAsksForTheStart() {
        event("Party until midnight").let {
            assertEquals("Party", it.title); assertEquals(today, it.date); assertNull(it.time); assertTrue(it.ambiguousTime)
            assertTrue(it.clarificationOnly); assertEquals(it.timePrompt, it.quickProblem(false, now)); assertNotNull(it.timePrompt)
        }
        ok("Party until midnight", "21:00").let { assertEquals(today, it.date); assertEquals(at(21), it.time); assertEquals(180, it.durationMinutes) }
        ok("Party Friday until midnight", "20:00").let { assertEquals(oct(9), it.date); assertEquals(at(20), it.time); assertEquals(240, it.durationMinutes) }
        event("Party tonight until midnight").let { assertEquals("Party", it.title); assertTrue(it.ambiguousTime); assertNull(it.time) }
        ok("Party tonight until midnight", "22:30").let { assertEquals(at(22, 30), it.time); assertEquals(90, it.durationMinutes) }
        ok("Party 9pm until midnight").let { assertEquals("Party", it.title); assertEquals(at(21), it.time); assertEquals(180, it.durationMinutes) }
        ok("Party 9pm till midnight").let { assertEquals(at(21), it.time); assertEquals(180, it.durationMinutes) }
        assertEquals("Tasks use due dates. Choose Event for a time or duration.", task("Bins until midnight").quickProblem(true, now))
    }

    // 3 (Q5-4): after a part of the day, an hour followed by a name is that hour, when it falls in that part of the day.
    @Test fun anHourAfterAPartOfTheDayBeforeAName() {
        ok("Dinner Friday night 8 Luigi's").let { assertEquals("Dinner Luigi's", it.title); assertEquals(oct(9), it.date); assertEquals(at(20), it.time) }
        ok("Dinner tonight 8 Sam").let { assertEquals("Dinner Sam", it.title); assertEquals(today, it.date); assertEquals(at(20), it.time) }
        ok("Lunch tomorrow afternoon 2 Sam's").let { assertEquals("Lunch Sam's", it.title); assertEquals(oct(6), it.date); assertEquals(at(14), it.time) }
        // "2nite 4 Sam" is text speak for "for Sam": 4 isn't an hour of the night (only 4am after it), so it stays (Q5).
        event("Pub at 2nite 4 Sam").let { assertEquals("Pub 4 Sam", it.title); assertNull(it.time); assertTrue(it.ambiguousTime) }
        event("Pub tonight 4 Sam").let { assertEquals("Pub 4 Sam", it.title); assertNull(it.time) }
        // A number before an ordinary word is a count, not an hour.
        event("Dinner Friday night 8 people").let { assertEquals("Dinner 8 people", it.title); assertNull(it.time); assertTrue(it.ambiguousTime) }
        // Unchanged: an hour at the end, or before schedule words.
        ok("Dinner Friday night 8").let { assertEquals("Dinner", it.title); assertEquals(at(20), it.time) }
    }

    // 4: a party size at the end after a booking or meal word is title text, not an unfinished length.
    @Test fun aPartySizeAtTheEnd() {
        event("Dinner Tonight For Two").let {
            val tonight = event("Dinner Tonight")
            assertEquals("Dinner For Two", it.title); assertEquals(today, it.date); assertNull(it.time)
            assertEquals(tonight.timePrompt, it.timePrompt); assertEquals(tonight.quickProblem(false, now), it.quickProblem(false, now))
        }
        event("Table for two tonight").let { assertEquals("Table for two", it.title); assertEquals(today, it.date); assertTrue(it.ambiguousTime) }
        ok("Booking for 4 tomorrow 7pm").let { assertEquals("Booking for 4", it.title); assertEquals(oct(6), it.date); assertEquals(at(19), it.time) }
        ok("Dinner Friday 7pm for two").let { assertEquals("Dinner for two", it.title); assertEquals(oct(9), it.date); assertEquals(at(19), it.time) }
        ok("Party Saturday for 20").let { assertEquals("Party for 20", it.title); assertEquals(oct(10), it.date) }
        // Without a booking word, a number at the end after "for" may still be a length being typed.
        assertNotNull(event("Study for two").error)
        assertNotNull(event("Study tomorrow for 30").error)
    }
}
