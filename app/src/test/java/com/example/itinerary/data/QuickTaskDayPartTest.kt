package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// A task has a due day, not a time: a part of the day ("tonight", "tomorrow night") only gives the day.
class QuickTaskDayPartTest {
    private val now = ZonedDateTime.of(2026, 10, 3, 14, 0, 0, 0, ZoneId.of("Australia/Sydney"))
    private val today = now.toLocalDate()
    private fun task(text: String) = QuickInput(text, task = true, baseDate = today).suggestion(now)
    private fun event(text: String) = QuickInput(text, baseDate = today).suggestion(now)

    @Test fun aPartOfTheDayGivesATaskItsDay() {
        for ((text, day) in listOf("Cooking tonight" to today, "cook dinner tonight" to today, "Cooking tomorrow night" to today.plusDays(1),
            "Bins Friday morning" to LocalDate.of(2026, 10, 9), "Cooking this evening" to today, "Cooking tonite" to today)) {
            val s = task(text)
            assertNull(text, s.quickProblem(true, now))
            assertEquals(text, day, s.date); assertNull(text, s.time); assertFalse(text, s.ambiguousTime)
            assertTrue(text, s.dateSpecified); assertTrue(text, s.title.startsWith("C") || s.title.startsWith("c") || s.title == "Bins")
        }
        assertEquals("Cooking", task("Cooking tonight").title)
    }

    @Test fun anExactTimeOnATaskStillAsksForAnEvent() {
        for (text in listOf("Cooking at 7pm", "Cooking 8 tonight", "Cooking tonight at 7")) {
            assertEquals(text, "Tasks use due dates. Choose Event for a time or duration.", task(text).quickProblem(true, now))
        }
    }

    @Test fun eventsStillAskWhatTimeTonightMeans() {
        val s = event("Cooking tonight")
        assertTrue(s.ambiguousTime)
        assertEquals("What time did you mean by ‘tonight’? Tap Choose time.", s.quickProblem(false, now))
        // The new spellings are read as tonight too.
        for (typo in listOf("tonite", "tonigh", "2nite")) {
            val e = event("Cooking $typo")
            assertEquals(typo, "Cooking", e.title); assertTrue(typo, e.ambiguousTime)
            assertEquals(typo, "What time did you mean by ‘$typo’? Tap Choose time.", e.quickProblem(false, now))
        }
        assertEquals(LocalTime.of(20, 0), event("Cooking 8 tonite").time)
    }
}
