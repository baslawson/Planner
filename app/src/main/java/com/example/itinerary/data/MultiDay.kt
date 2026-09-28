package com.example.itinerary.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

// All-day events that span several days ("Trip, 3 Oct – 7 Oct"): one event with an inclusive end date. Timed events
// keep their 24-hour duration limit; bills are always a single day.
object MultiDay {
    const val MAX_DAYS = 366

    // Valid when single-day, or an all-day non-bill event ending after it starts, at most MAX_DAYS days long.
    fun valid(date: LocalDate, endDate: LocalDate?, startTime: Any?, category: String): Boolean =
        endDate == null || startTime == null && category != "Bills" && endDate > date &&
            ChronoUnit.DAYS.between(date, endDate) < MAX_DAYS

    fun validate(item: ItineraryItem) = require(valid(item.date, item.endDate, item.startTime, item.category)) {
        "A multi-day event must be all day, not a bill, and end 1–${MAX_DAYS - 1} days after it starts."
    }
}

// The last day the event covers (its own date for a single-day event).
val ItineraryItem.lastDay: LocalDate get() = endDate ?: date
val PlanEvent.lastDay: LocalDate get() = endDate ?: date

// How many days it covers, counting both ends ("3 Oct – 7 Oct" = 5).
val ItineraryItem.dayCount: Int get() = ChronoUnit.DAYS.between(date, lastDay).toInt() + 1
val PlanEvent.dayCount: Int get() = ChronoUnit.DAYS.between(date, lastDay).toInt() + 1

fun ItineraryItem.covers(day: LocalDate): Boolean = day in date..lastDay
fun PlanEvent.covers(day: LocalDate): Boolean = day in date..lastDay

// Which day of the span [day] is, from 1 ("Day 3 of 5").
fun PlanEvent.dayNumber(day: LocalDate): Int = ChronoUnit.DAYS.between(date, day).toInt() + 1
fun ItineraryItem.dayNumber(day: LocalDate): Int = ChronoUnit.DAYS.between(date, day).toInt() + 1

// The same event starting on [start] instead, keeping its length.
fun ItineraryItem.startingOn(start: LocalDate): ItineraryItem =
    copy(date = start, endDate = endDate?.let { start.plusDays(ChronoUnit.DAYS.between(date, it)) })
