package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ReminderAtTest {
    private val sydney = ZoneId.of("Australia/Sydney")
    private val day = LocalDate.of(2026, 10, 20)
    private val ten = LocalTime.of(10, 0)

    private fun reminder(p: Pair<Int, ReminderUnit>) = Reminder(itemId = 1, amount = p.first, unit = p.second)

    @Test fun aPickedDateAndTimeBecomesTheOffsetBeforeTheEvent() {
        // The event's own clock time on an earlier day: whole days, like "1 day".
        assertEquals(2 to ReminderUnit.DAYS, reminderAt(day, ten, day.minusDays(2).atTime(ten), sydney))
        // The start itself: "At the time".
        assertEquals(0 to ReminderUnit.MINUTES, reminderAt(day, ten, day.atTime(ten), sydney))
        // Other times: hours when whole, else minutes.
        assertEquals(3 to ReminderUnit.HOURS, reminderAt(day, ten, day.atTime(7, 0), sydney))
        assertEquals(1_485 to ReminderUnit.MINUTES, reminderAt(day, ten, LocalDateTime.of(2026, 10, 19, 9, 15), sydney))
        // After the start: refused.
        assertNull(reminderAt(day, ten, day.atTime(10, 1), sydney))
        // All day: counted back from 9:00.
        assertEquals(1 to ReminderUnit.HOURS, reminderAt(day, null, day.atTime(8, 0), sydney))
    }

    @Test fun theStoredOffsetFiresAtThePickedMomentAcrossAClockChange() {
        // Sydney's clocks went forward on 4 Oct 2026 (2:00 -> 3:00): an elapsed offset still lands on the picked time.
        val event = LocalDate.of(2026, 10, 5)
        val picked = LocalDateTime.of(2026, 10, 3, 22, 30)
        val offset = reminderAt(event, ten, picked, sydney)!!
        assertEquals(picked.atZone(sydney), reminderTrigger(event, ten, reminder(offset), sydney))
        val sameTime = LocalDateTime.of(2026, 10, 2, 10, 0)
        assertEquals(sameTime.atZone(sydney), reminderTrigger(event, ten, reminder(reminderAt(event, ten, sameTime, sydney)!!), sydney))
    }

    @Test fun longOffsetsReadAsDaysHoursAndMinutes() {
        assertEquals("1 day and 45 minutes before", reminder(1_485 to ReminderUnit.MINUTES).label)
        assertEquals("2 days, 3 hours and 15 minutes before", reminder(3_075 to ReminderUnit.MINUTES).label)
        assertEquals("2 days before", reminder(48 to ReminderUnit.HOURS).label)
        assertEquals("1 day and 1 hour before", reminder(25 to ReminderUnit.HOURS).label)
        // Short ones stay as they were.
        assertEquals("30 minutes before", reminder(30 to ReminderUnit.MINUTES).label)
        assertEquals("3 hours before", reminder(3 to ReminderUnit.HOURS).label)
        assertEquals("3 days before", reminder(3 to ReminderUnit.DAYS).label)
        assertEquals("At the time", reminder(0 to ReminderUnit.MINUTES).label)
    }
}
