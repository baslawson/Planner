package com.example.itinerary.data

import java.time.LocalDate

/**
 * One small entry in a day of the maximised month grid: an event or bill (coloured as its card), or a task due that
 * day. [faded] marks what is already dealt with: a paid bill or a skipped date.
 */
data class DayChip(
    val key: String,
    val title: String,
    val task: Boolean = false,
    val colorIndex: Int = 0,
    val customColor: Int? = null,
    val faded: Boolean = false,
)

object CalendarChips {
    /**
     * Every day's entries for the maximised month grid. Events go on the same days the Calendar lists them
     * (eventsOnDay: each day of a multi-day event, the morning after an overnight one), in the same order; then the
     * tasks due that day that aren't done, the most important first.
     */
    fun byDate(events: List<ItineraryItem>, tasks: List<PlannerTask>): Map<LocalDate, List<DayChip>> {
        val eventsByDay = HashMap<LocalDate, MutableList<ItineraryItem>>()
        events.forEach { event ->
            daysOf(event).forEach { eventsByDay.getOrPut(it) { mutableListOf() }.add(event) }
        }
        val tasksByDay = tasks.filter { !it.done && it.dueDate != null }.groupBy { it.dueDate!! }
        return (eventsByDay.keys + tasksByDay.keys).associateWith { day ->
            eventsByDay[day].orEmpty().sortedWith(DAY_ORDER).map { it.chip() } +
                tasksByDay[day].orEmpty().sortedWith(TASK_ORDER)
                    .map { DayChip("task:${it.id}", it.title.ifBlank { "Untitled task" }, task = true) }
        }
    }

    /** The tasks due on [day] for the Calendar's day list: still to do first (the most important first), then done ones. */
    fun tasksOnDay(tasks: List<PlannerTask>, day: LocalDate): List<PlannerTask> =
        tasks.filter { it.dueDate == day }.sortedWith(compareBy<PlannerTask> { it.done }.then(TASK_ORDER))

    /**
     * How many of a day's [count] entries fit in [slots] lines: when they don't all fit, the last line says "+N"
     * instead, so the result is (entries shown, entries left over). No room at all shows none.
     */
    fun fit(count: Int, slots: Int): Pair<Int, Int> = when {
        slots <= 0 -> 0 to count
        count <= slots -> count to 0
        else -> (slots - 1) to (count - slots + 1)
    }

    // The days eventsOnDay puts this event on.
    private fun daysOf(event: ItineraryItem): Sequence<LocalDate> {
        val last = maxOf(event.lastDay, if (event.category != "Bills" && event.startTime != null && event.durationMinutes != null)
            event.date.atTime(event.startTime).plusMinutes(event.durationMinutes.toLong()).minusNanos(1).toLocalDate() else event.date)
        return generateSequence(event.date) { it.plusDays(1) }.takeWhile { it <= last }
    }

    private fun ItineraryItem.chip() = DayChip("event:$id", title.ifBlank { "Untitled" }, colorIndex = colorIndex,
        customColor = customColor, faded = skipped || category == "Bills" && paid)

    // The same order as eventsOnDay: all-day first, then by start.
    private val DAY_ORDER = compareBy<ItineraryItem> { it.startTime != null }.thenBy { it.date }.thenBy { it.startTime }.thenBy { it.id }

    private val TASK_ORDER = compareByDescending<PlannerTask> { it.priority.rank }.thenBy { it.title.lowercase() }.thenBy { it.id }
}
