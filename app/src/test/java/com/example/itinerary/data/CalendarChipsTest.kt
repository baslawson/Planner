package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

// The entries the maximised month grid writes in each day.
class CalendarChipsTest {
    private val oct5 = LocalDate.of(2026, 10, 5)
    private fun event(id: Long, title: String, date: LocalDate = oct5, time: LocalTime? = null, minutes: Int? = null,
                      end: LocalDate? = null, category: String = Categories.OTHER, paid: Boolean = false, skipped: Boolean = false) =
        ItineraryItem(id = id, tripId = 1, date = date, startTime = time, title = title, durationMinutes = minutes,
            endDate = end, category = category, paid = paid, skipped = skipped)

    private fun titles(map: Map<LocalDate, List<DayChip>>, day: LocalDate) = map[day]?.map { it.title }

    @Test fun eventsGoOnTheDaysTheCalendarListsThemInItsOrder() {
        val events = listOf(
            event(1, "Lunch", time = LocalTime.of(12, 0)),
            event(2, "Breakfast", time = LocalTime.of(8, 0)),
            event(3, "Holiday", date = oct5.minusDays(1), end = oct5.plusDays(1)),
            event(4, "Party", date = oct5.plusDays(1), time = LocalTime.of(22, 0), minutes = 180),
            event(5, "Late show", date = oct5.plusDays(2), time = LocalTime.of(22, 0), minutes = 120),
        )
        val chips = CalendarChips.byDate(events, emptyList())
        assertEquals(listOf("Holiday", "Breakfast", "Lunch"), titles(chips, oct5))
        // The party runs past midnight, so it shows the morning after too; the show ends at midnight exactly, so it doesn't.
        assertEquals(listOf("Holiday", "Party"), titles(chips, oct5.plusDays(1)))
        assertEquals(listOf("Party", "Late show"), titles(chips, oct5.plusDays(2)))
        assertNull(chips[oct5.plusDays(3)])
        // The same days as the day's list, for every day touched.
        for (day in chips.keys) assertEquals(eventsOnDay(events, day).map { it.title }, titles(chips, day))
    }

    @Test fun tasksDueThatDayFollowTheEventsMostImportantFirstAndDoneOnesAreLeftOut() {
        val tasks = listOf(
            PlannerTask(id = "a", title = "Water plants", dueDate = oct5),
            PlannerTask(id = "b", title = "Tax return", dueDate = oct5, priority = TaskPriority.HIGH),
            PlannerTask(id = "c", title = "Done already", dueDate = oct5, done = true),
            PlannerTask(id = "d", title = "Someday"),
            PlannerTask(id = "e", title = "", dueDate = oct5.plusDays(1)),
        )
        val chips = CalendarChips.byDate(listOf(event(1, "Dentist", time = LocalTime.of(9, 0))), tasks)
        assertEquals(listOf("Dentist", "Tax return", "Water plants"), titles(chips, oct5))
        assertEquals(listOf(false, true, true), chips[oct5]!!.map { it.task })
        assertEquals(listOf("Untitled task"), titles(chips, oct5.plusDays(1)))
        assertEquals(2, chips.size)
    }

    // The day's list: every task due that day, still to do first (most important first), done ones last.
    @Test fun theDaysListHasItsTasksWithDoneOnesLast() {
        val tasks = listOf(
            PlannerTask(id = "a", title = "Water plants", dueDate = oct5),
            PlannerTask(id = "b", title = "Tax return", dueDate = oct5, priority = TaskPriority.HIGH),
            PlannerTask(id = "c", title = "Already done", dueDate = oct5, done = true, priority = TaskPriority.HIGH),
            PlannerTask(id = "d", title = "Another day", dueDate = oct5.plusDays(1)),
            PlannerTask(id = "e", title = "Someday"),
        )
        assertEquals(listOf("Tax return", "Water plants", "Already done"), CalendarChips.tasksOnDay(tasks, oct5).map { it.title })
        assertEquals(emptyList<PlannerTask>(), CalendarChips.tasksOnDay(tasks, oct5.minusDays(1)))
    }

    @Test fun paidBillsAndSkippedDatesAreFaded() {
        val chips = CalendarChips.byDate(listOf(
            event(1, "Rent", category = "Bills", paid = true),
            event(2, "Power", category = "Bills"),
            event(3, "Gym", time = LocalTime.of(7, 0), skipped = true),
        ), emptyList())[oct5]!!
        assertEquals(mapOf("Rent" to true, "Power" to false, "Gym" to true), chips.associate { it.title to it.faded })
    }

    @Test fun whatDoesNotFitBecomesPlusN() {
        assertEquals(3 to 0, CalendarChips.fit(3, 3))
        assertEquals(2 to 0, CalendarChips.fit(2, 4))
        // Four entries in three lines: two shown, "+2" on the third.
        assertEquals(2 to 2, CalendarChips.fit(4, 3))
        assertEquals(0 to 5, CalendarChips.fit(5, 1))
        assertEquals(0 to 2, CalendarChips.fit(2, 0))
        assertEquals(0 to 0, CalendarChips.fit(0, 0))
    }
}
