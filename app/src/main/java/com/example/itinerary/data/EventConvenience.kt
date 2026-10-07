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

/**
 * A reminder at the date and time [at] (Reminders › "Pick date and time"), as the offset before the event that reminders
 * keep, so it moves with the event: whole days when [at] has the event's own clock time (as "1 day" does), else hours
 * or minutes of elapsed time. Null when [at] is after the event starts, or too far before it to store.
 */
fun reminderAt(date: LocalDate, time: LocalTime?, at: java.time.LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Pair<Int, ReminderUnit>? {
    val start = date.atTime(time ?: LocalTime.of(9, 0))
    if (at.isAfter(start)) return null
    if (at.toLocalTime() == start.toLocalTime()) {
        val days = java.time.temporal.ChronoUnit.DAYS.between(at.toLocalDate(), start.toLocalDate())
        return if (days in 0..Int.MAX_VALUE.toLong()) (if (days == 0L) 0 to ReminderUnit.MINUTES else days.toInt() to ReminderUnit.DAYS) else null
    }
    val minutes = java.time.Duration.between(at.atZone(zone), start.atZone(zone)).toMinutes()
    if (minutes < 0 || minutes > Int.MAX_VALUE) return null
    return if (minutes % 60 == 0L) (minutes / 60).toInt() to ReminderUnit.HOURS else minutes.toInt() to ReminderUnit.MINUTES
}

/** Days keep the event's clock time; hours and minutes keep their elapsed duration across a clock change. */
fun reminderTrigger(date: LocalDate, time: LocalTime?, reminder: Reminder, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime {
    val start = date.atTime(time ?: LocalTime.of(9, 0))
    return if (reminder.unit == ReminderUnit.DAYS) start.minusDays(reminder.amount.toLong()).atZone(zone)
        else start.atZone(zone).minusMinutes(reminder.offsetMinutes)
}
