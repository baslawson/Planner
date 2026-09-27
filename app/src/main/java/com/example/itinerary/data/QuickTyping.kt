package com.example.itinerary.data

import java.util.Locale

/** Only the scheduling detail that changed loses its explicit correction. */
fun QuickInput.edited(value: String): QuickInput {
    if (value == text) return this
    if (value.isBlank()) return QuickInput(text = value, task = task, baseDate = baseDate)
    val moved = moveQuickEntryLiterals(text, value, literals)
    val before = QuickEntry.parse(text, baseDate, literals)
    val after = QuickEntry.parse(value, baseDate, moved)
    fun signature(kind: QuickPhraseKind, old: Boolean): List<String> {
        val source = if (old) text else value
        val parsed = if (old) before else after
        val spans = parsed.phrases.filter { it.kind == kind }.map { it.start until it.end }.toMutableList()
        // An incomplete unrelated clause can make the parser return early. Keep intact old phrases.
        if (!old && after.error != null) before.phrases.filter { it.kind == kind }.forEach { phrase ->
            moveQuickEntryLiterals(text, value, listOf(phrase.start until phrase.end)).singleOrNull()?.let { range ->
                if (spans.none { it.first <= range.last && range.first <= it.last }) spans += range
            }
        }
        return spans.sortedBy { it.first }.map { source.substring(it).lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim() }
    }
    fun changed(kind: QuickPhraseKind) = signature(kind, true) != signature(kind, false)
    fun anchors(old: Boolean) = signature(QuickPhraseKind.REPEAT, old)
        .filter { Regex("every\\s+(?:mon|tue|wed|thu|fri|sat|sun)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    return copy(text = value, literals = moved, ai = null,
        dateOverride = dateOverride.takeUnless { changed(QuickPhraseKind.DATE) || anchors(true) != anchors(false) },
        timeOverride = timeOverride.takeUnless { changed(QuickPhraseKind.TIME) },
        countText = countText.takeUnless { changed(QuickPhraseKind.REPEAT) },
        removeReminder = removeReminder && !changed(QuickPhraseKind.REMINDER),
        durationText = durationText.takeUnless { changed(QuickPhraseKind.DURATION) || changed(QuickPhraseKind.TIME) },
    )
}

data class QuickCompletion(val label: String, val start: Int, val end: Int, val replacement: String) {
    fun apply(text: String): Pair<String, Int> {
        val insert = replacement + if (end == text.length || !text[end].isWhitespace()) " " else ""
        return text.replaceRange(start, end, insert) to (start + insert.length)
    }
}

/** Small, explicit, cursor-aware completions. Never edits text until the user taps one. */
fun quickCompletions(text: String, cursor: Int, literals: List<IntRange>, task: Boolean): List<QuickCompletion> {
    if (cursor !in 0..text.length || cursor == 0 || (cursor < text.length && text[cursor].isLetterOrDigit())) return emptyList()
    val prefix = text.take(cursor)
    val normalized = prefix.replace('“', '"').replace('”', '"')
    if (normalized.count { it == '"' } % 2 != 0) return emptyList()
    fun options(pattern: String, values: List<Pair<String, String>>): List<QuickCompletion>? {
        val match = Regex(pattern, RegexOption.IGNORE_CASE).find(prefix) ?: return null
        if (literals.any { it.first < cursor && it.last >= match.range.first }) return emptyList()
        return values.map { (label, replacement) -> QuickCompletion(label, match.range.first, cursor, replacement) }
    }
    options("\\b(?:remind|notify)(?:\\s+me)?\\s*$", listOf("10 minutes before" to "remind me 10 minutes before", "30 minutes before" to "remind me 30 minutes before", "1 day before" to "remind me 1 day before"))?.let { return it }
    options("\\bevery\\s*$", listOf("Every day" to "every day", "Every week" to "every week", "Every month" to "every month"))?.let { return it }
    if (!task) options("\\bfor\\s*$", listOf("30 minutes" to "for 30 minutes", "45 minutes" to "for 45 minutes", "1 hour" to "for 1 hour"))?.let { return it }
    val word = Regex("\\b[A-Za-z]{2,}$").find(prefix) ?: return emptyList()
    if (literals.any { it.first < cursor && it.last >= word.range.first }) return emptyList()
    val candidates = listOf("Today", "Tomorrow", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    if (candidates.any { it.equals(word.value, ignoreCase = true) }) return emptyList()
    val prefixMatches = candidates.filter { it.startsWith(word.value, ignoreCase = true) }
    // A narrow one-edit suggestion, including adjacent transpositions, never an automatic rewrite.
    val matches = prefixMatches.ifEmpty {
        if (word.value.length < 5) emptyList() else candidates.filter { oneTypingEdit(word.value.lowercase(Locale.ROOT), it.lowercase(Locale.ROOT)) ||
            (word.value.equals("tommorow", ignoreCase = true) && it == "Tomorrow") }
    }
    return matches.take(3)
        .map { QuickCompletion(it, word.range.first, cursor, it.lowercase(Locale.ROOT)) }
}

private fun oneTypingEdit(a: String, b: String): Boolean {
    if (kotlin.math.abs(a.length - b.length) > 1) return false
    val first = a.indices.firstOrNull { it >= b.length || a[it] != b[it] } ?: a.length
    if (a.length == b.length) {
        if (first == a.length) return false
        return a.substring(first + 1) == b.substring(first + 1) ||
            (first + 1 < a.length && a[first] == b[first + 1] && a[first + 1] == b[first] && a.substring(first + 2) == b.substring(first + 2))
    }
    val longer = if (a.length > b.length) a else b
    val shorter = if (a.length > b.length) b else a
    return longer.removeRange(first, first + 1) == shorter
}
