package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// Where a multi-day event shows: agenda (first day + "under way" under Today), a day's list, free time and search.
class MultiDayViewsTest {
    private val oct3 = LocalDate.of(2026, 10, 3)
    private val oct7 = LocalDate.of(2026, 10, 7)
    private fun plan(id: Long, date: LocalDate, title: String, end: LocalDate? = null) =
        PlanEvent(tripId = 1, id = id, date = date, startTime = null, title = title, colorIndex = 0, customColor = null, endDate = end)
    private val trip = plan(1, oct3, "Trip", oct7)
    private val lunch = plan(2, LocalDate.of(2026, 10, 5), "Lunch")

    @Test fun beforeItStartsTheTripIsListedOnceOnItsFirstDay() {
        val days = Agenda.days(listOf(trip, lunch), AgendaRange.UPCOMING, "", LocalDate.of(2026, 10, 1))
        assertEquals(listOf(oct3, LocalDate.of(2026, 10, 5)), days.map { it.date })
        assertEquals(listOf(false), days.first().entries.map { it.continuing })
    }

    @Test fun whileUnderWayItIsShownFirstUnderToday() {
        val today = LocalDate.of(2026, 10, 5)
        for (range in listOf(AgendaRange.TODAY, AgendaRange.THIS_WEEK, AgendaRange.UPCOMING)) {
            val days = Agenda.days(listOf(trip, lunch), range, "", today)
            assertEquals(range.name, today, days.first().date)
            assertEquals(range.name, listOf("Trip" to true, "Lunch" to false), days.first().entries.map { it.event.title to it.continuing })
        }
        // All also keeps it under its first day, so it appears twice.
        val all = Agenda.days(listOf(trip, lunch), AgendaRange.ALL, "", today)
        assertEquals(listOf(oct3, today), all.map { it.date })
        assertEquals(listOf(false), all.first().entries.map { it.continuing })
        // The day after it ends, it is gone from Today.
        assertTrue(Agenda.days(listOf(trip), AgendaRange.UPCOMING, "", oct7.plusDays(1)).isEmpty())
        // The text filter applies to it too.
        assertTrue(Agenda.days(listOf(trip), AgendaRange.TODAY, "lunch", today).isEmpty())
    }

    @Test fun aDaysListIncludesEveryDayOfTheSpan() {
        val item = ItineraryItem(id = 1, tripId = 1, date = oct3, startTime = null, title = "Trip", endDate = oct7)
        assertEquals(listOf(false, true, true, true, true, true, false),
            (2..8).map { eventsOnDay(listOf(item), LocalDate.of(2026, 10, it)).isNotEmpty() })
    }

    @Test fun freeTimeTreatsEveryDayOfTheSpanAsBusy() {
        val item = ItineraryItem(id = 1, tripId = 1, date = oct3, startTime = null, title = "Trip", endDate = oct7)
        val free = FreeTime.find(listOf(item), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 8), LocalTime.of(9, 0),
            LocalTime.of(17, 0), 60, false, true, 60, LocalDateTime.of(2026, 10, 1, 8, 0))
        assertEquals(setOf(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 8)), free.map { it.start.toLocalDate() }.toSet())
    }

    @Test fun aCalendarFileCarriesTheWholeSpan() {
        val item = ItineraryItem(id = 1, tripId = 1, date = oct3, startTime = null, title = "Trip", endDate = oct7)
        val text = CalendarExport.encode(item, "trip-1@planner")
        assertTrue(text.contains("DTSTART;VALUE=DATE:20261003\r\nDTEND;VALUE=DATE:20261008\r\n")) // end is exclusive
        assertEquals(oct7, CalendarFileImport.read(text).entries.single().item.endDate) // and it comes back as one event
    }

    @Test fun aDateSearchFindsATripOnAnyOfItsDays() {
        val plans = listOf(Trip(id = 1, name = "Agenda", destination = "", startDate = oct3, endDate = oct3))
        val item = ItineraryItem(id = 1, tripId = 1, date = oct3, startTime = null, title = "Trip", endDate = oct7)
        assertEquals(listOf(1L), Search.run("5 october", emptySet(), plans, listOf(item), emptyList(), oct3).hits.map { it.item.id })
        assertTrue(Search.run("9 october", emptySet(), plans, listOf(item), emptyList(), oct3).hits.isEmpty())
    }
}
