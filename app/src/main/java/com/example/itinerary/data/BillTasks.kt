package com.example.itinerary.data

import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

/** Bills retain their existing records/ledgers but follow task completion and due-date rules. */
object BillTasks {
    fun visible(bills: List<PlanEvent>, range: AgendaRange, query: String, today: LocalDate,
                showCompleted: Boolean): List<PlanEvent> {
        val needle = Search.normalize(query.trim())
        val weekEnd = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        return bills.filter { bill ->
            val complete = bill.paid || bill.skipped
            bill.category == "Bills" && (showCompleted || !complete) &&
                (needle.isEmpty() || Search.normalize(bill.title).contains(needle)) &&
                (when (range) {
                    AgendaRange.ALL -> true
                    AgendaRange.TODAY -> bill.date <= today
                    AgendaRange.THIS_WEEK -> bill.date <= weekEnd
                    AgendaRange.UPCOMING -> true
                })
        }.sortedWith(compareBy<PlanEvent> { it.date }.thenBy { it.paid || it.skipped }
            .thenBy { it.startTime }.thenBy { it.id })
    }
}

fun ItineraryItem.billTaskSummary() = PlanEvent(tripId, id, date, startTime, title, colorIndex, customColor,
    durationMinutes, location, checklist, category, paid, billAmountMinor, billCurrency, skipped, seriesId, payments)
