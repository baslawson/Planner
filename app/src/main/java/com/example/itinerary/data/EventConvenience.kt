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
// Whole days count on the calendar ("1 day before" keeps the clock time); hours and minutes are real time across a clock change.
fun reminderTrigger(date: LocalDate, time: LocalTime?, offsetMinutes: Long, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
    date.atTime(time ?: LocalTime.of(9, 0)).minusDays(offsetMinutes / 1440).atZone(zone).minusMinutes(offsetMinutes % 1440)
