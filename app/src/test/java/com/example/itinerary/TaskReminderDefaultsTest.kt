package com.example.itinerary

import com.example.itinerary.data.taskReminderDefault
import com.example.itinerary.data.taskReminderInstant
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class TaskReminderDefaultsTest {
    @Test fun nextHourAtNightUsesTomorrowEvenWhenTaskIsDueToday() {
        val now = ZonedDateTime.parse("2026-09-26T23:30:45+08:00[Australia/Perth]")
        for (due in listOf(null, now.toLocalDate(), now.toLocalDate().minusDays(1))) {
            val suggested = taskReminderDefault(due, now)
            assertEquals(LocalDate.of(2026, 9, 27), suggested.toLocalDate())
            assertEquals("00:30", suggested.toLocalTime().toString())
            assertTrue(suggested.toInstant() > now.toInstant())
        }
    }
    @Test fun futureDueDateIsPreservedAndMinutePrecisionIsUsed() {
        val now = ZonedDateTime.parse("2026-09-26T10:15:42+08:00[Australia/Perth]")
        val due = LocalDate.of(2026, 9, 30)
        val suggested = taskReminderDefault(due, now)
        assertEquals(due, suggested.toLocalDate())
        assertEquals("11:15", suggested.toLocalTime().toString())
    }
    @Test fun daylightSavingOverlapDoesNotTurnSuggestionIntoPastTime() {
        val now = ZonedDateTime.parse("2026-11-01T01:30:45-04:00[America/New_York]")
        val suggested = taskReminderDefault(null, now)
        assertEquals(suggested.toInstant().toEpochMilli(), taskReminderInstant(suggested.toLocalDate(), suggested.toLocalTime(), now))
        assertEquals(-5 * 3600, suggested.offset.totalSeconds)
        assertTrue(suggested.toInstant() > now.toInstant())
    }
}
