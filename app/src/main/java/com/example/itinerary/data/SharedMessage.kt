package com.example.itinerary.data

import java.time.LocalDate
import java.time.LocalTime

/**
 * An email shared as text with its headers on top, as Thunderbird (K-9) sends it: "Subject: …", "Date: …", "From: …",
 * "To: …", "Cc: …" (labels in the phone's language, Date and Subject left out when the message has none), a blank line,
 * then the body. Read by position rather than by label words, so any language works: the From line is the first with an
 * email address, and Subject, when there is one, is the line above everything else.
 */
object EmailHeaders {
    data class Parsed(val subject: String?, val from: String, val body: String)

    private val header = Regex("^([^:]{1,40}):[ \\t]*(.*)$")
    private val address = Regex("[^\\s<>@]+@[^\\s<>@]+\\.[^\\s<>@]+")

    /** The headers and body of [text], or null when it doesn't start with a header block (three to eight "Label: value" lines, one with an address, then a blank line). */
    fun read(text: String): Parsed? {
        val lines = text.lines()
        val blank = lines.indexOfFirst { it.isBlank() }
        if (blank !in 3..8) return null
        val values = lines.take(blank).map { header.matchEntire(it.trim())?.groupValues?.get(2)?.trim() ?: return null }
        val fromIndex = values.indexOfFirst { address.containsMatchIn(it) }
        if (fromIndex < 0) return null
        val subject = values.first().takeIf { fromIndex > 0 && !address.containsMatchIn(it) }?.ifBlank { null }
        return Parsed(subject, lines[fromIndex].trim(), lines.drop(blank + 1).joinToString("\n").trim())
    }
}

/**
 * When a shared text is about: [date] and [time] when it names one day (and one time), every day it names in [dates],
 * and for a time Quick entry can't place ("10:30": morning or evening?) the readings to pick from in [timeChoices].
 */
data class SharedWhen(val date: LocalDate?, val time: LocalTime?, val dates: List<LocalDate>,
                      val timeChoices: List<LocalTime> = emptyList())

/**
 * The days a shared text names, read sentence by sentence with Quick entry's grammar. Only days from [today] on that
 * Quick entry is sure of count: past days and ambiguous numeric dates are left out, and so is what Quick entry would
 * still ask about besides the day (a missing name, say). A day is offered only when it is the only one; several are
 * listed so the person picks, nothing is guessed.
 */
object SharedDates {
    private const val MAX_SENTENCES = 400
    private val sentenceBreak = Regex("(?<=[.!?])\\s+|\\s*\\n\\s*")

    private data class Found(val date: LocalDate, val time: LocalTime?, val choices: List<LocalTime>)

    fun find(text: String, today: LocalDate): SharedWhen {
        val found = text.split(sentenceBreak).asSequence().map { it.trim() }
            .filter { it.length in 3..QuickEntry.MAX_LENGTH }
            .take(MAX_SENTENCES)
            .mapNotNull { sentence ->
                val s = runCatching { QuickEntry.parse(sentence, today) }.getOrNull() ?: return@mapNotNull null
                if (!s.dateSpecified || s.pastDate || s.dateChoices.isNotEmpty() || s.date.isBefore(today) ||
                    s.error == QuickEntry.TOO_LONG) return@mapNotNull null
                val choices = s.timeChoices.takeIf { s.ambiguousTime }.orEmpty()
                Found(s.date, s.time?.takeIf { s.timed() && choices.isEmpty() }, choices)
            }.toList()
        val dates = found.map { it.date }.distinct().sorted()
        val date = dates.singleOrNull() ?: return SharedWhen(null, null, dates)
        val times = found.mapNotNull { it.time }.distinct()
        val choices = found.map { it.choices }.filter { it.isNotEmpty() }.distinct()
        return when {
            times.size == 1 && choices.all { times.single() in it } -> SharedWhen(date, times.single(), dates)
            times.isEmpty() && choices.size == 1 -> SharedWhen(date, null, dates, choices.single())
            else -> SharedWhen(date, null, dates)
        }
    }
}
