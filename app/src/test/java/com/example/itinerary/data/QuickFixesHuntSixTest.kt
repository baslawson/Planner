package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// Bug hunt #6 (4 Oct 2026): Quick entry items Q6-1, Q6-4, Q6-5, Q6-6, Q6-9, Q6-10, Q6-13.
class QuickFixesHuntSixTest {
    private val today = LocalDate.of(2026, 10, 4) // a Sunday
    private val now = ZonedDateTime.of(today, LocalTime.of(10, 0), ZoneId.of("Australia/Perth"))
    private fun parse(text: String) = QuickEntry.parse(text, today)

    // Q6-1: a sentence's full stop is not an unfinished number after for/at.
    @Test fun aFullStopAfterTheWhenKeepsIt() {
        parse("Your appointment is confirmed for Tuesday 13 October at 2pm.").let {
            assertNull(it.error); assertEquals(LocalDate.of(2026, 10, 13), it.date); assertEquals(LocalTime.of(14, 0), it.time)
            assertEquals("Your appointment is confirmed", it.title)
        }
        parse("Your table is booked for Saturday at 7pm.").let {
            assertNull(it.error); assertEquals(LocalDate.of(2026, 10, 10), it.date); assertEquals(LocalTime.of(19, 0), it.time)
        }
        // A bare hour before a full stop is a time to place, never silently title text.
        parse("Pick up at 5. Bring keys").let {
            assertTrue(it.ambiguousTime); assertEquals(listOf(LocalTime.of(5, 0), LocalTime.of(17, 0)), it.timeChoices)
            assertEquals("Pick up. Bring keys", it.title)
        }
        assertEquals(listOf(LocalTime.of(7, 0), LocalTime.of(19, 0)), parse("Dinner at 7.").timeChoices)
        // Still unfinished: a number being typed.
        assertNotNull(parse("Study for 30").error)
    }

    // Q6-5: am/pm after the hour that follows tonight is that hour's.
    @Test fun amOrPmAfterTonightsHour() {
        parse("Dinner tonight 8 pm").let { assertEquals("Dinner", it.title); assertEquals(LocalTime.of(20, 0), it.time) }
        parse("Dinner tonight 8 PM").let { assertEquals("Dinner", it.title); assertEquals(LocalTime.of(20, 0), it.time) }
        parse("Dinner tonight 8 AM").let { assertNull(it.time); assertEquals("08:00 isn't in the night. Correct the time, or remove ‘night’.", it.error) }
    }

    // Q6-4: a holiday name in the title doesn't replace a written range or start.
    @Test fun aHolidayNameLeavesAWrittenRangeAlone() {
        parse("Halloween party Fri-Sun").let {
            assertEquals("Halloween party", it.title); assertEquals(LocalDate.of(2026, 10, 9), it.date); assertEquals(LocalDate.of(2026, 10, 11), it.endDate)
        }
        parse("Christmas lunch 24-26 Dec").let { assertEquals(LocalDate.of(2026, 12, 24), it.date); assertEquals(LocalDate.of(2026, 12, 26), it.endDate) }
        parse("Christmas dinner every Monday starting 7 Dec").let { assertNull(it.error); assertEquals(LocalDate.of(2026, 12, 7), it.date) }
        // An end before the start is said plainly rather than failing on save.
        assertEquals("The last day is before the first. Check the dates.",
            QuickEntrySuggestion("Trip", today, null, endDate = today.minusDays(1)).quickProblem(false, now))
    }

    // Q6-6: a count with a capital is still a count.
    @Test fun aCapitalisedCountIsNoName() {
        parse("Dinner tonight 6 Guests").let { assertNull(it.time); assertEquals("Dinner 6 Guests", it.title) }
        parse("Dinner Friday night 8 Luigi's").let { assertEquals(LocalTime.of(20, 0), it.time); assertEquals("Dinner Luigi's", it.title) }
    }

    // Q6-9 and Q6-10: sentence punctuation and colons left by the when.
    @Test fun punctuationLeftByTheWhen() {
        assertEquals("Book table", parse("Book table for Saturday!").title)
        parse("Party from 7pm!").let { assertEquals("Party", it.title); assertEquals(LocalTime.of(19, 0), it.time) }
        parse("Gym Friday 6!").let { assertEquals("Gym", it.title); assertEquals(listOf(LocalTime.of(6, 0), LocalTime.of(18, 0)), it.timeChoices) }
        assertEquals("Dentist", parse("Tomorrow: dentist").title)
        assertEquals("Lunch Sam", parse("Lunch tomorrow: Sam").title)
        assertEquals("Lunch", parse("Lunch: tomorrow").title)
        assertEquals("Note: buy milk", parse("Note: buy milk tomorrow").title)
    }

    // Q6-13: a stay written backwards is asked about.
    @Test fun aBackwardsStayIsQuestioned() {
        assertEquals("Write the shorter stay first, for example 2-3 nights.", parse("Hotel 3-2 nights").error)
        assertEquals(LocalDate.of(2026, 10, 7), parse("Hotel 2-3 nights").endDate)
    }
}
