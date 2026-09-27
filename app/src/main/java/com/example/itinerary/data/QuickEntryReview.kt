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
    return copy(date = dateOverride?.takeIf { it.isNotEmpty() }?.let(LocalDate::parse) ?: date,
        dateSpecified = dateOverride?.isNotEmpty() ?: dateSpecified,
        time = if (timeOverride != null) timeOverride.takeIf { it.isNotEmpty() }?.let(LocalTime::parse) else time,
        dateChoices = dates, ambiguousTime = ambiguous, error = problem)
}

fun QuickEntrySuggestion.quickReminders(): List<Reminder> = reminderMinutes?.let { minutes ->
    val unit = when { minutes > 0 && minutes % 1440 == 0 -> ReminderUnit.DAYS; minutes > 0 && minutes % 60 == 0 -> ReminderUnit.HOURS; else -> ReminderUnit.MINUTES }
    listOf(Reminder(itemId = 0, amount = (minutes / unit.minutes).toInt(), unit = unit))
}.orEmpty()

fun QuickEntrySuggestion.quickTask(): PlannerTask = PlannerTask(
    title = title, dueDate = date.takeIf { dateSpecified }, repeat = repeat.name,
    reminderAt = reminderMinutes?.let { reminderTrigger(date, null, it.toLong()).toInstant().toEpochMilli() },
)

fun QuickEntrySuggestion.isPast(now: ZonedDateTime, task: Boolean): Boolean =
    (!task || dateSpecified) && date < now.toLocalDate() || !task && time != null && date.atTime(time).atZone(now.zone) < now
