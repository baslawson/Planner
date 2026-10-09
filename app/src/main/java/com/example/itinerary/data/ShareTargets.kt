package com.example.itinerary.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * A share (photos, files, text) added to an event, bill, task or note that already exists, instead of a new one: the
 * choices "Add to existing…" lists, and what adding does to the item (its files after the ones it has, the share's text
 * after its notes).
 */
object ShareTargets {
    enum class Kind(val heading: String) { EVENT("Events"), BILL("Bills"), TASK("Tasks"), NOTE("Notes") }

    /** One item to pick. [id]: the event's id as text, or the task's or note's id. [detail]: its date or state, to tell alike ones apart. */
    data class Target(val kind: Kind, val id: String, val title: String, val date: LocalDate?, val detail: String)

    // A long list helps no one: the nearest ones of each kind, the rest by searching.
    const val PER_KIND = 40

    /**
     * What "Add to existing…" offers for [query] (all of its words, in the title or notes; none = all): events and bills
     * nearest [today] first (upcoming before past on the same distance), tasks not done first (by due date), notes newest
     * first. Events from other calendars (read-only) and archived notes aren't offered.
     */
    fun targets(items: List<ItineraryItem>, tasks: List<PlannerTask>, notes: List<PlannerNote>, today: LocalDate, query: String,
                dayLabel: (LocalDate) -> String = { it.toString() }): List<Target> {
        val words = Search.normalize(query).split(' ').filter { it.isNotBlank() }
        fun matches(vararg texts: String): Boolean { if (words.isEmpty()) return true
            val have = texts.joinToString(" ") { Search.normalize(it) }.split(' ').filter { it.isNotBlank() }
            return words.all { w -> have.any { Search.matchesWord(it, w) } } }
        val events = items.filter { it.tripId != OutsideCalendars.TRIP_ID && matches(it.title, it.location, it.notes) }
            .sortedWith(compareBy({ abs(ChronoUnit.DAYS.between(today, it.date)) }, { if (it.date >= today) 0 else 1 }, { it.startTime }, { it.id }))
        val (bills, others) = events.partition { Categories.isBillsName(it.category) || it.billAmountMinor != null }
        fun event(kind: Kind, it: ItineraryItem) = Target(kind, it.id.toString(), it.title.ifBlank { "(No title)" }, it.date,
            dayLabel(it.date) + (it.startTime?.let { t -> " · $t" } ?: ""))
        val taskList = tasks.filter { matches(it.title, it.notes) }
            .sortedWith(compareBy({ it.done }, { it.dueDate == null }, { it.dueDate }, { it.title.lowercase() }))
            .map { Target(Kind.TASK, it.id, it.title.ifBlank { "(No title)" }, it.dueDate,
                if (it.done) "Done" else it.dueDate?.let { d -> "Due " + dayLabel(d) } ?: "No due date") }
        val noteList = notes.filter { !it.archived && matches(it.title, it.content) }
            .sortedByDescending { it.modified }
            .map { Target(Kind.NOTE, it.id, it.title.ifBlank { Notes.firstLine(it.content).ifBlank { "(Empty note)" } }, null,
                it.notebook.ifBlank { "Note" }) }
        return others.take(PER_KIND).map { event(Kind.EVENT, it) } + bills.take(PER_KIND).map { event(Kind.BILL, it) } +
            taskList.take(PER_KIND) + noteList.take(PER_KIND)
    }

    /** [notes] with the share's [text] after a blank line (none when there's no text), cut to fit [max]. */
    fun withText(notes: String, text: String?, max: Int): String {
        val added = text?.trim().orEmpty()
        if (added.isEmpty() || notes.contains(added)) return notes
        if (notes.length >= max) return notes // already full (an item from before the limits): left as it is
        val joined = if (notes.isBlank()) added else notes.trimEnd() + "\n\n" + added
        return if (joined.length <= max) joined else joined.take(max)
    }

    /** [have] with [files] after it (a file it already has isn't added twice); refused past [max] files. */
    fun withFiles(have: List<Attachment>, files: List<Attachment>, max: Int = 100): List<Attachment> {
        val added = files.filter { f -> have.none { it.fileName == f.fileName } }
        require(have.size + added.size <= max) { "That would make more than $max files on it. Choose another one, or remove some files there first." }
        return have + added
    }
}
