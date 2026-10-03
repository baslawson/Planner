package com.example.itinerary.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class QuickPhraseKind(val label: String) {
    DATE("Date"), TIME("Time"), DURATION("Duration"), LOCATION("Place"), REMINDER("Reminder"), REPEAT("Repeat"), TASK("Task"), UNSUPPORTED("Unrecognised")
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
    /** Last day of a multi-day entry ("3–7 Oct", "for 5 days"); null = one day. */
    val endDate: LocalDate? = null,
    /** "8am and 8pm": the later times of the same entry, each saved as its own event; [time] is the first. */
    val extraTimes: List<LocalTime> = emptyList(),
    /** The date is before today on purpose ("yesterday", "last Friday"): logging something that happened. */
    val pastDate: Boolean = false,
    /** "8pm and 2am": the last this many [extraTimes] are after midnight, on the day after [date]. */
    val nextDayTimes: Int = 0,
    /** The vague part of the day ("2night" in "Book 2night club") when title words follow it; null otherwise. */
    val periodInTitle: String? = null,
)

/** Local, explicit grammar. Quoted text is literal; consumed spans keep their original offsets. */
object QuickEntry {
    /** "In 2 hours" or "now" in a draft based on an earlier day: there is no current time to count from. */
    const val STALE_RELATIVE = "‘In …’ and ‘now’ count from the current time, but this draft is based on an earlier day. Edit the entry to use today, or replace ‘in …’ with a date."
    /** How to read a numeric date such as 3/4: day first, month first, or null to ask. Follows Settings → Date format. */
    @Volatile var numericDayFirst: Boolean? = null
    // UI-5: a pattern is compiled the first time it is used and then kept, rather than again on every parse (Quick entry
    // parses two or three times per letter typed). Regex is immutable and thread-safe. Only fixed patterns come here
    // (the one built from a local picks between two spellings), so these stay small. rx ignores case; re doesn't.
    private val ignoringCase = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private val matchingCase = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private fun rx(pattern: String) = ignoringCase.getOrPut(pattern) { Regex(pattern, RegexOption.IGNORE_CASE) }
    private fun re(pattern: String) = matchingCase.getOrPut(pattern) { Regex(pattern) }
    private const val weekdays = "monday|mon|tuesday|tues|tue|wednesday|weds|wed|thursday|thurs|thur|thu|friday|fri|saturday|sat|sunday|sun"
    private const val months = "january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|august|aug|september|sept|sep|october|oct|november|nov|december|dec"
    private const val dayNumber = "\\d{1,2}(?:st|nd|rd|th)?"
    private const val countWords = "a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"
    private const val relativeCount = "(?:\\d+|$countWords)"
    private const val relativeUnit = "(?:days?|weeks?|wks?|fortnights?|months?|years?)"
    /** Spellings read as tomorrow outright, not only offered as a correction while typing. */
    val tomorrowSpellings = listOf("tomorrow", "tmr", "tmrw", "tomoz", "tomorow", "tommorow", "tommorrow", "2morrow", "2moro", "2morow", "tmw")
    private val tomorrowWords = tomorrowSpellings.joinToString("|")
    /** Spellings read as tonight. */
    val tonightSpellings = listOf("tonight", "tonite", "tonigh", "2nite", "2night", "tonght")
    private val tonightWords = tonightSpellings.joinToString("|")
    // Dates built from words that are otherwise unsupported on their own: next week, weekend, end of, next month.
    private const val weekAfterNext = "(?:the\\s+)?week\\s+after\\s+next\\s+(?:on\\s+)?(?:$weekdays)|(?:$weekdays)\\s+(?:the\\s+)?week\\s+after\\s+next"
    private const val nextWeekDay = "next\\s+week\\s+(?:on\\s+)?(?:$weekdays)|(?:$weekdays)\\s+next\\s+week"
    // "Friday week", "a week on Friday": the Friday after the coming one.
    private const val weekOn = "(?:$weekdays)\\s+week(?!\\s+after)|a\\s+week\\s+(?:on|from)\\s+(?:$weekdays)"
    private const val endOfMonth = "(?:(?:at|by)\\s+)?(?:the\\s+)?(?:end|last\\s+day)\\s+of\\s+(?:the\\s+|this\\s+)?month"
    // Weekends, weeks and months as dates: next weekend (Saturday), (the) end of (this/next) week (its Friday), early next
    // week (Monday), start/beginning of next month (the 1st), end of next month (its last day).
    private const val nextWeekend = "next\\s+weekend"
    private const val endOfWeek = "(?:(?:at|by)\\s+)?(?:the\\s+)?end\\s+of\\s+(?:the\\s+|this\\s+|next\\s+)?week"
    private const val earlyNextWeek = "early\\s+next\\s+week"
    private const val startOfNextMonth = "(?:(?:at|by)\\s+)?(?:the\\s+)?(?:start|beginning)\\s+of\\s+next\\s+month"
    private const val endOfNextMonth = "(?:(?:at|by)\\s+)?(?:the\\s+)?end\\s+of\\s+next\\s+month"
    private const val nextMonthDay = "(?:the\\s+)?\\d{1,2}(?:st|nd|rd|th)\\s+of\\s+next\\s+month"
    // Round 5: "Monday after next" (the one after the coming Monday), "first Monday in November", "last day of October",
    // "end of the year", "in 2 weeks on Tuesday", "Monday 19th" (the next Monday that is the 19th).
    private const val afterNext = "(?:the\\s+)?(?:$weekdays)\\s+after\\s+next"
    private const val nthWeekdayIn = "(?:the\\s+)?(?:first|second|third|fourth|last|1st|2nd|3rd|4th)\\s+(?:$weekdays)\\s+(?:in|of)\\s+(?:$months)(?:\\s+\\d{4})?"
    private const val lastDayOf = "(?:the\\s+)?last\\s+day\\s+of\\s+(?:$months)(?:\\s+\\d{4})?"
    private const val endOfYear = "(?:(?:at|by)\\s+)?(?:the\\s+)?end\\s+of\\s+(?:the\\s+|this\\s+)?year"
    private const val inWeeksOn = "in\\s+$relativeCount\\s+weeks?\\s+on\\s+(?:$weekdays)"
    private const val weekdayOrdinal = "(?:$weekdays)\\s+(?:the\\s+)?\\d{1,2}(?:st|nd|rd|th)(?!\\s+(?:of\\s+)?(?:$months))"
    // Round 6: "Friday this week", "next month on the 5th", "the 5th next month".
    private const val weekThis = "(?:$weekdays)\\s+this\\s+week|this\\s+week\\s+(?:on\\s+)?(?:$weekdays)"
    private const val nextMonthOn = "next\\s+month\\s+on\\s+the\\s+\\d{1,2}(?:st|nd|rd|th)?|(?:on\\s+)?the\\s+\\d{1,2}(?:st|nd|rd|th)\\s+next\\s+month"
    private const val wordDates = "$weekThis|$nextMonthOn|$weekAfterNext|$afterNext|$nthWeekdayIn|$lastDayOf|$endOfYear|$inWeeksOn|$weekdayOrdinal|$nextWeekDay|$weekOn|this\\s+weekend|$nextWeekend|$endOfNextMonth|$endOfMonth|$endOfWeek|$earlyNextWeek|$startOfNextMonth|$nextMonthDay"
    // Round 8: dates before today, for logging what happened: "yesterday", "the day before yesterday", "2 days ago",
    // "last Friday" (the most recent one). "yesterday's" stays in the title.
    private const val pastDates = "(?:the\\s+)?day\\s+before\\s+yesterday|yesterday(?!['’])|$relativeCount\\s+$relativeUnit\\s+ago|last\\s+(?:$weekdays)(?!\\s+(?:of|in)\\b)"
    private val pastDateWords = rx("^(?:(?:on|by|before|due(?:\\s+(?:on|by))?)\\s+)?(?:$pastDates)$")
    // "a week today", "a week tomorrow", "tomorrow week": a week after today or tomorrow.
    private val weekFrom = "a\\s+week\\s+(?:today|from\\s+today|$tomorrowWords)|(?:$tomorrowWords)\\s+week(?!\\s+after)"
    private val wordDateMatches = rx("\\b(?:$wordDates|$weekFrom)\\b")
    // Day numbers in words: "October first", "the first of October", "the twenty-first".
    private val ordinalWordList = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth",
        "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth", "twentieth") +
        listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth").map { "twenty-$it" } + listOf("thirtieth", "thirty-first")
    private val ordinalWords = ordinalWordList.sortedByDescending { it.length }.joinToString("|") { it.replace("-", "[-\\s]") }
    private val ordinalDates = "(?:the\\s+)?(?:$ordinalWords)\\s+(?:of\\s+)?(?:$months)(?:\\s+\\d{4})?|(?:$months)\\s+(?:the\\s+)?(?:$ordinalWords)\\b(?:,?\\s+\\d{4})?" +
        // Alone only where nothing else follows: "Review the twenty-first", not "Watch the first episode".
        "|the\\s+(?:$ordinalWords)(?=\\s*(?:$|,|at\\b|@|from\\b|for\\b|\\d))"
    // Right after a day number, am or pm makes it a time: "Call Jan 3 pm" is 3pm, with Jan (a name) left in the title.
    private const val notClockHour = "(?!\\d{1,2}\\s*(?:am|pm|a\\.m|p\\.m)(?![a-z]))"
    // "Dentist 3rd 2pm": a day number (1–31) with st/nd/rd/th and no "the" is a date right before a time, not before a
    // date ("Sam's 21st 17 Oct"), and only when the entry has no other date (see parse); with "on", "by" or "due", also at
    // the end or before at/from/for ("Dentist on 3rd at 2pm", "Rent due 15th"). Before other words it stays in the title:
    // "3rd floor".
    private const val bareOrdinalValue = "(?:3[01]|[12]\\d|0?[1-9])(?:st|nd|rd|th)"
    private const val bareOrdinal = "$bareOrdinalValue(?=\\s*,?\\s*(?:at\\s+|@\\s*)?\\d)" +
        "(?!\\s*,?\\s*\\d{1,2}(?:st|nd|rd|th)?\\s+(?:of\\s+)?(?:$months)\\b|\\s*,?\\s*(?:\\d{1,2}/|\\d{1,2}\\.\\d{1,2}\\.|\\d{4}[-/.])\\d)"
    // After a currency sign or code, or before a code: "$450", "AUD 20.50", "20.50 AUD".
    private val currencyCodes = Bills.currencies.joinToString("|")
    private val moneyAmounts = rx("(?<=[£€¥$]\\s?|\\b(?:$currencyCodes)\\s?)\\d[\\d,]*(?:\\.\\d+)?|(?<![\\w.,])\\d[\\d,]*(?:\\.\\d+)?(?=\\s?(?:$currencyCodes)\\b)")
    // "Call 0491 570 156", "+61 491 570 156", "(08) 5550 1234", "0491-570-156": a phone number, never a time or date. At least
    // nine digits, so "Oct 12 2026 10am" and "0800 1400" keep their dates and times; see parse.
    private val phoneNumbers = Regex("(?<![\\w.:/+(-])(?:\\+\\d{1,3}(?:[ -]\\d{1,5}){2,}|\\(0\\d\\)\\s?\\d{3,4}[ -]?\\d{3,4}|\\d{2,5}(?: \\d{2,5}){2,}|0\\d{2,4}(?:-\\d{3,4}){2})(?![\\w.:/-])")
    // "Bus 2 - 3pm", "Ferry 12 till 10am": a number naming a bus, room or gate stays in the title rather than starting a range.
    // Written tight against the dash ("Court 1-2pm") it is still a range.
    // Train and row are also things to do ("Train 6 - 7pm"): after them only a number that can't be an hour is a label.
    private const val numberLabels = "bus|route|ferry|tram|flight|gate|platform|terminal|stop|bay|room|rm|level|floor|table|court|seat|line|no\\.?|number"
    private val labelledNumbers = rx("(?:(?<=\\b(?:$numberLabels)\\s{1,3}|#\\s?)|(?<=\\b(?:train|row)\\s{1,3})(?!(?:[1-9]|1[0-2])\\b))\\d{1,3}(?=\\s+(?:[-–—]|till?|'til|until)\\s)")
    private val bareOrdinalDate = rx("^$bareOrdinalValue$")
    private const val onOrdinal = "(?:on|by|due(?:\\s+(?:on|by))?)\\s+\\d{1,2}(?:st|nd|rd|th)(?=\\s*(?:$|,|at\\b|@|from\\b|for\\b|\\d))"
    // Every way of writing one date; also what may follow "until" on a repeat.
    private val datePhrases = "$wordDates|$weekFrom|$pastDates|(?:in\\s+$relativeCount\\s+$relativeUnit(?:['’]s?\\s+time)?|$relativeCount\\s+$relativeUnit\\s+from\\s+(?:today|now))|(?:the\\s+)?day\\s+after\\s+tomorrow|(?:later\\s+)?today|$tomorrowWords|$ordinalDates|(?:(?:next|nxt|this\\s+coming|this|coming)\\s+)?(?:$weekdays)|\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|(?:the\\s+)?$dayNumber\\s+(?:of\\s+)?(?:$months)(?:\\s+\\d{4})?|(?:$months)\\s+(?:the\\s+)?$notClockHour$dayNumber(?:,?\\s+\\d{4})?|the\\s+\\d{1,2}(?:st|nd|rd|th)|$bareOrdinal"
    // "before Friday" is a deadline: the date is Friday, "before" leaves the title.
    private val dates = rx("\\b(?:(?:(?<!-)(?:on|by|before|due(?:\\s+(?:on|by))?)\\s+)?(?:$datePhrases)|$onOrdinal)\\b")
    // A four-digit number after a month-name date ("3 Oct 1500", "Oct 3, 1930", "3-7 Oct 0800"): group 2 is its year only
    // when it could be one (see parse); otherwise group 1, the space before it, is masked so it stays a 24-hour time.
    private val yearAfterMonth = rx("\\b(?:$months)(?:\\s+(?:the\\s+)?(?:\\d{1,2}(?:st|nd|rd|th)?(?:$rangeJoin\\d{1,2}(?:st|nd|rd|th)?)?|(?:$ordinalWords)))?(,?\\s+)(\\d{4})(?!\\d)")
    // "Work 9-5 Oct 3rd": the month goes with the day after it, so 9-5 are hours. Group 1, the space before the month, is
    // masked so neither "5 Oct" nor "9-5 Oct" reads as a date. "3-7 Oct", "3-7 Oct 2026", "3-7 Oct 5pm" and "3-7 Oct 10:30"
    // stay date ranges.
    private val hoursBeforeMonthDay = rx("(?<![\\w/.:])\\d{1,2}(?:[:.]\\d{2})?\\s*(?:[-–—]|to|until|till)\\s*\\d{1,2}(\\s+)(?=(?:$months)\\s+(?:the\\s+)?$notClockHour\\d{1,2}(?:st|nd|rd|th)?\\b(?![:.]\\d))")
    private const val meridiem = "(?:am|pm|a\\.m\\.?|p\\.m\\.?)"
    // 24-hour times written as four digits: 0600, 1500, 0000, optionally 1500hrs.
    private const val hhmm = "(?:[01]\\d|2[0-3])[0-5]\\d"
    private const val hoursSuffix = "(?:hrs?|hours?|h)"
    private const val noonWords = "noon|midday|mid-day"
    // "730pm", "1030am": an hour and minutes without a colon, only with am/pm.
    private const val clockAmPm = "(?<![.:,\\d])(?:1[0-2]|0?[1-9])[0-5]\\d\\s*$meridiem"
    // "12 noon", "12 midnight": the 12 is part of the word.
    private const val noonOrMidnight = "(?:12\\s*)?(?:$noonWords|midnight)"
    private const val clock = "(?:$clockAmPm|$hhmm(?:\\s*$hoursSuffix)?|$noonOrMidnight|\\d{1,2}(?:[:.h]\\d{2})?(?:\\s*$meridiem)?)"
    // "630-830pm", "930-1030am": an hour and minutes without a colon or am/pm start a range whose end has am/pm, but only
    // written tight against the dash. With a space or a range word it is title text: "Bus 150 - 3pm", "room 204 till 3pm".
    private const val rangeStartAmPm = "[1-9][0-5]\\d(?=[-–—](?:$clockAmPm|\\d{1,2}(?:[:.]\\d{2})?\\s*$meridiem)(?![\\w]))"
    // Groups 1–2 "between 2 and 4", 3–4 every other form; see rangeEnds.
    private val ranges = rx("\\b(?:between\\s+($clock)\\s+and\\s+($clock)|(?:(?:from|at)\\s+)?($clock|$rangeStartAmPm)\\s*(?:[-–—]|to|until|till?|'til)\\s*($clock))(?![\\w])")
    private fun rangeEnds(match: MatchResult): Pair<String, String> = match.groupValues.let { g -> if (g[1].isNotEmpty()) g[1] to g[2] else g[3] to g[4] }
    // "@" works like "at", but only as a word of its own: bob@example.com is not a time or a place.
    private const val atWord = "(?:\\bat\\s+|(?<!\\S)@\\s*)"
    // Spoken times: half past 3, quarter to 5pm, 20 past 2, ten to 12, 3 o'clock. Only words go before "to",
    // because "4 to 5pm" is a range.
    private const val spokenAmount = "half|quarter|five|ten|twenty(?:[-\\s]five)?|\\d{1,2}"
    private const val spokenTime = "(?:(?:$spokenAmount)\\s+past|(?:quarter|five|ten|twenty(?:[-\\s]five)?)\\s+to)\\s+\\d{1,2}(?:\\s*$meridiem)?|\\d{1,2}\\s*o['’]?\\s?clock(?:\\s*$meridiem)?"
    // With the hour as a word ("half past four", "ten to five", "four o'clock"): only after at/by/around, so a title like
    // "Kids aged five to six" stays a title.
    private const val hourWords = "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"
    private const val spokenWords = "(?:(?:$spokenAmount)\\s+past|(?:quarter|five|ten|twenty(?:[-\\s]five)?)\\s+to)\\s+(?:$hourWords)(?:\\s*$meridiem)?|(?:$hourWords)\\s*o['’]?\\s?clock(?:\\s*$meridiem)?"
    // "around 3pm", "about 3pm", "~3pm", "3pm-ish", "7ish": approximately is still the time.
    private const val approx = "(?:(?:around|about|approx(?:imately)?|roughly|circa)\\s+|~\\s*)"
    // 3p, 3:30p and 15.30 are times only where they read like one; see readsAsTime.
    private const val shortClock = "\\d{1,2}(?::\\d{2})?[ap]|\\d{1,2}\\.[0-5]\\d"
    private val times = rx("(?:$atWord|\\bby\\s+|\\bbefore\\s+|\\b|(?<!\\S)(?=~))(?:(?:at\\s+)?$approx)?(?:$spokenTime|$clockAmPm|$hhmm(?:\\s*$hoursSuffix)?|$noonOrMidnight|\\d{1,2}(?:[:.]\\d{2})?\\s*$meridiem|\\d{1,2}[:h]\\d{2}|$shortClock|\\d{1,2}(?=-?ish))(?:\\s*-?ish|\\s+sharp)?(?![\\w])|$atWord\\d{1,2}\\b(?![:.h])(?:\\s+sharp\\b)?|(?:$atWord|\\b(?:by|around|about)\\s+)(?:$spokenWords)(?![\\w])")
    private val shortClocks = rx("(?<![\\w.:/])($shortClock)(?![\\w.])")
    private const val amount = "(?:-?(?:\\d+(?:\\.\\d+)?|\\.\\d+)|$countWords)"
    private const val hours = "(?:hours?|hrs?|h)"
    private const val minutes = "(?:minutes?|mins?|m)"
    private const val fraction = "(?:(?:a\\s+)?half\\s+(?:an?\\s+)?hour|(?:a\\s+)?quarter\\s+of\\s+an?\\s+hour)"
    private const val span = "(?:$fraction|$amount\\s+and\\s+a\\s+half\\s+$hours|$amount\\s*$hours(?:\\s+and\\s+a\\s+half|\\s*(?:and\\s+)?$amount\\s*$minutes)?|$amount\\s*$minutes)"
    // After for or in, minutes may follow the hours without a unit: "for 1 hour 30", "in 1h30". Not before a month: "2 hours 20 Oct".
    private const val wordSpan = "(?:$amount\\s*$hours\\s*(?:and\\s+)?[0-5]\\d(?![\\w:.])(?!\\s*$minutes\\b)(?!\\s+(?:of\\s+)?(?:$months)\\b)|$span)"
    private val durations = rx("\\bfor\\s+$wordSpan\\b")
    // A length right after a time, without "for": "1pm 1.5 hours", "3pm 45 mins" (see where it's read).
    private val bareDurations = rx("(?<![\\w.])$span(?![\\w])")
    private val relativeTimes = rx("\\bin\\s+$wordSpan\\b")
    private val durationParts = rx("($amount)\\s*($hours|$minutes)(?![a-z])")
    private val numericDate = rx("(?<![\\d:/.])\\b\\d{1,2}(?:[/-]\\d{1,2}(?:[/-]\\d{2,4})?|\\.\\d{1,2}\\.(?:\\d{4}|\\d{2}))\\b(?![:\\d]|[/.]\\d)")
    // Not after a hyphen: "check-in", "sign-on" are words, not unfinished phrases.
    private val unfinished = rx("(?<![-\\w])(?:at|on|in|for|from|until|to|next|this)(?:\\s+(?:[-\\d.]+|$countWords|half|a quarter))?\\s*$")
    // "Table for 4 Saturday", "Dinner for two Friday": a whole number after "for", with the when after it, is how many people:
    // title text rather than an unfinished length. At the very end ("Study for 30") the length may still be being typed.
    // Only 1–20: a larger number ("Study for 45 Monday") may be a length still missing its unit, so it keeps asking. 10–20 as
    // digits only after a booking or meal word ("Party for 20", "BBQ for 12"): "Practice for 15 tomorrow" may be minutes.
    private val partySize = rx("for\\s+(?:[1-9]|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)\\s*")
    private val largePartySize = rx("for\\s+(?:1\\d|20)\\s*")
    private val bookingWord = rx("\\b(?:table|booking|book|reservation|reserve|restaurant|dinner|lunch|breakfast|brunch|party|bbq|barbecue|catering|cook|pizza|tickets?)\\s+$")
    private val quote = Regex("\"[^\"]*\"")
    private val at = rx(atWord)
    private const val pluralWeekdays = "mondays|tuesdays|wednesdays|thursdays|fridays|saturdays|sundays"
    private val repeats = rx("\\b(?:(?:every|each|on)\\s+weekends?|weekends|every\\s+(?:\\d{1,3}(?:st|nd|rd|th)|second|third|fourth|fifth|sixth|seventh)\\s+(?:day|week|month)|bi-?weekly|(?:every|each)\\s+weekdays?|on\\s+weekdays|every\\s+other\\s+day|every\\s+$relativeCount\\s+(?:days|weeks|months)|every\\s+other\\s+(?:$weekdays)|(?:on\\s+)?(?:$pluralWeekdays)|every\\s+(?:$weekdays|day|week|(?:2|two|other)\\s+weeks?|other\\s+month|fortnight|month|year)|daily|weekly|fortnightly|monthly|quarterly|yearly|annually|weekdays)\\b")
    // Two or more weekdays: "every Mon, Wed and Fri", "Mon Wed Fri", "Tue/Thu", "Mondays and Thursdays".
    private val weekdayLists = rx("\\b(?:(?:every|each|on)\\s+)?(?:$pluralWeekdays|$weekdays)\\b(?:\\s*(?:,|/|&|\\band\\b)?\\s*(?:and\\s+)?(?:$pluralWeekdays|$weekdays)\\b)+")
    private const val weekOfMonth = "first|second|third|fourth|last|1st|2nd|3rd|4th"
    // "first Monday of every month", "every last Friday". "every 2nd Tuesday" could be fortnightly, so it asks.
    private val monthlyWeekdays = rx("\\b(?:(?:on|every|each)\\s+)?(?:the\\s+)?($weekOfMonth)\\s+($weekdays)\\s+of\\s+(?:every|each|the)\\s+month\\b|\\bevery\\s+($weekOfMonth)\\s+($weekdays)\\b(?!\\s+of\\b)")
    private val repeatCounts = rx("\\bfor\\s+(\\d+)\\s+(?:times?|occurrences?)\\b")
    // With a repeat, "for 10 weeks" says how long it runs. Without one it stays title text: Holiday for 2 weeks.
    // Multi-day entries. A date range: "3 Oct – 7 Oct", "3–7 Oct", "Oct 3–7", "from Friday to Sunday", "Fri-Sun". With a
    // clock time and no next/this/from, a weekday range is a repeat on those days: "Work Mon-Fri 9am". "for 5 days" is a
    // length only without a repeat; with one it stays an occurrence count.
    private const val rangeJoin = "\\s*(?:[-–—]|to|until|till|through|thru)\\s*"
    private val dateRanges = rx("\\b(?:from\\s+)?(?:the\\s+)?($dayNumber)(?:\\s+(?:of\\s+)?($months))?(?:\\s+(\\d{4}))?$rangeJoin(?:the\\s+)?($dayNumber)\\s+(?:of\\s+)?($months)(?:\\s+(\\d{4}))?\\b" +
        "|\\b(?:from\\s+)?($months)\\s+($dayNumber)$rangeJoin(?:($months)\\s+)?($dayNumber)(?:,?\\s+(\\d{4}))?\\b" +
        "|\\b(?:from\\s+)?((?:next\\s+|this\\s+)?(?:$weekdays))(?:\\s*[-–—]\\s*|\\s+(?:to|until|till|through|thru)\\s+)($weekdays)\\b")
    // "3 nights from Friday", "for 5 nights": a stay, ending the morning after the last night. "A night" or "one night" only
    // with "for": "Book a night out", "One night in Bangkok" are titles. The count is group 1 (with for) or 2. Not the end
    // of a numeric date: "Gig 12/10 night" is a night on 12/10.
    private val nights = rx("\\b(?:for\\s+($relativeCount)|(?<![/.-])(?!(?:a|an|one)\\b)($relativeCount))\\s+nights?(?:\\s+from)?\\b")
    private val spanDays = rx("\\bfor\\s+($relativeCount)\\s+(days?|weeks?|wks?)\\b")
    private val repeatPeriods = rx("\\bfor\\s+($relativeCount)\\s+(days?|weeks?|wks?|fortnights?|months?|years?)\\b")
    private const val reminderSpan = "$span|(?:$amount|half(?:\\s+an?)?)\\s*(?:days?|weeks?)|the\\s+(?:day|week)"
    // "remind me 2 days before", "alarm 30 min before", "remind me 1 day before at 9am" (group 2: the clock time that day).
    private val reminders = rx("\\b(?:and\\s+)?(?:(?:remind|notify|alert)\\s+me|(?:with\\s+)?(?:an?\\s+)?(?:alarm|alert))\\s+($reminderSpan)\\s+before\\b(?:\\s+(?:at\\s+|@\\s*)($clock)(?![\\w]))?")
    // "remind me at 9am", "remind me at 8pm the day before": a clock time, turned into minutes before once the event time is known.
    private val reminderClocks = rx("\\b(?:and\\s+)?(?:remind|notify)\\s+me\\s+(?:at\\s+|@\\s*)($clock)(\\s+(?:on\\s+)?the\\s+day\\s+before)?(?![\\w])")
    // "remind me the night before": the day before at 8pm.
    private val nightBefore = rx("\\b(?:and\\s+)?(?:remind|notify|alert)\\s+me\\s+(?:on\\s+)?the\\s+(?:night|evening)\\s+before\\b")
    private val noReminder = rx("\\b(?:(?:and|with)\\s+)?no\\s+(?:reminders?|alarms?|alerts?)\\b")
    // "EOD", "by close of business": 5pm.
    private val endOfDay = rx("\\b(?:(?:by|at|before|due(?:\\s+by)?)\\s+)?(?:eod|cob|close\\s+of\\s+business|end\\s+of\\s+(?:the\\s+)?(?:business\\s+)?day)\\b")
    // A time zone right after a time: converted to the phone's time zone. "Sydney time", "UK time": that place's zone,
    // daylight saving included.
    private val zoneOffsets = mapOf("AEST" to ZoneOffset.ofHours(10), "AEDT" to ZoneOffset.ofHours(11), "ACST" to ZoneOffset.ofHoursMinutes(9, 30),
        "ACDT" to ZoneOffset.ofHoursMinutes(10, 30), "AWST" to ZoneOffset.ofHours(8), "UTC" to ZoneOffset.UTC, "GMT" to ZoneOffset.UTC)
    private val placeZones = mapOf(
        "Australia/Sydney" to listOf("sydney", "canberra", "nsw"), "Australia/Melbourne" to listOf("melbourne", "vic"),
        "Australia/Brisbane" to listOf("brisbane", "queensland", "qld"), "Australia/Adelaide" to listOf("adelaide"),
        "Australia/Perth" to listOf("perth", "wa"), "Australia/Darwin" to listOf("darwin"), "Australia/Hobart" to listOf("hobart", "tasmania", "tas"),
        "Pacific/Auckland" to listOf("auckland", "wellington", "nz", "new zealand"), "Europe/London" to listOf("london", "uk"),
        "Europe/Paris" to listOf("paris"), "Europe/Berlin" to listOf("berlin"), "America/New_York" to listOf("new york", "nyc", "ny"),
        "America/Los_Angeles" to listOf("los angeles", "la", "san francisco"), "Asia/Tokyo" to listOf("tokyo", "japan"),
        "Asia/Singapore" to listOf("singapore"), "Asia/Hong_Kong" to listOf("hong kong"), "Asia/Makassar" to listOf("bali"),
        "Asia/Dubai" to listOf("dubai"), "Asia/Kolkata" to listOf("india", "delhi", "mumbai"),
    ).flatMap { (zone, names) -> names.map { it to ZoneId.of(zone) } }.toMap()
    private val timeZones = rx("(?<![\\w])(aest|aedt|acst|acdt|awst|utc|gmt|(?:${placeZones.keys.sortedByDescending { it.length }.joinToString("|") { it.replace(" ", "\\s+") }})\\s+time)(?![\\w+-])")
    // "all afternoon": a block of that part of the day.
    private val allPeriod = rx("\\ball\\s+(?:the\\s+)?(morning|afternoon|arvo|evening)\\b")
    private val periodBlocks = mapOf("morning" to (LocalTime.of(9, 0) to 180), "afternoon" to (LocalTime.of(13, 0) to 240),
        "arvo" to (LocalTime.of(13, 0) to 240), "evening" to (LocalTime.of(18, 0) to 180))
    // "twice a day at 8am and 8pm": daily, at each of the times given.
    private val timesPerDay = rx("\\b(twice|two\\s+times|three\\s+times)\\s+(?:(?:a|per|each)\\s+)?(?:day|daily)\\b")
    // Between the times of one entry: "8am and 8pm", "8am, 2pm and 8pm", "7am & 5pm".
    private val timeJoin = rx("\\s*(?:,\\s*(?:and\\s+)?|&\\s*|and\\s+)")
    // A bare hour range such as 9-5, read as hours rather than a date when the entry already has a day or repeat.
    private val hourRange = Regex("(1[0-2]|[1-9])-(1[0-2]|[1-9])")
    // "todo buy milk", "Task: pay rent": a task, without a reminder.
    private val taskPrefix = rx("^\\s*(?:(?:todo|to-do)\\s*:?|(?:to\\s+do|task)\\s*:)\\s+(?=\\S)")
    // "every morning", "each evening": daily, at a time of that part of the day (asked, or settled by a clock time).
    private val everyPeriod = rx("\\b(?:every|each)\\s+(?:(weekday|day|$weekdays)\\s+)?(morning|afternoon|arvo|evening|night)\\b")
    // "with a reminder", "…, and remind me": a reminder at the time of the event.
    private val plainReminder = rx("\\bwith\\s+(?:a\\s+)?reminder\\b|(?:,\\s*|\\band\\s+)remind\\s+me\\s*$")
    // "until Christmas", "until 30 November", "till next Friday": when a repeat stops. A number with one dot is a time,
    // as elsewhere: "9 until 5.30" is an hour range; "until 5.10.26" is a date.
    private const val holidayNames = "anzac\\s+day|australia\\s+day|father['’]?s\\s+day|nye|easter(?:\\s+(?:sunday|monday))?|good\\s+friday|mother['’]?s\\s+day|christmas\\s+eve|(?:christmas|xmas)(?:\\s+day)?|boxing\\s+day|new\\s+year['’]?s(?:\\s+(?:eve|day))?|hallowe['’]?en|valentine['’]?s(?:\\s+day)?"
    private val repeatUntil = rx("\\b(?:until|till|til|through|thru|up\\s+(?:to|until))\\s+(?:and\\s+including\\s+)?($holidayNames|$datePhrases|\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}|\\d{1,2}[/-]\\d{1,2})\\b")
    // A repeat's first date: "starting next week", "from 6 Oct", "beginning Monday".
    private val repeatStart = rx("\\b(?:starting|beginning|from)\\s+(?:on\\s+)?(next\\s+week|$datePhrases)\\b")
    // "weekly on Tuesdays", "fortnightly on Friday": the rule with its weekday.
    private val ruleOnWeekday = rx("\\b(weekly|fortnightly|bi-?weekly|every\\s+(?:week|fortnight|other\\s+week))\\s+on\\s+(?:the\\s+)?($pluralWeekdays|$weekdays)\\b")
    // "every month on the last day", "the last day of every month": monthly on the 31st, so shorter months get their last day.
    private val lastDayMonthly = rx("\\b(?:(?:every\\s+month|monthly)\\s+on\\s+the\\s+last\\s+day|(?:on\\s+)?the\\s+last\\s+day\\s+of\\s+(?:every|each)\\s+month)\\b")
    // Repeats Planner can't hold in one rule: said plainly rather than half-read.
    private val tooManyPerPeriod = rx("\\bhourly\\b|\\bevery\\s+(?:\\d+\\s+|few\\s+)?(?:hours?|hrs?|minutes?|mins?)\\b|\\b(?:twice|two\\s+times|three\\s+times)\\s+(?:(?:a|an|per|each)\\s+)?(?:day|week|fortnight|month|year|daily|weekly|monthly|yearly)\\b|\\b(?:$weekOfMonth)\\s+and\\s+(?:the\\s+)?(?:$weekOfMonth)\\s+(?:$weekdays)\\b")
    // A length written first: "2hr meeting at 3pm", "30min call 2pm" (only when a clock time is there too).
    private val leadingLength = rx("^\\s*($span)(?=\\s+\\p{L})")
    private val monthDayRepeat = rx("\\b(?:on\\s+)?(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)\\s+(?:of\\s+)?(?:every|each)\\s+month\\b")
    private val schedulingWords = rx("\\b(?:remind|notify|every)\\b")
    // "Remind me to …", also with the when first: "Remind me tomorrow to …", "Remind me in 2 hours to …".
    // A title typed in its own box is masked in front (\uE000), so it may come first.
    private val remindTo = rx("^[\\s\uE000]*(?:please\\s+)?remind\\s+me\\s+(?:(.{1,60}?)\\s+)??to\\b")
    // "Remind me tomorrow", "remind me friday": the when follows directly, usually after a title typed in its own box.
    private val remindLead = rx("^[\\s\uE000]*(?:please\\s+)?remind\\s+me\\b")
    private val allDay = rx("\\ball[-\\s]day\\b")
    private val nowWords = rx("(?<!\\bfrom\\s)\\b(?:right\\s+)?now\\b")
    // Named days set the date but stay in the title. Plain "Christmas" only with on, or lunch/dinner/breakfast/morning:
    // "Christmas shopping" and "Christmas party" are usually before the day.
    private val holidays = rx("\\b(?:anzac\\s+day|australia\\s+day|father['’]?s\\s+day|nye|easter\\s+(?:sunday|monday)|good\\s+friday|mother['’]?s\\s+day|(?<=\\bon\\s)easter|easter(?=\\s+(?:lunch|dinner|breakfast|morning|egg|service)\\b)|christmas\\s+eve|(?:christmas|xmas)\\s+day|boxing\\s+day|new\\s+year['’]?s\\s+eve|new\\s+year['’]?s\\s+day|valentine['’]?s(?:\\s+day)?|hallowe['’]?en|(?<=\\bon\\s)(?:christmas|xmas)|(?:christmas|xmas)(?=\\s+(?:lunch|dinner|breakfast|morning)\\b))(?![\\w'’])" +
        // Getting ready happens before the day: "Halloween costume shopping", "Christmas Eve prep".
        "(?!\\s+(?:shopping|costumes?|prep\\w*|planning|decorations?|cards?|gifts?|presents?)\\b)")
    private val bareWeekday = rx("^(?:on\\s+)?(?:$weekdays)$")
    private val fourDigits = rx("(?<![\\w.:/])(\\d{4})(?=(?:$hoursSuffix)?\\b)(?![/.:]\\d)(?!\\s*$meridiem(?![\\w]))")

    // Short words that are also ordinary title words: "Buy sun cream", "Sat nav", "Midnight Mass".
    // They count as scheduling only beside other scheduling words, never before an ordinary word.
    private val titleWordCandidates = rx("\\b(sun|sat|wed|noon|midnight|midday|now)\\b")
    private val nextWord = Regex("^[\\s,]*([A-Za-z]+)")
    private val scheduleVocabulary = (listOf("at", "on", "in", "from", "for", "to", "until", "till", "by", "with", "and", "then",
        "before", "after", "actually", "every", "each", "remind", "notify", "next", "this", "the", "today", "tomorrow", "tmr",
        "morning", "afternoon", "arvo", "evening", "night", "noon", "midnight", "midday", "am", "pm", "til", "all",
        "yesterday", "last", "ago") +
        weekdays.split('|') + months.split('|') + tomorrowSpellings + tonightSpellings).toSet()
    // Words that make a part of the day before them part of a name: "Tonight Show", "Monday Night Football", "Saturday Night
    // Live", "Friday Night Lights", "2night club". Any other word after it ("Dinner Tonight Sam", "Pay bills tonight
    // online") leaves the part of the day a when.
    private val periodNameWords = setOf("show", "shows", "live", "football", "footy", "lights", "fever", "club", "special",
        "news", "raw", "smackdown", "frights")
    // "the Tonight Show": an article before a part of the day makes it a name. Not a possessive: "Meet Her Friday Night".
    private val articleBefore = rx("\\b(?:the|a|an)\\s+(?:+\\s+)*$")
    // A frequency word naming something: "Weekly report", "The Daily Telegraph", "the Every Day Cafe". Group 1 is the
    // adjective form, group 2 the word after it; see parse.
    private val frequencyNames = rx("(?<![\\w-])(?:(daily|weekly|bi-?weekly|fortnightly|monthly|quarterly|yearly|annually)|every\\s+day)(?=\\s+(\\p{L}+))")
    // Also with a word already kept as title text between: "the midnight sun".
    private val determinerBefore = rx("\\b(?:the|a|an|my|our|your|his|her|their)\\s+(?:\uE000+\\s+)*$")
    // Capitalised words right after "at": the place's name so far ("at Rising "). Not a possessive: "at Mum's Sun" is Sunday.
    private val placeNameBefore = Regex("(?:\\b[Aa][Tt]\\s+|(?<!\\S)@\\s*)(?:\\p{Lu}[\\p{L}&-]*\\s+)+$")

    // "Gig 12/10 night": right after a numeric date, a part of the day is that day's, as after a weekday. Not after 9-5 or
    // 10.30, which may be hours.
    private const val afterNumericDate = "(?<=\\b\\d{1,2}(?:/\\d{1,2}(?:/\\d{2,4})?|[.-]\\d{1,2}[.-]\\d{2,4})\\s{1,3})"
    private val unsupported = rx("\\b(?:every\\s+other\\s+(?!weeks?\\b|day\\b|months?\\b|(?:$weekdays)\\b)\\w+|(?:at\\s+)?lunch\\s*time|first\\s+thing(?:\\s+in\\s+the\\s+morning)?|(?:after|before)\\s+(?:breakfast|lunch|dinner|work)|(?:$tomorrowWords|yesterday|this|next|nxt)\\s+(?:morning|afternoon|arvo|evening|night|weekend)|last\\s+night(?!\\s+of\\b)|(?:$weekdays)\\s+(?:morning|afternoon|arvo|evening|night)|(?:this|next|nxt)\\s+(?:week|wk|month|mth|year|yr)|(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?(?:week|month|year)|$tonightWords|in\\s+the\\s+(?:morning|afternoon|arvo|evening)|arvo|$afterNumericDate(?:morning|afternoon|evening|night))\\b")
    private val vagueTimes = rx("(?:morning|afternoon|arvo|evening|night|$tonightWords|breakfast|lunch|dinner|work|lunch\\s*time|first\\s+thing)$")
    // A part of the day that also settles am/pm for a clock time beside it: tomorrow morning at 7.
    private val dayPeriods = mapOf(
        "morning" to LocalTime.of(4, 0)..LocalTime.of(11, 59), "afternoon" to LocalTime.NOON..LocalTime.of(17, 59), "arvo" to LocalTime.NOON..LocalTime.of(17, 59),
        "evening" to LocalTime.of(16, 0)..LocalTime.of(23, 59), "night" to LocalTime.of(17, 0)..LocalTime.of(23, 59),
        "tonight" to LocalTime.of(17, 0)..LocalTime.of(23, 59),
        "lunchtime" to LocalTime.of(11, 0)..LocalTime.of(14, 59), "lunch time" to LocalTime.of(11, 0)..LocalTime.of(14, 59),
        "first thing" to LocalTime.of(4, 0)..LocalTime.of(10, 59))

    /** [now] enables "in 30 minutes"; without it such phrases ask for a time. */
    fun parse(input: String, today: LocalDate, literalRanges: List<IntRange> = emptyList(),
              now: LocalDateTime? = null, dayFirst: Boolean? = numericDayFirst, zone: ZoneId = ZoneId.systemDefault()): QuickEntrySuggestion {
        // Keep offsets identical to the text field, including repeated spaces/newlines.
        val text = input.replace('“', '"').replace('”', '"').map { if (it.isWhitespace()) ' ' else it }.joinToString("")
        val phrases = mutableListOf<QuickEntryPhrase>()
        fun error(message: String) = QuickEntrySuggestion(text.trim(), today, null, message, phrases = phrases.toList())
        if (text.count { it == '"' } % 2 != 0) return error("Close the quotation marks around your title or place name.")
        var remaining = text
        val consumed = mutableListOf<IntRange>()
        fun mask(range: IntRange, fill: Char = ' ') { remaining = remaining.replaceRange(range, fill.toString().repeat(range.count())) }
        fun consume(range: IntRange, kind: QuickPhraseKind) {
            // A dash standing alone just before a date or time separates it: "Meeting - Monday - 10am".
            val dash = if (kind == QuickPhraseKind.DATE || kind == QuickPhraseKind.TIME)
                re("(?<=\\s)[-–—]\\s*$").find(remaining.substring(0, range.first))?.range?.first else null
            val whole = (dash ?: range.first)..range.last
            consumed += whole; phrases += QuickEntryPhrase(range.first, range.last + 1, kind); mask(whole)
        }
        quote.findAll(text).forEach { mask(it.range, '\uE000') }
        literalRanges.filter { it.first >= 0 && it.last < text.length && !it.isEmpty() }.forEach { mask(it, '\uE000') }
        // "Rent $450pm", "Gym $120 pm", "Pay Sam AUD 20.50": a number beside a currency sign or code is an amount, never a
        // time or date.
        moneyAmounts.findAll(remaining).toList().forEach { mask(it.range, '\uE000') }
        phoneNumbers.findAll(remaining).filter { m ->
            m.value.count { it.isDigit() } >= 9 && !rx("\\b(?:$months)\\.?,?\\s*$").containsMatchIn(remaining.substring(0, m.range.first))
        }.toList().forEach { mask(it.range, '\uE000') }
        labelledNumbers.findAll(remaining).toList().forEach { mask(it.range, '\uE000') }
        // A space masked with \uE001 keeps the words on either side apart: see yearAfterMonth and hoursBeforeMonthDay.
        fun separate(range: IntRange) = range.filter { remaining[it].isWhitespace() }.forEach { mask(it..it, '\uE001') }
        // A year from last year to 2100; any other four digits after a month-name date are a 24-hour time.
        yearAfterMonth.findAll(remaining).toList().forEach { if (it.groupValues[2].toInt() !in today.year - 1..2100) separate(it.groups[1]!!.range) }
        hoursBeforeMonthDay.findAll(remaining).toList().forEach { separate(it.groups[1]!!.range) }
        titleWordCandidates.findAll(remaining).toList().forEach { match ->
            val clockWord = match.value.lowercase(Locale.ROOT) in setOf("noon", "midnight", "midday")
            val lead = if (clockWord) "at|from|until|till?|to|by" else "on|next|this|last|every|each|from|until|till?|to|by"
            val before = remaining.substring(0, match.range.first)
            if (rx("(?:\\b(?:$lead)\\s+|[-–—]\\s*)$").containsMatchIn(before)) return@forEach
            val following = nextWord.find(remaining.substring(match.range.last + 1))?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            // "Watch the sun", "Dinner at the Sun", "Lunch at Rising Sun": after "the", or ending a place's name, a word.
            if (determinerBefore.containsMatchIn(before) ||
                following == null && match.value.equals("sun", ignoreCase = true) && placeNameBefore.containsMatchIn(before)) {
                mask(match.range, '\uE000'); return@forEach
            }
            // "Watch Midnight in Paris": a capitalised clock word before in/at/of and a name is a title. Written in lower
            // case it is still the time: "Call midnight in Perth".
            if (clockWord && match.value[0].isUpperCase() &&
                re("^\\s+(?:in|at|of)\\s+(?:the\\s+)?\\p{Lu}").containsMatchIn(remaining.substring(match.range.last + 1))) {
                mask(match.range, '\uE000'); return@forEach
            }
            if (following == null) return@forEach
            // "now" is scheduling only at the end: "Meeting now", but "now and then", "Now TV".
            if (following !in scheduleVocabulary || match.value.equals("now", ignoreCase = true)) mask(match.range, '\uE000')
        }
        // A frequency word before an ordinary word is part of a name, not a repeat, where a name would be: capitalised in the
        // middle, after "the", or first and followed by a capitalised word ("Read The Daily Telegraph", "Daily Mail delivery").
        // "Every day" only after "the" or written as a name ("Every Day Cafe"). First and followed by an ordinary word, it still
        // repeats but stays in the title ("Weekly report due Friday", "Daily standup 9am"). "Gym weekly" still repeats.
        var leadingRepeat: IntRange? = null
        frequencyNames.findAll(remaining).toList().forEach { match ->
            val following = match.groupValues[2]
            if (following.lowercase(Locale.ROOT) in scheduleVocabulary) return@forEach
            val before = remaining.substring(0, match.range.first)
            val adjective = match.groups[1] != null
            if (adjective && before.isBlank() && !following[0].isUpperCase()) { leadingRepeat = match.range; return@forEach }
            val named = determinerBefore.containsMatchIn(before) || if (adjective) before.isBlank() || match.value[0].isUpperCase()
                else match.value.split(' ').last()[0].isUpperCase() && following[0].isUpperCase()
            if (named) mask(match.range, '\uE000')
        }
        var taskHint = false
        // Where "remind me" starts, after any masked title in front.
        fun remindStart(match: MatchResult) = match.range.first + match.value.indexOfFirst { !it.isWhitespace() && it != '\uE000' }
        // Words before "to" that aren't a when are the start of the title: "Remind me about the trip to Paris tomorrow".
        remindTo.find(remaining)?.takeIf { match -> match.groups[1]?.let { readsAsWhen(it.value) } != false }?.let { match ->
            taskHint = true
            // The when between "remind me" and "to" is left for the date and time parsers.
            val between = match.groups[1]
            if (between == null) consume(remindStart(match)..match.range.last, QuickPhraseKind.REMINDER) else {
                consume(remindStart(match) until between.range.first, QuickPhraseKind.REMINDER)
                consume(between.range.last + 1..match.range.last, QuickPhraseKind.REMINDER)
            }
        }
        // "Remind me tomorrow": as "remind me to", unless it starts a detailed reminder ("remind me 30 min before", "at 9am").
        if (!taskHint) remindLead.find(remaining)?.let { match ->
            val detailed = sequenceOf(reminders, reminderClocks, nightBefore)
                .any { r -> r.findAll(remaining).any { it.range.first <= match.range.last && match.range.first <= it.range.last } }
            if (!detailed) { taskHint = true; consume(remindStart(match)..match.range.last, QuickPhraseKind.REMINDER) }
        }

        var taskPrefixSaid = false
        if (!taskHint) taskPrefix.find(remaining)?.let { match ->
            taskHint = true; taskPrefixSaid = true
            consume(match.range, QuickPhraseKind.TASK)
        }

        var timePrompt: String? = null
        var dayPeriod: String? = null
        var periodHour: Int? = null
        var impliedToday = false
        var impliedYesterday = false
        // The vague part of the day as typed, and where it ends: see periodInTitle.
        var periodSaid: String? = null
        var periodEnd: Int? = null
        // "twice a day": daily, and needs that many times (checked once the times are read).
        val perDayMatches = timesPerDay.findAll(remaining).toList()
        if (perDayMatches.size > 1) return error("Use one repeat rule. Adjust it in the full editor (More options → Open in full editor).")
        val perDay = perDayMatches.firstOrNull()?.let { match ->
            consume(match.range, QuickPhraseKind.REPEAT)
            match.groupValues[1].lowercase(Locale.ROOT).let { if (it.startsWith("three")) 3 else 2 } to match.value
        }
        tooManyPerPeriod.find(remaining)?.let { match ->
            phrases += QuickEntryPhrase(match.range.first, match.range.last + 1, QuickPhraseKind.UNSUPPORTED)
            return error(if (rx("\\band\\b").containsMatchIn(match.value))
                "Planner repeats on one week of the month. Add ‘${match.value.substringAfter(" and ").trim()}’ as a second entry."
                else if (rx("hour|hrs?\\b|min").containsMatchIn(match.value)) "Planner repeats at most once a day, so it can't repeat ‘${match.value}’. Edit it, or open More options → Adjust recognised text to keep it in the title."
                else "Planner can't repeat ‘${match.value}’ in one entry. Add an entry for each, or keep the words in the title.")
        }
        // "every morning", "every weekday evening", "every Monday night": a repeat whose time is asked for, or settled by a
        // clock time in that part of the day.
        var dailyPeriod = false
        var periodRule: RepeatRule? = null
        var periodDay: DayOfWeek? = null
        everyPeriod.findAll(remaining).toList().let { found ->
            if (found.size > 1) return error("Use one repeat rule. Adjust it in the full editor (More options → Open in full editor).")
            found.firstOrNull()?.let { match ->
                dailyPeriod = true
                val which = match.groupValues[1].lowercase(Locale.ROOT)
                periodRule = when (which) { "", "day" -> RepeatRule.DAILY; "weekday" -> RepeatRule.WEEKDAYS; else -> { periodDay = weekdayOf(which); RepeatRule.WEEKLY } }
                dayPeriod = match.groupValues[2].lowercase(Locale.ROOT)
                timePrompt = "What time did you mean by ‘${match.value}’? Tap Choose time."
                consume(match.range, QuickPhraseKind.REPEAT)
            }
        }
        // "all afternoon": that part of the day as a block, read before "arvo" alone asks for a time.
        val allPeriodMatches = allPeriod.findAll(remaining).toList()
        if (allPeriodMatches.size > 1) return error("Use one time phrase, or choose a specific time.")
        val periodBlock = allPeriodMatches.firstOrNull()?.let { match ->
            consume(match.range, QuickPhraseKind.TIME)
            match.value.trim() to periodBlocks.getValue(match.groupValues[1].lowercase(Locale.ROOT))
        }
        // "starting next week" is a repeat's start, checked once the repeat is known.
        val wordDateRanges = (wordDateMatches.findAll(remaining) + repeatStart.findAll(remaining)).map { it.range }.toList()
        unsupported.findAll(remaining).toList().forEach { match ->
            if (wordDateRanges.any { it.first <= match.range.last && match.range.first <= it.last }) return@forEach
            val value = match.value.lowercase(Locale.ROOT).replace(re("\\s+"), " ").let { if (it in tonightSpellings) "tonight" else it }
            if (!vagueTimes.containsMatchIn(value)) {
                phrases += QuickEntryPhrase(match.range.first, match.range.last + 1, QuickPhraseKind.UNSUPPORTED)
                return error("‘${match.value}’ needs a specific date, time or supported repeat. Edit it, or open More options → Adjust recognised text to keep it in the title.")
            }
            // "Read tonight's paper", "Plan tomorrow night's dinner": a possessive part of the day is title text.
            val after = remaining.substring(match.range.last + 1)
            if (re("^['\u2019]s\\b").containsMatchIn(after)) { mask(match.range, '\uE000'); return@forEach }
            // "Watch The Tonight Show", "Monday Night Football", "Last Night in Soho": capitalised in the middle, after "the"
            // or before a word that names a show or club, a part of the day is part of a name. Before any other word, a
            // name or not, it is still a when: "Bins Friday Night", "Dinner Tonight Sam", "Meet Her Friday Night",
            // "Drinks Friday Night at Luigi's".
            val nameAfter = re("^\\s+(\\p{Lu}\\p{L}*)").find(after)?.groupValues?.get(1)?.lowercase(Locale.ROOT)?.let { it in periodNameWords } == true
            if (remaining.substring(0, match.range.first).any { !it.isWhitespace() } &&
                match.value.split(re("\\s+")).all { it[0].isUpperCase() } &&
                (articleBefore.containsMatchIn(remaining.substring(0, match.range.first)) || nameAfter ||
                    value == "last night" && re("^\\s+(?:in|of)\\s+(?:the\\s+)?\\p{Lu}").containsMatchIn(after))) {
                mask(match.range, '\uE000'); return@forEach
            }
            if (timePrompt != null) return error("Use one time phrase, or choose a specific time.")
            periodSaid = match.value
            timePrompt = "What time did you mean by ‘${match.value}’? Tap Choose time."
            dayPeriod = dayPeriods.keys.firstOrNull { value.endsWith(it) }
            // Keep 'tomorrow' or a weekday for the date parser; the rest is a time awaiting confirmation.
            val first = value.substringBefore(' ')
            val keepsDate = first in tomorrowSpellings || first == "yesterday" || rx("^(?:$weekdays)$").matches(first)
            var start = if (keepsDate && ' ' in value) match.range.first + first.length else match.range.first
            var end = match.range.last
            impliedToday = value.startsWith("this ") || value == "tonight" || value == "arvo" || value == "last night"
            // "last night": yesterday, at a time in the night.
            impliedYesterday = value == "last night"
            // "3 in the afternoon", "8 tonight": the number is the hour.
            if (dayPeriod != null) rx("(?<![\\w.:/£€¥$])(\\d{1,2})\\s+$").find(remaining.substring(0, start))?.let { hour ->
                // After at/@ the ordinary time rules take it: "Dinner at 7 tonight".
                val afterAt = rx("(?:\\bat\\s+|@\\s*)$").containsMatchIn(remaining.substring(0, hour.range.first))
                if (!afterAt && hour.groupValues[1].toInt() in 1..12) { periodHour = hour.groupValues[1].toInt(); start = hour.range.first }
            }
            // "Friday night 8": the hour after the part of the day, when nothing but schedule words follow. "9/10" is a date,
            // "tonight 4 Sam" a title.
            if (dayPeriod != null && periodHour == null) rx("^\\s+(\\d{1,2})(?![\\w:./\\-–—])(?!\\s*(?:[-–—]|to|till?|until)\\b)").find(remaining.substring(match.range.last + 1))?.let { hour ->
                val rest = remaining.substring(match.range.last + 1 + hour.range.last + 1)
                val scheduleNext = rest.isBlank() || nextWord.find(rest)?.groupValues?.get(1)?.lowercase(Locale.ROOT)?.let { it in scheduleVocabulary } == true
                if (hour.groupValues[1].toInt() in 1..12 && scheduleNext) { periodHour = hour.groupValues[1].toInt(); end = match.range.last + hour.range.last + 1 }
            }
            consume(start..end, QuickPhraseKind.TIME)
            periodEnd = end + 1
        }

        // Protect a place before interpreting its words as dates. Later schedule clauses are independent.
        var location = ""
        for (marker in at.findAll(remaining).toList()) {
            val start = marker.range.last + 1
            // "Drinks at tonight with Sam", "at every morning": "at" belongs to the part of the day already read, not a place.
            // Measured from the word itself: the marker's spaces run on across the masked phrase.
            val wordEnd = marker.range.first + marker.value.trimEnd().length
            val next = wordEnd + text.substring(wordEnd).let { it.length - it.trimStart().length }
            phrases.lastOrNull { it.kind in setOf(QuickPhraseKind.TIME, QuickPhraseKind.REPEAT) && next in it.start until it.end }?.let {
                consume(marker.range, it.kind); continue
            }
            val tail = remaining.substring(start)
            if (rx("^(?:\\d|~|noon\\b|midd?ay\\b|mid-day\\b|midnight\\b|(?:$spokenTime)|(?:$spokenWords)|$approx\\d|(?:the\\s+)?(?:end|last\\s+day)\\s+of\\s+(?:the\\s+)?month\\b)").containsMatchIn(tail)) continue
            val end = sequenceOf(dates, ranges, times, durations, relativeTimes, numericDate, unfinished, repeats, monthDayRepeat, reminders, reminderClocks, nightBefore, noReminder, endOfDay, nights, repeatCounts, allDay, schedulingWords).flatMap { it.findAll(remaining, start) }
                .filter { it.range.first > start }.map { it.range.first }.plus(phrases.filter { it.kind == QuickPhraseKind.TIME && it.start > start }.map { it.start }).minOrNull() ?: text.length
            val value = text.substring(start, end).trim().trimEnd(',').replace("\"", "")
            if (value.isBlank()) return error("Add a place after at, or remove at.")
            location = value
            consume(marker.range.first until end, QuickPhraseKind.LOCATION)
            break
        }

        var reminderMinutes: Int? = null
        var reminderAt: LocalTime? = null
        var reminderDaysBack = 0
        val reminderMatches = reminders.findAll(remaining).toList()
        val reminderClockMatches = reminderClocks.findAll(remaining).toList() + nightBefore.findAll(remaining)
        if (reminderMatches.size + reminderClockMatches.size > 1) return error("Use one reminder here. Add more in the full editor (More options → Open in full editor).")
        fun consumeReminder(match: MatchResult) {
            val comma = remaining.substring(0, match.range.first).indexOfLast { !it.isWhitespace() }
            val start = if (comma >= 0 && remaining[comma] == ',') comma else match.range.first
            consume(start..match.range.last, QuickPhraseKind.REMINDER)
        }
        reminderMatches.firstOrNull()?.let { match ->
            val total = reminderSpanMinutes(match.groupValues[1].lowercase(Locale.ROOT).replace(re("\\s+"), " "))
            if (!total.isFinite() || total !in 0.0..525600.0 || total % 1 != 0.0)
                return error("Use a reminder from 0 to 525600 whole minutes before the event.")
            // "remind me 2 days before at 6pm": that many days back, at that time.
            if (match.groupValues[2].isNotBlank()) {
                if (total < 1440 || total % 1440 != 0.0) return error("Use whole days with a reminder time, for example remind me 1 day before at 6pm.")
                val raw = match.groupValues[2]
                reminderAt = readTime(raw)?.takeUnless { re("([1-9]|1[0-2])[:.]([0-5]\\d)").matches(raw.trim()) }
                    ?: return error("Add am or pm to the reminder time, for example remind me at 9am.")
                reminderDaysBack = (total / 1440).toInt()
            } else reminderMinutes = total.toInt()
            consumeReminder(match)
        }
        reminderClockMatches.firstOrNull()?.takeIf { nightBefore.matches(it.value) }?.let { match ->
            reminderAt = LocalTime.of(20, 0); reminderDaysBack = 1
            consumeReminder(match)
        }
        reminderClockMatches.firstOrNull()?.takeUnless { nightBefore.matches(it.value) }?.let { match ->
            val raw = match.groupValues[1]
            reminderAt = readTime(raw)?.takeUnless { re("([1-9]|1[0-2])[:.]([0-5]\\d)").matches(raw.trim()) }
                ?: return error("Add am or pm to the reminder time, for example remind me at 9am.")
            if (match.groupValues[2].isNotBlank()) reminderDaysBack = 1
            consumeReminder(match)
        }
        plainReminder.findAll(remaining).toList().takeIf { it.isNotEmpty() }?.let { found ->
            if (found.size + reminderMatches.size + reminderClockMatches.size > 1) return error("Use one reminder here. Add more in the full editor (More options → Open in full editor).")
            reminderMinutes = 0
            consumeReminder(found.first())
        }
        // "no reminder": said, so none is added, not even the one "Remind me to …" brings.
        var noReminderSaid = false
        noReminder.findAll(remaining).toList().takeIf { it.isNotEmpty() }?.let { found ->
            if (found.size > 1 || reminderMinutes != null || reminderAt != null) return error("Choose a reminder or no reminder, not both.")
            noReminderSaid = true
            consumeReminder(found.first())
        }
        val allDayMatches = allDay.findAll(remaining).toList()
        allDayMatches.forEach { consume(it.range, QuickPhraseKind.TIME) }
        // A date range, read before times (so "3–7" in "3–7 Oct" isn't 3 to 7 o'clock) and before weekday repeats.
        var rangeStart: LocalDate? = null
        var rangeEnd: LocalDate? = null
        val rangeMatches = dateRanges.findAll(remaining).toList()
        if (rangeMatches.size > 1) return error("Use one date range.")
        var rangeRepeat: RepeatRule? = null
        var rangeRepeatDay: DayOfWeek? = null
        rangeMatches.firstOrNull()?.let { match ->
            val g = match.groupValues
            // "Work Mon-Fri 9am", "Gym Monday to Friday 6am": with a clock time, a repeat on those days.
            if (g[12].isNotEmpty() && !rx("^(?:from|next|this)\\b").containsMatchIn(match.value.trim()) &&
                (times.containsMatchIn(remaining) || ranges.containsMatchIn(remaining))) {
                val from = weekdayOf(g[12]); val to = weekdayOf(g[13])
                val days = buildSet { var d = from; while (true) { add(d); if (d == to) break; d = d.plus(1) } }
                rangeRepeat = when {
                    days.size == 7 -> RepeatRule.DAILY
                    days.size == 1 -> { rangeRepeatDay = from; RepeatRule.WEEKLY }
                    days == (1..5).map { DayOfWeek.of(it) }.toSet() -> RepeatRule.WEEKDAYS
                    else -> RepeatRule.onDays(days)
                }
                consume(match.range, QuickPhraseKind.REPEAT)
                return@let
            }
            fun day(v: String) = v.takeWhile { it.isDigit() }
            var (start, end) = when {
                g[4].isNotEmpty() -> { // 3 Oct – 7 Oct, 3–7 Oct
                    val startMonth = g[2].ifEmpty { g[5] }
                    parseDate(("${day(g[1])} $startMonth${g[3].let { if (it.isEmpty()) g[6].let { y -> if (y.isEmpty()) "" else " $y" } else " $it" }}").lowercase(Locale.ROOT), today) to
                        parseDate(("${day(g[4])} ${g[5]}${if (g[6].isEmpty()) "" else " ${g[6]}"}").lowercase(Locale.ROOT), today)
                }
                g[7].isNotEmpty() -> // Oct 3–7, Oct 3 – Nov 2
                    parseDate(("${day(g[8])} ${g[7]}${if (g[11].isEmpty()) "" else " ${g[11]}"}").lowercase(Locale.ROOT), today) to
                        parseDate(("${day(g[10])} ${g[9].ifEmpty { g[7] }}${if (g[11].isEmpty()) "" else " ${g[11]}"}").lowercase(Locale.ROOT), today)
                else -> { // from Friday to Sunday
                    val first = parseDate(g[12].lowercase(Locale.ROOT), today)
                    first to first?.with(TemporalAdjusters.nextOrSame(weekdayOf(g[13])))
                }
            }
            if (start == null || end == null) return error("That date isn't valid.")
            // "28-3 Jan", "Dec 28-3": one month named once, so the end can't be in another year.
            val oneMonth = g[4].isNotEmpty() && g[2].isEmpty() || g[7].isNotEmpty() && g[9].isEmpty()
            if (oneMonth && day(if (g[4].isNotEmpty()) g[4] else g[10]).toInt() < day(if (g[4].isNotEmpty()) g[1] else g[8]).toInt())
                return error("End the date range after it starts.")
            val hasYear = re("\\d{4}").containsMatchIn(match.value)
            // "28 Dec – 3 Jan 2027": a year on the end only, across New Year, starts the range the year before.
            val endYearOnly = g[4].isNotEmpty() && g[3].isEmpty() && g[6].isNotEmpty() || g[7].isNotEmpty() && g[11].isNotEmpty()
            if (endYearOnly && start.monthValue > end.monthValue) start = start.minusYears(1)
            // "28 Dec – 3 Jan" without years ends in the next year.
            var last = if (end < start && !hasYear) end.plusYears(1) else end
            // "30 Sep – 4 Oct" on 1 October: the range under way, not next year's.
            if (!hasYear && g[12].isEmpty() && start > today && start.minusYears(1) <= today) {
                val earlierLast = if (end < start.minusYears(1)) end.plusYears(1) else end
                if (earlierLast >= today && earlierLast < start) { start = start.minusYears(1); last = earlierLast }
            }
            if (last <= start) return error("End the date range after it starts.")
            if (java.time.temporal.ChronoUnit.DAYS.between(start, last) >= MultiDay.MAX_DAYS)
                return error("A date range can cover at most ${MultiDay.MAX_DAYS} days.")
            rangeStart = start; rangeEnd = last
            consume(match.range, QuickPhraseKind.DATE)
        }
        var repeat = RepeatRule.NONE
        var repeatDay: DayOfWeek? = null
        var monthDay: Int? = null
        val monthDayMatches = monthDayRepeat.findAll(remaining).toList()
        monthDayMatches.firstOrNull()?.let { match ->
            monthDay = match.groupValues[1].toInt().takeIf { it in 1..31 } ?: return error("That date isn't valid.")
            repeat = RepeatRule.MONTHLY
            consume(match.range, QuickPhraseKind.REPEAT)
        }
        // Rules with a weekday of the month or several weekdays go first, before their weekdays read as dates.
        val monthlyWeekdayMatches = monthlyWeekdays.findAll(remaining).toList()
        monthlyWeekdayMatches.firstOrNull()?.let { match ->
            val week = (match.groups[1] ?: match.groups[3])!!.value.lowercase(Locale.ROOT)
            val day = weekdayOf((match.groups[2] ?: match.groups[4])!!.value)
            if (match.groups[3] != null && week in setOf("second", "2nd"))
                return error("‘${match.value.trim()}’ could mean every other ${dayName(day)} or the $week ${dayName(day)} of every month. Type one of those.")
            repeat = RepeatRule.monthlyOn(when (week) { "first", "1st" -> 1; "second", "2nd" -> 2; "third", "3rd" -> 3; "fourth", "4th" -> 4; else -> RepeatRule.LAST }, day)
            consume(match.range, QuickPhraseKind.REPEAT)
        }
        // "weekly on Tuesdays", "every other week on Friday".
        val ruleOnWeekdayMatches = ruleOnWeekday.findAll(remaining).toList()
        ruleOnWeekdayMatches.firstOrNull()?.let { match ->
            repeatDay = weekdayOf(match.groupValues[2])
            repeat = if (rx("fortnight|bi-?weekly|other").containsMatchIn(match.groupValues[1])) RepeatRule.FORTNIGHTLY else RepeatRule.WEEKLY
            consume(match.range, QuickPhraseKind.REPEAT)
        }
        // "monthly on the last day": the 31st, which falls back to the last day of shorter months.
        val lastDayMatches = lastDayMonthly.findAll(remaining).toList()
        lastDayMatches.firstOrNull()?.let { match ->
            monthDay = 31; repeat = RepeatRule.MONTHLY
            consume(match.range, QuickPhraseKind.REPEAT)
        }
        // Only clearly a repeat: with every/each/on, plural days, separators, or three or more days.
        // Two bare weekdays side by side ("Friday Saturday") are more likely a slip, and still ask.
        val weekdayListMatches = weekdayLists.findAll(remaining).filter { match ->
            rx("^(?:every|each|on)\\b|(?:$pluralWeekdays)\\b|[,/&]|\\band\\b").containsMatchIn(match.value) ||
                rx("\\b(?:$weekdays)\\b").findAll(match.value).count() >= 3
        }.toList()
        weekdayListMatches.firstOrNull()?.let { match ->
            val days = rx("\\b(?:$pluralWeekdays|$weekdays)\\b").findAll(match.value).map { weekdayOf(it.value) }.toSet()
            repeat = if (days.size == 1) { repeatDay = days.single(); RepeatRule.WEEKLY } else RepeatRule.onDays(days)
            consume(match.range, QuickPhraseKind.REPEAT)
        }
        val repeatMatches = repeats.findAll(remaining).toList()
        if (repeatMatches.size + monthDayMatches.size + monthlyWeekdayMatches.size + weekdayListMatches.size + ruleOnWeekdayMatches.size +
            lastDayMatches.size + (if (dailyPeriod) 1 else 0) + (if (rangeRepeat != null) 1 else 0) + (if (perDay != null) 1 else 0) > 1)
            return error("Use one repeat rule. Adjust it in the full editor (More options → Open in full editor).")
        if (perDay != null) repeat = RepeatRule.DAILY
        if (dailyPeriod) { repeat = periodRule!!; periodDay?.let { repeatDay = it } }
        rangeRepeat?.let { repeat = it; rangeRepeatDay?.let { day -> repeatDay = day } }
        repeatMatches.firstOrNull()?.let { match ->
            val rule = match.value.lowercase(Locale.ROOT).replace(re("\\s+"), " ").removePrefix("every ").removePrefix("on ")
            // "other day", "3 days", "six weeks", "3 months": every few days, weeks or months.
            val interval = when (rule) {
                "other day" -> 2 to "days"
                "other month" -> 2 to "months"
                else -> rx("^($relativeCount) (days|weeks|months)$").matchEntire(rule)?.let { readAmount(it.groupValues[1]).toInt() to it.groupValues[2] }
                    // "every 3rd day", "every second week".
                    ?: rx("^(?:(\\d{1,3})(?:st|nd|rd|th)|(second|third|fourth|fifth|sixth|seventh)) (day|week|month)$").matchEntire(rule)?.let {
                        (it.groupValues[1].toIntOrNull() ?: (ordinalWordList.indexOf(it.groupValues[2]) + 1)) to it.groupValues[3] + "s" }
            }
            repeat = if (interval != null) {
                val (n, unit) = interval
                val limit = when (unit) { "days" -> 1..365; "months" -> 1..24; else -> 1..52 }
                if (n !in limit) return error("Repeat every 1 to ${limit.last} $unit.")
                when {
                    unit == "days" -> if (n == 1) RepeatRule.DAILY else RepeatRule.everyDays(n)
                    unit == "months" -> if (n == 1) RepeatRule.MONTHLY else RepeatRule.everyMonths(n)
                    n == 1 -> RepeatRule.WEEKLY
                    n == 2 -> RepeatRule.FORTNIGHTLY
                    else -> RepeatRule.everyWeeks(n)
                }
            } else if ("weekend" in rule) RepeatRule.onDays(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
            else if ("weekday" in rule) RepeatRule.WEEKDAYS else when (rule) {
                "day", "daily" -> RepeatRule.DAILY
                "week", "weekly" -> RepeatRule.WEEKLY
                "2 week", "2 weeks", "two week", "two weeks", "other week", "other weeks", "fortnight", "fortnightly", "biweekly", "bi-weekly" -> RepeatRule.FORTNIGHTLY
                "month", "monthly" -> RepeatRule.MONTHLY
                "quarterly" -> RepeatRule.everyMonths(3)
                "year", "yearly", "annually" -> RepeatRule.YEARLY
                else -> {
                    // A weekday, "tuesdays", or "other friday" (fortnightly on Fridays).
                    val day = rule.removePrefix("other ")
                    repeatDay = DayOfWeek.entries.first { it.name.lowercase(Locale.ROOT).startsWith(day.take(3)) }
                    if (rule.startsWith("other ")) RepeatRule.FORTNIGHTLY else RepeatRule.WEEKLY
                }
            }
            // A leading "Weekly report": the repeat is read, the word stays in the title.
            if (match.range == leadingRepeat) { phrases += QuickEntryPhrase(match.range.first, match.range.last + 1, QuickPhraseKind.REPEAT); mask(match.range, '\uE000') }
            else consume(match.range, QuickPhraseKind.REPEAT)
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
        var repeatUntilText: String? = null
        if (repeat != RepeatRule.NONE) {
            val periods = repeatPeriods.findAll(remaining).toList()
            if (periods.size + countMatches.size > 1) return error("Use one occurrence count.")
            periods.firstOrNull()?.let { match ->
                val count = readAmount(match.groupValues[1].lowercase(Locale.ROOT))
                if (!count.isFinite() || count < 1 || count > 1000) return error("Choose a repeat rule and between 2 and 365 occurrences.")
                repeatPeriod = count.toLong() to match.groupValues[2].lowercase(Locale.ROOT)
                consume(match.range, QuickPhraseKind.REPEAT)
            }
            val untils = repeatUntil.findAll(remaining).toList()
            if (untils.size + periods.size + countMatches.size > 1) return error("End the repeat one way: a date, a number of times or a length.")
            untils.firstOrNull()?.let { match ->
                repeatUntilText = match.groupValues[1]
                consume(match.range, QuickPhraseKind.REPEAT)
            }
        }
        // "every Monday starting 12 October": when the repeat begins.
        var startFrom: LocalDate? = null
        var startFromPast = false
        if (repeat == RepeatRule.NONE) repeatStart.findAll(remaining).firstOrNull { it.groupValues[1].lowercase(Locale.ROOT).startsWith("next") }?.let { match ->
            phrases += QuickEntryPhrase(match.range.first, match.range.last + 1, QuickPhraseKind.UNSUPPORTED)
            return error("‘${match.groupValues[1]}’ needs a specific date, time or supported repeat. Edit it, or open More options → Adjust recognised text to keep it in the title.")
        }
        if (repeat != RepeatRule.NONE) repeatStart.findAll(remaining).toList().let { starts ->
            if (starts.size > 1) return error("Use one start date.")
            starts.firstOrNull()?.let { match ->
                val value = match.groupValues[1].lowercase(Locale.ROOT).replace(re("\\s+"), " ")
                startFromPast = rx("^(?:$pastDates)$").matches(value.removePrefix("on "))
                startFrom = (if (value == "next week") today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1)
                    else parseDate(value.removePrefix("on "), today)) ?: return error("That start date isn't valid.")
                consume(match.range, QuickPhraseKind.DATE)
            }
        }
        var spanLength: Long? = null
        if (repeat == RepeatRule.NONE) spanDays.findAll(remaining).toList().let { spans ->
            if (spans.size > 1) return error("Use one length.")
            spans.firstOrNull()?.let { match ->
                val count = readAmount(match.groupValues[1].lowercase(Locale.ROOT))
                val days = if (match.groupValues[2].lowercase(Locale.ROOT).startsWith("d")) count else count * 7
                if (!days.isFinite() || days % 1 != 0.0 || days < 1 || days > MultiDay.MAX_DAYS)
                    return error("An entry can cover 1–${MultiDay.MAX_DAYS} days.")
                if (rangeStart != null) return error("Use a date range or a length, not both.")
                spanLength = days.toLong()
                consume(match.range, QuickPhraseKind.DATE)
            }
        }
        if (repeat == RepeatRule.NONE) nights.findAll(remaining).toList().let { found ->
            if (found.size + (if (spanLength != null) 1 else 0) > 1) return error("Use one length.")
            found.firstOrNull()?.let { match ->
                val count = readAmount(match.groupValues[1].ifEmpty { match.groupValues[2] }.lowercase(Locale.ROOT))
                if (!count.isFinite() || count % 1 != 0.0 || count < 1 || count + 1 > MultiDay.MAX_DAYS)
                    return error("An entry can cover 1–${MultiDay.MAX_DAYS - 1} nights.")
                if (rangeStart != null) return error("Use a date range or a length, not both.")
                spanLength = count.toLong() + 1
                consume(match.range, QuickPhraseKind.DATE)
            }
        }
        // "in 30 minutes" counts from now, rounded up to the next five minutes.
        val relativeMatches = relativeTimes.findAll(remaining).toList()
        if (relativeMatches.size > 1) return error("Use one time.")
        var relativeAt: LocalDateTime? = null
        relativeMatches.firstOrNull()?.let { match ->
            val total = spanMinutes(match.value.lowercase(Locale.ROOT).replace(re("\\s+"), " "))
            if (!total.isFinite() || total !in 1.0..1440.0 || total % 1 != 0.0 || match.value.contains('-'))
                return error("Use a time from 1 minute to 24 hours from now, in whole minutes.")
            // Counted on the zone's timeline, so "in 2 hours" is two real hours across a clock change.
            relativeAt = now?.let { roundUpToFive(it.atZone(zone).plusMinutes(total.toLong()).toLocalDateTime()) }
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
        // The weekday in "Good Friday", "Easter Monday" is part of the holiday's name.
        var ds = dates.findAll(remaining).filterNot { d ->
            bareWeekday.matches(d.value) && rx("\\b(?:good|easter)\\s+$").containsMatchIn(remaining.substring(0, d.range.first))
        }.toList()
        // "Sam's 21st 7pm Saturday", "Sam's 21st tomorrow": beside another date, an ordinal without "the" or "on" is title text.
        fun dropBareOrdinals(otherDate: Boolean) { if (otherDate) ds = ds.filterNot { bareOrdinalDate.matches(it.value) } }
        dropBareOrdinals(ds.any { !bareOrdinalDate.matches(it.value) } || rangeStart != null || startFrom != null || relative || impliedToday ||
            holidays.containsMatchIn(remaining))
        val meridiemRanges = ranges.findAll(remaining).filter { rx("$meridiem$").containsMatchIn(rangeEnds(it).second) }.toList()
        // "Work 9-5 weekdays", "Shift Saturday 10-2": with a day or repeat already given, 9-5 is hours, not a date.
        val whenGiven = repeat != RepeatRule.NONE || ds.isNotEmpty() || rangeStart != null || startFrom != null || impliedToday || holidays.containsMatchIn(remaining)
        // It stays a date beside another clock time ("Gym every Monday 12-10 6pm"), or where it is the very day a weekday
        // given with it names ("Dentist Fri 2-10" is Friday 2 October; "Meeting Friday 9-10" is hours on that Friday).
        fun hoursNotDate(n: MatchResult): Boolean {
            if (!whenGiven || !hourRange.matches(n.value)) return false
            // A four-digit number without a leading zero or "hrs" is title text here ("Budget 2026 review"); see fourDigits.
            if (times.findAll(remaining).any { (it.range.last < n.range.first || it.range.first > n.range.last) &&
                    !re("[1-9]\\d{3}").matches(it.value) }) return false
            val (a, b) = n.value.split('-').map { it.toInt() }
            val weekday = ds.firstOrNull { bareWeekday.matches(it.value) }?.let { weekdayOf(it.value) } ?: return true
            val named = today.with(TemporalAdjusters.nextOrSame(weekday))
            val readings = listOfNotNull(true to (a to b), false to (b to a)).filter { dayFirst == null || it.first == dayFirst }.mapNotNull { (_, dm) ->
                runCatching { LocalDate.of(today.year, dm.second, dm.first).let { if (it < today) it.plusYears(1) else it } }.getOrNull() }
            return readings.none { d -> d == named && (repeatDay == null || d.dayOfWeek == repeatDay) && repeat.fits(d) && (monthDay == null || d.dayOfMonth == monthDay) }
        }
        val numeric = numericDate.findAll(remaining).filter { n -> (ds + meridiemRanges).none { d -> n.range.first <= d.range.last && d.range.first <= n.range.last } }
            .filterNot(::hoursNotDate).toList()
        // Not beside a range that could be 24-hour hours: "Workshop 5th 10-14" has two dates, so it asks for one.
        dropBareOrdinals(numeric.any { n -> !(re("\\d{1,2}-\\d{1,2}").matches(n.value) && n.value.split('-').all { it.toInt() <= 23 }) })
        // A weekday beside a calendar date is a cross-check, not a second date: Friday 2 October, Fri 3/10.
        var weekdayCheck: DayOfWeek? = null
        (ds + numeric).sortedBy { it.range.first }.takeIf { it.size == 2 }?.let { (a, b) ->
            val weekday = listOf(a, b).singleOrNull { bareWeekday.matches(it.value) } ?: return@let
            val other = if (weekday === a) b else a
            if (!rx("\\d").containsMatchIn(other.value) || rx("\\bin\\s|from\\s+today").containsMatchIn(other.value)) return@let
            if (!re("\\s*,?\\s*").matches(remaining.substring(a.range.last + 1, b.range.first))) return@let
            weekdayCheck = weekdayOf(weekday.value)
            // Include the separating comma so "Fri, Oct 2" leaves no stray punctuation in the title.
            consume(if (weekday === a) a.range.first until b.range.first else a.range.last + 1..b.range.last, QuickPhraseKind.DATE)
            ds = ds.filter { it.range != weekday.range }
        }
        // "next Thursday or Friday": both offered, as for a date that reads two ways.
        var orDates: List<LocalDate>? = null
        var orPast = false
        if (ds.size == 2 && numeric.isEmpty() && !impliedToday) {
            val (a, b) = ds
            if (rx("\\s*,?\\s*or\\s+").matches(remaining.substring(a.range.last + 1, b.range.first))) {
                // "due by next Thursday": the lead word goes, as for a single date, so "next" is read.
                val lead = rx("^(?:on|by|before|due(?:\\s+(?:on|by))?)\\s+")
                fun read(m: MatchResult) = m.value.lowercase(Locale.ROOT).replace(re("\\s+"), " ").replace(lead, "")
                val first = read(a)
                // "next Thursday or Friday": the second is next week's too, unless it has its own "on" or "by".
                val carried = rx("^(?:next|this) ").find(first)?.value?.takeIf { bareWeekday.matches(b.value) && !lead.containsMatchIn(b.value) } ?: ""
                orPast = listOf(first, read(b)).any { pastDateWords.matches(it) }
                orDates = listOf(parseDate(first, today) ?: return error("That date isn't valid."),
                    parseDate(carried + read(b), today) ?: return error("That date isn't valid.")).distinct()
                consume(a.range.first..b.range.last, QuickPhraseKind.DATE)
                ds = emptyList()
            }
        }
        if (ds.size + numeric.size > 1 || impliedToday && (ds.isNotEmpty() || numeric.isNotEmpty())) {
            phrases += (ds + numeric).map { QuickEntryPhrase(it.range.first, it.range.last + 1, QuickPhraseKind.DATE) }
            return error("Use one date. Remove the extra date or keep literal words in the title.")
        }
        if (relative && (ds.isNotEmpty() || numeric.isNotEmpty() || impliedToday))
            return error("Use one date or time: ‘in …’ already says when.")
        if (startFrom != null && (ds.isNotEmpty() || numeric.isNotEmpty() || relative || impliedToday || rangeStart != null))
            return error("Use one date: the repeat already says when it starts.")
        if (rangeStart != null && (ds.isNotEmpty() || numeric.isNotEmpty() || relative || impliedToday))
            return error("Use one date. A date range already says when.")
        // Repeats anchored to a weekday, to weekdays or to a day of the month start on a matching date.
        val anchorName = when {
            repeatDay != null -> "repeating weekday"
            repeat == RepeatRule.WEEKDAYS -> "weekday repeat"
            repeat.kind == RepeatRule.Kind.DAYS_OF_WEEK -> "repeating weekdays"
            repeat.kind == RepeatRule.Kind.MONTHLY_WEEKDAY -> "repeating weekday of the month"
            monthDay != null -> "repeating day of the month"
            else -> null
        }
        fun fitsAnchor(d: LocalDate) = (repeatDay == null || d.dayOfWeek == repeatDay) && repeat.fits(d) && (monthDay == null || d.dayOfMonth == monthDay)
        var date = relativeAt?.toLocalDate() ?: if (impliedToday) today.minusDays(if (impliedYesterday) 1 else 0) else
            (startFrom ?: today).let { first -> generateSequence(first) { it.plusDays(1) }.take(400).firstOrNull(::fitsAnchor) ?: first }
        rangeStart?.let { date = it }
        var dateChoices = emptyList<LocalDate>()
        orDates?.let { choices ->
            if (relative || rangeStart != null || startFrom != null) return error("Use one date.")
            date = choices.first(); if (choices.size > 1) dateChoices = choices
        }
        if (ds.isNotEmpty()) {
            date = parseDate(ds.single().value.lowercase(Locale.ROOT).replace(re("\\s+"), " ").removePrefix("due ").removePrefix("on ").removePrefix("by ").removePrefix("before "), today)
                ?: return error("That date isn't valid.")
            weekdayCheck?.let { if (date.dayOfWeek != it)
                return error("${longDate(date)} is a ${dayName(date.dayOfWeek)}, not a ${dayName(it)}. Correct the date or the weekday.") }
            // "for 5 nights from 12 Dec": "from" belongs to the date.
            val from = if (spanLength != null) rx("\\bfrom\\s+$").find(remaining.substring(0, ds.single().range.first)) else null
            consume((from?.range?.first ?: ds.single().range.first)..ds.single().range.last, QuickPhraseKind.DATE)
        }
        numeric.firstOrNull()?.let { match ->
            val parts = match.value.split('/', '-', '.')
            if (parts.size == 3 && parts[2].length !in setOf(2, 4)) return error("Use a four-digit year, for example 03/04/2027.")
            // A two-digit year is this century: 3/10/26 is 2026.
            val year = parts.getOrNull(2)?.toIntOrNull()?.let { if (parts[2].length == 2) 2000 + it else it } ?: today.year
            fun candidate(day: Int, month: Int): LocalDate? =
                (if (parts.size == 2) nextDayMonth(today, month, day) else runCatching { LocalDate.of(year, month, day) }.getOrNull())
                    ?.takeIf { it.year in 1..9999 }
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
        if (ds.isEmpty() && numeric.isEmpty() && !relative && !impliedToday && orDates == null) holidays.find(remaining)?.let { match ->
            // "Boxing Day 2027": that year's, and the year is not a time. "Christmas Day 1100" is a 24-hour time instead.
            val year = rx("^\\s*,?\\s*(\\d{4})\\b").find(remaining.substring(match.range.last + 1))?.takeIf { it.groupValues[1].toInt() in today.year - 1..2100 }
            date = nextHoliday(match.value.lowercase(Locale.ROOT), year?.let { LocalDate.of(it.groupValues[1].toInt(), 1, 1) } ?: today)
            year?.groups?.get(1)?.range?.let { r -> consume(r.first + match.range.last + 1..r.last + match.range.last + 1, QuickPhraseKind.DATE) }
            holiday = true
            // Recognised, so it can be kept literally, but not consumed: the name stays in the title.
            phrases += QuickEntryPhrase(match.range.first, match.range.last + 1, QuickPhraseKind.DATE)
        }
        // A date before today typed on purpose: saved as it is, for logging what happened. A repeat can't start there.
        // "this Monday" on a Wednesday is this week's, already gone: past as well.
        val pastSaid = impliedYesterday || orPast || ds.any { pastDateWords.matches(it.value.lowercase(Locale.ROOT).replace(re("\\s+"), " ")) } ||
            startFromPast || date < today && ds.any { rx("^(?:(?:on|by|before|due(?:\\s+(?:on|by))?)\\s+)?(?:this\\s+(?:$weekdays)|$weekThis)$").matches(it.value) }
        if (pastSaid && repeat != RepeatRule.NONE) return error("A repeat can't start in the past. Start it today or later, or remove the repeat.")
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
        repeatUntilText?.let { raw ->
            val value = raw.lowercase(Locale.ROOT).replace(re("\\s+"), " ")
            val end = (when {
                rx("^(?:$holidayNames)$").matches(value) -> nextHoliday(value, date)
                re("\\d{1,2}[/.-]\\d{1,2}(?:[/.-]\\d{2,4})?").matches(value) -> {
                    val parts = value.split('/', '.', '-').map { it.toInt() }
                    val year = parts.getOrNull(2)?.let { if (it < 100) 2000 + it else it }
                    fun candidate(day: Int, month: Int) = runCatching { LocalDate.of(year ?: date.year, month, day)
                        .let { if (year == null && it < date) it.plusYears(1) else it } }.getOrNull()
                    val readings = listOfNotNull(candidate(parts[0], parts[1])?.let { true to it }, candidate(parts[1], parts[0])?.let { false to it })
                        .distinctBy { it.second }
                    (readings.singleOrNull() ?: readings.firstOrNull { it.first == dayFirst }
                        ?: if (readings.isNotEmpty()) return error("Write the end date with the month's name, for example until 3 October.") else null)?.second
                }
                else -> parseDate(value, today)
            }) ?: return error("That end date isn't valid.")
            if (end < date) return error("The repeat ends before it starts. Choose a later end date.")
            repeatCount = repeat.dates(date, 365).count { it <= end }
            if (repeatCount !in 2..365) return error("Choose an end date that gives between 2 and 365 occurrences.")
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
                // The next of several times: "0800 and 2000".
                rx("(?:\\b$hhmm(?:\\s*$hoursSuffix)?|\\b\\d{1,2}(?::\\d{2})?\\s*$meridiem|\\b\\d{1,2}:\\d{2})\\s*(?:,\\s*(?:and\\s+)?|&\\s*|and\\s+)$").containsMatchIn(before) ||
                rx("^\\s*(?:[-–—]|to\\b|until\\b|till\\b|[,—–-]?\\s*actually\\s+(?:at\\s+)?)\\s*$hhmm\\b").containsMatchIn(after) ||
                phrases.any { it.kind == QuickPhraseKind.DATE && it.end <= match.range.first && text.substring(it.end, match.range.first).matches(re("[\\s,]*")) })
            if (!timeLike) mask(match.range, '\uE000')
        }
        // 3p, 3:30p and 15.30 read as times only after at/from/until/by or right beside a date: "Call 3p tomorrow",
        // "Meeting 15.30 Friday". Otherwise they stay in the title: "Meeting room 6a", "Version 2.10", "$12.50".
        shortClocks.findAll(remaining).toList().forEach { match ->
            val before = remaining.substring(0, match.range.first)
            val after = remaining.substring(match.range.last + 1)
            val besideDate = phrases.any { it.kind == QuickPhraseKind.DATE &&
                (it.end <= match.range.first && text.substring(it.end, match.range.first).matches(re("[\\s,]*")) ||
                    it.start > match.range.last && text.substring(match.range.last + 1, it.start).matches(re("[\\s,]*"))) }
            val money = rx("[£€¥$]\\s*$").containsMatchIn(before)
            // "for 1.25 hours": a length, read as one below.
            if (!money && rx("^\\s*(?:$hours|$minutes)(?![a-z])").containsMatchIn(after)) return@forEach
            val timeLike = !money && besideDate || !money && rx("(?:\\b(?:at|from|until|till?|to|by|actually)\\s+|@\\s*|$approx|[-–—]\\s*)$").containsMatchIn(before) ||
                rx("^\\s*(?:-?ish\\b|[-–—]|to\\b|until\\b|till?\\b)").containsMatchIn(after)
            if (!timeLike) mask(match.range, '\uE000')
        }
        val lengths = durations.findAll(remaining).toList()
        // Without "for", only a length right after a clock time or time range: "1pm 1.5 hours", "3pm 45 mins".
        val clockEnds = (times.findAll(remaining) + ranges.findAll(remaining)).map { it.range.last }.toSet()
        // …or right before one: "Flight 1.5h 7am".
        val clockStarts = (times.findAll(remaining) + ranges.findAll(remaining)).map { it.range.first }.toSet()
        val bareLengths = bareDurations.findAll(remaining).filter { match ->
            val before = remaining.substring(0, match.range.first).trimEnd().trimEnd(',').trimEnd()
            val after = remaining.substring(match.range.last + 1)
            val next = match.range.last + 1 + after.length - after.trimStart().trimStart(',').trimStart().length
            (before.isNotEmpty() && before.length - 1 in clockEnds || after.isNotBlank() && next in clockStarts) &&
                lengths.none { it.range.first <= match.range.last && match.range.first <= it.range.last }
        }.toList()
        val leading = if (clockEnds.isEmpty()) null else leadingLength.find(remaining)
            ?.takeIf { l -> (lengths + bareLengths).none { it.range.first <= l.range.last && l.range.first <= it.range.last } }
        if (lengths.size + bareLengths.size + (if (leading != null) 1 else 0) > 1) return error("Use one duration, such as for 1h 30m.")
        var duration = (lengths + bareLengths).firstOrNull()?.let {
            val value = it.value.lowercase(Locale.ROOT).replace(re("\\s+"), " ")
            val total = spanMinutes(if (value.startsWith("for ")) value else "for $value")
            if (!total.isFinite() || total !in 1.0..1440.0 || total % 1 != 0.0 || value.contains('-'))
                return error("Use a duration from 1 minute to 24 hours, in whole minutes.")
            consume(it.range, QuickPhraseKind.DURATION)
            total.toInt()
        } ?: leading?.let {
            val span = it.groups[1]!!
            val total = spanMinutes("for " + span.value.lowercase(Locale.ROOT).replace(re("\\s+"), " "))
            if (!total.isFinite() || total !in 1.0..1440.0 || total % 1 != 0.0 || span.value.contains('-'))
                return error("Use a duration from 1 minute to 24 hours, in whole minutes.")
            consume(span.range, QuickPhraseKind.DURATION)
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
            val endpoints = rangeEnds(range).toList().map(::normaliseClock)
            val hasMeridiem = endpoints.map { rx("(?:am|pm)$").containsMatchIn(it) }
            val twelveHour = re("([1-9]|1[0-2])(?::([0-5]\\d))?")
            // "9-5", "7:30-9:30", "7.30-9.30": either half of the day, as for a single 7:30. A leading zero, an hour of 0
            // or above 12, four digits or "9h30" read one way only: "08:00-09:30", "9:00-17:30", "1000-1200".
            if (hasMeridiem.none { it } && rangeEnds(range).toList().all { re("([1-9]|1[0-2])(?:[:.][0-5]\\d)?").matches(it.trim()) }) {
                // "2 till 4", "12 till 1", "4:30-6": morning or afternoon is asked, as for a single time. Both readings last
                // as long: the end is the first one after the start, within 12 hours.
                fun at(value: String, pm: Boolean) = twelveHour.matchEntire(value)!!.destructured.let { (h, m) ->
                    LocalTime.of(h.toInt() % 12 + if (pm) 12 else 0, m.ifEmpty { "0" }.toInt()) }
                val starts = listOf(at(endpoints[0], false), at(endpoints[0], true))
                // "9-5", "10 till 2", "Brunch 11-1": a start from 6 to 11 and an end from 2 to 6 (1 after a start of 10 or
                // 11) is the working day, morning to afternoon. Others still ask: "Party 8-1" may run past midnight, "Match
                // 2-1" is a score, "Night shift 10-6" is at night.
                val (startHour, endHour) = endpoints.map { twelveHour.matchEntire(it)!!.groupValues[1].toInt() }
                // With a part of the day ("tonight 10-2") that decides am/pm instead.
                if (startHour in 6..11 && (endHour in 2..6 || endHour == 1 && startHour >= 10) && timePrompt == null && !rx("\\bnight").containsMatchIn(text)) {
                    val length = at(endpoints[1], true).toSecondOfDay() / 60 - starts[0].toSecondOfDay() / 60
                    if (duration != null && duration != length) return error("The time range and duration disagree. Correct one or remove it.")
                    time = starts[0]; duration = length
                    consume(range.range, QuickPhraseKind.TIME)
                } else {
                val length = listOf(false, true).map { pm ->
                    Math.floorMod(at(endpoints[1], pm).toSecondOfDay() / 60 - starts[0].toSecondOfDay() / 60, 1440)
                }.filter { it in 1..719 }.minOrNull() ?: return error("The start and end times are the same. Use a duration if you mean 24 hours.")
                if (duration != null && duration != length) return error("The time range and duration disagree. Correct one or remove it.")
                ambiguous = true; timeChoices = starts; duration = length
                consume(range.range, QuickPhraseKind.TIME)
                }
            } else {
            // A trailing suffix can cover an increasing range within the same half-day: 3–4pm.
            // Never infer across noon/midnight or reinterpret an explicit 24-hour start.
            var start = readTime(endpoints[0])
            val end = readTime(endpoints[1])
            if (!hasMeridiem[0] && hasMeridiem[1] && endpoints[0].matches(rx("[1-9]\\d?(?::\\d{2})?|[1-9][0-5]\\d"))) {
                val candidate = readTime(endpoints[0] + endpoints[1].takeLast(2))
                if (candidate == null || end == null || !candidate.isBefore(end))
                    return error("Give am/pm on both range times, for example 9am–5pm.")
                start = candidate
            } else if (hasMeridiem[0] != hasMeridiem[1] && endpoints.any { ':' in it && !rx("(?:am|pm)$").containsMatchIn(it) })
                return error("Use am/pm on both times, or use 24-hour times on both, for example 14:00–15:30.")
            if (start == null || end == null) return error("Give both range times explicitly, for example 2pm–3:30pm or 14:00–15:30.")
            var length = Math.floorMod(end.toSecondOfDay() / 60 - start.toSecondOfDay() / 60, 1440)
            // "12:30-1:30", "11:30-1:30": without am/pm, the end is the first one after the start (not 13 hours on).
            if (hasMeridiem.none { it } && endpoints.all { twelveHour.matches(it) } && length > 720) length -= 720
            if (length == 0) return error("The start and end times are the same. Use a duration if you mean 24 hours.")
            if (duration != null && duration != length) return error("The time range and duration disagree. Correct one or remove it.")
            time = start; duration = length
            consume(range.range, QuickPhraseKind.TIME)
            }
        }
        // "EOD", "by close of business": 5pm, today unless a date is given.
        var endOfDaySaid = false
        endOfDay.findAll(remaining).toList().takeIf { it.isNotEmpty() }?.let { found ->
            if (found.size > 1 || rs.isNotEmpty() || times.containsMatchIn(remaining) || timePrompt != null || relative)
                return error("Use one time: end of day or a clock time.")
            time = LocalTime.of(17, 0); endOfDaySaid = true
            consume(found.single().range, QuickPhraseKind.TIME)
        }
        // "Brekkie Sunday 9", "Gym every Monday 6": an hour right after the date or a weekday repeat, when nothing else follows
        // but schedule words. Not after "daily": "Pills daily 2" may be a count.
        val bareHour = if (rs.isNotEmpty() || timePrompt != null || endOfDaySaid || times.containsMatchIn(remaining)) null
            else rx("(?<![\\w.:/£€¥$#])(\\d{1,2})(?![\\w:./-])").findAll(remaining).firstOrNull { m ->
                m.value.toInt() in 1..12 &&
                    phrases.any { (it.kind == QuickPhraseKind.DATE || it.kind == QuickPhraseKind.REPEAT && rx("(?:$weekdays|$pluralWeekdays)$").containsMatchIn(text.substring(it.start, it.end))) && it.end <= m.range.first && text.substring(it.end, m.range.first).matches(re("[\\s,]*")) } &&
                    remaining.substring(m.range.last + 1).let { after -> after.isBlank() ||
                        nextWord.find(after)?.groupValues?.get(1)?.lowercase(Locale.ROOT)?.let { it in scheduleVocabulary } == true }
            }
        var ts = times.findAll(remaining).toList() + listOfNotNull(bareHour)
        fun clockText(value: String) = spokenToClock(value.lowercase(Locale.ROOT).replace(re("\\s+"), " ")
            .replace(re("^(?:(?:at|by|before|around|about|approx(?:imately)?|roughly|circa)\\s+|[@~]\\s*)+"), "")
            .replace(re("\\s*-?ish$|\\s+sharp$"), "").trim()).let { r -> if (re("\\d{1,2}\\.\\d{2}").matches(r)) r.replace('.', ':') else r }
        // "8am and 8pm", "8am, 2pm and 8pm": one event at each time. Each time must be clear on its own.
        var extraTimes = emptyList<LocalTime>()
        var nextDayTimes = 0
        if (ts.size in 2..3 && rs.isEmpty() && bareHour == null && timePrompt == null &&
            ts.zipWithNext().all { (a, b) -> timeJoin.matches(remaining.substring(a.range.last + 1, b.range.first)) }) {
            val read = ts.map { m -> clockText(m.value).takeUnless { re("([1-9]|1[0-2])(?::[0-5]\\d)?").matches(it) }?.let(::readTime) }
            if (read.any { it == null }) {
                phrases += ts.map { QuickEntryPhrase(it.range.first, it.range.last + 1, QuickPhraseKind.TIME) }
                return error("Add am or pm to each time, for example 8am and 8pm.")
            }
            val written = read.filterNotNull()
            val sorted = written.distinct().sorted()
            if (sorted.size != ts.size) return error("Use different times, for example 8am and 8pm.")
            // "8pm and 2am": early hours written after a later time are after midnight, the next day. Otherwise in order.
            val wrap = (1 until written.size).firstOrNull { written[it] < written[it - 1] }
            if (wrap != null && written.drop(wrap).let { after -> after == after.sorted() && after.all { it < LocalTime.of(6, 0) } && after.last() < written.first() } &&
                written.take(wrap) == written.take(wrap).sorted()) {
                time = written.first(); extraTimes = written.drop(1); nextDayTimes = written.size - wrap
            } else { time = sorted.first(); extraTimes = sorted.drop(1) }
            consume(ts.first().range.first..ts.last().range.last, QuickPhraseKind.TIME)
            ts = emptyList()
        }
        if (perDay != null && extraTimes.size + 1 != perDay.first) {
            return error("Give ${if (perDay.first == 2) "both" else "all three"} times for ‘${perDay.second}’, for example ${perDay.second} at ${if (perDay.first == 2) "8am and 8pm" else "8am, 1pm and 6pm"}.")
        }
        if (ts.size > 1 || ts.isNotEmpty() && rs.isNotEmpty()) {
            phrases += ts.map { QuickEntryPhrase(it.range.first, it.range.last + 1, QuickPhraseKind.TIME) }
            return error("Use one start time or one time range.")
        }
        // "all afternoon": its block of time, unless another time is given too.
        periodBlock?.let { (said, block) ->
            if (time != null || ambiguous || ts.isNotEmpty() || rs.isNotEmpty() || endOfDaySaid || relative || allDayMatches.isNotEmpty())
                return error("Use one time: ‘$said’ or a clock time.")
            if (duration != null) return error("Use ‘$said’ or a duration, not both.")
            time = block.first; duration = block.second
        }
        var midnightSaid = false
        ts.firstOrNull()?.let {
            val raw = clockText(it.value)
            midnightSaid = normaliseClock(raw) == "midnight"
            // 7:30 could be morning or evening; 07:30 and 19:30 are unambiguous.
            val twelveHour = re("([1-9]|1[0-2]):([0-5]\\d)").matchEntire(raw)
            time = if (twelveHour != null) null else readTime(raw)
            if (twelveHour != null) {
                ambiguous = true
                val (hour, minute) = twelveHour.destructured.let { (h, m) -> h.toInt() % 12 to m.toInt() }
                timeChoices = listOf(LocalTime.of(hour, minute), LocalTime.of(hour + 12, minute))
            } else if (time == null) {
                if (raw.matches(re("\\d{1,2}")) && raw.toInt() in 0..23) {
                    val hour = raw.toInt()
                    // "at 13", "at 0": a 24-hour hour, one reading only.
                    if (hour in 1..12) { ambiguous = true; timeChoices = listOf(LocalTime.of(hour % 12, 0), LocalTime.of(hour % 12 + 12, 0)) }
                    else time = LocalTime.of(hour, 0)
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
            val night = period == "night" || period == "tonight"
            time = fitting.singleOrNull { it in window }
                // "tomorrow night 1am": the early hours after that night; "midnight tonight": the end of the day.
                ?: fitting.singleOrNull { night && it < LocalTime.of(5, 0) }?.let { early ->
                    if (early == LocalTime.MIDNIGHT) LocalTime.of(23, 59)
                    else { date = date.plusDays(1); dateChoices = dateChoices.map { it.plusDays(1) }; early }
                }
                ?: return error("${fitting.joinToString(" or ")} isn't in the $period. Correct the time, or remove ‘$period’.")
            timeChoices = emptyList(); ambiguous = false; timePrompt = null
        }
        // "by midnight Friday", "Friday midnight": the end of that day, like "midnight tonight", not the night before it.
        // 00:00 of the next day, so a deadline or a start then is exact. "midnight" with no date stays tonight's 00:00.
        if (midnightSaid && time == LocalTime.MIDNIGHT && (ds.isNotEmpty() || numeric.isNotEmpty() || orDates != null)) {
            date = date.plusDays(1); dateChoices = dateChoices.map { it.plusDays(1) }
        }
        if (relative && (timePrompt != null || ts.isNotEmpty() || rs.isNotEmpty() || extraTimes.isNotEmpty()))
            return error("Use one time: ‘in …’ or a clock time.")
        if (relative) time = relativeAt?.toLocalTime() ?: return error(STALE_RELATIVE)
        if (allDayMatches.isNotEmpty() && (time != null || ambiguous || duration != null))
            return error("Use all day or a time, not both.")
        // "3pm AEST": converted to the phone's time zone.
        var zoneMovedDate = false
        timeZones.find(remaining)?.let { zm ->
            val lastTime = phrases.filter { it.kind == QuickPhraseKind.TIME && it.end <= zm.range.first }.maxOfOrNull { it.end }
            if (lastTime == null || text.substring(lastTime, zm.range.first).isNotBlank()) return@let
            val said = zm.value.replace(re("\\s+"), " ")
            val name = zoneOffsets.keys.firstOrNull { it.equals(said, ignoreCase = true) } ?: said
            val from: ZoneId = zoneOffsets[name] ?: placeZones.getValue(said.lowercase(Locale.ROOT).removeSuffix(" time"))
            val at = time ?: return error("Add am or pm to the time to use $name, for example 3pm $name.")
            fun converted(t: LocalTime, days: Long = 0) = LocalDateTime.of(date.plusDays(days), t).atZone(from).withZoneSameInstant(zone).toLocalDateTime()
            fun extraDays(i: Int) = if (i >= extraTimes.size - nextDayTimes) 1L else 0L
            val local = converted(at)
            if (local.toLocalDate() != date && (repeat != RepeatRule.NONE || dateChoices.isNotEmpty() || rangeEnd != null || (spanLength ?: 0) > 1))
                return error("In your time zone that is ${longDate(local.toLocalDate())}. Type the date and time in your own time zone.")
            val others = extraTimes.mapIndexed { i, t -> converted(t, extraDays(i)) }
            if (others.withIndex().any { (i, it) -> it.toLocalDate() != local.toLocalDate().plusDays(extraDays(i)) })
                return error("In your time zone these times fall on different days. Type them in your own time zone.")
            zoneMovedDate = local.toLocalDate() != date
            date = local.toLocalDate(); time = local.toLocalTime(); extraTimes = others.map { it.toLocalTime() }
            consume(zm.range, QuickPhraseKind.TIME)
        }
        reminderAt?.let { at ->
            if (extraTimes.isNotEmpty()) return error("With more than one time, use remind me … before, for example remind me 10 min before.")
            val start = time ?: return error("Add the event's time to be reminded at ${at}, or use remind me … before.")
            var before = (start.toSecondOfDay() - at.toSecondOfDay()) / 60
            // A reminder time later in the day than the event means the day before.
            if (reminderDaysBack > 0) before += 1440 * reminderDaysBack else if (before < 0) before += 1440
            reminderMinutes = before
        }
        schedulingWords.find(remaining)?.let { match ->
            val end = remaining.indexOf(',', match.range.first).takeIf { it >= 0 } ?: remaining.length
            phrases += QuickEntryPhrase(match.range.first, end, QuickPhraseKind.UNSUPPORTED)
            return error("Finish the reminder or repeat phrase, or open More options → Adjust recognised text to keep those words in the title.")
        }
        // "Book table for Saturday", "Move dentist to Friday", "Party from 7pm": a word left right before the date or time
        // it introduces goes with it, out of the title. Before a time only for and from: "Work until 5pm" gives no start.
        rx("(?<![-\\w])(for|from|to|until|till)\\s*$").find(remaining)?.let { match ->
            val word = match.groups[1]!!.range
            val next = word.last + 1 + text.substring(word.last + 1).let { it.length - it.trimStart().length }
            val kinds = if (match.groupValues[1].lowercase(Locale.ROOT) in setOf("for", "from")) setOf(QuickPhraseKind.DATE, QuickPhraseKind.TIME) else setOf(QuickPhraseKind.DATE)
            if (phrases.any { it.start == next && it.kind in kinds } && consumed.any { next in it })
                consume(word, phrases.first { it.start == next && it.kind in kinds }.kind)
        }
        // Scheduling-shaped fragments must not silently turn into part of a saved title.
        if (duration != null && rx("\\band(?:\\s+(?:a|half|\\d+))?\\s*$").containsMatchIn(remaining))
            return error("Finish the duration, for example for 1 hour and 30 minutes.")
        if (rx("\\b(?:in\\s+(?:-?\\d+|$countWords)\\s*(?:days?|weeks?|months?|years?|fortnights?|hours?|hrs?|minutes?|mins?|seconds?|secs?|h|m)|for\\s+-?(?:[\\d.,]*\\d|$countWords|half)(?:[\\s.,]+(?:and|half|quarter|[\\d.,]*\\d|$countWords))*\\s*(?:$hours|$minutes)|\\d{1,2}[:h]\\d*|\\d{1,2}\\.\\d+\\s*$meridiem)\\b").containsMatchIn(remaining) ||
            unfinished.findAll(remaining).any { m -> !((m.value.trim() in setOf("in", "at", "on") || partySize.matches(m.value) ||
                    largePartySize.matches(m.value) && bookingWord.containsMatchIn(remaining.substring(0, m.range.first))) &&
                text.substring(m.range.first + m.value.trimEnd().length).isNotBlank()) } ||
            rx("(?:[-–—]\\s*$|\\b(?:to|until)\\s+\\d)").containsMatchIn(remaining))
            return error("Finish the date, time or duration, or put literal title text in quotes.")
        // Phrases may overlap ("Saturday or all day Sunday" spans "all day"), so each run of consumed text becomes one space.
        val gone = BooleanArray(text.length).also { flags -> consumed.forEach { r -> r.forEach { if (it in flags.indices) flags[it] = true } } }
        var title = text.indices.filter { !gone[it] || it == 0 || !gone[it - 1] }.joinToString("") { if (gone[it]) " " else text[it].toString() }
        // "Book 2night club", "Watch tonight show": a part of the day before a word that names a show or club may be part of
        // the title. A task then asks rather than take it silently as the due day; see quickProblem. Other words after it
        // ("Pay bills tonight online", "Gym tomorrow morning early") leave it the due day.
        val periodInTitle = periodEnd?.takeIf { end ->
            val next = end + text.substring(end).let { it.length - it.trimStart().length }
            val word = re("^\\p{L}+(?![\\p{L}'’])").find(text.substring(next))?.value?.lowercase(Locale.ROOT)
            next < text.length && !gone[next] && word in periodNameWords
        }?.let { periodSaid }
        title = title.replace("\"", "").replace(re("\\(\\s*\\)|\\[\\s*]"), " ")
            // Commas left alone by removed phrases: "Dentist 3 October, 2pm".
            .replace(re("(?<=^|\\s)[,;]+(?=\\s|$)"), " ").replace(re("[\\s,;]+$"), "")
            .trim().replace(re("\\s+"), " ")
        // A title that followed a leading length or date keeps the capital the entry started with: "Friday drinks" → "Drinks".
        val startedCapital = text.trimStart().firstOrNull()?.isUpperCase() == true && consumed.any { it.first <= text.indexOfFirst { c -> !c.isWhitespace() } }
        if (taskHint || leading != null || startedCapital) title = title.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val dateSpecified = impliedToday || ds.isNotEmpty() || numeric.isNotEmpty() || repeat != RepeatRule.NONE || relative || holiday || rangeStart != null || startFrom != null ||
            orDates != null || endOfDaySaid || zoneMovedDate
        val endDate = rangeEnd ?: spanLength?.takeIf { it > 1 }?.let { date.plusDays(it - 1) }
        // "Remind me to …" with a when: remind at the time, or at the usual 09:00 on the day.
        val reminderImplied = taskHint && !taskPrefixSaid && !noReminderSaid && reminderMinutes == null && (dateSpecified || time != null || ambiguous)
        val clarification = when {
            dateChoices.isNotEmpty() -> "Which date did you mean?"
            ambiguous -> timePrompt ?: "Morning or afternoon? Choose a time below, or type am or pm."
            else -> null
        }
        return QuickEntrySuggestion(title, date, time,
            if (title.isBlank()) "Add a name." else clarification,
            dateSpecified, duration, ambiguous, location,
            phrases.sortedBy { it.start }, dateChoices, timeChoices, clarification != null && title.isNotBlank(),
            reminderMinutes ?: if (reminderImplied) 0 else null, repeat, repeatCount, countMatches.isNotEmpty() || repeatPeriod != null || repeatUntilText != null, timePrompt,
            taskHint, reminderImplied, endDate, extraTimes, pastSaid, nextDayTimes, periodInTitle)
    }

    /** Whether [words] say only when: "tomorrow", "in 2 hours", "on Friday at 3pm", "next week" (refused later). */
    private fun readsAsWhen(words: String): Boolean =
        sequenceOf(dates, numericDate, ranges, times, relativeTimes, unsupported, wordDateMatches, holidays, endOfDay, nowWords, allDay,
            everyPeriod, monthlyWeekdays, monthDayRepeat, weekdayLists, repeats)
            .fold(words) { left, phrase -> phrase.replace(left, " ") }
            .let { rx("[\\s,]*(?:\\b(?:at|on|in|by|from|and|then|the)\\b[\\s,]*)*").matches(it) }

    private fun spanMinutes(value: String): Double = when {
        rx("^(?:for|in) (?:a )?half\\b").containsMatchIn(value) -> 30.0
        "quarter" in value -> 15.0
        // "2 and a half hours".
        else -> re("^(?:for|in) (\\S+) and a half (?:hours?|hrs?|h)$").find(value)?.let { readAmount(it.groupValues[1]) * 60 + 30 }
            ?: (durationParts.findAll(value).sumOf { part ->
                readAmount(part.groupValues[1]) * if (part.groupValues[2].startsWith("h")) 60 else 1
            } + (if (value.endsWith("and a half")) 30.0 else 0.0) +
                // "1 hour 30", "1h30": the minutes without their unit.
                (re("(?:hours?|hrs?|h) ?(?:and )?([0-5]\\d)$").find(value)?.groupValues?.get(1)?.toDouble() ?: 0.0))
    }.let { minutes -> Math.round(minutes).toDouble().takeIf { Math.abs(it - minutes) < 1e-6 } ?: minutes } // 1.45 hours is 87

    /** Minutes in "10 minutes", "1 hour and 30 minutes", "2 days", "half a day", "the day" (before). */
    private fun reminderSpanMinutes(value: String): Double {
        if (value == "the day") return 1440.0
        if (value == "the week") return 10080.0
        re("^(.+?) ?(days?|weeks?)$").matchEntire(value)?.let { match ->
            val amountText = match.groupValues[1]
            val number = if (amountText.startsWith("half")) 0.5 else readAmount(amountText)
            return number * if (match.groupValues[2].startsWith("d")) 1440 else 10080
        }
        return spanMinutes("for $value")
    }

    private fun nextHoliday(name: String, today: LocalDate): LocalDate {
        // Easter and Mother's Day (the second Sunday of May) move each year.
        fun moving(year: Int): LocalDate? = when {
            "easter" in name -> easterSunday(year).plusDays(if ("monday" in name) 1 else 0)
            "good" in name -> easterSunday(year).minusDays(2)
            "mother" in name -> LocalDate.of(year, 5, 1).with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.SUNDAY))
            // Father's Day in Australia: the first Sunday of September.
            "father" in name -> LocalDate.of(year, 9, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.SUNDAY))
            else -> null
        }
        moving(today.year)?.let { return if (it < today) moving(today.year + 1)!! else it }
        val (month, day) = when {
            name == "nye" -> 12 to 31
            "anzac" in name -> 4 to 25
            "australia" in name -> 1 to 26
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

    /** Western Easter Sunday (the anonymous Gregorian computus). */
    private fun easterSunday(year: Int): LocalDate {
        val a = year % 19; val b = year / 100; val c = year % 100
        val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30; val i = c / 4; val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        return LocalDate.of(year, month, (h + l - 7 * m + 114) % 31 + 1)
    }

    private fun roundUpToFive(time: LocalDateTime): LocalDateTime {
        // A part-minute counts as the next minute, so 15:05:10 becomes 15:10 rather than 15:05 (before the time asked).
        val whole = time.truncatedTo(ChronoUnit.MINUTES)
        val minute = if (whole < time) whole.plusMinutes(1) else whole
        return minute.plusMinutes(((5 - minute.minute % 5) % 5).toLong())
    }

    /** A weekday with next, this, last or nothing, as quick entry and search read it: next Friday is next week's (weeks run
     *  Monday to Sunday), this Friday this week's (it may have passed), last Friday the latest before today, Friday the
     *  coming one (today included). */
    fun weekdayDate(day: DayOfWeek, modifier: String, today: LocalDate): LocalDate = when (modifier) {
        "next" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1).with(day)
        "this" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).with(day)
        "last" -> today.with(TemporalAdjusters.previous(day))
        else -> today.with(TemporalAdjusters.nextOrSame(day))
    }

    private fun monthOf(word: String): Month = Month.entries.first { it.name.lowercase(Locale.ROOT).startsWith(word.take(3)) }

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

    /** A day and month without a year: the next on or after [today], so 29 February is the next leap year's. */
    private fun nextDayMonth(today: LocalDate, month: Int, day: Int): LocalDate? =
        (0..8).asSequence().mapNotNull { runCatching { LocalDate.of(today.year + it, month, day) }.getOrNull() }.firstOrNull { it >= today }

    private fun readAmount(value: String): Double {
        if (value == "a" || value == "an") return 1.0
        val words = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")
        return words.indexOf(value).takeIf { it >= 0 }?.let { (it + 1).toDouble() } ?: value.toDoubleOrNull() ?: Double.NaN
    }

    /** half past 3 → 3:30, quarter to 5pm → 16:45, 3 o'clock → 3. Without am/pm the result stays a 12-hour time. */
    private fun spokenToClock(input: String): String {
        // Hour words become numbers: "quarter past seven" → quarter past 7, "nine o'clock" → 9 o'clock.
        val raw = re("(?<=^|past |to )($hourWords)\\b").replace(input) { (hourWords.split('|').indexOf(it.value) + 1).toString() }
        re("^(\\d{1,2}) ?o['’]? ?clock ?(.*)$").matchEntire(raw)?.let { return it.groupValues[1] + it.groupValues[2] }
        val spoken = re("^(half|quarter|five|ten|twenty(?:[- ]five)?|\\d{1,2}) (past|to) (\\d{1,2}) ?(.*)$").matchEntire(raw) ?: return raw
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
        .replace(re("\\s+"), "").removeSuffix("sharp").replace("a.m.", "am").replace("p.m.", "pm")
        .replace("a.m", "am").replace("p.m", "pm").replace('.', ':').replace(re("(?<=\\d)h(?=\\d)"), ":")
        .replace(re("^(\\d{2})(\\d{2})$hoursSuffix?$"), "$1:$2")
        .replace(re("(?<=\\d)([ap])$"), "$1m")
        // "730pm" → 7:30pm; "12 noon" → noon.
        .replace(re("^(1[0-2]|0?[1-9])([0-5]\\d)(?=[ap]m$)"), "$1:$2")
        .replace(re("^12(?=noon$|midday$|mid-day$|midnight$)"), "")

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
            d == "today" || d == "later today" -> today
            rx("^(?:$pastDates)$").matches(d) -> when {
                d.endsWith("before yesterday") -> today.minusDays(2)
                d == "yesterday" -> today.minusDays(1)
                d.endsWith(" ago") -> {
                    val (amount, unit) = d.split(' ')
                    val count = readAmount(amount).toLong()
                    when {
                        unit.startsWith("fortnight") -> today.minusWeeks(count * 2)
                        unit.startsWith("w") -> today.minusWeeks(count)
                        unit.startsWith("month") -> today.minusMonths(count)
                        unit.startsWith("year") -> today.minusYears(count)
                        else -> today.minusDays(count)
                    }
                }
                // "last Friday": the most recent one before today.
                else -> today.with(TemporalAdjusters.previous(weekdayOf(d.substringAfterLast(' '))))
            }
            rx("^(?:$weekThis)$").matches(d) -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).with(weekdayIn(d.replace("this week", "").trim().removePrefix("on ").trim()))
            rx("^(?:$nextMonthOn)$").matches(d) -> YearMonth.from(today).plusMonths(1).atDay(re("\\d{1,2}").find(d)!!.value.toInt())
            d in tomorrowSpellings -> today.plusDays(1)
            rx("^(?:$weekFrom)$").matches(d) -> today.plusDays(if (rx("\\b(?:$tomorrowWords)\\b").containsMatchIn(d)) 8 else 7)
            rx("^(?:$afterNext)$").matches(d) -> today.with(TemporalAdjusters.next(weekdayIn(d.removePrefix("the ")))).plusWeeks(1)
            rx("^(?:$inWeeksOn)$").matches(d) -> {
                val count = readAmount(d.removePrefix("in ").substringBefore(' ')).toLong()
                today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(count).with(weekdayOf(d))
            }
            rx("^(?:$nthWeekdayIn)$").matches(d) -> {
                val parts = d.removePrefix("the ").split(' ')
                val nth = when (parts[0]) { "first", "1st" -> 1; "second", "2nd" -> 2; "third", "3rd" -> 3; "fourth", "4th" -> 4; else -> -1 }
                val day = weekdayOf(parts[1])
                fun inYear(year: Int) = YearMonth.of(year, monthOf(parts[3])).atDay(1)
                    .with(if (nth < 0) TemporalAdjusters.lastInMonth(day) else TemporalAdjusters.dayOfWeekInMonth(nth, day))
                parts.getOrNull(4)?.let { inYear(it.toInt()) } ?: inYear(today.year).let { if (it < today) inYear(today.year + 1) else it }
            }
            rx("^(?:$lastDayOf)$").matches(d) -> {
                val parts = d.removePrefix("the ").split(' ')
                fun inYear(year: Int) = YearMonth.of(year, monthOf(parts[3])).atEndOfMonth()
                parts.getOrNull(4)?.let { inYear(it.toInt()) } ?: inYear(today.year).let { if (it < today) inYear(today.year + 1) else it }
            }
            rx("^(?:$endOfYear)$").matches(d) -> LocalDate.of(today.year, 12, 31)
            rx("^(?:$weekdayOrdinal)$").matches(d) -> {
                // "Friday the 13th": the next month whose 13th is a Friday.
                val day = weekdayOf(d.substringBefore(' '))
                val number = re("\\d{1,2}").find(d)!!.value.toInt()
                generateSequence(YearMonth.from(today)) { it.plusMonths(1) }.take(40)
                    .mapNotNull { m -> if (number <= m.lengthOfMonth()) m.atDay(number) else null }
                    .first { it >= today && it.dayOfWeek == day }
            }
            rx("^(?:$weekAfterNext)$").matches(d) -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(2).with(weekdayIn(d))
            rx("^(?:$nextWeekDay)$").matches(d) -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1).with(weekdayIn(d))
            rx("^(?:$weekOn)$").matches(d) -> today.with(TemporalAdjusters.nextOrSame(weekdayIn(d))).plusWeeks(1)
            rx("^(?:$nextMonthDay)$").matches(d) -> YearMonth.from(today).plusMonths(1).atDay(d.removePrefix("the ").takeWhile { it.isDigit() }.toInt())
            d == "this weekend" -> if (today.dayOfWeek.value >= 6) today else today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
            rx("^(?:$nextWeekend)$").matches(d) -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1).with(DayOfWeek.SATURDAY)
            rx("^(?:$endOfNextMonth)$").matches(d) -> YearMonth.from(today).plusMonths(1).atEndOfMonth()
            // The end of a week is its Friday; once that has passed this week, today.
            rx("^(?:$endOfWeek)$").matches(d) -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .plusWeeks(if (rx("\\bnext\\b").containsMatchIn(d)) 1 else 0).plusDays(4).let { if (it < today) today else it }
            rx("^(?:$earlyNextWeek)$").matches(d) -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1)
            rx("^(?:$startOfNextMonth)$").matches(d) -> YearMonth.from(today).plusMonths(1).atDay(1)
            rx("^(?:$endOfMonth)$").matches(d) -> today.withDayOfMonth(today.lengthOfMonth())
            d == "day after tomorrow" || d == "the day after tomorrow" -> today.plusDays(2)
            // "October first", "the twenty-first": the words become a number.
            rx("\\b(?:$ordinalWords)\\b").containsMatchIn(d) -> parseDate(rx("\\b(?:$ordinalWords)\\b").replace(d) {
                (ordinalWordList.indexOf(it.value.lowercase(Locale.ROOT).replace(re("\\s+"), "-")) + 1).toString() + "th" }, today)!!
            d.startsWith("in ") || d.endsWith(" from today") || d.endsWith(" from now") -> {
                val parts = d.removePrefix("in ").removeSuffix(" from today").removeSuffix(" from now").split(' ')
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
            d.matches(re("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}")) -> d.split('-', '/', '.').map { it.toInt() }.let { (y, m, day) -> LocalDate.of(y, m, day) }
            rx("^(?:the )?\\d{1,2}(?:st|nd|rd|th)$").matches(d) -> nextDayOfMonth(today, d.removePrefix("the ").takeWhile { it.isDigit() }.toInt())
            rx("\\d").containsMatchIn(d) -> {
                // "3 of October", "November the 21st".
                val parts = d.removePrefix("the ").replace(",", "").replace(" of ", " ").replace(" the ", " ").split(' ')
                val dayFirst = parts[0].first().isDigit()
                val monthWord = parts[if (dayFirst) 1 else 0]
                val day = parts[if (dayFirst) 0 else 1].takeWhile { it.isDigit() }.toInt()
                val month = Month.entries.first { it.name.lowercase(Locale.ROOT).startsWith(monthWord.take(3)) }
                parts.getOrNull(2)?.let { LocalDate.of(it.toInt(), month, day) } ?: nextDayMonth(today, month.value, day)!!
            }
            else -> {
                val word = d.substringAfterLast(' ')
                val day = DayOfWeek.entries.first { it.name.lowercase(Locale.ROOT).startsWith(word.take(3)) }
                when {
                    // "this coming Monday": the next one after today.
                    d.startsWith("this coming ") || d.startsWith("coming ") -> today.with(TemporalAdjusters.next(day))
                    d.startsWith("next ") || d.startsWith("nxt ") -> weekdayDate(day, "next", today)
                    d.startsWith("this ") -> weekdayDate(day, "this", today)
                    else -> weekdayDate(day, "", today)
                }
            }
        }
    }.getOrNull()?.takeIf { it.year in 1..9999 }
}
