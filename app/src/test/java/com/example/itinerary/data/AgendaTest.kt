package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class AgendaTest {
    private val today = LocalDate.of(2026, 9, 24)

    private fun event(id: Long, tripId: Long, date: LocalDate, time: LocalTime?, title: String) =
        PlanEvent(tripId, id, date, time, title, colorIndex = 0, customColor = null)

    // Deliberately out of order, with plans whose manual order is the reverse of their dates.
    private val events = listOf(
        event(5, 2, today, LocalTime.of(9, 0), "Nine"),
        event(1, 1, today.minusDays(3), null, "Past"),
        event(4, 1, today, LocalTime.of(9, 0), "Also nine"),
        event(3, 2, today, null, "All day B"),
        event(2, 1, today, null, "All day A"),
        event(6, 1, today.plusDays(1), LocalTime.of(8, 0), "Tomorrow early"),
        event(7, 2, today.plusDays(10), null, "Café visit"),
    )

    private fun titles(days: List<AgendaDay>) = days.flatMap { d -> d.entries.map { it.event.title } }

    @Test fun upcomingIsTodayAndLaterInChronologicalOrder() {
        val days = Agenda.days(events, AgendaRange.UPCOMING, "", today)
        assertEquals(listOf(today, today.plusDays(1), today.plusDays(10)), days.map { it.date })
        // All-day first, then by time; equal times and all-day ties by id, whatever the plan.
        assertEquals(
            listOf("All day A", "All day B", "Also nine", "Nine", "Tomorrow early", "Café visit"),
            titles(days),
        )
    }

    @Test fun todayOnlyAndAllIncludesPast() {
        assertEquals(listOf(today), Agenda.days(events, AgendaRange.TODAY, "", today).map { it.date })
        val all = Agenda.days(events, AgendaRange.ALL, "", today)
        assertEquals("Past", titles(all).first())
        assertEquals(7, titles(all).size)
    }

    @Test fun filterMatchesOnlyEventTitleIgnoringCaseAndAccents() {
        assertEquals(listOf("Café visit"), titles(Agenda.days(events, AgendaRange.ALL, "CAFE", today)))
        // Hidden legacy plan names must not produce matches.
        assertEquals(
            emptyList<String>(),
            titles(Agenda.days(events, AgendaRange.ALL, "  zurich ", today)),
        )
        assertEquals(emptyList<String>(), titles(Agenda.days(events, AgendaRange.ALL, "nothing", today)))
    }

    @Test fun todayIncludesOvernightButNotMidnightEnd() {
        val overnight = event(10, 1, today.minusDays(1), LocalTime.of(23, 30), "Overnight").copy(durationMinutes = 120)
        val midnight = overnight.copy(id = 11, title = "Midnight", durationMinutes = 30)
        for (range in listOf(AgendaRange.TODAY, AgendaRange.THIS_WEEK, AgendaRange.UPCOMING)) {
            assertEquals(listOf("Overnight"), titles(Agenda.days(listOf(overnight, midnight), range, "", today)))
        }
    }

    @Test fun overnightFromYesterdayIsListedUnderTodayAsContinuing() {
        val overnight = event(10, 1, today.minusDays(1), LocalTime.of(23, 30), "Overnight").copy(durationMinutes = 120)
        val later = event(12, 1, today, LocalTime.of(9, 0), "Later")
        for (range in listOf(AgendaRange.TODAY, AgendaRange.THIS_WEEK, AgendaRange.UPCOMING)) {
            val days = Agenda.days(listOf(later, overnight), range, "", today)
            // Once, under Today and first, like a multi-day event under way; not under yesterday's heading.
            assertEquals(range.name, listOf(today), days.map { it.date })
            assertEquals(range.name, listOf("Overnight" to true, "Later" to false), days.single().entries.map { it.event.title to it.continuing })
        }
        // All keeps it under its own day too, as it does a multi-day event.
        val all = Agenda.days(listOf(later, overnight), AgendaRange.ALL, "", today)
        assertEquals(listOf(today.minusDays(1), today), all.map { it.date })
        assertEquals(listOf("Overnight" to false), all.first().entries.map { it.event.title to it.continuing })
        assertEquals(listOf("Overnight" to true, "Later" to false), all.last().entries.map { it.event.title to it.continuing })
        // The text filter applies; once it has ended it is gone from Today.
        assertTrue(Agenda.days(listOf(overnight), AgendaRange.TODAY, "later", today).isEmpty())
        assertTrue(Agenda.days(listOf(overnight), AgendaRange.TODAY, "", today.plusDays(1)).isEmpty())
    }

    @Test fun thisWeekIncludesTodayThroughSundayAndAppliesTitleFilter() {
        val friday = LocalDate.of(2026, 9, 25)
        val rows = listOf(
            event(1, 1, friday.minusDays(1), null, "Earlier"),
            event(2, 1, friday, null, "Café today"),
            event(3, 1, friday.plusDays(2), null, "Café Sunday"),
            event(4, 1, friday.plusDays(3), null, "Café Monday"),
        )
        assertEquals(listOf("Café today", "Café Sunday"), titles(Agenda.days(rows, AgendaRange.THIS_WEEK, "", friday)))
        assertEquals(listOf("Café Sunday"), titles(Agenda.days(rows, AgendaRange.THIS_WEEK, "CAFE", friday.plusDays(2))))
        assertEquals(listOf("Café Monday"), titles(Agenda.days(rows, AgendaRange.THIS_WEEK, "", friday.plusDays(3))))
    }

    @Test fun thisWeekHandlesYearBoundaryAndKeepsNormalEventStatuses() {
        val date = LocalDate.of(2026, 12, 31)
        val rows = listOf(event(1, 1, date, null, "Paid").copy(category = "Bills", paid = true),
            event(2, 1, LocalDate.of(2027, 1, 3), null, "Skipped").copy(skipped = true),
            event(3, 1, LocalDate.of(2027, 1, 4), null, "Next week"))
        assertEquals(listOf("Skipped"), titles(Agenda.days(rows, AgendaRange.THIS_WEEK, "", date)))
    }
}
