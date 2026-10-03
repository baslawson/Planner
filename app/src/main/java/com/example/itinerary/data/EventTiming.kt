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
    // UI-6: a series of a year against thousands of events was every date against every event. Each event is now tried
    // only against the planned dates that could reach it (below); the test itself is unchanged, so the result is too.
    val planned = java.util.TreeSet(dates)
    val buffered = bufferedDuration(minutes, before, after)
    return events.filter { other ->
        other.category != "Bills" && !other.skipped && other.id !in excluded && other.startTime != null &&
            reachableDates(planned, time, minutes, before, buffered, other).any { date ->
            overlaps(date.atTime(time), minutes, other.date.atTime(other.startTime), other.durationMinutes) ||
            overlaps(date.atTime(time).minusMinutes(before.toLong()), buffered,
                other.date.atTime(other.startTime).minusMinutes(other.bufferBeforeMinutes.toLong()),
                bufferedDuration(other.durationMinutes, other.bufferBeforeMinutes, other.bufferAfterMinutes))
        }
    }
}

// The planned dates whose start (P) could overlap [other] (start O). overlaps(s1, d1, s2, d2) can only hold when
// s1 - s2 lies in [-d1, d2] (a missing duration counting as 0), whichever durations are missing. So the plain test
// needs P - O in [-minutes, other's minutes], and the buffered one, with s1 = P - before and s2 = O - other's before,
// needs P - O in [before - otherBefore - buffered, before - otherBefore + other's buffered]. Any date whose P falls
// outside both ranges fails both tests; the dates inside still get the exact test.
private fun reachableDates(planned: java.util.TreeSet<LocalDate>, time: LocalTime, minutes: Int?, before: Int, buffered: Int?,
                           other: ItineraryItem): Set<LocalDate> {
    val start = other.date.atTime(other.startTime ?: return emptySet())
    val otherBuffered = bufferedDuration(other.durationMinutes, other.bufferBeforeMinutes, other.bufferAfterMinutes)
    val shift = before.toLong() - other.bufferBeforeMinutes
    val low = minOf(-(minutes ?: 0).toLong(), shift - (buffered ?: 0))
    val high = maxOf((other.durationMinutes ?: 0).toLong(), shift + (otherBuffered ?: 0))
    if (low > high || planned.isEmpty()) return emptySet()
    // P = date + time, so P in [start + low, start + high] puts the date between these two (the first rounded down).
    val first = start.plusMinutes(low).minusNanos(time.toNanoOfDay()).toLocalDate()
    val last = start.plusMinutes(high).minusNanos(time.toNanoOfDay()).toLocalDate()
    return if (first > last) emptySet() else planned.subSet(first, true, last, true)
}

// Include overnight events that extend into this day, but not ones ending exactly at midnight, and every day of a
// multi-day all-day event.
fun eventsOnDay(events: List<ItineraryItem>, day: LocalDate): List<ItineraryItem> = events.filter {
    it.date == day || it.endDate != null && it.covers(day) ||
        it.category != "Bills" && it.date < day && it.startTime != null && it.durationMinutes != null &&
        it.date.atTime(it.startTime).plusMinutes(it.durationMinutes.toLong()) > day.atStartOfDay()
}.sortedWith(compareBy<ItineraryItem> { it.startTime != null }.thenBy { it.date }.thenBy { it.startTime }.thenBy { it.id })

// The home-screen widget's day (U-N6): like the Agenda (BillTasks.visible), a paid or skipped bill is done, so it is
// neither listed as "Bill due" nor counted; a skipped date isn't either.
fun widgetEventsOnDay(events: List<ItineraryItem>, day: LocalDate): List<ItineraryItem> =
    eventsOnDay(events.filterNot { it.skipped || it.category == "Bills" && it.paid }, day)

private fun bufferedDuration(minutes: Int?, before: Int, after: Int): Int? =
    if (minutes == null && before == 0 && after == 0) null else (minutes ?: 0) + before + after
