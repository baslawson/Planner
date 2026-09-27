package com.example.itinerary.data

import java.time.LocalDate

// Each plan on "My plans" is shown as its events rather than as a date range, so the card has to choose which
// few of them to draw. The question it answers is "what is next in this plan", so it shows the first events
// that have not happened yet; a plan that is entirely in the past has nothing ahead, so its most recent events
// are shown instead. Pure list work, kept out of the composable so it can be reasoned about on its own.
object PlanSummary {
    // How many events fit on a card before it starts to crowd the list.
    const val MAX_EVENTS = 3

    // [events] must already be in date then time order, as ItemDao.observePlanEvents returns them.
    fun visible(events: List<PlanEvent>, today: LocalDate, max: Int = MAX_EVENTS): List<PlanEvent> {
        if (events.size <= max) return events
        val firstAhead = events.indexOfFirst { !it.date.isBefore(today) }
        // Nothing ahead, or too few ahead to fill the card: end on the last event rather than leave a gap.
        if (firstAhead < 0 || firstAhead > events.size - max) return events.takeLast(max)
        return events.subList(firstAhead, firstAhead + max).toList()
    }

    // The day headings the card draws: an event carries its date only when it is the first shown on that day.
    fun withDayBreaks(events: List<PlanEvent>): List<Pair<LocalDate?, PlanEvent>> {
        var previous: LocalDate? = null
        return events.map { event ->
            val heading = event.date.takeIf { it != previous }
            previous = event.date
            heading to event
        }
    }
}
