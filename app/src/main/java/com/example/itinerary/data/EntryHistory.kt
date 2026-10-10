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
        prepare(items, today).titles(typed, bills, except)

    /** Places (a bill's payee too) like [typed]. */
    fun locations(items: List<ItineraryItem>, typed: String, today: LocalDate = LocalDate.now()): List<String> =
        prepare(items, today).locations(typed)

    /**
     * The events made ready to suggest from, once per change to them (hunt 21 S5): each title and place normalised once,
     * the newest first, each once (by event kind for titles). An editor then only filters these as letters are typed,
     * instead of normalising every event (each occurrence of a series too) again on each letter.
     */
    fun prepare(items: List<ItineraryItem>, today: LocalDate = LocalDate.now()): Prepared {
        val sorted = newestFirst(items, today) { it.date }
        // Two of each title (the newest and the one before), so the event being edited can't hide its title's history.
        val seen = HashMap<Pair<Boolean, String>, Int>()
        val titles = sorted.filter { it.title.isNotBlank() }
            .map { Prepared.Title(it.id, it.category == "Bills", Search.normalize(it.title.trim()),
                Past(it.title.trim(), it.location.trim(), it.category, it.billAmountMinor, it.billCurrency)) }
            .filter { t -> seen.merge(t.bill to t.key, 1, Int::plus)!! <= 2 }
        val places = sorted.filter { it.location.isNotBlank() }.map { it.location.trim() }.distinctBy(Search::normalize)
            .map { it to Search.normalize(it) }
        return Prepared(titles, places)
    }

    class Prepared internal constructor(private val titles: List<Title>, private val places: List<Pair<String, String>>) {
        internal class Title(val id: Long, val bill: Boolean, val key: String, val past: Past)
        // [except]: the event being edited; when its own title is its kind's newest, an older one with that title stands in.
        fun titles(typed: String, bills: Boolean, except: Long? = null): List<Past> {
            val needle = Search.normalize(typed.trim()); if (needle.isEmpty()) return emptyList()
            return titles.filter { it.bill == bills && it.id != except && it.key.contains(needle) && !it.past.title.equals(typed.trim(), ignoreCase = true) }
                .distinctBy { it.key }.take(MAX).map { it.past }
        }
        fun locations(typed: String): List<String> {
            val needle = Search.normalize(typed.trim()); if (needle.isEmpty()) return emptyList()
            return places.filter { (place, key) -> key.contains(needle) && !place.equals(typed.trim(), ignoreCase = true) }.take(MAX).map { it.first }
        }
    }

    /** Task titles like [typed], those due most recently first (then ones with no due date). */
    fun taskTitles(tasks: List<PlannerTask>, typed: String, today: LocalDate = LocalDate.now(), except: String? = null): List<String> =
        newestFirst(tasks.filter { it.id != except && it.title.isNotBlank() && matches(it.title, typed) }, today) { it.dueDate ?: LocalDate.MIN }
            .map { it.title.trim() }.distinctBy(Search::normalize).take(MAX)

    /**
     * Checklist items used before in events, bills and tasks (user, 10 Oct), made ready once: the most used first (a
     * repeating event counts once, not once per date), each once. [ChecklistItems.like] then only filters them.
     */
    fun checklistItems(items: List<ItineraryItem>, tasks: List<PlannerTask>): ChecklistItems {
        val texts = items.distinctBy { it.seriesId?.let { s -> "s$s" } ?: "e${it.id}" }.flatMap { it.checklist } + tasks.flatMap { it.checklist }
        val counted = texts.map { it.text.trim() }.filter { it.isNotEmpty() }.groupBy(Search::normalize)
            .map { (key, all) -> Triple(all.first(), key, all.size) }
            .sortedWith(compareByDescending<Triple<String, String, Int>> { it.third }.thenBy { it.second })
        return ChecklistItems(counted.map { it.first to it.second })
    }

    class ChecklistItems internal constructor(private val items: List<Pair<String, String>>) {
        /** Items like [typed], leaving out those already in the checklist ([others]) and just what is typed. */
        fun like(typed: String, others: List<String> = emptyList()): List<String> {
            val needle = Search.normalize(typed.trim()); if (needle.isEmpty()) return emptyList()
            val taken = others.mapTo(HashSet()) { Search.normalize(it.trim()) }
            return items.filter { (text, key) -> key.contains(needle) && key !in taken && !text.equals(typed.trim(), ignoreCase = true) }
                .take(MAX).map { it.first }
        }
    }

    /** Currency codes: the ones used, most used first, then [common]; those starting with [typed] (all when empty). */
    fun currencies(used: List<String>, typed: String, common: List<String>): List<String> {
        val start = typed.trim().uppercase()
        val byUse = used.map { it.uppercase() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        return (byUse + common).distinct().filter { it.startsWith(start) && it != start }
    }
}
