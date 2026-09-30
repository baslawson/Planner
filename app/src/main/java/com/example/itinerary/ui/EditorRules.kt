package com.example.itinerary.ui

import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.MultiDay
import com.example.itinerary.data.RepeatRule
import com.example.itinerary.data.eventsOnDay
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

// Pure rules behind the event editor, kept here so they can be unit tested without Compose.
object EditorRules {
    const val DEFAULT_REPEAT_COUNT = 12

    // The held end date counts only while it makes a span Save would accept; a longer one (e.g. after picking a
    // far earlier start) is dropped rather than shown and then refused.
    fun spanEnd(date: LocalDate, endDate: LocalDate?, time: LocalTime?, category: String): LocalDate? =
        endDate?.takeIf { MultiDay.valid(date, it, time, category) }

    // Moving the start from the single-date picker carries a held span along, so its length is kept.
    fun movedEndDate(oldDate: LocalDate, newDate: LocalDate, endDate: LocalDate?): LocalDate? =
        endDate?.let { if (it > oldDate) it.plusDays(ChronoUnit.DAYS.between(oldDate, newDate)) else it }

    // A template that doesn't repeat stores a count of 1; the field keeps a usable count for a repeat picked later.
    fun templateCount(repeat: RepeatRule, count: Int, current: String): String =
        if (repeat != RepeatRule.NONE) count.toString() else current.takeIf { it.toIntOrNull() in 2..365 } ?: DEFAULT_REPEAT_COUNT.toString()

    // Choosing a repeat while the count is unusable starts from the default instead of an error.
    fun countForRepeat(repeat: RepeatRule, current: String): String =
        if (repeat == RepeatRule.NONE || current.toIntOrNull() in 2..365) current else DEFAULT_REPEAT_COUNT.toString()

    // Repository checks give readable reasons; anything else gets the generic retry text.
    fun saveError(e: Exception, billTask: Boolean): String =
        (e as? com.example.itinerary.data.PaymentUpdateException)?.message
            ?: (e as? IllegalArgumentException)?.message?.takeIf { it.isNotBlank() && it != "Failed requirement." }
            ?: "Couldn't save the ${if (billTask) "bill" else "event"}. Your changes are still here; try again."

    // After a save, the calendar stays put when the event still shows on the chosen day (a later day of a trip, or
    // the morning after an overnight event); otherwise it follows the event to its first day.
    fun followDate(item: ItineraryItem, selected: LocalDate): LocalDate? =
        item.date.takeIf { eventsOnDay(listOf(item), selected).isEmpty() }
}
