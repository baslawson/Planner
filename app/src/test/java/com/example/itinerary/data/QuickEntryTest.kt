package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class QuickEntryTest {
    private val today = LocalDate.of(2026, 9, 27)
    @Test fun everydayPhrasesKeepTitleAndComputeDuration() {
        val entry = QuickEntry.parse("Dentist Fri 3pm for 45 minutes", today)
        assertNull(entry.error); assertEquals("Dentist", entry.title)
        assertEquals(LocalDate.of(2026,10,2), entry.date)
        assertEquals(LocalTime.of(15,0), entry.time); assertEquals(45, entry.durationMinutes)
        assertTrue(entry.dateSpecified)
        assertEquals(LocalTime.NOON, QuickEntry.parse("Lunch tmr noon", today).time)
        assertEquals(today.plusDays(1), QuickEntry.parse("Lunch tmr noon", today).date)
        assertEquals(90, QuickEntry.parse("Work today at 23:30 for 1.5 hours", today).durationMinutes)
        assertEquals(1440, QuickEntry.parse("Work midnight for 24h", today).durationMinutes)
    }
    @Test fun namedDatesResolveYearAndRejectImpossibleDates() {
        assertEquals(LocalDate.of(2026,9,30), QuickEntry.parse("Visit on 30 Sep", today).date)
        assertEquals("Visit", QuickEntry.parse("Visit on 30 Sep", today).title)
        assertEquals(LocalDate.of(2027,1,5), QuickEntry.parse("Visit 5 January", today).date)
        assertEquals(LocalDate.of(2028,2,29), QuickEntry.parse("Visit 29 Feb 2028", today).date)
        assertNotNull(QuickEntry.parse("Visit 31 Sep", today).error)
        assertNotNull(QuickEntry.parse("Visit 29 Feb 2027", today).error)
    }
    @Test fun taskWithoutDateStaysDistinguishableFromToday() {
        assertFalse(QuickEntry.parse("Buy groceries", today).dateSpecified)
        assertTrue(QuickEntry.parse("Buy groceries today", today).dateSpecified)
        assertEquals("Call 12345", QuickEntry.parse("Call 12345", today).title)
        assertEquals("Buy milk for 3 people", QuickEntry.parse("Buy milk for 3 people", today).title)
    }
    @Test fun ambiguousTimesRequireChoiceWithoutLosingTitle() {
        val entry = QuickEntry.parse("Call plumber tomorrow at 3", today)
        assertTrue(entry.ambiguousTime); assertNotNull(entry.error); assertNull(entry.time)
        assertEquals("Call plumber", entry.title); assertEquals(today.plusDays(1), entry.date)
        assertFalse(QuickEntry.parse("Call at 3pm", today).ambiguousTime)
    }
    @Test fun conflictingOrInvalidValuesCannotSave() {
        for (text in listOf("Work 3pm 4pm", "Work today tmr", "Work 25:00", "Work 13pm",
            "Work for 0 minutes", "Work for -3 minutes", "Work for 25 hours", "Work for 0.5 minutes",
            "Work for 30min for 1h", "")) assertNotNull(text, QuickEntry.parse(text, today).error)
    }
}
