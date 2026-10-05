package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ReminderUnitsDstTest {
    private val ny = ZoneId.of("America/New_York")
    private val time = LocalTime.of(9, 0)

    @Test fun calendarDayAndElapsedUnitsDifferAtBothClockChanges() {
        listOf(LocalDate.of(2026, 3, 8) to 23L, LocalDate.of(2026, 11, 1) to 25L).forEach { (day, calendarHours) ->
            val start = day.atTime(time).atZone(ny)
            val calendar = reminderTrigger(day, time, Reminder(itemId = 0, amount = 1, unit = ReminderUnit.DAYS), ny)
            assertEquals(time, calendar.toLocalTime())
            assertEquals(calendarHours, Duration.between(calendar, start).toHours())
            listOf(Reminder(itemId = 0, amount = 24, unit = ReminderUnit.HOURS),
                Reminder(itemId = 0, amount = 1440, unit = ReminderUnit.MINUTES),
                Reminder(itemId = 0, amount = 49, unit = ReminderUnit.HOURS)).forEach { r ->
                assertEquals(r.offsetMinutes, Duration.between(reminderTrigger(day, time, r, ny), start).toMinutes())
            }
        }
    }

    @Test fun deliveryAndSiblingIdentityKeepCalendarAndElapsedSchedulesDistinct() {
        val day = Reminder(itemId = 0, amount = 1, unit = ReminderUnit.DAYS)
        val hours = Reminder(itemId = 0, amount = 24, unit = ReminderUnit.HOURS)
        val minutes = Reminder(itemId = 0, amount = 1440, unit = ReminderUnit.MINUTES)
        val item = ItineraryItem(tripId = 0, date = LocalDate.of(2026, 3, 8), startTime = time, title = "Meeting")
        assertNotEquals(day.scheduleKey, hours.scheduleKey)
        assertEquals(hours.scheduleKey, minutes.scheduleKey)
        assertFalse(ReminderDeliveries.delivered(ReminderDeliveries.key(item, day), item, hours))
        assertTrue(ReminderDeliveries.delivered(ReminderDeliveries.key(item, hours), item, minutes))
        assertEquals("2026-03-08|09:00|1440", ReminderDeliveries.key(item, day))
    }

    @Test fun quickEntryPreservesItsStatedHoursMinutesOrDays() {
        val date = LocalDate.of(2026, 3, 8)
        listOf("24 hours" to ReminderUnit.HOURS, "1440 minutes" to ReminderUnit.MINUTES,
            "1 day" to ReminderUnit.DAYS).forEach { (words, unit) ->
            val s = QuickEntry.parse("Meeting 8 March 2026 at 9am remind me $words before", LocalDate.of(2026, 3, 1), zone = ny)
            assertNull(s.error)
            assertEquals(unit, s.reminderUnit)
            val r = s.quickReminders(ny).single()
            assertEquals(unit, r.unit)
            assertEquals(reminderTrigger(date, time, r, ny), s.quickReminderTrigger(task = false, zone = ny))
            assertEquals(if (unit == ReminderUnit.DAYS) 23L else 24L,
                Duration.between(s.quickReminderTrigger(false, ny), date.atTime(time).atZone(ny)).toHours())
        }
    }

    @Test fun conversionKeepsTheSecondOccurrenceOfAnAmbiguousClockTime() {
        val date = LocalDate.of(2026, 11, 1)
        val secondOneThirty = date.atTime(1, 30).atZone(ny).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
        val converted = TaskEventConversion.toEvent(PlannerTask(title = "x", dueDate = date, reminderAt = secondOneThirty), date, ny)
        assertEquals(secondOneThirty, reminderTrigger(date, null, converted.reminders.single(), ny).toInstant().toEpochMilli())
        assertTrue(converted.dropped.isEmpty())
    }
    @Test fun quickClockReminderKeepsItsRequestedClockTimeAcrossDaylightSaving() {
        listOf(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1)).forEach { date ->
            val s = QuickEntry.parse("Meeting $date at 9am remind me 1 day before at 6pm", date.minusDays(5), zone = ny)
            assertNull(s.error)
            val expected = date.minusDays(1).atTime(18, 0).atZone(ny)
            assertEquals(expected, s.quickReminderTrigger(false, ny))
            assertEquals(expected.toInstant(), reminderTrigger(date, time, s.quickReminders(ny).single(), ny).toInstant())
            val corrected = s.corrected(date.plusDays(1).toString(), null)
            assertEquals(date.atTime(18, 0).atZone(ny), corrected.quickReminderTrigger(false, ny))
        }
        val implicit = QuickEntry.parse("Meeting 8 March 2026 at 9am remind me at 6pm", LocalDate.of(2026, 3, 1), zone = ny)
        assertNull(implicit.error)
        assertEquals(LocalDate.of(2026, 3, 7).atTime(18, 0).atZone(ny), implicit.quickReminderTrigger(false, ny))
        assertEquals(LocalDate.of(2026, 3, 8).atTime(18, 0).atZone(ny), implicit.corrected(null, "20:00").quickReminderTrigger(false, ny))
    }

}
