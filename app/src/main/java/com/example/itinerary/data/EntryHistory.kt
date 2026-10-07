package com.example.itinerary.data

import java.time.LocalDate

/**
 * What the editors suggest as you type, from what is already in Planner: titles of events, bills and tasks used before
 * (with an event's or bill's last place, category and amount, filled in when one is picked), places, and currencies.
 * A suggestion matches anywhere in its text, ignoring capitals and accents, from the first letter typed; the newest
 * first (one that has happened before one still to come), each once; never just what is already typed.
 */
object EntryHistory {
    const val MAX = 8

    /** A title used before, with what went with it the last time. */
    data class Past(val title: String, val location: String, val category: String, val amountMinor: Long?, val currency: String)

    private fun matches(text: String, typed: String): Boolean {
        val needle = Search.normalize(typed.trim())
        return needle.isNotEmpty() && Search.normalize(text).contains(needle) && !text.trim().equals(typed.trim(), ignoreCase = true)
    }

    // The newest that has happened (up to [today]) first, then those still to come, nearest first.
    private fun <T> newestFirst(list: List<T>, today: LocalDate, date: (T) -> LocalDate): List<T> {
        val (done, coming) = list.partition { !date(it).isAfter(today) }
        return done.sortedByDescending(date) + coming.sortedBy(date)
    }

    /** Event titles ([bills]: bill titles) like [typed]; [except] is the event being edited. */
    fun titles(items: List<ItineraryItem>, typed: String, bills: Boolean, today: LocalDate = LocalDate.now(), except: Long? = null): List<Past> =
        newestFirst(items.filter { it.id != except && (it.category == "Bills") == bills && it.title.isNotBlank() && matches(it.title, typed) }, today) { it.date }
            .distinctBy { Search.normalize(it.title.trim()) }.take(MAX)
            .map { Past(it.title.trim(), it.location.trim(), it.category, it.billAmountMinor, it.billCurrency) }

    /** Places (a bill's payee too) like [typed]. */
    fun locations(items: List<ItineraryItem>, typed: String, today: LocalDate = LocalDate.now()): List<String> =
        newestFirst(items.filter { it.location.isNotBlank() && matches(it.location, typed) }, today) { it.date }
            .map { it.location.trim() }.distinctBy(Search::normalize).take(MAX)

    /** Task titles like [typed], those due most recently first (then ones with no due date). */
    fun taskTitles(tasks: List<PlannerTask>, typed: String, today: LocalDate = LocalDate.now(), except: String? = null): List<String> =
        newestFirst(tasks.filter { it.id != except && it.title.isNotBlank() && matches(it.title, typed) }, today) { it.dueDate ?: LocalDate.MIN }
            .map { it.title.trim() }.distinctBy(Search::normalize).take(MAX)

    /** Currency codes: the ones used, most used first, then [common]; those starting with [typed] (all when empty). */
    fun currencies(used: List<String>, typed: String, common: List<String>): List<String> {
        val start = typed.trim().uppercase()
        val byUse = used.map { it.uppercase() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        return (byUse + common).distinct().filter { it.startsWith(start) && it != start }
    }
}
