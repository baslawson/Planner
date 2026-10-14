package com.example.itinerary.data

import java.time.LocalDate

/**
 * The day view with hours (user, 14 Oct): where each timed event of a day sits on its hour grid. Events that overlap
 * share the width side by side: each gets a [Block.column] of the [Block.columns] its group of overlapping events needs.
 * An event that started the evening before begins at midnight here, and one running past midnight ends there. All-day
 * and multi-day events aren't on the grid (see [allDay]).
 */
object DayTimeline {
    data class Block(val item: ItineraryItem, val startMinute: Int, val endMinute: Int, val column: Int, val columns: Int)

    // A short or open-ended event still gets room for its title.
    const val MIN_MINUTES = 30
    private const val DAY = 24 * 60

    // Drags on the grid move and stretch events in steps of this many minutes.
    const val STEP = 15

    private fun timed(item: ItineraryItem) = item.startTime != null && item.endDate == null

    /** Whether [item]'s block on [day] can be dragged: a Planner event with a time that starts that day. */
    fun movable(item: ItineraryItem, day: LocalDate): Boolean =
        timed(item) && item.date == day && !OutsideCalendars.isOutside(item.id)

    private fun snap(minutes: Float): Int = Math.round(minutes / STEP) * STEP

    /** Where a block starting at [startMinute] lands when dragged [deltaMinutes]: on a 15-minute step, within the day. */
    fun movedStart(startMinute: Int, deltaMinutes: Float): Int = snap(startMinute + deltaMinutes).coerceIn(0, DAY - STEP)

    /**
     * How long an event lasts when its bottom edge is dragged [deltaMinutes]: the end on a 15-minute step, at least 15
     * minutes, at most a day. One with no length starts from the half hour its block shows.
     */
    fun resized(startMinute: Int, durationMinutes: Int?, deltaMinutes: Float): Int {
        val end = snap(startMinute + (durationMinutes ?: MIN_MINUTES) + deltaMinutes)
        return (end - startMinute).coerceIn(STEP, DAY)
    }

    /** The day's events that go above the grid: all-day ones and those on several days. */
    fun allDay(events: List<ItineraryItem>): List<ItineraryItem> = events.filterNot(::timed)

    fun blocks(events: List<ItineraryItem>, day: LocalDate): List<Block> {
        val spans = events.filter(::timed).mapNotNull { item ->
            val start = item.date.atTime(item.startTime!!)
            val end = start.plusMinutes((item.durationMinutes ?: 0).toLong())
            val from = if (start.toLocalDate() < day) 0 else if (start.toLocalDate() > day) return@mapNotNull null else start.toLocalTime().toSecondOfDay() / 60
            val to = if (end.toLocalDate() > day) DAY else if (end.toLocalDate() < day) return@mapNotNull null else end.toLocalTime().toSecondOfDay() / 60
            // Bug hunt 28 P6: still half an hour tall late in the evening, ending at midnight instead.
            val last = minOf(DAY, maxOf(to, from + MIN_MINUTES))
            Triple(item, minOf(from, last - MIN_MINUTES), last)
        }.sortedWith(compareBy<Triple<ItineraryItem, Int, Int>> { it.second }.thenByDescending { it.third }.thenBy { it.first.id })

        val out = mutableListOf<Block>()
        // Groups of events that overlap one another, each laid out in as few columns as it needs.
        var group = mutableListOf<Pair<Triple<ItineraryItem, Int, Int>, Int>>()
        var groupEnd = -1
        val columnEnds = mutableListOf<Int>()
        fun close() {
            val columns = (group.maxOfOrNull { it.second } ?: -1) + 1
            group.forEach { (span, column) -> out += Block(span.first, span.second, span.third, column, columns) }
            group = mutableListOf(); columnEnds.clear()
        }
        for (span in spans) {
            if (span.second >= groupEnd) { close(); groupEnd = -1 }
            val column = columnEnds.indexOfFirst { it <= span.second }.let { if (it < 0) { columnEnds += 0; columnEnds.lastIndex } else it }
            columnEnds[column] = span.third
            group += span to column
            groupEnd = maxOf(groupEnd, span.third)
        }
        close()
        return out
    }
}
