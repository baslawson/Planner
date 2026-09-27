package com.example.itinerary.data

import java.time.LocalDate
import java.time.LocalTime

enum class RepeatRule(val label: String) {
    NONE("Does not repeat"), DAILY("Daily"), WEEKLY("Weekly"), FORTNIGHTLY("Fortnightly"), MONTHLY("Monthly"), YEARLY("Yearly");

    // Always advance from the original date: Jan 31 → Feb 28 → Mar 31, without month-end drift.
    fun dates(start: LocalDate, count: Int): List<LocalDate> {
        require(count in 1..365) { "Choose between 1 and 365 occurrences" }
        return List(if (this == NONE) 1 else count) { index ->
            when (this) {
                NONE -> start
                DAILY -> start.plusDays(index.toLong())
                WEEKLY -> start.plusWeeks(index.toLong())
                FORTNIGHTLY -> start.plusWeeks(index.toLong() * 2)
                MONTHLY -> start.plusMonths(index.toLong())
                YEARLY -> start.plusYears(index.toLong())
            }
        }
    }
}

data class EventSaveOptions(
    val repeat: RepeatRule = RepeatRule.NONE,
    val count: Int = 1,
    val entireSeries: Boolean = false,
    val changeRepeat: Boolean = false,
    val draftToken: String? = null,
    val paymentBaseline: PaymentState? = null,
)

fun sameTimeEvents(events: List<PlanEvent>, dates: List<LocalDate>, time: LocalTime?, excludedIds: Set<Long>): List<PlanEvent> {
    if (time == null) return emptyList()
    val days = dates.toHashSet()
    return events.filter { it.id !in excludedIds && it.date in days && it.startTime == time }
}

fun millisUntilNextDay(now: java.time.ZonedDateTime): Long =
    java.time.Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone))
        .toMillis().coerceAtLeast(1)
