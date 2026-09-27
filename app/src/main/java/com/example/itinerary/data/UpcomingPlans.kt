package com.example.itinerary.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Plans that start soon (or are already under way) can be shown at the top of "My plans". This only changes what is
// shown: the order the user set by dragging (Trip.sortOrder) is never touched, so a plan drops back to its own place
// once its window has passed.
object UpcomingPlans {
    const val DEFAULT_DAYS = 7
    const val MIN_DAYS = 1
    const val MAX_DAYS = 365

    // The ready-made choices in Settings; anything else is a custom number of days.
    val PRESET_DAYS = listOf(3, 7, 14, 30)

    // Under way today, or starting within the next [days] days (today counts as day 0).
    fun isSoon(trip: Trip, today: LocalDate, days: Int): Boolean =
        !trip.endDate.isBefore(today) && !trip.startDate.isAfter(today.plusDays(days.toLong()))

    // [manual] is the user's own order. Plans that are soon come first, earliest start first (a plan already under
    // way starts earliest, so it leads); ties and all the other plans keep their manual order (sortedBy is stable).
    fun arrange(manual: List<Trip>, today: LocalDate, enabled: Boolean, days: Int): List<Trip> {
        if (!enabled) return manual
        val (soon, rest) = manual.partition { isSoon(it, today, days) }
        return soon.sortedBy { it.startDate } + rest
    }

    // A short reason for a plan being at the top, for its row.
    fun caption(trip: Trip, today: LocalDate): String {
        val until = ChronoUnit.DAYS.between(today, trip.startDate)
        return when {
            until < 0 -> "In progress"
            until == 0L -> "Starts today"
            until == 1L -> "Starts tomorrow"
            else -> "Starts in $until days"
        }
    }
}
