package com.example.itinerary.data

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class SearchHit(val item: ItineraryItem, val trip: Trip, val score: Double, val documentName: String? = null)

// [tokens] are the normalised words that were matched, for highlighting;
// [dateLabels] say how date words such as "tomorrow" were understood.
// [invalidDates] identifies date tokens that must be corrected before showing results.
class SearchOutcome(
    val hits: List<SearchHit>,
    val tokens: List<String>,
    val dateLabels: List<String>,
    val invalidDates: List<String> = emptyList(),
    val taskHits: List<PlannerTask> = emptyList(),
) {
    companion object {
        val EMPTY = SearchOutcome(emptyList(), emptyList(), emptyList())
        val LOADING = SearchOutcome(emptyList(), emptyList(), emptyList())
    }
}

// On-device search over every trip. Words must all match somewhere (title, location, notes,
// attachment names or category), tolerating capitals, accents, part-words and small typos.
// Date words like "tomorrow", "next friday", "june" or "12 june" narrow by date instead of matching text.
object Search {
    private val MARKS = Regex("\\p{M}+")
    private val ISO_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    private val STOP_WORDS = setOf("a", "an", "the", "on", "in", "at", "to", "for", "of", "my", "and", "with", "from", "is", "i")
    private val MODIFIERS = setOf("next", "this", "last")

    private val WEEKDAYS: Map<String, DayOfWeek> = DayOfWeek.entries.associateBy { it.name.lowercase(Locale.ROOT) }
    private val MONTHS: Map<String, Month> = Month.entries.associateBy { it.name.lowercase(Locale.ROOT) }
    private val MONTH_ABBREVIATIONS: Map<String, Month> =
        Month.entries.associateBy { it.name.lowercase(Locale.ROOT).take(3) } + ("sept" to Month.SEPTEMBER)

    // Everyday words that point at a category, so "hotel" finds Stay and "dinner" finds Food.
    // The user's own categories aren't listed here; their words are matched in [matchesCategory].
    private val CATEGORY_WORDS: Map<String, String> = buildMap {
        Categories.BUILT_IN.forEach { put(normalize(it), it) }
        mapOf(
            "Flight" to listOf("flights", "fly", "plane", "airport", "airline", "boarding"),
            "Stay" to listOf("stays", "hotel", "hotels", "hostel", "airbnb", "lodging", "accommodation", "resort", "checkin"),
            "Food" to listOf("eat", "dinner", "lunch", "breakfast", "brunch", "restaurant", "restaurants", "cafe", "meal", "meals", "drinks"),
            "Bills" to listOf("bill", "invoice", "invoices", "rent", "electricity", "utilities", "utility", "payment", "payments", "insurance", "tax", "taxes"),
            "Transport" to listOf("train", "trains", "bus", "buses", "taxi", "taxis", "ferry", "metro", "subway", "car", "transfer", "uber"),
        ).forEach { (category, words) -> words.forEach { put(it, category) } }
    }

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT)

    // Whether one already-normalised word matches a search token.
    fun matchesWord(word: String, token: String): Boolean = wordFactor(word, token) > 0.0

    fun run(
        query: String,
        categories: Set<String>,
        trips: List<Trip>,
        items: List<ItineraryItem>,
        attachments: List<Attachment>,
        today: LocalDate = LocalDate.now(),
    ): SearchOutcome = prepare(trips, items, attachments).run(query, categories, today)

    // Normalise stored text once per database snapshot, not once per keystroke. The immutable index
    // can be built on a worker thread and reused for text, category and date queries.
    fun prepare(trips: List<Trip>, items: List<ItineraryItem>, attachments: List<Attachment>, tasks: List<PlannerTask> = emptyList()): Index =
        Index(trips, items, attachments, tasks)

    class Index internal constructor(trips: List<Trip>, items: List<ItineraryItem>, attachments: List<Attachment>, tasks: List<PlannerTask> = emptyList()) {
        private val taskDocuments = tasks.map { it to splitWords("${it.title} ${it.notes} ${it.priority.label} ${it.checklist.joinToString(" ") { entry -> entry.text }} ${it.attachments.joinToString(" ") { file -> file.name + " " + file.recognizedText }} task tasks") }
        private val documents: List<Document>

        init {
            val tripsById = trips.associateBy { it.id }
            val fileWordsByItem = HashMap<Long, MutableList<String>>()
            val recognizedByItem = HashMap<Long, MutableList<RecognizedDocument>>()
            attachments.forEach { attachment ->
                val words = fileWordsByItem.getOrPut(attachment.itemId) { mutableListOf() }
                words += splitWords(attachment.name)
                words += splitWords(attachment.url.orEmpty())
                val recognizedWords = splitWords(attachment.recognizedText)
                words += recognizedWords
                if (attachment.recognizedText.isNotBlank())
                    recognizedByItem.getOrPut(attachment.itemId) { mutableListOf() }
                        .add(RecognizedDocument(attachment.name, recognizedWords))
            }
            documents = items.mapNotNull { item ->
                val trip = tripsById[item.tripId] ?: return@mapNotNull null
                Document(
                    item, trip,
                    listOf(
                        Field(10.0, splitWords(item.title)),
                        Field(6.0, splitWords(item.location)),
                        Field(3.0, splitWords(item.notes)),
                    ) + Field(3.0, fileWordsByItem[item.id].orEmpty()),
                    splitWords(item.category),
                    recognizedByItem[item.id].orEmpty(),
                )
            }
        }

        fun run(query: String, categories: Set<String>, today: LocalDate = LocalDate.now(), checkCancelled: () -> Unit = {}): SearchOutcome {
            val parsed = parse(query, today)
            // Invalid dates must still block results, including queries with valid dates or categories.
            if (parsed.invalidDates.isNotEmpty()) {
                return SearchOutcome(emptyList(), parsed.words, emptyList(), parsed.invalidDates)
            }
            if (parsed.words.isEmpty() && parsed.dates.isEmpty() && categories.isEmpty()) return SearchOutcome.EMPTY
            val hits = documents.mapNotNull { document ->
                checkCancelled()
                val item = document.item
                if (categories.isNotEmpty() && item.category !in categories && !("Tasks" in categories && item.category == "Bills")) return@mapNotNull null
                // A multi-day event matches a date on any day it covers.
                if (parsed.dates.isNotEmpty() && parsed.dates.none { filter ->
                        generateSequence(item.date) { it.plusDays(1) }.takeWhile { it <= item.lastDay }.any { day -> filter.test(day) } }) return@mapNotNull null
                val score = score(parsed.words, document) ?: return@mapNotNull null
                val matchingDocument = document.attachments.firstOrNull { attachment ->
                    parsed.words.any { token -> attachment.words.any { word -> matchesWord(word, token) } }
                }
                SearchHit(item, document.trip, score, matchingDocument?.name)
            }.sortedWith(
                compareByDescending<SearchHit> { it.score }
                    .thenBy { it.item.date }
                    .thenBy { it.item.startTime ?: LocalTime.MIN },
            )
            val taskHits = if (categories.isNotEmpty() && "Tasks" !in categories) emptyList() else taskDocuments.mapNotNull { (task, words) ->
                checkCancelled()
                if (parsed.dates.isNotEmpty() && (task.dueDate == null || parsed.dates.none { it.test(task.dueDate) })) return@mapNotNull null
                task.takeIf { parsed.words.all { token -> words.any { matchesWord(it, token) } } }
            }.sortedWith(compareBy<PlannerTask> { it.dueDate == null }.thenBy { it.dueDate }.then(Tasks.order))
            return SearchOutcome(hits, parsed.words, parsed.dates.map { it.label }, taskHits = taskHits)
        }

        companion object {
            val EMPTY = Index(emptyList(), emptyList(), emptyList())
        }
    }

    private class RecognizedDocument(val name: String, val words: List<String>)
    private class Field(val weight: Double, val words: List<String>)
    private class Document(
        val item: ItineraryItem,
        val trip: Trip,
        val fields: List<Field>,
        val categoryWords: List<String>,
        val attachments: List<RecognizedDocument>,
    )

    private fun score(tokens: List<String>, document: Document): Double? {
        if (tokens.isEmpty()) return 0.0
        var total = 0.0
        for (token in tokens) {
            var best = 0.0
            for (field in document.fields) {
                val factor = field.words.maxOfOrNull { wordFactor(it, token) } ?: 0.0
                best = maxOf(best, field.weight * factor)
            }
            if (matchesCategory(token, document)) best = maxOf(best, 5.0)
            if (best == 0.0) return null // every word has to match something
            total += best
        }
        return total
    }

    // Exact words only: a part-word like "resta" shouldn't pull in every Food event. The words of a
    // category's own name count too, which is how the user's own categories are found.
    private fun matchesCategory(token: String, document: Document): Boolean =
        CATEGORY_WORDS[token] == document.item.category || token in document.categoryWords

    private fun wordFactor(word: String, token: String): Double = when {
        word == token -> 1.0
        word.startsWith(token) -> 0.8
        token.length >= 3 && word.contains(token) -> 0.6
        token.length >= 4 && withinEdits(word, token, if (token.length >= 8) 2 else 1) -> 0.5
        else -> 0.0
    }

    // Levenshtein distance, giving up early once it can't be within [max].
    private fun withinEdits(a: String, b: String, max: Int): Boolean {
        if (abs(a.length - b.length) > max) return false
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMin = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                rowMin = minOf(rowMin, current[j])
            }
            if (rowMin > max) return false
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length] <= max
    }

    private fun splitWords(text: String): List<String> =
        normalize(text).split(SPLIT).filter { it.isNotEmpty() }

    private class DateFilter(val label: String, val test: (LocalDate) -> Boolean)

    private class Parsed(val words: List<String>, val dates: List<DateFilter>, val invalidDates: List<String>)

    private fun parse(query: String, today: LocalDate): Parsed {
        var text = normalize(query)
        val dates = mutableListOf<DateFilter>()
        val invalidDates = mutableListOf<String>()
        ISO_DATE.findAll(text).forEach { match ->
            val date = runCatching { LocalDate.parse(match.value) }.getOrNull()
            if (date == null) invalidDates += match.value else dates += exact(date)
        }
        text = ISO_DATE.replace(text, " ")

        val tokens = text.split(SPLIT).filter { it.isNotEmpty() }
        val words = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val next = tokens.getOrNull(i + 1)
            when {
                token == "today" -> { dates += exact(today); i++ }
                token == "tomorrow" -> { dates += exact(today.plusDays(1)); i++ }
                token == "yesterday" -> { dates += exact(today.minusDays(1)); i++ }
                token in MODIFIERS && next in WEEKDAYS -> {
                    dates += exact(weekdayDate(WEEKDAYS.getValue(next!!), token, today))
                    i += 2
                }
                token in WEEKDAYS -> { dates += exact(weekdayDate(WEEKDAYS.getValue(token), "this", today)); i++ }
                isDayNumber(token) && next != null && monthOf(next) != null -> {
                    val year = yearAt(tokens, i + 2)
                    val size = if (year != null) 3 else 2
                    named(token.toInt(), monthOf(next)!!, year, tokens.subList(i, i + size), dates, invalidDates)
                    i += size
                }
                monthOf(token) != null && next != null && isDayNumber(next) -> {
                    val year = yearAt(tokens, i + 2)
                    val size = if (year != null) 3 else 2
                    named(next.toInt(), monthOf(token)!!, year, tokens.subList(i, i + size), dates, invalidDates)
                    i += size
                }
                token in MONTHS -> {
                    val year = yearAt(tokens, i + 1)
                    dates += month(MONTHS.getValue(token), year)
                    i += if (year != null) 2 else 1
                }
                else -> {
                    if (token !in STOP_WORDS) words += token
                    i++
                }
            }
        }
        return Parsed(words, dates, invalidDates.distinct())
    }

    // A named date is checked like an ISO one: "31 February" or "29 February 2027" must be corrected. Without a year,
    // 29 February is allowed (it falls in leap years).
    private fun named(day: Int, month: Month, year: Int?, words: List<String>, dates: MutableList<DateFilter>, invalidDates: MutableList<String>) {
        val valid = if (year == null) day <= month.maxLength() else java.time.YearMonth.of(year, month).isValidDay(day)
        if (valid) dates += dayMonth(day, month, year) else invalidDates += words.joinToString(" ")
    }

    private fun isDayNumber(token: String): Boolean = token.length <= 2 && token.toIntOrNull() in 1..31

    private fun monthOf(token: String): Month? = MONTHS[token] ?: MONTH_ABBREVIATIONS[token]

    private fun yearAt(tokens: List<String>, index: Int): Int? =
        tokens.getOrNull(index)?.takeIf { it.length == 4 }?.toIntOrNull()?.takeIf { it in 1900..2100 }

    // "friday" and "this friday" include today; "next friday" is the first one after today.
    private fun weekdayDate(day: DayOfWeek, modifier: String, today: LocalDate): LocalDate = when (modifier) {
        "last" -> today.minusDays(((today.dayOfWeek.value - day.value + 7) % 7).let { if (it == 0) 7 else it }.toLong())
        "next" -> today.plusDays(((day.value - today.dayOfWeek.value + 7) % 7).let { if (it == 0) 7 else it }.toLong())
        else -> today.plusDays(((day.value - today.dayOfWeek.value + 7) % 7).toLong())
    }

    // Formatters are immutable, so one per language is enough; `get()` built a new one for every date
    // word in every query. The locale is part of the key, so changing the phone's language cannot
    // reuse the wrong one.
    private val exactLabels = ConcurrentHashMap<Locale, DateTimeFormatter>()

    private fun exactLabel(): DateTimeFormatter = Locale.getDefault().let { locale ->
        exactLabels.getOrPut(locale) { DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale) }
    }

    private fun exact(date: LocalDate) = DateFilter(date.format(exactLabel())) { it == date }

    private fun dayMonth(day: Int, month: Month, year: Int?): DateFilter {
        val name = month.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
        return DateFilter("$day $name" + (year?.let { " $it" } ?: "")) {
            it.dayOfMonth == day && it.month == month && (year == null || it.year == year)
        }
    }

    private fun month(month: Month, year: Int?): DateFilter {
        val name = month.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
        return DateFilter(name + (year?.let { " $it" } ?: "")) {
            it.month == month && (year == null || it.year == year)
        }
    }
}
