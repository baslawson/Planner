package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** A Quick entry draft started on an earlier day and reopened today. */
class QuickStaleDraftTest {
    private val started = LocalDate.of(2026, 9, 28)
    private val now = ZonedDateTime.of(2026, 9, 30, 6, 3, 0, 0, ZoneId.of("Australia/Perth"))
    private val draft = QuickInput(text = "dinner", task = true, baseDate = started, title = "gym")

    @Test fun untouchedOldDraftStillCannotCountFromNow() {
        val old = draft.copy(text = "dinner in 2 hours")
        assertEquals(QuickEntry.STALE_RELATIVE, old.suggestion(now).error)
    }

    @Test fun untouchedOldDraftKeepsItsOwnTomorrow() {
        assertEquals(LocalDate.of(2026, 9, 29), draft.copy(text = "tomorrow").suggestion(now).date)
    }

    @Test fun editingAnOldDraftReadsItAgainstToday() {
        val typed = draft.rebased(now.toLocalDate()).edited("dinner in 2 hours")
        assertEquals(now.toLocalDate(), typed.baseDate)
        val suggestion = typed.suggestion(now)
        assertNull(suggestion.error)
        assertEquals(now.toLocalDate(), suggestion.date)
        assertEquals(LocalTime.of(8, 5), suggestion.time)
        assertEquals(LocalDate.of(2026, 10, 1), draft.rebased(now.toLocalDate()).edited("tomorrow").suggestion(now).date)
    }

    @Test fun rebasingKeepsCorrectionsAndTodayDraftsAlone() {
        val corrected = draft.copy(dateOverride = "2026-10-05")
        assertEquals("2026-10-05", corrected.rebased(now.toLocalDate()).dateOverride)
        val current = draft.copy(baseDate = now.toLocalDate())
        assertSame(current, current.rebased(now.toLocalDate()))
    }
}
