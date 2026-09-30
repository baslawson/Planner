package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class TaskReminderPresetsTest {
    private val zone = ZoneId.of("Australia/Perth")
    private fun now(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, zone)
    private fun at(day: LocalDate, hour: Int, minute: Int = 0) = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    private val sep = { d: Int -> LocalDate.of(2026, 9, d) }

    @Test fun withADueDateTheDayAndTheDayBeforeAtNine() {
        assertEquals(listOf(TaskReminderPreset.ON_THE_DAY to at(sep(30), 9), TaskReminderPreset.DAY_BEFORE to at(sep(29), 9)),
            taskReminderPresets(sep(30), now(28, 10)))
        // The day before's 09:00 has passed: only the day itself.
        assertEquals(listOf(TaskReminderPreset.ON_THE_DAY to at(sep(30), 9)), taskReminderPresets(sep(30), now(29, 12)))
    }
    @Test fun withoutADueDateOrWhenBothHavePassedLaterTodayAndTomorrow() {
        val expected = listOf(TaskReminderPreset.LATER_TODAY to at(sep(28), 11, 30), TaskReminderPreset.TOMORROW to at(sep(29), 9))
        assertEquals(expected, taskReminderPresets(null, now(28, 10, 30)))
        assertEquals(expected, taskReminderPresets(sep(28), now(28, 10, 30)))
        assertEquals(expected, taskReminderPresets(sep(20), now(28, 10, 30)))
        // Late in the evening "in an hour" is tomorrow: only tomorrow at 09:00.
        assertEquals(listOf(TaskReminderPreset.TOMORROW to at(sep(29), 9)), taskReminderPresets(null, now(28, 23, 30)))
    }
}
