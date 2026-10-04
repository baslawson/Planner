package com.example.itinerary.data

import java.time.LocalDate
import java.time.LocalTime

/**
 * An email shared as text with its headers on top, as Thunderbird (K-9) sends it: "Subject: …", "Date: …", "From: …",
 * "To: …", "Cc: …" (labels in the phone's language, Subject and Date left out when the message has none), a blank line,
 * then the body. Read by position rather than by label words, so any language works: the headers end in a run of lines
 * with addresses (From, To, Cc), at least two of them, and at most Subject and Date come before it.
 */
object EmailHeaders {
    data class Parsed(val subject: String?, val from: String, val body: String)

    private val header = Regex("^([^:\\s][^:]{0,39}):[ \\t]*(.*)$")
    private val address = Regex("[^\\s<>@,;]+@[^\\s<>@,;]+\\.[^\\s<>@,;]+")
    // Thunderbird's sent date, in any of its formats: a year and a clock time.
    private val sentDate = Regex("(?:19|20)\\d\\d.*\\d{1,2}[:.]\\d\\d|\\d{1,2}[:.]\\d\\d.*(?:19|20)\\d\\d")

    /** The headers and body of [text], or null when it doesn't start with an email's header block. */
    fun read(text: String): Parsed? {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        val blank = lines.indexOfFirst { it.isBlank() }.let { if (it < 0) lines.size else it }
        if (blank !in 2..10) return null
        // A folded header goes on over lines that start with a space.
        val block = mutableListOf<String>()
        for (line in lines.take(blank)) {
            if (line.first().isWhitespace() && block.isNotEmpty()) block[block.size - 1] += " " + line.trim() else block += line.trim()
        }
        val values = block.map { header.matchEntire(it)?.groupValues?.get(2)?.trim() ?: return null }
        // From, To and Cc: the trailing run of lines with an address (SH-1: one address is a contact card, not an email).
        val from = values.indices.reversed().takeWhile { address.containsMatchIn(values[it]) }.lastOrNull() ?: return null
        if (values.size - from < 2 || from > 2) return null
        val subject = when (from) {
            2 -> values[0]
            // One line before From: the subject, unless it is the sent date (a message without a subject, SH-2).
            1 -> values[0].takeUnless { sentDate.containsMatchIn(it) && it.length <= 40 }
            else -> null
        }?.ifBlank { null }
        return Parsed(subject, block[from], lines.drop(blank + 1).joinToString("\n").trim())
    }

    private val quoteIntro = Regex("^\\s*(?:-{2,}\\s*Original Message\\s*-{2,}|_{5,}|On .{1,200} wrote:)\\s*$", RegexOption.IGNORE_CASE)
    private val signature = Regex("^(?:-- ?|Sent from my .*|Get Outlook for .*)$")

    /**
     * The words of [body] that are the message itself: without a quoted earlier message (the "On … wrote:" line, any line
     * before the first ">" line that ends in a colon, and what follows) or a signature ("-- "). Only these are read for a
     * date: an earlier message's sent date or a signature's opening hours aren't this message's appointment (SH-4).
     */
    fun message(body: String): String {
        val lines = body.replace("\r\n", "\n").lines()
        val cut = lines.indices.firstOrNull { i ->
            val line = lines[i]
            line.trimStart().startsWith(">") || quoteIntro.matches(line) || signature.matches(line.trimEnd().let { if (it == "--") "-- " else it }) ||
                line.trimEnd().endsWith(":") && lines.getOrNull(i + 1)?.trimStart()?.startsWith(">") == true
        } ?: lines.size
        return lines.take(cut).joinToString("\n").trim()
    }
}

/**
 * When a shared text is about: [date] and [time] when it names one day (and one time), every day it names in [dates],
 * and for a time Quick entry can't place ("10:30": morning or evening?) the readings to pick from in [timeChoices].
 */
data class SharedWhen(val date: LocalDate?, val time: LocalTime?, val dates: List<LocalDate>,
                      val timeChoices: List<LocalTime> = emptyList())

/**
 * The days a message names, read sentence by sentence with Quick entry's grammar, but only where it is plainly a day:
 * a date with its month written ("14 October", "Oct 14"), a numeric date with its year (14/10/2026), or a weekday with a
 * time in the same sentence ("Saturday at 7pm"). Not repeats or ranges ("Monday to Friday", "every Monday"), "until …",
 * short number pairs ("24/7", "1/2", "9-5"), a weekday alone ("We spoke on Monday"), past days or a time already gone
 * today. A day is offered only when it is the only one; several are listed, and nothing is guessed (SH-3, Q6-2, Q6-3).
 */
object SharedDates {
    /** How much of a message is read: its opening, where an appointment is said; the rest is left (SH-5). */
    const val MAX_READ = 8_000
    private const val MAX_SENTENCES = 200
    private const val weekdays = "monday|mon|tuesday|tues|tue|wednesday|weds|wed|thursday|thurs|thur|thu|friday|fri|saturday|sat|sunday|sun"
    private const val months = "january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|august|aug|september|sept|sep|october|oct|november|nov|december|dec"
    private val monthWord = Regex("\\b(?:$months)\\b", RegexOption.IGNORE_CASE)
    private val weekdayWord = Regex("\\b(?:$weekdays)\\b", RegexOption.IGNORE_CASE)
    private val numericWithYear = Regex("\\b\\d{1,2}[/.-]\\d{1,2}[/.-](?:\\d{4}|\\d{2})\\b|\\b\\d{4}-\\d{2}-\\d{2}\\b")
    private val until = Regex("\\b(?:until|till|til|through|thru|ends?|valid|expires?)\\b", RegexOption.IGNORE_CASE)
    // A full stop after these is no sentence end: "Oct. 12", "Mon. 12 Oct", "10 a.m. on Friday", "Dr. Smith" (Q6-7).
    private const val shortNames = "jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun"
    private val abbreviation = Regex("(?:\\b(?:$shortNames|dr|mr|mrs|ms|st|no|approx|incl)|\\b[ap]\\.m|\\b[ap])$", RegexOption.IGNORE_CASE)
    // Quick entry reads "Oct 12" but not "Oct. 12".
    private val shortNameDot = Regex("\\b($shortNames)\\.(?=\\s)", RegexOption.IGNORE_CASE)
    private val sentenceEnd = Regex("[.!?]+\\s+")

    private data class Found(val date: LocalDate, val time: LocalTime?, val choices: List<LocalTime>)

    // The message's sentences: lines, split after . ! ? except after an abbreviation; a long one also at , and ; (Q6-12).
    internal fun sentences(text: String): List<String> = text.lines().flatMap { line ->
        val parts = mutableListOf<String>()
        var start = 0
        for (end in sentenceEnd.findAll(line)) {
            if (abbreviation.containsMatchIn(line.substring(start, end.range.first))) continue
            parts += line.substring(start, end.range.first + 1); start = end.range.last + 1
        }
        parts += line.substring(start)
        parts.flatMap { if (it.length > QuickEntry.MAX_LENGTH) it.split(Regex("[,;]\\s+")) else listOf(it) }
    }.map { it.trim() }.filter { it.length in 3..QuickEntry.MAX_LENGTH }

    fun find(text: String, today: LocalDate, now: LocalTime? = null): SharedWhen {
        val found = sentences(text.take(MAX_READ)).asSequence().take(MAX_SENTENCES).mapNotNull { said ->
            val sentence = said.replace(shortNameDot, "$1")
            val s = runCatching { QuickEntry.parse(sentence, today) }.getOrNull() ?: return@mapNotNull null
            if (!s.dateSpecified || s.pastDate || s.dateChoices.isNotEmpty() || s.date.isBefore(today) || s.error == QuickEntry.TOO_LONG ||
                s.repeat != RepeatRule.NONE || s.endDate != null) return@mapNotNull null
            val dateWords = s.phrases.filter { it.kind == QuickPhraseKind.DATE }.joinToString(" ") { sentence.substring(it.start, it.end) }
            val choices = s.timeChoices.takeIf { s.ambiguousTime }.orEmpty()
            val time = s.time?.takeIf { s.timed() && choices.isEmpty() }
            val plainDay = monthWord.containsMatchIn(dateWords) || numericWithYear.containsMatchIn(dateWords) ||
                weekdayWord.containsMatchIn(dateWords) && (time != null || choices.isNotEmpty())
            if (!plainDay || until.containsMatchIn(sentence)) return@mapNotNull null
            // Earlier today is gone: a reply's "today at 8:00" isn't ahead.
            if (s.date == today && now != null && time != null && time < now) return@mapNotNull null
            Found(s.date, time, choices)
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
