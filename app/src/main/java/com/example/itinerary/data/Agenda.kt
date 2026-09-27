package com.example.itinerary.data

import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

// Which days the agenda shows. Upcoming (today and later) is the default; past events are reached through All.
enum class AgendaRange(val label: String) {
    TODAY("Today"),
    THIS_WEEK("This week"),
    UPCOMING("Upcoming"),
    ALL("All"),
}

// One event card on the agenda.
data class AgendaEntry(val event: PlanEvent)

// One day on the agenda: its date and every event on it, in order.
data class AgendaDay(val date: LocalDate, val entries: List<AgendaEntry>)

// Chronological event grouping, independent of legacy storage owners.
object Agenda {
    // Day, then all-day events before timed ones, then time, then the event's id so equal times never swap places.
    private val order: Comparator<PlanEvent> =
        compareBy<PlanEvent> { it.date }
            .thenBy { it.startTime != null }
            .thenBy { it.startTime }
            .thenBy { it.id }

    // Filter event titles, ignoring capitals and accents.
    fun days(
        events: List<PlanEvent>,
        range: AgendaRange,
        text: String,
        today: LocalDate,
    ): List<AgendaDay> {
        val needle = Search.normalize(text.trim())
        val weekEnd = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        return events.asSequence()
            .filter { it.category != "Bills" }
            .filter { event ->
                val continuesToday = event.date < today && event.startTime != null && event.durationMinutes != null &&
                    event.date.atTime(event.startTime).plusMinutes(event.durationMinutes.toLong()) > today.atStartOfDay()
                when (range) {
                    AgendaRange.TODAY -> event.date == today || continuesToday
                    AgendaRange.THIS_WEEK -> event.date in today..weekEnd || continuesToday
                    AgendaRange.UPCOMING -> !event.date.isBefore(today) || continuesToday
                    AgendaRange.ALL -> true
                }
            }
            .filter { event ->
                needle.isEmpty() ||
                    Search.normalize(event.title).contains(needle)
            }
            .sortedWith(order)
            .map { AgendaEntry(it) }
            .groupBy { it.event.date }
            .map { (date, entries) -> AgendaDay(date, entries) }
    }

}
