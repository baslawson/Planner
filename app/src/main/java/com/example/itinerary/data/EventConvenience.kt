package com.example.itinerary.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// Equal clock times mean a full day, not a zero-length event. The picker explicitly previews next day.
fun durationUntilEnd(start: LocalTime, end: LocalTime): Int {
    val difference = (end.hour * 60 + end.minute) - (start.hour * 60 + start.minute)
    return if (difference > 0) difference else difference + 1440
}

// A single calculation for both the displayed preview and the actual alarm, including DST resolution.
// Legacy mixed offsets from calendar text: whole days on the calendar, then elapsed minutes.
// A stored Reminder uses the unit-aware overload below.
fun reminderTrigger(date: LocalDate, time: LocalTime?, offsetMinutes: Long, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
    date.atTime(time ?: LocalTime.of(9, 0)).minusDays(offsetMinutes / 1440).atZone(zone).minusMinutes(offsetMinutes % 1440)

/** Days keep the event's clock time; hours and minutes keep their elapsed duration across a clock change. */
fun reminderTrigger(date: LocalDate, time: LocalTime?, reminder: Reminder, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime {
    val start = date.atTime(time ?: LocalTime.of(9, 0))
    return if (reminder.unit == ReminderUnit.DAYS) start.minusDays(reminder.amount.toLong()).atZone(zone)
        else start.atZone(zone).minusMinutes(reminder.offsetMinutes)
}
