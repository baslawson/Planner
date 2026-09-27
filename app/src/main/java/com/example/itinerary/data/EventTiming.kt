package com.example.itinerary.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

fun overlaps(start: LocalDateTime, minutes: Int?, otherStart: LocalDateTime, otherMinutes: Int?): Boolean {
    // Missing durations are points, not invented appointment lengths. Known intervals are half-open.
    return when {
        minutes == null && otherMinutes == null -> start == otherStart
        minutes == null -> start >= otherStart && start < otherStart.plusMinutes(otherMinutes!!.toLong())
        otherMinutes == null -> otherStart >= start && otherStart < start.plusMinutes(minutes.toLong())
        else -> start < otherStart.plusMinutes(otherMinutes.toLong()) && otherStart < start.plusMinutes(minutes.toLong())
    }
}

fun overlappingEvents(events: List<ItineraryItem>, dates: List<LocalDate>, time: LocalTime?, minutes: Int?, excluded: Set<Long>, before: Int = 0, after: Int = 0): List<ItineraryItem> {
    if (time == null) return emptyList()
    return events.filter { other ->
        other.category != "Bills" && !other.skipped && other.id !in excluded && other.startTime != null && dates.any { date ->
            overlaps(date.atTime(time), minutes, other.date.atTime(other.startTime), other.durationMinutes) ||
            overlaps(date.atTime(time).minusMinutes(before.toLong()), bufferedDuration(minutes, before, after),
                other.date.atTime(other.startTime).minusMinutes(other.bufferBeforeMinutes.toLong()),
                bufferedDuration(other.durationMinutes, other.bufferBeforeMinutes, other.bufferAfterMinutes))
        }
    }
}

// Include overnight events that extend into this day, but not ones ending exactly at midnight.
fun eventsOnDay(events: List<ItineraryItem>, day: LocalDate): List<ItineraryItem> = events.filter {
    it.date == day || it.category != "Bills" && it.date < day && it.startTime != null && it.durationMinutes != null &&
        it.date.atTime(it.startTime).plusMinutes(it.durationMinutes.toLong()) > day.atStartOfDay()
}.sortedWith(compareBy<ItineraryItem> { it.startTime != null }.thenBy { it.date }.thenBy { it.startTime }.thenBy { it.id })

private fun bufferedDuration(minutes: Int?, before: Int, after: Int): Int? =
    if (minutes == null && before == 0 && after == 0) null else (minutes ?: 0) + before + after
