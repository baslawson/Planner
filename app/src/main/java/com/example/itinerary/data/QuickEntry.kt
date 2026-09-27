package com.example.itinerary.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class QuickPhraseKind(val label: String) {
    DATE("Date"), TIME("Time"), DURATION("Duration"), LOCATION("Place"), REMINDER("Reminder"), REPEAT("Repeat"), UNSUPPORTED("Unrecognised")
}
data class QuickEntryPhrase(val start: Int, val end: Int, val kind: QuickPhraseKind)

data class QuickEntrySuggestion(
    val title: String, val date: LocalDate, val time: LocalTime?, val error: String? = null,
    val dateSpecified: Boolean = false, val durationMinutes: Int? = null,
    val ambiguousTime: Boolean = false, val location: String = "",
    val phrases: List<QuickEntryPhrase> = emptyList(),
    val dateChoices: List<LocalDate> = emptyList(), val timeChoices: List<LocalTime> = emptyList(),
    val clarificationOnly: Boolean = false,
    val reminderMinutes: Int? = null, val repeat: RepeatRule = RepeatRule.NONE,
    val repeatCount: Int = 12, val repeatCountSpecified: Boolean = false,
    val timePrompt: String? = null,
)

/** Local, explicit grammar. Quoted text is literal; consumed spans keep their original offsets. */
object QuickEntry {
    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)
    private const val weekdays = "monday|mon|tuesday|tues|tue|wednesday|wed|thursday|thurs|thu|friday|fri|saturday|sat|sunday|sun"
    private const val months = "january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|august|aug|september|sept|sep|october|oct|november|nov|december|dec"
    private const val dayNumber = "\\d{1,2}(?:st|nd|rd|th)?"
    private const val countWords = "a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"
    private const val relativeCount = "(?:\\d+|$countWords)"
    private val dates = rx("\\b(?:on\\s+)?(?:(?:in\\s+$relativeCount\\s+(?:days?|weeks?)|$relativeCount\\s+(?:days?|weeks?)\\s+from\\s+today)|(?:the\\s+)?day\\s+after\\s+tomorrow|today|tomorrow|tmr|(?:(?:next|this)\\s+)?(?:$weekdays)|\\d{4}-\\d{2}-\\d{2}|$dayNumber\\s+(?:$months)(?:\\s+\\d{4})?|(?:$months)\\s+$dayNumber(?:,?\\s+\\d{4})?)\\b")
    private const val meridiem = "(?:am|pm|a\\.m\\.?|p\\.m\\.?)"
    private const val clock = "(?:noon|midnight|\\d{1,2}(?:[:.h]\\d{2})?(?:\\s*$meridiem)?)"
    private val ranges = rx("\\b(?:(?:from|at)\\s+)?($clock)\\s*(?:[-–—]|to|until)\\s*($clock)(?![\\w])")
    private val times = rx("\\b(?:at\\s+)?(?:noon|midnight|\\d{1,2}(?:[:.]\\d{2})?\\s*$meridiem|\\d{1,2}[:h]\\d{2})(?![\\w])|\\bat\\s+\\d{1,2}\\b(?![:.h])")
    private const val amount = "(?:-?\\d+(?:\\.\\d+)?|$countWords)"
    private const val hours = "(?:hours?|hrs?|h)"
    private const val minutes = "(?:minutes?|mins?|m)"
    private const val fraction = "(?:half\\s+(?:an?\\s+)?hour|(?:a\\s+)?quarter\\s+of\\s+an?\\s+hour)"
    private val durations = rx("\\bfor\\s+(?:$fraction|$amount\\s*$hours(?:\\s+and\\s+a\\s+half|\\s*(?:and\\s+)?$amount\\s*$minutes)?|$amount\\s*$minutes)\\b")
    private val durationParts = rx("($amount)\\s*($hours|$minutes)(?![a-z])")
    private val numericDate = rx("(?<![\\d:])\\b\\d{1,2}[/-]\\d{1,2}(?:[/-]\\d{2,4})?\\b(?![:\\d])")
    private val unfinished = rx("\\b(?:at|on|in|for|from|until|to|next|this)(?:\\s+(?:[-\\d.]+|$countWords|half|a quarter))?\\s*$")
    private val quote = Regex("\"[^\"]*\"")
    private val at = rx("\\bat\\s+")
    private val repeats = rx("\\b(?:every\\s+(?:$weekdays|day|week|(?:2|two|other)\\s+weeks?|fortnight|month|year)|daily|weekly|fortnightly|monthly|yearly)\\b")
    private val repeatCounts = rx("\\bfor\\s+(\\d+)\\s+(?:times?|occurrences?)\\b")
    private val reminders = rx("\\b(?:and\\s+)?(?:remind|notify)\\s+me\\s+($amount|half(?:\\s+an?)?)\\s*(minutes?|mins?|m|hours?|hrs?|h|days?|weeks?)\\s+before\\b")
    private val schedulingWords = rx("\\b(?:remind|notify|every)\\b")

    private val unsupported = rx("\\b(?:every\\s+(?:weekdays?|weekends?|other\\s+(?!weeks?\\b)\\w+|[3-9]\\s+weeks?)|(?:after|before)\\s+(?:breakfast|lunch|dinner|work)|(?:tomorrow|this|next)\\s+(?:morning|afternoon|evening|night|weekend)|tonight|in\\s+the\\s+(?:morning|afternoon|evening))\\b")

    fun parse(input: String, today: LocalDate, literalRanges: List<IntRange> = emptyList()): QuickEntrySuggestion {
        // Keep offsets identical to the text field, including repeated spaces/newlines.
        val text = input.replace('“', '"').replace('”', '"').map { if (it.isWhitespace()) ' ' else it }.joinToString("")
        val phrases = mutableListOf<QuickEntryPhrase>()
        fun error(message: String) = QuickEntrySuggestion(text.trim(), today, null, message, phrases = phrases.toList())
        if (text.count { it == '"' } % 2 != 0) return error("Close the quotation marks around your title or place name.")
        var remaining = text
        val consumed = mutableListOf<IntRange>()
        fun mask(range: IntRange, fill: Char = ' ') { remaining = remaining.replaceRange(range, fill.toString().repeat(range.count())) }
        fun consume(range: IntRange, kind: QuickPhraseKind) { consumed += range; phrases += QuickEntryPhrase(range.first, range.last + 1, kind); mask(range) }
        quote.findAll(text).forEach { mask(it.range, '\uE000') }
        literalRanges.filter { it.first >= 0 && it.last < text.length && !it.isEmpty() }.forEach { mask(it, '\uE000') }

        var timePrompt: String? = null
        var impliedToday = false
        unsupported.findAll(remaining).toList().forEach { match ->
            val value = match.value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
            val vagueTime = value.startsWith("tomorrow ") && !value.endsWith("weekend") ||
                value.startsWith("this ") && !value.endsWith("weekend") || value == "tonight" ||
                value.startsWith("in the ") || value.startsWith("after ") || value.startsWith("before ")
            if (!vagueTime) {
                phrases += QuickEntryPhrase(match.range.first, match.range.last + 1, QuickPhraseKind.UNSUPPORTED)
                return error("‘${match.value}’ needs a specific date, time or supported repeat. Edit it, or open Details → Adjust recognised text to keep it in the title.")
            }
            if (timePrompt != null) return error("Use one time phrase, or choose a specific time.")
            timePrompt = "What time did you mean by ‘${match.value}’? Tap Choose time."
            // Keep 'tomorrow' for the date parser; the rest is a time awaiting confirmation.
            val start = if (value.startsWith("tomorrow ")) match.range.first + "tomorrow".length else match.range.first
            impliedToday = value.startsWith("this ") || value == "tonight"
            consume(start..match.range.last, QuickPhraseKind.TIME)
        }

        // Protect a place before interpreting its words as dates. Later schedule clauses are independent.
        var location = ""
        for (marker in at.findAll(remaining).toList()) {
            val start = marker.range.last + 1
            val tail = remaining.substring(start)
            if (rx("^(?:\\d|noon\\b|midnight\\b)").containsMatchIn(tail)) continue
            val end = sequenceOf(dates, ranges, times, durations, numericDate, unfinished, repeats, reminders, repeatCounts, schedulingWords).flatMap { it.findAll(remaining, start) }
                .filter { it.range.first > start }.map { it.range.first }.plus(phrases.filter { it.kind == QuickPhraseKind.TIME && it.start > start }.map { it.start }).minOrNull() ?: text.length
            val value = text.substring(start, end).trim().trimEnd(',').replace("\"", "")
            if (value.isBlank()) return error("Add a place after at, or remove at.")
            location = value
            consume(marker.range.first until end, QuickPhraseKind.LOCATION)
            break
        }

        var reminderMinutes: Int? = null
        val reminderMatches = reminders.findAll(remaining).toList()
        if (reminderMatches.size > 1) return error("Use one reminder here. Add more in More details.")
        reminderMatches.firstOrNull()?.let { match ->
            val amountText = match.groupValues[1].lowercase(Locale.ROOT)
            val number = if (amountText.startsWith("half")) 0.5 else readAmount(amountText)
            val unit = match.groupValues[2].lowercase(Locale.ROOT)
            val total = number * when { unit.startsWith("h") -> 60; unit.startsWith("d") -> 1440; unit.startsWith("w") -> 10080; else -> 1 }
            if (!total.isFinite() || total !in 0.0..525600.0 || total % 1 != 0.0)
                return error("Use a reminder from 0 to 525600 whole minutes before the event.")
            reminderMinutes = total.toInt()
            val comma = remaining.substring(0, match.range.first).indexOfLast { !it.isWhitespace() }
            val start = if (comma >= 0 && remaining[comma] == ',') comma else match.range.first
            consume(start..match.range.last, QuickPhraseKind.REMINDER)
        }
        var repeat = RepeatRule.NONE
        var repeatDay: DayOfWeek? = null
        val repeatMatches = repeats.findAll(remaining).toList()
        if (repeatMatches.size > 1) return error("Use one repeat rule. Adjust it in More details.")
        repeatMatches.firstOrNull()?.let { match ->
            val rule = match.value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").removePrefix("every ")
            repeat = when (rule) {
                "day", "daily" -> RepeatRule.DAILY
                "week", "weekly" -> RepeatRule.WEEKLY
                "2 week", "2 weeks", "two week", "two weeks", "other week", "other weeks", "fortnight", "fortnightly" -> RepeatRule.FORTNIGHTLY
                "month", "monthly" -> RepeatRule.MONTHLY
                "year", "yearly" -> RepeatRule.YEARLY
                else -> {
                    repeatDay = DayOfWeek.entries.first { it.name.lowercase(Locale.ROOT).startsWith(rule.take(3)) }
                    RepeatRule.WEEKLY
                }
            }
            consume(match.range, QuickPhraseKind.REPEAT)
        }
        val countMatches = repeatCounts.findAll(remaining).toList()
        if (countMatches.size > 1) return error("Use one occurrence count.")
        var repeatCount = 12
        countMatches.firstOrNull()?.let { match ->
            repeatCount = match.groupValues[1].toIntOrNull() ?: 0
            if (repeat == RepeatRule.NONE || repeatCount !in 2..365) return error("Choose a repeat rule and between 2 and 365 occurrences.")
            consume(match.range, QuickPhraseKind.REPEAT)
        }

        // Only explicit adjacent date corrections replace a date; unrelated dates still need review.
        val correctionDates = dates.findAll(remaining).toList()
        correctionDates.zipWithNext().forEach { (old, replacement) ->
            val bridge = remaining.substring(old.range.last + 1, replacement.range.first)
            if (rx("\\s*[,—–-]?\\s*actually\\s+(?:on\\s+)?").matches(bridge))
                consume(old.range.first until replacement.range.first, QuickPhraseKind.DATE)
        }
        dates.findAll(remaining).lastOrNull()?.let { last ->
            if (rx("\\s*[,—–-]?\\s*actually\\s*(?:on\\s*)?").matches(remaining.substring(last.range.last + 1)))
                return error("Finish the corrected date after actually.")
        }
        val ds = dates.findAll(remaining).toList()
        val meridiemRanges = ranges.findAll(remaining).filter { rx("$meridiem$").containsMatchIn(it.groupValues[2]) }.toList()
        val numeric = numericDate.findAll(remaining).filter { n -> (ds + meridiemRanges).none { d -> n.range.first <= d.range.last && d.range.first <= n.range.last } }.toList()
        if (ds.size + numeric.size > 1 || impliedToday && (ds.isNotEmpty() || numeric.isNotEmpty())) {
            phrases += (ds + numeric).map { QuickEntryPhrase(it.range.first, it.range.last + 1, QuickPhraseKind.DATE) }
            return error("Use one date. Remove the extra date or keep literal words in the title.")
        }
        var date = if (impliedToday) today else repeatDay?.let { today.with(TemporalAdjusters.nextOrSame(it)) } ?: today
        var dateChoices = emptyList<LocalDate>()
        if (ds.isNotEmpty()) {
            date = parseDate(ds.single().value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").removePrefix("on "), today)
                ?: return error("That date isn't valid.")
            consume(ds.single().range, QuickPhraseKind.DATE)
        }
        numeric.firstOrNull()?.let { match ->
            val parts = match.value.split('/', '-')
            if (parts.size == 3 && parts[2].length != 4) return error("Use a four-digit year, for example 03/04/2027.")
            val year = parts.getOrNull(2)?.toIntOrNull() ?: today.year
            fun candidate(day: Int, month: Int): LocalDate? = runCatching {
                val value = LocalDate.of(year, month, day)
                if (parts.size == 2 && value < today) LocalDate.of(year + 1, month, day) else value
            }.getOrNull()?.takeIf { it.year in 1..9999 }
            dateChoices = listOfNotNull(candidate(parts[0].toInt(), parts[1].toInt()), candidate(parts[1].toInt(), parts[0].toInt())).distinct()
            if (dateChoices.isEmpty()) return error("That date isn't valid.")
            if (repeatDay != null) dateChoices = dateChoices.filter { it.dayOfWeek == repeatDay }
            if (dateChoices.isEmpty()) return error("The date doesn't match the repeating weekday.")
            date = dateChoices.first()
            consume(match.range, QuickPhraseKind.DATE)
            if (dateChoices.size == 1) dateChoices = emptyList()
        }
        if (repeatDay != null && dateChoices.isEmpty() && date.dayOfWeek != repeatDay)
            return error("The start date doesn't match the repeating weekday. Choose a matching date.")

        val lengths = durations.findAll(remaining).toList()
        if (lengths.size > 1) return error("Use one duration, such as for 1h 30m.")
        var duration = lengths.firstOrNull()?.let {
            val value = it.value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
            val total = if (value.startsWith("for half")) 30.0 else if ("quarter" in value) 15.0 else durationParts.findAll(value).sumOf { part ->
                val number = readAmount(part.groupValues[1])
                number * if (part.groupValues[2].startsWith("h")) 60 else 1
            } + if (value.endsWith("and a half")) 30.0 else 0.0
            if (!total.isFinite() || total !in 1.0..1440.0 || total % 1 != 0.0 || value.contains('-'))
                return error("Use a duration from 1 minute to 24 hours, in whole minutes.")
            consume(it.range, QuickPhraseKind.DURATION)
            total.toInt()
        }
        // Explicit adjacent clock corrections only; never discard an endpoint from a range.
        val protectedRanges = ranges.findAll(remaining).map { it.range }.toList()
        val correctionTimes = times.findAll(remaining).filter { t ->
            protectedRanges.none { t.range.first <= it.last && it.first <= t.range.last }
        }.toList()
        correctionTimes.zipWithNext().forEach { (old, replacement) ->
            val bridge = remaining.substring(old.range.last + 1, replacement.range.first)
            if (rx("\\s*[,—–-]?\\s*actually\\s+(?:at\\s+)?").matches(bridge))
                consume(old.range.first until replacement.range.first, QuickPhraseKind.TIME)
        }
        correctionTimes.lastOrNull()?.let { last ->
            if (rx("^\\s*[,—–-]?\\s*actually\\b").containsMatchIn(remaining.substring(last.range.last + 1)))
                return error("Finish the corrected time after actually, for example actually 4pm.")
        }
        val rs = ranges.findAll(remaining).toList()
        if (rs.size > 1) return error("Use one time range.")
        var time: LocalTime? = null
        var ambiguous = timePrompt != null
        var timeChoices = emptyList<LocalTime>()
        if (rs.isNotEmpty()) {
            val range = rs.single()
            val endpoints = listOf(range.groupValues[1], range.groupValues[2]).map(::normaliseClock)
            val hasMeridiem = endpoints.map { rx("(?:am|pm)$").containsMatchIn(it) }
            // A trailing suffix can cover an increasing range within the same half-day: 3–4pm.
            // Never infer across noon/midnight or reinterpret an explicit 24-hour start.
            var start = readTime(endpoints[0])
            val end = readTime(endpoints[1])
            if (!hasMeridiem[0] && hasMeridiem[1] && endpoints[0].matches(rx("[1-9]\\d?(?::\\d{2})?"))) {
                val candidate = readTime(endpoints[0] + endpoints[1].takeLast(2))
                if (candidate == null || end == null || !candidate.isBefore(end))
                    return error("Give am/pm on both range times, for example 9am–5pm.")
                start = candidate
            } else if (hasMeridiem[0] != hasMeridiem[1] && endpoints.any { ':' in it && !rx("(?:am|pm)$").containsMatchIn(it) })
                return error("Use am/pm on both times, or use 24-hour times on both, for example 14:00–15:30.")
            if (start == null || end == null) return error("Give both range times explicitly, for example 2pm–3:30pm or 14:00–15:30.")
            val length = Math.floorMod(end.toSecondOfDay() / 60 - start.toSecondOfDay() / 60, 1440)
            if (length == 0) return error("The start and end times are the same. Use a duration if you mean 24 hours.")
            if (duration != null && duration != length) return error("The time range and duration disagree. Correct one or remove it.")
            time = start; duration = length
            consume(range.range, QuickPhraseKind.TIME)
        }
        val ts = times.findAll(remaining).toList()
        if (ts.size > 1 || ts.isNotEmpty() && rs.isNotEmpty()) {
            phrases += ts.map { QuickEntryPhrase(it.range.first, it.range.last + 1, QuickPhraseKind.TIME) }
            return error("Use one start time or one time range.")
        }
        ts.firstOrNull()?.let {
            val raw = it.value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").removePrefix("at ")
            time = readTime(raw)
            if (time == null) {
                if (raw.matches(Regex("\\d{1,2}")) && raw.toInt() in 0..23) {
                    ambiguous = true
                    val hour = raw.toInt()
                    timeChoices = if (hour in 1..12) listOf(LocalTime.of(hour % 12, 0), LocalTime.of(hour % 12 + 12, 0)) else listOf(LocalTime.of(hour, 0))
                }
                else return error("That time isn't valid.")
            }
            consume(it.range, QuickPhraseKind.TIME)
        }
        if (timePrompt != null && (time != null || ts.isNotEmpty() || rs.isNotEmpty()))
            return error("Use one time: remove the vague phrase or the extra clock time.")
        schedulingWords.find(remaining)?.let { match ->
            val end = remaining.indexOf(',', match.range.first).takeIf { it >= 0 } ?: remaining.length
            phrases += QuickEntryPhrase(match.range.first, end, QuickPhraseKind.UNSUPPORTED)
            return error("Finish the reminder or repeat phrase, or open Details → Adjust recognised text to keep those words in the title.")
        }
        // Scheduling-shaped fragments must not silently turn into part of a saved title.
        if (duration != null && rx("\\band(?:\\s+(?:a|half|\\d+))?\\s*$").containsMatchIn(remaining))
            return error("Finish the duration, for example for 1 hour and 30 minutes.")
        if (rx("\\b(?:in\\s+(?:-?\\d+|$countWords)\\s*\\w*|for\\s+(?:$amount|half)\\s*(?:$hours|$minutes)|\\d{1,2}[:h]\\d*|\\d{1,2}\\.\\d+\\s*$meridiem)\\b").containsMatchIn(remaining) ||
            unfinished.containsMatchIn(remaining) ||
            rx("(?:[-–—]\\s*$|\\b(?:to|until)\\s+\\d)").containsMatchIn(remaining))
            return error("Finish the date, time or duration, or put literal title text in quotes.")
        var title = text
        consumed.sortedByDescending { it.first }.forEach { title = title.replaceRange(it, " ") }
        title = title.replace("\"", "").trim().replace(Regex("\\s+"), " ")
        val clarification = when {
            dateChoices.isNotEmpty() -> "Which date did you mean?"
            ambiguous -> timePrompt ?: "Morning or afternoon? Choose a time below, or type am or pm."
            else -> null
        }
        return QuickEntrySuggestion(title, date, time,
            if (title.isBlank()) "Add a name." else clarification,
            impliedToday || ds.isNotEmpty() || numeric.isNotEmpty() || repeat != RepeatRule.NONE, duration, ambiguous, location,
            phrases.sortedBy { it.start }, dateChoices, timeChoices, clarification != null && title.isNotBlank(),
            reminderMinutes, repeat, repeatCount, countMatches.isNotEmpty(), timePrompt)
    }

    private fun readAmount(value: String): Double {
        if (value == "a" || value == "an") return 1.0
        val words = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")
        return words.indexOf(value).takeIf { it >= 0 }?.let { (it + 1).toDouble() } ?: value.toDoubleOrNull() ?: Double.NaN
    }

    private fun normaliseClock(raw: String): String = raw.lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), "").replace("a.m.", "am").replace("p.m.", "pm")
        .replace("a.m", "am").replace("p.m", "pm").replace('.', ':').replace(Regex("(?<=\\d)h(?=\\d)"), ":")

    private fun readTime(raw: String): LocalTime? {
        val value = normaliseClock(raw)
        if (value == "noon") return LocalTime.NOON
        if (value == "midnight") return LocalTime.MIDNIGHT
        val ampm = value.takeLast(2).takeIf { it == "am" || it == "pm" }
        val parts = (if (ampm != null) value.dropLast(2) else value).split(':')
        var hour = parts[0].toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (ampm == null && parts.size == 1 || minute !in 0..59 || hour !in (if (ampm == null) 0..23 else 1..12)) return null
        if (ampm != null) hour = hour % 12 + if (ampm == "pm") 12 else 0
        return LocalTime.of(hour, minute)
    }

    private fun parseDate(d: String, today: LocalDate): LocalDate? = runCatching {
        when {
            d == "today" -> today
            d == "tomorrow" || d == "tmr" -> today.plusDays(1)
            d == "day after tomorrow" || d == "the day after tomorrow" -> today.plusDays(2)
            d.startsWith("in ") || d.endsWith(" from today") -> {
                val parts = d.removePrefix("in ").removeSuffix(" from today").split(' ')
                val words = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")
                val count = when (parts[0]) {
                    "a", "an" -> 1L
                    in words -> (words.indexOf(parts[0]) + 1).toLong()
                    else -> parts[0].toLong()
                }
                today.plusDays(Math.multiplyExact(count, if (parts[1].startsWith("week")) 7L else 1L))
            }
            d.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> LocalDate.parse(d)
            rx("\\d").containsMatchIn(d) -> {
                val parts = d.replace(",", "").split(' ')
                val dayFirst = parts[0].first().isDigit()
                val monthWord = parts[if (dayFirst) 1 else 0]
                val day = parts[if (dayFirst) 0 else 1].takeWhile { it.isDigit() }.toInt()
                val month = Month.entries.first { it.name.lowercase(Locale.ROOT).startsWith(monthWord.take(3)) }
                val year = parts.getOrNull(2)?.toInt() ?: today.year
                val candidate = LocalDate.of(year, month, day)
                if (parts.size == 2 && candidate < today) LocalDate.of(year + 1, month, day) else candidate
            }
            else -> {
                val word = d.substringAfterLast(' ')
                val day = DayOfWeek.entries.first { it.name.lowercase(Locale.ROOT).startsWith(word.take(3)) }
                when {
                    d.startsWith("next ") -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1).with(day)
                    d.startsWith("this ") -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).with(day)
                    else -> today.with(TemporalAdjusters.nextOrSame(day))
                }
            }
        }
    }.getOrNull()?.takeIf { it.year in 1..9999 }
}
