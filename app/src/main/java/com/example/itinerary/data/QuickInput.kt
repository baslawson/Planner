package com.example.itinerary.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.Locale

/** A Quick entry. Its base date freezes relative phrases while a draft is unfinished. */
data class QuickInput(
    val text: String = "", val task: Boolean = false,
    val literals: List<IntRange> = emptyList(), val dateOverride: String? = null,
    val timeOverride: String? = null, val countText: String? = null,
    val removeReminder: Boolean = false, val baseDate: LocalDate = LocalDate.now(),
    val durationText: String? = null,
    val ai: QuickAiEntry? = null,
    /** Typed in its own box: always kept literally, never read as a date or time. */
    val title: String = "",
) {
    // Quotes would unbalance the parser's own quoting, so they are dropped from a typed title.
    val typedTitle: String get() = title.filterNot { it == '"' || it == '“' || it == '”' }.trim()
    private val offset get() = if (typedTitle.isEmpty()) 0 else typedTitle.length + 1

    /** Title and when as one line; the title is quoted so it stays literal (sent to AI). */
    val entryText: String get() = if (typedTitle.isEmpty()) text else "\"$typedTitle\" $text".trimEnd()
    val length: Int get() = offset + text.length
    val empty: Boolean get() = text.isBlank() && typedTitle.isEmpty()

    /** Parses title and when together, the title kept in title; phrase offsets stay relative to [text]. */
    fun parse(now: LocalDateTime? = null): QuickEntrySuggestion {
        if (typedTitle.isEmpty()) return QuickEntry.parse(text, baseDate, literals, now)
        val shifted = literals.map { it.first + offset..it.last + offset }
        val parsed = QuickEntry.parse("$typedTitle $text", baseDate, listOf(0 until typedTitle.length) + shifted, now)
        return parsed.copy(phrases = parsed.phrases.map { it.copy(start = it.start - offset, end = it.end - offset) })
    }

    fun suggestion(now: ZonedDateTime = ZonedDateTime.now()): QuickEntrySuggestion {
        // "In 30 minutes" counts from now, so only for entries based on today.
        val clock = now.toLocalDateTime().takeIf { baseDate == now.toLocalDate() }
        val parsed = ai?.suggestion(baseDate) ?: parse(clock)
        val corrected = parsed.corrected(dateOverride, timeOverride)
        // A reminder implied by "remind me to" is dropped once it has passed, rather than blocking the entry.
        val impliedPassed = parsed.reminderImplied && parsed.reminderMinutes != null &&
            reminderTrigger(corrected.date, if (task) null else corrected.time, parsed.reminderMinutes.toLong(), now.zone) <= now
        return corrected.copy(
            title = if (task && parsed.location.isNotBlank()) "${parsed.title} at ${parsed.location}" else parsed.title,
            location = if (task) "" else parsed.location,
            reminderMinutes = parsed.reminderMinutes.takeUnless { removeReminder || impliedPassed },
            durationMinutes = if (durationText == null) parsed.durationMinutes else if (durationText.isEmpty()) null else durationText.toIntOrNull() ?: 0,
            repeatCount = countText?.toIntOrNull() ?: if (countText == null) parsed.repeatCount else 0,
        )
    }
}

fun QuickEntrySuggestion.quickProblem(task: Boolean, now: ZonedDateTime): String? {
    val reminder = reminderMinutes?.let { reminderTrigger(date, if (task) null else time, it.toLong(), now.zone) }
    return when {
        task && (time != null || ambiguousTime || durationMinutes != null) -> "Tasks use due dates. Choose Event for a time or duration."
        task && endDate != null -> "Tasks use one due date. Choose Event for several days."
        endDate != null && (time != null || durationMinutes != null) -> "An entry over several days is all day. Remove the time, or add each day separately."
        error != null -> error
        title.isBlank() -> "Add a title."
        durationMinutes != null && durationMinutes !in 1..1440 -> "Choose a duration from 1 to 1440 minutes."
        !task && durationMinutes != null && time == null -> "Choose a start time for this duration."
        task && repeatCountSpecified -> "Task repeats create the next task after completion. Use Event for a fixed occurrence count."
        !task && repeat != RepeatRule.NONE && (repeatCount !in 2..365 || runCatching { repeat.dates(date, repeatCount).any { it.year !in 1..9999 } }.getOrDefault(true)) -> "Choose 2–365 occurrences within the supported calendar dates."
        task && reminder != null && !dateSpecified -> "Choose a due date for this task reminder."
        reminder != null && reminder <= now -> "This reminder time has passed. Change the date or time, or remove the reminder."
        else -> null
    }
}

data class QuickCandidate(val token: String, val suggestion: QuickEntrySuggestion, val task: Boolean, val label: String = suggestion.title)

/** Actual intervals, including buffers and overnight events. Unknown duration is a point, not a guessed hour. */
fun quickConflicts(candidates: List<QuickCandidate>, items: List<ItineraryItem>, tasks: List<PlannerTask>): List<String> {
    fun name(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    val messages = linkedSetOf<String>()
    val events = items.filterNot { it.skipped || it.draftToken in candidates.map { c -> c.token } }.toMutableList()
    val pendingTasks = tasks.filterNot { t -> candidates.any { it.token == t.id } }.toMutableList()
    fun overlap(a: ItineraryItem, b: ItineraryItem): Boolean {
        if (a.startTime == null || b.startTime == null || a.category == "Bills" || b.category == "Bills") return false
        val startA = a.date.atTime(a.startTime).minusMinutes(a.bufferBeforeMinutes.toLong())
        val endA = a.date.atTime(a.startTime).plusMinutes((a.durationMinutes ?: 0).toLong() + a.bufferAfterMinutes)
        val startB = b.date.atTime(b.startTime).minusMinutes(b.bufferBeforeMinutes.toLong())
        val endB = b.date.atTime(b.startTime).plusMinutes((b.durationMinutes ?: 0).toLong() + b.bufferAfterMinutes)
        return when {
            startA == endA && startB == endB -> startA == startB
            startA == endA -> startA >= startB && startA < endB
            startB == endB -> startB >= startA && startB < endA
            else -> startA < endB && startB < endA
        }
    }
    candidates.forEach { candidate ->
        val s = candidate.suggestion
        if (candidate.task) {
            val task = s.quickTask().copy(id = candidate.token)
            pendingTasks.filter { !it.done && name(it.title) == name(task.title) && it.dueDate == task.dueDate }.forEach {
                messages += "${candidate.label}: possible duplicate task ‘${it.title}’."
            }
            pendingTasks += task
        } else {
            val dates = if (s.repeat == RepeatRule.NONE) listOf(s.date) else s.repeat.dates(s.date, s.repeatCount)
            dates.forEach { date ->
                val event = ItineraryItem(tripId = 0, date = date, startTime = s.time, title = s.title, durationMinutes = s.durationMinutes)
                events.forEach { existing ->
                    val duplicate = name(existing.title) == name(event.title) && existing.date == date && existing.startTime == event.startTime
                    if (duplicate || overlap(event, existing)) messages += "${candidate.label} ($date): ${if (duplicate) "possible duplicate of" else "overlaps"} ‘${existing.title}’ (${existing.date}${existing.startTime?.let { " $it" }.orEmpty()})."
                }
                events += event
            }
        }
    }
    return messages.toList()
}
