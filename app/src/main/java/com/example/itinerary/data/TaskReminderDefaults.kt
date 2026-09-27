package com.example.itinerary.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

// Keep the suggested day and time together when the next hour crosses midnight.
fun taskReminderDefault(dueDate: LocalDate?, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
    val nextHour = now.plusHours(1).withSecond(0).withNano(0)
    return if (dueDate != null && dueDate > nextHour.toLocalDate())
        dueDate.atTime(nextHour.toLocalTime()).atZone(now.zone)
    else nextHour
}

fun taskReminderInstant(date: LocalDate, time: LocalTime, now: ZonedDateTime = ZonedDateTime.now()): Long {
    val first = date.atTime(time).atZone(now.zone)
    // During the repeated daylight-saving hour, prefer the future occurrence if the first passed.
    val later = first.withLaterOffsetAtOverlap()
    val selected = if (!first.isAfter(now) && later.isAfter(now)) later else first
    return selected.toInstant().toEpochMilli()
}
