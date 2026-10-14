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

    // The week row Monday 5 to Sunday 11 October 2026.
    private val week = List(7) { oct5.plusDays(it.toLong()) }
    // Each piece as "title start-end@line", with ( and ) for a rounded start and end, [ and ] where it carries on.
    private fun pieces(lines: WeekLines) = lines.pieces.sortedWith(compareBy({ it.line }, { it.start })).map {
        "${if (it.opens) "(" else "["}${it.chip.title} ${it.start}-${it.end}@${it.line}${if (it.closes) ")" else "]"}"
    }

    @Test fun anEventOnSeveralDaysIsOneBarAndTheOthersFillTheGaps() {
        val chips = CalendarChips.byDate(listOf(
            event(1, "Trip", date = oct5.plusDays(1), end = oct5.plusDays(3)),
            event(2, "Course", date = oct5.plusDays(2), end = oct5.plusDays(5)),
            event(3, "Dentist", time = LocalTime.of(9, 0)),
            event(4, "Gym", date = oct5.plusDays(5), time = LocalTime.of(7, 0)),
            event(5, "Lunch", date = oct5.plusDays(4), time = LocalTime.of(12, 0)),
            // Past midnight, so a bar over two days too (user, 14 Oct: "a").
            event(6, "Party", date = oct5.plusDays(5), time = LocalTime.of(22, 0), minutes = 180),
        ), listOf(PlannerTask(id = "t", title = "Tax", dueDate = oct5.plusDays(1))))
        val lines = CalendarChips.weekLines(week, chips, slots = 4)
        assertEquals(listOf(
            "(Dentist 0-0@0)", "(Trip 1-3@0)", "(Lunch 4-4@0)", "(Party 5-6@0)",
            "(Tax 1-1@1)", "(Course 2-5@1)",
            // Tax and Lunch take the gaps on lines 1 and 0; on Saturday lines 0 and 1 are the party's and the course's.
            "(Gym 5-5@2)",
        ), pieces(lines))
        assertEquals(List(7) { 0 }, lines.more)
    }

    @Test fun aBarCarriesOnIntoTheNextRowAndPastTheMonth() {
        val chips = CalendarChips.byDate(listOf(event(1, "Holiday", date = oct5.minusDays(2), end = oct5.plusDays(9))), emptyList())
        assertEquals(listOf("[Holiday 0-6@0]"), pieces(CalendarChips.weekLines(week, chips, slots = 3)))
        assertEquals(listOf("[Holiday 0-2@0)"), pieces(CalendarChips.weekLines(week.map { it.plusWeeks(1) }, chips, slots = 3)))
        assertEquals(listOf("(Holiday 5-6@0]"), pieces(CalendarChips.weekLines(week.map { it.minusWeeks(1) }, chips, slots = 3)))
        // The blanks around a month: the first row of November (Sunday 1 November at the end) with a bar from October.
        val novStart = List(6) { null } + LocalDate.of(2026, 11, 1)
        val late = CalendarChips.byDate(listOf(event(2, "Late", date = LocalDate.of(2026, 10, 30), end = LocalDate.of(2026, 11, 2))), emptyList())
        assertEquals(listOf("[Late 6-6@0]"), pieces(CalendarChips.weekLines(novStart, late, slots = 3)))
    }

    @Test fun whatDoesNotFitBecomesPlusNAndCutsABarThere() {
        val chips = CalendarChips.byDate(listOf(
            event(1, "Trip", date = oct5, end = oct5.plusDays(2)),
            event(2, "Course", date = oct5, end = oct5.plusDays(2)),
        ) + (3L..5L).map { event(it, "Meeting $it", date = oct5.plusDays(1), time = LocalTime.of(it.toInt() + 6, 0)) }, emptyList())
        val lines = CalendarChips.weekLines(week, chips, slots = 2)
        // Tuesday has 5 entries in 2 lines: the trip on the first, "+4" on the second, which cuts the course there.
        assertEquals(listOf("(Trip 0-2@0)", "(Course 0-0@1]", "[Course 2-2@1)"), pieces(lines))
        assertEquals(listOf(0, 4, 0, 0, 0, 0, 0), lines.more)
        assertEquals(1, lines.moreLine)
        // Everything fits in 5 lines: no "+N".
        assertEquals(List(7) { 0 }, CalendarChips.weekLines(week, chips, slots = 5).more)
        // No room at all: nothing laid out, the days show a dot.
        assertEquals(emptyList<String>(), pieces(CalendarChips.weekLines(week, chips, slots = 0)))
    }
}
