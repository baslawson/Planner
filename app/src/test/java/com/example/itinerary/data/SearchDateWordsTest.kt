package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

// 1 Oct 2026 review: search reads "next Friday" as quick entry does, ordinal day numbers, and a month word that may be a title word.
class SearchDateWordsTest {
    private val today = LocalDate.of(2026, 10, 1) // a Thursday
    private val plans = listOf(Trip(id = 1, name = "Plan", destination = "", startDate = today, endDate = today))
    private fun event(id: Long, date: LocalDate, title: String) = ItineraryItem(id = id, tripId = 1, date = date, startTime = null, title = title)
    private val events = listOf(
        event(1, LocalDate.of(2026, 10, 1), "Dentist"),
        event(2, LocalDate.of(2026, 10, 2), "Gym this Friday"),
        event(3, LocalDate.of(2026, 10, 9), "Gym next Friday"),
        event(4, LocalDate.of(2026, 9, 28), "Gym this Monday"),
        event(5, LocalDate.of(2026, 10, 5), "Climate March"),
        event(6, LocalDate.of(2027, 3, 4), "Dentist"),
        event(7, LocalDate.of(2026, 9, 25), "Gym last Friday"),
    )
    private fun hits(query: String) = Search.run(query, emptySet(), plans, events, emptyList(), today).hits.map { it.item.id }.toSet()

    @Test fun nextAndThisWeekdayMeanWhatQuickEntryMeans() {
        assertEquals(setOf(3L), hits("gym next friday"))
        assertEquals(setOf(2L), hits("gym this friday"))
        assertEquals(setOf(2L), hits("gym friday"))
        assertEquals(setOf(4L), hits("gym this monday"))
        assertEquals(setOf(7L), hits("gym last friday"))
        // The same dates quick entry gives.
        for (words in listOf("next friday", "this friday", "this monday", "next monday", "friday", "last friday"))
            assertEquals(words, QuickEntry.parse("Gym $words", today, dayFirst = true).date,
                Search.run(words, emptySet(), plans, (0L..30L).map { event(100 + it, today.minusDays(10).plusDays(it), "x") }, emptyList(), today)
                    .hits.single().item.date)
    }

    @Test fun ordinalDayNumbers() {
        assertEquals(setOf(1L), hits("dentist 1st october"))
        assertEquals(setOf(1L), hits("dentist october 1st"))
        assertEquals(setOf(6L), hits("4th march"))
    }

    @Test fun aMonthWordAloneMatchesTheWordOrTheMonth() {
        // "march": events in March, or with the word.
        assertEquals(setOf(5L, 6L), hits("march"))
        assertEquals(setOf(5L), hits("climate march"))
        assertEquals(setOf(6L), hits("dentist march"))
        // With a year or a day it is a date only.
        assertEquals(setOf(6L), hits("march 2027"))
        assertEquals(setOf(6L), hits("4 march"))
        assertEquals(setOf(1L, 2L, 3L, 5L), hits("october"))
    }

    // Second bug hunt of 1 Oct 2026 (Q-N10): the date words quick entry reads.
    @Test fun dayOfMonthWithOf() {
        assertEquals(setOf(1L), hits("dentist 1st of october"))
        assertEquals(setOf(6L), hits("4th of march"))
        assertEquals(setOf(6L), hits("dentist the 4th of march 2027"))
        assertEquals(setOf(6L), hits("4 of mar"))
    }

    @Test fun abbreviatedWeekdaysAndTomorrow() {
        assertEquals(setOf(2L), hits("gym fri"))
        assertEquals(setOf(2L), hits("gym tmrw"))
        assertEquals(setOf(2L), hits("gym tmr"))
        assertEquals(setOf(2L), hits("gym tomoz"))
        assertEquals(setOf(3L), hits("gym next fri"))
        assertEquals(setOf(4L), hits("gym this mon"))
        assertEquals(setOf(7L), hits("gym last fri"))
        for (words in listOf("fri", "thurs", "tue", "next fri", "this mon", "tmrw"))
            assertEquals(words, QuickEntry.parse("Gym $words", today, dayFirst = true).date,
                Search.run(words, emptySet(), plans, (0L..30L).map { event(100 + it, today.minusDays(10).plusDays(it), "x") }, emptyList(), today)
                    .hits.single().item.date)
    }

    @Test fun sunSatAndWedAloneMayBeTitleWords() {
        val list = listOf(event(1, LocalDate.of(2026, 10, 4), "Brunch"), event(2, LocalDate.of(2026, 10, 9), "Sun cream"),
            event(3, LocalDate.of(2026, 10, 9), "Lunch"), event(4, LocalDate.of(2026, 10, 9), "Sat nav"))
        fun found(query: String) = Search.run(query, emptySet(), plans, list, emptyList(), today).hits.map { it.item.id }.toSet()
        // As quick entry reads "Buy sun cream" and "Lunch Sun": the day, or the word.
        assertEquals(setOf(1L, 2L), found("sun"))
        assertEquals(setOf(2L), found("sun cream"))
        assertEquals(setOf(1L), found("brunch sun"))
        assertEquals(setOf(4L), found("sat"))
        assertEquals(setOf(1L), found("this sun"))
    }
}
