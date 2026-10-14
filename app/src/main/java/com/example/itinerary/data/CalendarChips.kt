package com.example.itinerary.data

import java.time.LocalDate

/**
 * One small entry in a day of the maximised month grid: an event or bill (coloured as its card), or a task due that
 * day. [faded] marks what is already dealt with: a paid bill or a skipped date. An event on more than one day has its
 * first and last day in [from] and [to], and is drawn as one bar across them.
 */
data class DayChip(
    val key: String,
    val title: String,
    val task: Boolean = false,
    val colorIndex: Int = 0,
    val customColor: Int? = null,
    val faded: Boolean = false,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

/**
 * A piece of an entry in one week row of the maximised month: columns [start] to [end] (0 to 6, in reading order) on
 * line [line]. [opens] and [closes] say the entry really starts or ends there (a rounded end), rather than carrying on
 * from or into another row, or past a day's "+N".
 */
data class WeekPiece(val chip: DayChip, val start: Int, val end: Int, val line: Int, val opens: Boolean, val closes: Boolean)

/** A week row's [pieces], and for each column how many entries are hidden behind the "+N" on line [moreLine] (0: none). */
data class WeekLines(val pieces: List<WeekPiece>, val more: List<Int>, val moreLine: Int)

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
            eventsByDay[day].orEmpty().sortedWith(DAY_ORDER).map { it.chip(daysOf(it).last()) } +
                tasksByDay[day].orEmpty().sortedWith(TASK_ORDER)
                    .map { DayChip("task:${it.id}", it.title.ifBlank { "Untitled task" }, task = true) }
        }
    }

    /** The tasks due on [day] for the Calendar's day list: still to do first (the most important first), then done ones. */
    fun tasksOnDay(tasks: List<PlannerTask>, day: LocalDate): List<PlannerTask> =
        tasks.filter { it.dueDate == day }.sortedWith(compareBy<PlannerTask> { it.done }.then(TASK_ORDER))

    /**
     * Lays out one week row ([week]: its seven days, null for the blanks around the month) in [slots] lines per day.
     * Entries on more than one day go first, as bars that keep one line across the row: the earliest start first, the
     * longer one first when two start the same day, each on the highest line free on all its days. Then each day's
     * other entries fill its free lines, gaps between bars included, in the day's order. A day whose entries go below
     * its last line shows "+N" on that line instead, and a bar crossing that spot is cut there. No room at all
     * (slots 0) lays out nothing: the day shows a dot instead.
     */
    fun weekLines(week: List<LocalDate?>, chips: Map<LocalDate, List<DayChip>>, slots: Int): WeekLines {
        if (slots <= 0) return WeekLines(emptyList(), List(7) { 0 }, 0)
        val days = List(7) { week.getOrNull(it) }
        val taken = List(7) { HashSet<Int>() }
        fun freeLine(cols: IntRange) = generateSequence(0) { it + 1 }.first { line -> cols.all { line !in taken[it] } }

        class Span(val chip: DayChip, val start: Int, var end: Int, val order: Int) { var line = 0 }
        val spans = LinkedHashMap<String, Span>()
        days.forEachIndexed { col, day ->
            day?.let { chips[it] }.orEmpty().forEachIndexed { i, chip ->
                if (chip.from != null) spans.getOrPut(chip.key) { Span(chip, col, col, i) }.end = col
            }
        }
        spans.values.sortedWith(compareBy<Span> { it.start }.thenByDescending { it.end - it.start }.thenBy { it.order }).forEach { s ->
            s.line = freeLine(s.start..s.end)
            (s.start..s.end).forEach { taken[it] += s.line }
        }
        val singles = days.mapIndexed { col, day ->
            day?.let { chips[it] }.orEmpty().filter { it.from == null }.map { chip ->
                val line = freeLine(col..col)
                taken[col] += line
                chip to line
            }
        }
        // The lines each day shows: all of them while everything fits, else the last one is the "+N".
        val limit = IntArray(7) { col -> if ((taken[col].maxOrNull() ?: -1) < slots) slots else slots - 1 }
        val more = List(7) { col -> taken[col].count { it >= limit[col] } }

        val pieces = mutableListOf<WeekPiece>()
        spans.values.forEach { s ->
            var runStart = -1
            for (col in s.start..s.end + 1) {
                val shows = col <= s.end && s.line < limit[col]
                if (shows && runStart < 0) runStart = col
                if (!shows && runStart >= 0) {
                    pieces += WeekPiece(s.chip, runStart, col - 1, s.line,
                        opens = runStart == s.start && days[runStart] == s.chip.from,
                        closes = col - 1 == s.end && days[col - 1] == s.chip.to)
                    runStart = -1
                }
            }
        }
        singles.forEachIndexed { col, list ->
            list.forEach { (chip, line) -> if (line < limit[col]) pieces += WeekPiece(chip, col, col, line, opens = true, closes = true) }
        }
        return WeekLines(pieces, more, slots - 1)
    }

    // The days eventsOnDay puts this event on.
    private fun daysOf(event: ItineraryItem): Sequence<LocalDate> {
        val last = maxOf(event.lastDay, if (event.category != "Bills" && event.startTime != null && event.durationMinutes != null)
            event.date.atTime(event.startTime).plusMinutes(event.durationMinutes.toLong()).minusNanos(1).toLocalDate() else event.date)
        return generateSequence(event.date) { it.plusDays(1) }.takeWhile { it <= last }
    }

    // [last]: the last day it shows on; past its first day, it is a bar.
    private fun ItineraryItem.chip(last: LocalDate) = DayChip("event:$id", title.ifBlank { "Untitled" }, colorIndex = colorIndex,
        customColor = customColor, faded = skipped || category == "Bills" && paid,
        from = date.takeIf { last > date }, to = last.takeIf { last > date })

    // The same order as eventsOnDay: all-day first, then by start.
    private val DAY_ORDER = compareBy<ItineraryItem> { it.startTime != null }.thenBy { it.date }.thenBy { it.startTime }.thenBy { it.id }

    private val TASK_ORDER = compareByDescending<PlannerTask> { it.priority.rank }.thenBy { it.title.lowercase() }.thenBy { it.id }
}
