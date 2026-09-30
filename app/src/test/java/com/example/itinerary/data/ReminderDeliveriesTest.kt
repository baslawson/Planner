package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ReminderDeliveriesTest {
    private val item = ItineraryItem(id = 1, tripId = 1, date = LocalDate.of(2026, 10, 5), startTime = LocalTime.of(10, 0), title = "Dentist")
    private val reminder = Reminder(id = 7, itemId = 1, amount = 1, unit = ReminderUnit.HOURS)
    private fun trigger(zone: String) = reminderTrigger(item.date, item.startTime, reminder.offsetMinutes, ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test fun eastwardMoveStillDeliversTheOldAlarm() {
        // Scheduled in Perth for 09:00; the phone then moves to Sydney, where 09:00 came two hours earlier.
        val scheduled = trigger("Australia/Perth")
        val expected = trigger("Australia/Sydney")
        assertTrue(expected < scheduled)
        assertTrue(ReminderDeliveries.accepts(scheduled, expected, snoozed = false, now = scheduled))
        assertTrue(ReminderDeliveries.accepts(scheduled, expected, snoozed = false, now = scheduled + 60_000))
        // Not before the old alarm's own time, and not for a snooze (an absolute time that no zone change moves).
        assertFalse(ReminderDeliveries.accepts(scheduled, expected, snoozed = false, now = scheduled - 1))
        assertFalse(ReminderDeliveries.accepts(scheduled, expected, snoozed = true, now = scheduled))
    }

    @Test fun onlyExactOrZoneSizedLateAlarmsAreAccepted() {
        val expected = trigger("UTC")
        assertTrue(ReminderDeliveries.accepts(expected, expected, snoozed = true, now = expected))
        assertTrue(ReminderDeliveries.accepts(0L, expected, snoozed = false, now = expected))
        // An alarm earlier than the recomputed time (a westward move; the future alarm replaces it) never shows.
        assertFalse(ReminderDeliveries.accepts(expected - 3_600_000, expected, snoozed = false, now = expected))
        // A later alarm beyond any time-zone difference is stale, not a zone change.
        val tooLate = expected + ReminderDeliveries.MAX_ZONE_SHIFT_MS + 1
        assertFalse(ReminderDeliveries.accepts(tooLate, expected, snoozed = false, now = tooLate))
        val latest = expected + ReminderDeliveries.MAX_ZONE_SHIFT_MS
        assertTrue(ReminderDeliveries.accepts(latest, expected, snoozed = false, now = latest))
    }

    @Test fun deliveryRecordSurvivesZoneChangeButNotAnEdit() {
        // Delivered in Sydney; after moving west to Perth the same reminder is two hours ahead again but must not be rescheduled.
        val recorded = ReminderDeliveries.key(item, reminder)
        assertTrue(trigger("Australia/Perth") > trigger("Australia/Sydney"))
        assertTrue(ReminderDeliveries.delivered(recorded, item, reminder))
        assertFalse(ReminderDeliveries.delivered(recorded, item.copy(startTime = LocalTime.of(11, 0)), reminder))
        assertFalse(ReminderDeliveries.delivered(recorded, item.copy(date = item.date.plusDays(1)), reminder))
        assertFalse(ReminderDeliveries.delivered(recorded, item, reminder.copy(amount = 2)))
        assertFalse(ReminderDeliveries.delivered(null, item, reminder))
        // A snoozed reminder is never skipped for an earlier on-time delivery.
        assertNull(ReminderDeliveries.key(item, reminder.copy(snoozedUntil = 5L)))
        assertFalse(ReminderDeliveries.delivered(recorded, item, reminder.copy(snoozedUntil = 5L)))
        // All-day events have no time; the key still tells them from 00:00.
        assertNotEquals(ReminderDeliveries.key(item.date, null, 0), ReminderDeliveries.key(item.date, LocalTime.MIDNIGHT, 0))
    }
}
