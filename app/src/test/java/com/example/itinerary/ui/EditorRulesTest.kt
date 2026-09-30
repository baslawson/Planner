package com.example.itinerary.ui

import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.MultiDay
import com.example.itinerary.data.PaymentUpdateException
import com.example.itinerary.data.RepeatRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class EditorRulesTest {
    private val oct3 = LocalDate.of(2026, 10, 3)
    private val oct7 = LocalDate.of(2026, 10, 7)

    @Test fun spanEndKeepsAValidAllDaySpan() {
        assertEquals(oct7, EditorRules.spanEnd(oct3, oct7, null, "Travel"))
        assertNull(EditorRules.spanEnd(oct3, oct7, LocalTime.NOON, "Travel"))
        assertNull(EditorRules.spanEnd(oct3, oct7, null, "Bills"))
        assertNull(EditorRules.spanEnd(oct7, oct3, null, "Travel"))
    }

    @Test fun spanEndDropsASpanLongerThanTheLimit() {
        val start = LocalDate.of(2025, 9, 1)
        assertNull(EditorRules.spanEnd(start, oct7, null, "Travel"))
        val last = start.plusDays((MultiDay.MAX_DAYS - 1).toLong())
        assertEquals(last, EditorRules.spanEnd(start, last, null, "Travel"))
        assertNull(EditorRules.spanEnd(start, last.plusDays(1), null, "Travel"))
    }

    @Test fun movingTheStartKeepsTheHeldSpanLength() {
        val newStart = LocalDate.of(2025, 9, 1)
        assertEquals(LocalDate.of(2025, 9, 5), EditorRules.movedEndDate(oct3, newStart, oct7))
        assertEquals(LocalDate.of(2026, 10, 12), EditorRules.movedEndDate(oct3, LocalDate.of(2026, 10, 8), oct7))
        assertNull(EditorRules.movedEndDate(oct3, newStart, null))
        // A stale end on or before the start is not a span and is left alone.
        assertEquals(oct3, EditorRules.movedEndDate(oct3, newStart, oct3))
    }

    @Test fun nonRepeatingTemplateLeavesAUsableCount() {
        assertEquals("12", EditorRules.templateCount(RepeatRule.NONE, 1, "1"))
        assertEquals("12", EditorRules.templateCount(RepeatRule.NONE, 1, ""))
        assertEquals("20", EditorRules.templateCount(RepeatRule.NONE, 1, "20"))
        assertEquals("5", EditorRules.templateCount(RepeatRule.WEEKLY, 5, "12"))
    }

    @Test fun pickingARepeatResetsAnUnusableCount() {
        assertEquals("12", EditorRules.countForRepeat(RepeatRule.WEEKLY, "1"))
        assertEquals("12", EditorRules.countForRepeat(RepeatRule.WEEKLY, ""))
        assertEquals("30", EditorRules.countForRepeat(RepeatRule.WEEKLY, "30"))
        assertEquals("1", EditorRules.countForRepeat(RepeatRule.NONE, "1"))
    }

    @Test fun saveErrorShowsReadableReasonsOnly() {
        val generic = "Couldn't save the event. Your changes are still here; try again."
        assertEquals("Needs a duration", EditorRules.saveError(IllegalArgumentException("Needs a duration"), false))
        assertEquals("Paid twice", EditorRules.saveError(PaymentUpdateException("Paid twice"), true))
        assertEquals(generic, EditorRules.saveError(IllegalArgumentException("Failed requirement."), false))
        assertEquals(generic, EditorRules.saveError(IllegalStateException("disk"), false))
        assertEquals("Couldn't save the bill. Your changes are still here; try again.", EditorRules.saveError(RuntimeException(), true))
    }

    @Test fun followOnlyWhenTheEventLeavesTheSelectedDay() {
        val trip = ItineraryItem(tripId = 1, date = oct3, startTime = null, endDate = oct7, title = "Trip")
        assertNull(EditorRules.followDate(trip, LocalDate.of(2026, 10, 5)))
        assertEquals(oct3, EditorRules.followDate(trip, LocalDate.of(2026, 10, 8)))
        val overnight = ItineraryItem(tripId = 1, date = oct3, startTime = LocalTime.of(22, 0), durationMinutes = 180, title = "Flight")
        assertNull(EditorRules.followDate(overnight, oct3.plusDays(1)))
        assertEquals(oct3, EditorRules.followDate(overnight, oct3.plusDays(2)))
        val moved = ItineraryItem(tripId = 1, date = oct7, startTime = null, title = "Dinner")
        assertEquals(oct7, EditorRules.followDate(moved, oct3))
        assertNull(EditorRules.followDate(moved, oct7))
    }
}
