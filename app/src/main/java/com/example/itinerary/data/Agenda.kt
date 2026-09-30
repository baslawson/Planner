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

// One event card on the agenda. [continuing] marks an event shown under Today while it is under way: a multi-day one
// ("Day 3 of 5") or a timed one from yesterday running past midnight; All also lists it in full under its first day.
data class AgendaEntry(val event: PlanEvent, val continuing: Boolean = false)

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
        val matches = { event: PlanEvent -> needle.isEmpty() || Search.normalize(event.title).contains(needle) }
        val listed = events.asSequence()
            .filter { it.category != "Bills" }
            .filter { event ->
                when (range) {
                    AgendaRange.TODAY -> event.date == today
                    AgendaRange.THIS_WEEK -> event.date in today..weekEnd
                    AgendaRange.UPCOMING -> !event.date.isBefore(today)
                    AgendaRange.ALL -> true
                }
            }
            .filter(matches)
            .sortedWith(order)
            .map { AgendaEntry(it) }
            .toList()
        // An event that started before today and is still under way also shows under Today, first: a multi-day one, or a
        // timed one running past midnight ("Started yesterday").
        val underWay = events.filter { event ->
            event.category != "Bills" && event.date < today && matches(event) && (event.endDate != null && event.covers(today) ||
                event.endDate == null && event.startTime != null && event.durationMinutes != null &&
                event.date.atTime(event.startTime).plusMinutes(event.durationMinutes.toLong()) > today.atStartOfDay())
        }.sortedWith(order).map { AgendaEntry(it, continuing = true) }
        val byDay = listed.groupBy { it.event.date }.toMutableMap()
        if (underWay.isNotEmpty()) byDay[today] = underWay + byDay[today].orEmpty()
        return byDay.toSortedMap().map { (date, entries) -> AgendaDay(date, entries) }
    }

}
