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

// One event card on the agenda. [continuing] marks a multi-day event shown again under Today while it is under way
// ("Day 3 of 5"); it is listed in full under its first day.
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
        val listed = events.asSequence()
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
            .toList()
        // A multi-day event that started before today and is still under way also shows under Today, first.
        val underWay = events.filter { event ->
            event.category != "Bills" && event.endDate != null && event.date < today && event.covers(today) &&
                (needle.isEmpty() || Search.normalize(event.title).contains(needle))
        }.sortedWith(order).map { AgendaEntry(it, continuing = true) }
        val byDay = listed.groupBy { it.event.date }.toMutableMap()
        if (underWay.isNotEmpty()) byDay[today] = underWay + byDay[today].orEmpty()
        return byDay.toSortedMap().map { (date, entries) -> AgendaDay(date, entries) }
    }

}
