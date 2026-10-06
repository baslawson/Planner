package com.example.itinerary.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** Preserve literal selections when typing outside them; editing a selection lets it be parsed again. */
fun moveQuickEntryLiterals(before: String, after: String, spans: List<IntRange>): List<IntRange> {
    val prefix = before.zip(after).takeWhile { it.first == it.second }.size
    var suffix = 0
    while (suffix < before.length - prefix && suffix < after.length - prefix &&
        before[before.lastIndex - suffix] == after[after.lastIndex - suffix]) suffix++
    val oldEnd = before.length - suffix
    val change = after.length - before.length
    return spans.mapNotNull { span ->
        when {
            span.last < prefix -> span
            span.first >= oldEnd -> (span.first + change)..(span.last + change)
            else -> null
        }
    }
}

fun QuickEntrySuggestion.corrected(dateOverride: String?, timeOverride: String?): QuickEntrySuggestion {
    val dates = if (dateOverride != null) emptyList() else dateChoices
    val ambiguous = ambiguousTime && (timeOverride == null || timePrompt != null && timeOverride.isEmpty())
    val problem = if (!clarificationOnly) error else when {
        dates.isNotEmpty() -> "Which date did you mean?"
        ambiguous -> timePrompt ?: "Morning or afternoon? Choose a time below, or type am or pm."
        else -> null
    }
    val newDate = dateOverride?.takeIf { it.isNotEmpty() }?.let(LocalDate::parse) ?: date
    return copy(date = newDate,
        // A multi-day entry keeps its length when its start date is changed.
        endDate = endDate?.let { newDate.plusDays(java.time.temporal.ChronoUnit.DAYS.between(date, it)) },
        dateSpecified = dateOverride?.isNotEmpty() ?: dateSpecified,
        time = if (timeOverride != null) timeOverride.takeIf { it.isNotEmpty() }?.let(LocalTime::parse) else time,
        dateChoices = dates, ambiguousTime = ambiguous, error = problem,
        // T16-3: a month end chosen instead still keeps to month ends; another day is its own.
        repeatAnchorDay = when {
            newDate == date -> repeatAnchorDay
            // H17-Q1: a monthly series moved onto a shorter month's end (31 Oct → 30 Nov) keeps its own day, 31 Dec next.
            repeat.kind in setOf(RepeatRule.Kind.MONTHLY, RepeatRule.Kind.EVERY_N_MONTHS) && newDate.dayOfMonth == newDate.lengthOfMonth() &&
                (repeatAnchorDay.takeIf { it > 0 } ?: date.dayOfMonth) > newDate.dayOfMonth -> repeatAnchorDay.takeIf { it > 0 } ?: date.dayOfMonth
            newDate.dayOfMonth == newDate.lengthOfMonth() -> repeatAnchorDay
            else -> 0
        },
        // A time chosen by hand is the one time: "8am and 8pm" then adds a single event.
        extraTimes = if (timeOverride != null) emptyList() else extraTimes, nextDayTimes = if (timeOverride != null) 0 else nextDayTimes)
}

/** One suggestion per time: "8am and 8pm" adds an event at each; "8pm and 2am" puts 2am on the next day. */
fun QuickEntrySuggestion.eachTime(): List<QuickEntrySuggestion> =
    if (extraTimes.isEmpty()) listOf(this) else (listOf(time) + extraTimes).mapIndexed { i, at ->
        copy(date = if (i > extraTimes.size - nextDayTimes) date.plusDays(1) else date, time = at, extraTimes = emptyList(), nextDayTimes = 0)
    }

/** The save token of an entry's [index]th event: the first keeps the draft's own token. */
fun quickToken(token: String, index: Int) = if (index == 0) token else "$token-$index"

/** Has a clock time, a vague time awaiting one, or a duration: an event rather than a task. */
fun QuickEntrySuggestion.timed(): Boolean = phrases.any { it.kind == QuickPhraseKind.TIME || it.kind == QuickPhraseKind.DURATION }

fun QuickEntrySuggestion.quickReminders(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<Reminder> = reminderMinutes?.let { minutes ->
    val unit = reminderUnit
    val reminder = if (unit != null) Reminder(itemId = 0, amount = (minutes / unit.minutes).toInt(), unit = unit)
        else TaskEventConversion.reminderBefore(date, time,
            quickReminderTrigger(task = false, zone = zone)!!.toInstant().toEpochMilli(), zone)
    listOfNotNull(reminder)
}.orEmpty()

/** The same unit-aware time in the preview, validation and saved task. */
fun QuickEntrySuggestion.quickReminderTrigger(task: Boolean, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): ZonedDateTime? =
    reminderMinutes?.let { minutes ->
        val at = if (task) null else time
        val unit = reminderUnit
        if (reminderClock != null) {
            val days = if (reminderDaysBefore >= 0) reminderDaysBefore else if (reminderClock > (at ?: LocalTime.of(9, 0))) 1 else 0
            date.minusDays(days.toLong()).atTime(reminderClock).atZone(zone)
        } else if (unit == null) reminderTrigger(date, at, minutes.toLong(), zone)
        else reminderTrigger(date, at, Reminder(itemId = 0, amount = (minutes / unit.minutes).toInt(), unit = unit), zone)
    }

fun QuickEntrySuggestion.quickTask(): PlannerTask = PlannerTask(
    title = title, dueDate = date.takeIf { dateSpecified }, repeat = repeat.name,
    repeatAnchorDay = repeatAnchorDay,
    reminderAt = quickReminderTrigger(task = true)?.toInstant()?.toEpochMilli(),
)

fun QuickEntrySuggestion.isPast(now: ZonedDateTime, task: Boolean): Boolean =
    (!task || dateSpecified) && date < now.toLocalDate() || !task && time != null && date.atTime(time).atZone(now.zone) < now
