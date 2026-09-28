package com.example.itinerary.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
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
    /** "Remind me to …": suggests a task (or an event when a time is given). */
    val taskHint: Boolean = false,
    /** The reminder came from "remind me to", not from an explicit "… before". */
    val reminderImplied: Boolean = false,
)

/** Local, explicit grammar. Quoted text is literal; consumed spans keep their original offsets. */
object QuickEntry {
    /** How to read a numeric date such as 3/4: day first, month first, or null to ask. Follows Settings → Date format. */
    @Volatile var numericDayFirst: Boolean? = null
    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)
    private const val weekdays = "monday|mon|tuesday|tues|tue|wednesday|weds|wed|thursday|thurs|thur|thu|friday|fri|saturday|sat|sunday|sun"
    private const val months = "january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|august|aug|september|sept|sep|october|oct|november|nov|december|dec"
    private const val dayNumber = "\\d{1,2}(?:st|nd|rd|th)?"
    private const val countWords = "a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"
    private const val relativeCount = "(?:\\d+|$countWords)"
    private const val relativeUnit = "(?:days?|weeks?|wks?|fortnights?|months?|years?)"
    /** Spellings read as tomorrow outright, not only offered as a correction while typing. */
    val tomorrowSpellings = listOf("tomorrow", "tmr", "tmrw", "tomoz", "tomorow", "tommorow", "tommorrow")
    private val tomorrowWords = tomorrowSpellings.joinToString("|")
    // Dates built from words that are otherwise unsupported on their own: next week, weekend, end of, next month.
    private const val weekAfterNext = "(?:the\\s+)?week\\s+after\\s+next\\s+(?:on\\s+)?(?:$weekdays)|(?:$weekdays)\\s+(?:the\\s+)?week\\s+after\\s+next"
    private const val nextWeekDay = "next\\s+week\\s+(?:on\\s+)?(?:$weekdays)|(?:$weekdays)\\s+next\\s+week"
    // "Friday week", "a week on Friday": the Friday after the coming one.
    private const val weekOn = "(?:$weekdays)\\s+week(?!\\s+after)|a\\s+week\\s+(?:on|from)\\s+(?:$weekdays)"
    private const val endOfMonth = "(?:(?:at|by)\\s+)?(?:the\\s+)?(?:end|last\\s+day)\\s+of\\s+(?:the\\s+)?month"
    private const val nextMonthDay = "(?:the\\s+)?\\d{1,2}(?:st|nd|rd|th)\\s+of\\s+next\\s+month"
    private const val wordDates = "$weekAfterNext|$nextWeekDay|$weekOn|this\\s+weekend|$endOfMonth|$nextMonthDay"
    private val wordDateMatches = rx("\\b(?:$wordDates)\\b")
    private val dates = rx("\\b(?:(?<!-)(?:on|by)\\s+)?(?:$wordDates|(?:in\\s+$relativeCount\\s+$relativeUnit|$relativeCount\\s+$relativeUnit\\s+from\\s+today)|(?:the\\s+)?day\\s+after\\s+tomorrow|today|$tomorrowWords|(?:(?:next|this)\\s+)?(?:$weekdays)|\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|(?:the\\s+)?$dayNumber\\s+(?:of\\s+)?(?:$months)(?:\\s+\\d{4})?|(?:$months)\\s+$dayNumber(?:,?\\s+\\d{4})?|the\\s+\\d{1,2}(?:st|nd|rd|th))\\b")
    private const val meridiem = "(?:am|pm|a\\.m\\.?|p\\.m\\.?)"
    // 24-hour times written as four digits: 0600, 1500, 0000, optionally 1500hrs.
    private const val hhmm = "(?:[01]\\d|2[0-3])[0-5]\\d"
    private const val hoursSuffix = "(?:hrs?|hours?|h)"
    private const val noonWords = "noon|midday|mid-day"
    private const val clock = "(?:$hhmm(?:\\s*$hoursSuffix)?|$noonWords|midnight|\\d{1,2}(?:[:.h]\\d{2})?(?:\\s*$meridiem)?)"
    private val ranges = rx("\\b(?:(?:from|at)\\s+)?($clock)\\s*(?:[-–—]|to|until|till?|'til)\\s*($clock)(?![\\w])")
    // "@" works like "at", but only as a word of its own: bob@example.com is not a time or a place.
    private const val atWord = "(?:\\bat\\s+|(?<!\\S)@\\s*)"
    // Spoken times: half past 3, quarter to 5pm, 20 past 2, ten to 12, 3 o'clock. Only words go before "to",
    // because "4 to 5pm" is a range.
    private const val spokenTime = "(?:(?:half|quarter|\\d{1,2})\\s+past|(?:quarter|five|ten|twenty(?:[-\\s]five)?)\\s+to)\\s+\\d{1,2}(?:\\s*$meridiem)?|\\d{1,2}\\s*o['’]?\\s?clock(?:\\s*$meridiem)?"
    // "around 3pm", "about 3pm", "~3pm", "3pm-ish", "7ish": approximately is still the time.
    private const val approx = "(?:(?:around|about|approx(?:imately)?|roughly|circa)\\s+|~\\s*)"
    // 3p, 3:30p and 15.30 are times only where they read like one; see readsAsTime.
    private const val shortClock = "\\d{1,2}(?::\\d{2})?[ap]|\\d{1,2}\\.[0-5]\\d"
    private val times = rx("(?:$atWord|\\bby\\s+|\\b|(?<!\\S)(?=~))(?:(?:at\\s+)?$approx)?(?:$spokenTime|$hhmm(?:\\s*$hoursSuffix)?|$noonWords|midnight|\\d{1,2}(?:[:.]\\d{2})?\\s*$meridiem|\\d{1,2}[:h]\\d{2}|$shortClock|\\d{1,2}(?=-?ish))(?:\\s*-?ish)?(?![\\w])|$atWord\\d{1,2}\\b(?![:.h])")
    private val shortClocks = rx("(?<![\\w.:/])($shortClock)(?![\\w.])")
    private const val amount = "(?:-?\\d+(?:\\.\\d+)?|$countWords)"
    private const val hours = "(?:hours?|hrs?|h)"
    private const val minutes = "(?:minutes?|mins?|m)"
    private const val fraction = "(?:half\\s+(?:an?\\s+)?hour|(?:a\\s+)?quarter\\s+of\\s+an?\\s+hour)"
    private const val span = "(?:$fraction|$amount\\s*$hours(?:\\s+and\\s+a\\s+half|\\s*(?:and\\s+)?$amount\\s*$minutes)?|$amount\\s*$minutes)"
    private val durations = rx("\\bfor\\s+$span\\b")
    private val relativeTimes = rx("\\bin\\s+$span\\b")
    private val durationParts = rx("($amount)\\s*($hours|$minutes)(?![a-z])")
    private val numericDate = rx("(?<![\\d:/.])\\b\\d{1,2}(?:[/-]\\d{1,2}(?:[/-]\\d{2,4})?|\\.\\d{1,2}\\.(?:\\d{4}|\\d{2}))\\b(?![:\\d]|[/.]\\d)")
    // Not after a hyphen: "check-in", "sign-on" are words, not unfinished phrases.
    private val unfinished = rx("(?<![-\\w])(?:at|on|in|for|from|until|to|next|this)(?:\\s+(?:[-\\d.]+|$countWords|half|a quarter))?\\s*$")
    private val quote = Regex("\"[^\"]*\"")
    private val at = rx(atWord)
    private const val pluralWeekdays = "mondays|tuesdays|wednesdays|thursdays|fridays|saturdays|sundays"
    private val repeats = rx("\\b(?:(?:every|each)\\s+weekdays?|on\\s+weekdays|every\\s+other\\s+(?:$weekdays)|(?:on\\s+)?(?:$pluralWeekdays)|every\\s+(?:$weekdays|day|week|(?:2|two|other)\\s+weeks?|fortnight|month|year)|daily|weekly|fortnightly|monthly|yearly|weekdays)\\b")
    private val repeatCounts = rx("\\bfor\\s+(\\d+)\\s+(?:times?|occurrences?)\\b")
    // With a repeat, "for 10 weeks" says how long it runs. Without one it stays title text: Holiday for 2 weeks.
    private val repeatPeriods = rx("\\bfor\\s+($relativeCount)\\s+(days?|weeks?|wks?|fortnights?|months?|years?)\\b")
    private const val reminderSpan = "$span|(?:$amount|half(?:\\s+an?)?)\\s*(?:days?|weeks?)|the\\s+(?:day|week)"
    private val reminders = rx("\\b(?:and\\s+)?(?:remind|notify)\\s+me\\s+($reminderSpan)\\s+before\\b")
    // "remind me at 9am", "remind me at 8pm the day before": a clock time, turned into minutes before once the event time is known.
    private val reminderClocks = rx("\\b(?:and\\s+)?(?:remind|notify)\\s+me\\s+(?:at\\s+|@\\s*)($clock)(\\s+(?:on\\s+)?the\\s+day\\s+before)?(?![\\w])")
    private val monthDayRepeat = rx("\\b(?:on\\s+)?(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)\\s+(?:of\\s+)?(?:every|each)\\s+month\\b")
    private val schedulingWords = rx("\\b(?:remind|notify|every)\\b")
    // "Remind me to …", also with the when first: "Remind me tomorrow to …", "Remind me in 2 hours to …".
    private val remindTo = rx("^\\s*(?:please\\s+)?remind\\s+me\\s+(?:(.{1,60}?)\\s+)??to\\b")
    private val allDay = rx("\\ball[-\\s]day\\b")
    private val nowWords = rx("\\b(?:right\\s+)?now\\b")
    // Named days set the date but stay in the title. Plain "Christmas" only with on, or lunch/dinner/breakfast/morning:
    // "Christmas shopping" and "Christmas party" are usually before the day.
    private val holidays = rx("\\b(?:christmas\\s+eve|(?:christmas|xmas)\\s+day|boxing\\s+day|new\\s+year['’]?s\\s+eve|new\\s+year['’]?s\\s+day|valentine['’]?s(?:\\s+day)?|hallowe['’]?en|(?<=\\bon\\s)(?:christmas|xmas)|(?:christmas|xmas)(?=\\s+(?:lunch|dinner|breakfast|morning)\\b))(?![\\w'’])" +
        // Getting ready happens before the day: "Halloween costume shopping", "Christmas Eve prep".
        "(?!\\s+(?:shopping|costumes?|prep\\w*|planning|decorations?|cards?|gifts?|presents?)\\b)")
    private val bareWeekday = rx("^(?:on\\s+)?(?:$weekdays)$")
    private val fourDigits = rx("(?<![\\w.:/])(\\d{4})(?=(?:$hoursSuffix)?\\b)(?![/.:]\\d)")

    // Short words that are also ordinary title words: "Buy sun cream", "Sat nav", "Midnight Mass".
    // They count as scheduling only beside other scheduling words, never before an ordinary word.
    private val titleWordCandidates = rx("\\b(sun|sat|wed|noon|midnight|midday|now)\\b")
    private val nextWord = Regex("^[\\s,]*([A-Za-z]+)")
    private val scheduleVocabulary = (listOf("at", "on", "in", "from", "for", "to", "until", "till", "by", "with", "and", "then",
        "before", "after", "actually", "every", "each", "remind", "notify", "next", "this", "the", "today", "tomorrow", "tmr",
        "tonight", "morning", "afternoon", "evening", "night", "noon", "midnight", "midday", "am", "pm", "til", "all") +
        weekdays.split('|') + months.split('|') + tomorrowSpellings).toSet()

    private val unsupported = rx("\\b(?:every\\s+(?:weekends?|other\\s+(?!weeks?\\b|(?:$weekdays)\\b)\\w+|[3-9]\\s+weeks?)|(?:first|second|third|fourth|last|1st|2nd|3rd|4th)\\s+(?:$weekdays)\\s+of\\s+(?:every|each|the)\\s+month|(?:after|before)\\s+(?:breakfast|lunch|dinner|work)|(?:$tomorrowWords|this|next)\\s+(?:morning|afternoon|evening|night|weekend)|(?:this|next)\\s+(?:week|month|year)|(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?(?:week|month|year)|tonight|in\\s+the\\s+(?:morning|afternoon|evening))\\b")
    private val vagueTimes = rx("(?:morning|afternoon|evening|night|tonight|breakfast|lunch|dinner|work)$")
    // A part of the day that also settles am/pm for a clock time beside it: tomorrow morning at 7.
    private val dayPeriods = mapOf(
        "morning" to LocalTime.of(4, 0)..LocalTime.of(11, 59), "afternoon" to LocalTime.NOON..LocalTime.of(17, 59),
        "evening" to LocalTime.of(16, 0)..LocalTime.of(23, 59), "night" to LocalTime.of(17, 0)..LocalTime.of(23, 59),
        "tonight" to LocalTime.of(17, 0)..LocalTime.of(23, 59))

    /** [now] enables "in 30 minutes"; without it such phrases ask for a time. */
    fun parse(input: String, today: LocalDate, literalRanges: List<IntRange> = emptyList(),
              now: LocalDateTime? = null, dayFirst: Boolean? = numericDayFirst): QuickEntrySuggestion {
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
        titleWordCandidates.findAll(remaining).toList().forEach { match ->
            val clockWord = match.value.lowercase(Locale.ROOT) in setOf("noon", "midnight", "midday")
            val lead = if (clockWord) "at|from|until|till?|to|by" else "on|next|this|every|each|from|until|till?|to|by"
            if (rx("(?:\\b(?:$lead)\\s+|[-–—]\\s*)$").containsMatchIn(remaining.substring(0, match.range.first))) return@forEach
            val following = nextWord.find(remaining.substring(match.range.last + 1))?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: return@forEach
            // "now" is scheduling only at the end: "Meeting now", but "now and then", "Now TV".
            if (following !in scheduleVocabulary || match.value.equals("now", ignoreCase = true)) mask(match.range, '\uE000')
        }
        var taskHint = false
        remindTo.find(remaining)?.let { match ->
            taskHint = true
            // The when between "remind me" and "to" is left for the date and time parsers.
            val between = match.groups[1]
            if (between == null) consume(match.range, QuickPhraseKind.REMINDER) else {
                consume(match.range.first until between.range.first, QuickPhraseKind.REMINDER)
                consume(between.range.last + 1..match.range.last, QuickPhraseKind.REMINDER)
            }
        }

        var timePrompt: String? = null
        var dayPeriod: String? = null
        var periodHour: Int? = null
        var impliedToday = false
        val wordDateRanges = wordDateMatches.findAll(remaining).map { it.range }.toList()
        unsupported.findAll(remaining).toList().forEach { match ->
            if (wordDateRanges.any { it.first <= match.range.last && match.range.first <= it.last }) return@forEach
            val value = match.value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
            if (!vagueTimes.containsMatchIn(value)) {
                phrases += QuickEntryPhrase(match.range.first, match.range.last + 1, QuickPhraseKind.UNSUPPORTED)
                return error("‘${match.value}’ needs a specific date, time or supported repeat. Edit it, or open Details → Adjust recognised text to keep it in the title.")
            }
            if (timePrompt != null) return error("Use one time phrase, or choose a specific time.")
            timePrompt = "What time did you mean by ‘${match.value}’? Tap Choose time."
            dayPeriod = dayPeriods.keys.firstOrNull { value.endsWith(it) }
            // Keep 'tomorrow' for the date parser; the rest is a time awaiting confirmation.
            val first = value.substringBefore(' ')
            var start = if (first in tomorrowSpellings && ' ' in value) match.range.first + first.length else match.range.first
            impliedToday = value.startsWith("this ") || value == "tonight"
            // "3 in the afternoon", "8 tonight": the number is the hour.
            if (dayPeriod != null) rx("(?<![\\w.:/£€¥$])(\\d{1,2})\\s+$").find(remaining.substring(0, start))?.let { hour ->
                // After at/@ the ordinary time rules take it: "Dinner at 7 tonight".
                val afterAt = rx("(?:\\bat\\s+|@\\s*)$").containsMatchIn(remaining.substring(0, hour.range.first))
                if (!afterAt && hour.groupValues[1].toInt() in 1..12) { periodHour = hour.groupValues[1].toInt(); start = hour.range.first }
            }
            consume(start..match.range.last, QuickPhraseKind.TIME)
        }

        // Protect a place before interpreting its words as dates. Later schedule clauses are independent.
        var location = ""
        for (marker in at.findAll(remaining).toList()) {
            val start = marker.range.last + 1
            val tail = remaining.substring(start)
            if (rx("^(?:\\d|~|noon\\b|midd?ay\\b|mid-day\\b|midnight\\b|(?:$spokenTime)|$approx\\d|(?:the\\s+)?(?:end|last\\s+day)\\s+of\\s+(?:the\\s+)?month\\b)").containsMatchIn(tail)) continue
            val end = sequenceOf(dates, ranges, times, durations, relativeTimes, numericDate, unfinished, repeats, monthDayRepeat, reminders, reminderClocks, repeatCounts, allDay, schedulingWords).flatMap { it.findAll(remaining, start) }
                .filter { it.range.first > start }.map { it.range.first }.plus(phrases.filter { it.kind == QuickPhraseKind.TIME && it.start > start }.map { it.start }).minOrNull() ?: text.length
            val value = text.substring(start, end).trim().trimEnd(',').replace("\"", "")
            if (value.isBlank()) return error("Add a place after at, or remove at.")
            location = value
            consume(marker.range.first until end, QuickPhraseKind.LOCATION)
            break
        }

        var reminderMinutes: Int? = null
        var reminderAt: LocalTime? = null
        var reminderDayBefore = false
        val reminderMatches = reminders.findAll(remaining).toList()
        val reminderClockMatches = reminderClocks.findAll(remaining).toList()
        if (reminderMatches.size + reminderClockMatches.size > 1) return error("Use one reminder here. Add more in More details.")
        fun consumeReminder(match: MatchResult) {
            val comma = remaining.substring(0, match.range.first).indexOfLast { !it.isWhitespace() }
            val start = if (comma >= 0 && remaining[comma] == ',') comma else match.range.first
            consume(start..match.range.last, QuickPhraseKind.REMINDER)
        }
        reminderMatches.firstOrNull()?.let { match ->
            val total = reminderSpanMinutes(match.groupValues[1].lowercase(Locale.ROOT).replace(Regex("\\s+"), " "))
            if (!total.isFinite() || total !in 0.0..525600.0 || total % 1 != 0.0)
                return error("Use a reminder from 0 to 525600 whole minutes before the event.")
            reminderMinutes = total.toInt()
            consumeReminder(match)
        }
        reminderClockMatches.firstOrNull()?.let { match ->
            val raw = match.groupValues[1]
            reminderAt = readTime(raw)?.takeUnless { Regex("([1-9]|1[0-2])[:.]([0-5]\\d)").matches(raw.trim()) }
                ?: return error("Add am or pm to the reminder time, for example remind me at 9am.")
            reminderDayBefore = match.groupValues[2].isNotBlank()
            consumeReminder(match)
        }
        val allDayMatches = allDay.findAll(remaining).toList()
        allDayMatches.forEach { consume(it.range, QuickPhraseKind.TIME) }
        var repeat = RepeatRule.NONE
        var repeatDay: DayOfWeek? = null
        var monthDay: Int? = null
        val monthDayMatches = monthDayRepeat.findAll(remaining).toList()
        monthDayMatches.firstOrNull()?.let { match ->
            monthDay = match.groupValues[1].toInt().takeIf { it in 1..31 } ?: return error("That date isn't valid.")
            repeat = RepeatRule.MONTHLY
            consume(match.range, QuickPhraseKind.REPEAT)
        }
        val repeatMatches = repeats.findAll(remaining).toList()
        if (repeatMatches.size + monthDayMatches.size > 1) return error("Use one repeat rule. Adjust it in More details.")
        repeatMatches.firstOrNull()?.let { match ->
            val rule = match.value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").removePrefix("every ").removePrefix("on ")
            repeat = if ("weekday" in rule) RepeatRule.WEEKDAYS else when (rule) {
                "day", "daily" -> RepeatRule.DAILY
                "week", "weekly" -> RepeatRule.WEEKLY
                "2 week", "2 weeks", "two week", "two weeks", "other week", "other weeks", "fortnight", "fortnightly" -> RepeatRule.FORTNIGHTLY
                "month", "monthly" -> RepeatRule.MONTHLY
                "year", "yearly" -> RepeatRule.YEARLY
                else -> {
                    // A weekday, "tuesdays", or "other friday" (fortnightly on Fridays).
                    val day = rule.removePrefix("other ")
                    repeatDay = DayOfWeek.entries.first { it.name.lowercase(Locale.ROOT).startsWith(day.take(3)) }
                    if (rule.startsWith("other ")) RepeatRule.FORTNIGHTLY else RepeatRule.WEEKLY
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
        var repeatPeriod: Pair<Long, String>? = null
        if (repeat != RepeatRule.NONE) {
            val periods = repeatPeriods.findAll(remaining).toList()
            if (periods.size + countMatches.size > 1) return error("Use one occurrence count.")
            periods.firstOrNull()?.let { match ->
                val count = readAmount(match.groupValues[1].lowercase(Locale.ROOT))
                if (!count.isFinite() || count < 1 || count > 1000) return error("Choose a repeat rule and between 2 and 365 occurrences.")
                repeatPeriod = count.toLong() to match.groupValues[2].lowercase(Locale.ROOT)
                consume(match.range, QuickPhraseKind.REPEAT)
            }
        }
        // "in 30 minutes" counts from now, rounded up to the next five minutes.
        val relativeMatches = relativeTimes.findAll(remaining).toList()
        if (relativeMatches.size > 1) return error("Use one time.")
        var relativeAt: LocalDateTime? = null
        relativeMatches.firstOrNull()?.let { match ->
            val total = spanMinutes(match.value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " "))
            if (!total.isFinite() || total !in 1.0..1440.0 || total % 1 != 0.0 || match.value.contains('-'))
                return error("Use a time from 1 minute to 24 hours from now, in whole minutes.")
            relativeAt = now?.let { roundUpToFive(it.plusMinutes(total.toLong())) }
            consume(match.range, QuickPhraseKind.TIME)
        }
        val nowMatches = nowWords.findAll(remaining).toList()
        if (nowMatches.size + relativeMatches.size > 1) return error("Use one time.")
        nowMatches.firstOrNull()?.let { match ->
            relativeAt = now?.let(::roundUpToFive)
            consume(match.range, QuickPhraseKind.TIME)
        }
        val relative = relativeMatches.isNotEmpty() || nowMatches.isNotEmpty()

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
        var ds = dates.findAll(remaining).toList()
        val meridiemRanges = ranges.findAll(remaining).filter { rx("$meridiem$").containsMatchIn(it.groupValues[2]) }.toList()
        val numeric = numericDate.findAll(remaining).filter { n -> (ds + meridiemRanges).none { d -> n.range.first <= d.range.last && d.range.first <= n.range.last } }.toList()
        // A weekday beside a calendar date is a cross-check, not a second date: Friday 2 October, Fri 3/10.
        var weekdayCheck: DayOfWeek? = null
        (ds + numeric).sortedBy { it.range.first }.takeIf { it.size == 2 }?.let { (a, b) ->
            val weekday = listOf(a, b).singleOrNull { bareWeekday.matches(it.value) } ?: return@let
            val other = if (weekday === a) b else a
            if (!rx("\\d").containsMatchIn(other.value) || rx("\\bin\\s|from\\s+today").containsMatchIn(other.value)) return@let
            if (!Regex("\\s*,?\\s*").matches(remaining.substring(a.range.last + 1, b.range.first))) return@let
            weekdayCheck = weekdayOf(weekday.value)
            // Include the separating comma so "Fri, Oct 2" leaves no stray punctuation in the title.
            consume(if (weekday === a) a.range.first until b.range.first else a.range.last + 1..b.range.last, QuickPhraseKind.DATE)
            ds = ds.filter { it.range != weekday.range }
        }
        if (ds.size + numeric.size > 1 || impliedToday && (ds.isNotEmpty() || numeric.isNotEmpty())) {
            phrases += (ds + numeric).map { QuickEntryPhrase(it.range.first, it.range.last + 1, QuickPhraseKind.DATE) }
            return error("Use one date. Remove the extra date or keep literal words in the title.")
        }
        if (relative && (ds.isNotEmpty() || numeric.isNotEmpty() || impliedToday))
            return error("Use one date or time: ‘in …’ already says when.")
        // Repeats anchored to a weekday, to weekdays or to a day of the month start on a matching date.
        val anchorName = when {
            repeatDay != null -> "repeating weekday"
            repeat == RepeatRule.WEEKDAYS -> "weekday repeat"
            monthDay != null -> "repeating day of the month"
            else -> null
        }
        fun fitsAnchor(d: LocalDate) = (repeatDay == null || d.dayOfWeek == repeatDay) &&
            (repeat != RepeatRule.WEEKDAYS || d.dayOfWeek.value <= 5) && (monthDay == null || d.dayOfMonth == monthDay)
        var date = relativeAt?.toLocalDate() ?: if (impliedToday) today else
            generateSequence(today) { it.plusDays(1) }.take(366).firstOrNull(::fitsAnchor) ?: today
        var dateChoices = emptyList<LocalDate>()
        if (ds.isNotEmpty()) {
            date = parseDate(ds.single().value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").removePrefix("on ").removePrefix("by "), today)
                ?: return error("That date isn't valid.")
            weekdayCheck?.let { if (date.dayOfWeek != it)
                return error("${longDate(date)} is a ${dayName(date.dayOfWeek)}, not a ${dayName(it)}. Correct the date or the weekday.") }
            consume(ds.single().range, QuickPhraseKind.DATE)
        }
        numeric.firstOrNull()?.let { match ->
            val parts = match.value.split('/', '-', '.')
            if (parts.size == 3 && parts[2].length !in setOf(2, 4)) return error("Use a four-digit year, for example 03/04/2027.")
            // A two-digit year is this century: 3/10/26 is 2026.
            val year = parts.getOrNull(2)?.toIntOrNull()?.let { if (parts[2].length == 2) 2000 + it else it } ?: today.year
            fun candidate(day: Int, month: Int): LocalDate? = runCatching {
                val value = LocalDate.of(year, month, day)
                if (parts.size == 2 && value < today) LocalDate.of(year + 1, month, day) else value
            }.getOrNull()?.takeIf { it.year in 1..9999 }
            // Each reading remembers whether it was day-first, so the date-format setting can pick one.
            var readings = listOfNotNull(candidate(parts[0].toInt(), parts[1].toInt())?.let { true to it },
                candidate(parts[1].toInt(), parts[0].toInt())?.let { false to it }).distinctBy { it.second }
            if (readings.isEmpty()) return error("That date isn't valid.")
            readings = readings.filter { fitsAnchor(it.second) }
            if (readings.isEmpty()) return error("The date doesn't match the $anchorName.")
            weekdayCheck?.let { day ->
                readings = readings.filter { it.second.dayOfWeek == day }
                if (readings.isEmpty()) return error("${match.value} isn't a ${dayName(day)}. Correct the date or the weekday.")
            }
            if (readings.size == 2 && dayFirst != null) readings = readings.filter { it.first == dayFirst }
            dateChoices = readings.map { it.second }
            date = dateChoices.first()
            // "on 12/10": the "on" belongs to the date.
            val on = rx("\\bon\\s+$").find(remaining.substring(0, match.range.first))
            consume((on?.range?.first ?: match.range.first)..match.range.last, QuickPhraseKind.DATE)
            if (dateChoices.size == 1) dateChoices = emptyList()
        }
        var holiday = false
        if (ds.isEmpty() && numeric.isEmpty() && !relative && !impliedToday) holidays.find(remaining)?.let { match ->
            date = nextHoliday(match.value.lowercase(Locale.ROOT), today)
            holiday = true
            // Recognised, so it can be kept literally, but not consumed: the name stays in the title.
            phrases += QuickEntryPhrase(match.range.first, match.range.last + 1, QuickPhraseKind.DATE)
        }
        if (anchorName != null && dateChoices.isEmpty() && !fitsAnchor(date))
            return error("The start date doesn't match the $anchorName. Choose a matching date.")
        repeatPeriod?.let { (count, unit) ->
            val end = when {
                unit.startsWith("d") -> date.plusDays(count)
                unit.startsWith("f") -> date.plusWeeks(count * 2)
                unit.startsWith("w") -> date.plusWeeks(count)
                unit.startsWith("m") -> date.plusMonths(count)
                else -> date.plusYears(count)
            }
            repeatCount = repeat.dates(date, 365).count { it < end }
            if (repeatCount !in 2..365) return error("Choose a repeat rule and between 2 and 365 occurrences.")
        }

        // A four-digit number is a time only where it reads like one: a leading zero, after at/from/until or a date,
        // before hrs or another range end. Otherwise it stays in the title: "Buy 1500 screws", "Tax return 2027".
        fourDigits.findAll(remaining).toList().forEach { match ->
            val value = match.groupValues[1]
            val before = remaining.substring(0, match.range.first)
            val after = remaining.substring(match.range.last + 1)
            val timeLike = rx("^$hhmm$").matches(value) && (value.startsWith("0") ||
                rx("(?:\\b(?:at|from|until|till|to|by|actually)\\s+|[-–—]\\s*)$").containsMatchIn(before) ||
                rx("^\\s*$hoursSuffix\\b").containsMatchIn(after) ||
                rx("^\\s*(?:[-–—]|to\\b|until\\b|till\\b|[,—–-]?\\s*actually\\s+(?:at\\s+)?)\\s*$hhmm\\b").containsMatchIn(after) ||
                phrases.any { it.kind == QuickPhraseKind.DATE && it.end <= match.range.first && text.substring(it.end, match.range.first).matches(Regex("[\\s,]*")) })
            if (!timeLike) mask(match.range, '\uE000')
        }
        // 3p, 3:30p and 15.30 read as times only after at/from/until/by or right beside a date: "Call 3p tomorrow",
        // "Meeting 15.30 Friday". Otherwise they stay in the title: "Meeting room 6a", "Version 2.10", "$12.50".
        shortClocks.findAll(remaining).toList().forEach { match ->
            val before = remaining.substring(0, match.range.first)
            val after = remaining.substring(match.range.last + 1)
            val besideDate = phrases.any { it.kind == QuickPhraseKind.DATE &&
                (it.end <= match.range.first && text.substring(it.end, match.range.first).matches(Regex("[\\s,]*")) ||
                    it.start > match.range.last && text.substring(match.range.last + 1, it.start).matches(Regex("[\\s,]*"))) }
            val money = rx("[£€¥$]\\s*$").containsMatchIn(before)
            val timeLike = !money && besideDate || !money && rx("(?:\\b(?:at|from|until|till?|to|by|actually)\\s+|@\\s*|$approx|[-–—]\\s*)$").containsMatchIn(before) ||
                rx("^\\s*(?:-?ish\\b|[-–—]|to\\b|until\\b|till?\\b)").containsMatchIn(after)
            if (!timeLike) mask(match.range, '\uE000')
        }
        val lengths = durations.findAll(remaining).toList()
        if (lengths.size > 1) return error("Use one duration, such as for 1h 30m.")
        var duration = lengths.firstOrNull()?.let {
            val value = it.value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
            val total = spanMinutes(value)
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
            val raw = spokenToClock(it.value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
                .replace(Regex("^(?:(?:at|by|around|about|approx(?:imately)?|roughly|circa)\\s+|[@~]\\s*)+"), "")
                .replace(Regex("\\s*-?ish$"), "").trim()).let { r -> if (Regex("\\d{1,2}\\.\\d{2}").matches(r)) r.replace('.', ':') else r }
            // 7:30 could be morning or evening; 07:30 and 19:30 are unambiguous.
            val twelveHour = Regex("([1-9]|1[0-2]):([0-5]\\d)").matchEntire(raw)
            time = if (twelveHour != null) null else readTime(raw)
            if (twelveHour != null) {
                ambiguous = true
                val (hour, minute) = twelveHour.destructured.let { (h, m) -> h.toInt() % 12 to m.toInt() }
                timeChoices = listOf(LocalTime.of(hour, minute), LocalTime.of(hour + 12, minute))
            } else if (time == null) {
                if (raw.matches(Regex("\\d{1,2}")) && raw.toInt() in 0..23) {
                    ambiguous = true
                    val hour = raw.toInt()
                    timeChoices = if (hour in 1..12) listOf(LocalTime.of(hour % 12, 0), LocalTime.of(hour % 12 + 12, 0)) else listOf(LocalTime.of(hour, 0))
                }
                else return error("That time isn't valid.")
            }
            consume(it.range, QuickPhraseKind.TIME)
        }
        if (timePrompt != null && (time != null || ts.isNotEmpty() || rs.isNotEmpty() || periodHour != null)) {
            // A part of the day beside a clock time: the clock gives the time, the part of the day settles am/pm.
            val period = dayPeriod ?: return error("Use one time: remove the vague phrase or the extra clock time.")
            if (periodHour != null && (ts.isNotEmpty() || rs.isNotEmpty())) return error("Use one time: remove the vague phrase or the extra clock time.")
            val window = dayPeriods.getValue(period)
            val fitting = periodHour?.let { listOf(LocalTime.of(it % 12, 0), LocalTime.of(it % 12 + 12, 0)) } ?: time?.let { listOf(it) } ?: timeChoices
            time = fitting.singleOrNull { it in window }
                ?: return error("${fitting.joinToString(" or ")} isn't in the $period. Correct the time, or remove ‘$period’.")
            timeChoices = emptyList(); ambiguous = false; timePrompt = null
        }
        if (relative && (timePrompt != null || ts.isNotEmpty() || rs.isNotEmpty()))
            return error("Use one time: ‘in …’ or a clock time.")
        if (relative) time = relativeAt?.toLocalTime() ?: return error("‘In …’ counts from now, so it only works on today's entries. Choose a time.")
        if (allDayMatches.isNotEmpty() && (time != null || ambiguous || duration != null))
            return error("Use all day or a time, not both.")
        reminderAt?.let { at ->
            val start = time ?: return error("Add the event's time to be reminded at ${at}, or use remind me … before.")
            var before = (start.toSecondOfDay() - at.toSecondOfDay()) / 60
            // A reminder time later in the day than the event means the day before.
            if (reminderDayBefore) before += 1440 else if (before < 0) before += 1440
            reminderMinutes = before
        }
        schedulingWords.find(remaining)?.let { match ->
            val end = remaining.indexOf(',', match.range.first).takeIf { it >= 0 } ?: remaining.length
            phrases += QuickEntryPhrase(match.range.first, end, QuickPhraseKind.UNSUPPORTED)
            return error("Finish the reminder or repeat phrase, or open Details → Adjust recognised text to keep those words in the title.")
        }
        // Scheduling-shaped fragments must not silently turn into part of a saved title.
        if (duration != null && rx("\\band(?:\\s+(?:a|half|\\d+))?\\s*$").containsMatchIn(remaining))
            return error("Finish the duration, for example for 1 hour and 30 minutes.")
        if (rx("\\b(?:in\\s+(?:-?\\d+|$countWords)\\s*(?:days?|weeks?|months?|years?|fortnights?|hours?|hrs?|minutes?|mins?|seconds?|secs?|h|m)|for\\s+(?:$amount|half)\\s*(?:$hours|$minutes)|\\d{1,2}[:h]\\d*|\\d{1,2}\\.\\d+\\s*$meridiem)\\b").containsMatchIn(remaining) ||
            unfinished.containsMatchIn(remaining) ||
            rx("(?:[-–—]\\s*$|\\b(?:to|until)\\s+\\d)").containsMatchIn(remaining))
            return error("Finish the date, time or duration, or put literal title text in quotes.")
        var title = text
        consumed.sortedByDescending { it.first }.forEach { title = title.replaceRange(it, " ") }
        title = title.replace("\"", "")
            // Commas left alone by removed phrases: "Dentist 3 October, 2pm".
            .replace(Regex("(?<=^|\\s)[,;]+(?=\\s|$)"), " ").replace(Regex("[\\s,;]+$"), "")
            .trim().replace(Regex("\\s+"), " ")
        if (taskHint) title = title.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val dateSpecified = impliedToday || ds.isNotEmpty() || numeric.isNotEmpty() || repeat != RepeatRule.NONE || relative || holiday
        // "Remind me to …" with a when: remind at the time, or at the usual 09:00 on the day.
        val reminderImplied = taskHint && reminderMinutes == null && (dateSpecified || time != null || ambiguous)
        val clarification = when {
            dateChoices.isNotEmpty() -> "Which date did you mean?"
            ambiguous -> timePrompt ?: "Morning or afternoon? Choose a time below, or type am or pm."
            else -> null
        }
        return QuickEntrySuggestion(title, date, time,
            if (title.isBlank()) "Add a name." else clarification,
            dateSpecified, duration, ambiguous, location,
            phrases.sortedBy { it.start }, dateChoices, timeChoices, clarification != null && title.isNotBlank(),
            reminderMinutes ?: if (reminderImplied) 0 else null, repeat, repeatCount, countMatches.isNotEmpty() || repeatPeriod != null, timePrompt,
            taskHint, reminderImplied)
    }

    private fun spanMinutes(value: String): Double = when {
        rx("^(?:for|in) half\\b").containsMatchIn(value) -> 30.0
        "quarter" in value -> 15.0
        else -> durationParts.findAll(value).sumOf { part ->
            readAmount(part.groupValues[1]) * if (part.groupValues[2].startsWith("h")) 60 else 1
        } + if (value.endsWith("and a half")) 30.0 else 0.0
    }

    /** Minutes in "10 minutes", "1 hour and 30 minutes", "2 days", "half a day", "the day" (before). */
    private fun reminderSpanMinutes(value: String): Double {
        if (value == "the day") return 1440.0
        if (value == "the week") return 10080.0
        Regex("^(.+?) ?(days?|weeks?)$").matchEntire(value)?.let { match ->
            val amountText = match.groupValues[1]
            val number = if (amountText.startsWith("half")) 0.5 else readAmount(amountText)
            return number * if (match.groupValues[2].startsWith("d")) 1440 else 10080
        }
        return spanMinutes("for $value")
    }

    private fun nextHoliday(name: String, today: LocalDate): LocalDate {
        val (month, day) = when {
            "eve" in name && "year" in name -> 12 to 31
            "year" in name -> 1 to 1
            "eve" in name -> 12 to 24
            "boxing" in name -> 12 to 26
            "valentine" in name -> 2 to 14
            "hallow" in name -> 10 to 31
            else -> 12 to 25
        }
        val thisYear = LocalDate.of(today.year, month, day)
        return if (thisYear < today) thisYear.plusYears(1) else thisYear
    }

    private fun roundUpToFive(time: LocalDateTime): LocalDateTime {
        val minute = time.truncatedTo(ChronoUnit.MINUTES)
        return minute.plusMinutes(((5 - minute.minute % 5) % 5).toLong())
    }

    private fun weekdayIn(phrase: String): DayOfWeek =
        weekdayOf(phrase.split(' ').first { it in weekdays.split('|') })

    private fun weekdayOf(value: String): DayOfWeek {
        val word = value.lowercase(Locale.ROOT).trim().substringAfterLast(' ')
        return DayOfWeek.entries.first { it.name.lowercase(Locale.ROOT).startsWith(word.take(3)) }
    }
    private fun dayName(day: DayOfWeek) = day.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    private fun longDate(date: LocalDate) = "${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${date.year}"

    /** The next date on or after [today] with this day of the month, skipping months that are too short. */
    private fun nextDayOfMonth(today: LocalDate, day: Int): LocalDate? {
        if (day !in 1..31) return null
        var month = YearMonth.from(today)
        repeat(13) {
            if (day <= month.lengthOfMonth() && month.atDay(day) >= today) return month.atDay(day)
            month = month.plusMonths(1)
        }
        return null
    }

    private fun readAmount(value: String): Double {
        if (value == "a" || value == "an") return 1.0
        val words = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")
        return words.indexOf(value).takeIf { it >= 0 }?.let { (it + 1).toDouble() } ?: value.toDoubleOrNull() ?: Double.NaN
    }

    /** half past 3 → 3:30, quarter to 5pm → 16:45, 3 o'clock → 3. Without am/pm the result stays a 12-hour time. */
    private fun spokenToClock(raw: String): String {
        Regex("^(\\d{1,2}) ?o['’]? ?clock ?(.*)$").matchEntire(raw)?.let { return it.groupValues[1] + it.groupValues[2] }
        val spoken = Regex("^(half|quarter|five|ten|twenty(?:[- ]five)?|\\d{1,2}) (past|to) (\\d{1,2}) ?(.*)$").matchEntire(raw) ?: return raw
        val (amount, direction, hourText, suffix) = spoken.destructured
        val minutes = when (amount) {
            "half" -> 30; "quarter" -> 15; "five" -> 5; "ten" -> 10; "twenty" -> 20
            else -> if (amount.startsWith("twenty")) 25 else amount.toInt()
        }
        val hour = hourText.toInt()
        if (minutes !in 1..59 || hour !in 0..23) return raw
        val ampm = normaliseClock(suffix)
        if (ampm.isNotEmpty() || hour !in 1..12) {
            val base = readTime(if (ampm.isEmpty()) "$hour:00" else "$hour$ampm") ?: return raw
            val result = if (direction == "past") base.plusMinutes(minutes.toLong()) else base.minusMinutes(minutes.toLong())
            // Written back with am/pm so it is not mistaken for an ambiguous 12-hour time.
            return "${(result.hour % 12).let { if (it == 0) 12 else it }}:%02d${if (result.hour < 12) "am" else "pm"}".format(result.minute)
        }
        val total = hour % 12 * 60 + if (direction == "past") minutes else -minutes
        val twelve = Math.floorMod(total, 720)
        return "${(twelve / 60).let { if (it == 0) 12 else it }}:%02d".format(twelve % 60)
    }

    private fun normaliseClock(raw: String): String = raw.lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), "").replace("a.m.", "am").replace("p.m.", "pm")
        .replace("a.m", "am").replace("p.m", "pm").replace('.', ':').replace(Regex("(?<=\\d)h(?=\\d)"), ":")
        .replace(Regex("^(\\d{2})(\\d{2})$hoursSuffix?$"), "$1:$2")
        .replace(Regex("(?<=\\d)([ap])$"), "$1m")

    private fun readTime(raw: String): LocalTime? {
        val value = normaliseClock(raw)
        if (value == "noon" || value == "midday" || value == "mid-day") return LocalTime.NOON
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
            d in tomorrowSpellings -> today.plusDays(1)
            rx("^(?:$weekAfterNext)$").matches(d) -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(2).with(weekdayIn(d))
            rx("^(?:$nextWeekDay)$").matches(d) -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1).with(weekdayIn(d))
            rx("^(?:$weekOn)$").matches(d) -> today.with(TemporalAdjusters.nextOrSame(weekdayIn(d))).plusWeeks(1)
            rx("^(?:$nextMonthDay)$").matches(d) -> YearMonth.from(today).plusMonths(1).atDay(d.removePrefix("the ").takeWhile { it.isDigit() }.toInt())
            d == "this weekend" -> if (today.dayOfWeek.value >= 6) today else today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
            rx("^(?:$endOfMonth)$").matches(d) -> today.withDayOfMonth(today.lengthOfMonth())
            d == "day after tomorrow" || d == "the day after tomorrow" -> today.plusDays(2)
            d.startsWith("in ") || d.endsWith(" from today") -> {
                val parts = d.removePrefix("in ").removeSuffix(" from today").split(' ')
                val words = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")
                val count = when (parts[0]) {
                    "a", "an" -> 1L
                    in words -> (words.indexOf(parts[0]) + 1).toLong()
                    else -> parts[0].toLong()
                }
                when {
                    parts[1].startsWith("fortnight") -> today.plusDays(Math.multiplyExact(count, 14L))
                    parts[1].startsWith("w") -> today.plusDays(Math.multiplyExact(count, 7L))
                    parts[1].startsWith("month") -> today.plusMonths(count)
                    parts[1].startsWith("year") -> today.plusYears(count)
                    else -> today.plusDays(count)
                }
            }
            d.matches(Regex("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}")) -> d.split('-', '/', '.').map { it.toInt() }.let { (y, m, day) -> LocalDate.of(y, m, day) }
            rx("^(?:the )?\\d{1,2}(?:st|nd|rd|th)$").matches(d) -> nextDayOfMonth(today, d.removePrefix("the ").takeWhile { it.isDigit() }.toInt())
            rx("\\d").containsMatchIn(d) -> {
                val parts = d.removePrefix("the ").replace(",", "").replace(" of ", " ").split(' ')
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
