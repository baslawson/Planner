package com.example.itinerary.data

import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale
import java.util.UUID

/** One editable row. Its base date freezes relative phrases while a draft is unfinished. */
data class QuickInput(
    val text: String = "", val task: Boolean = false,
    val literals: List<IntRange> = emptyList(), val dateOverride: String? = null,
    val timeOverride: String? = null, val countText: String? = null,
    val removeReminder: Boolean = false, val baseDate: LocalDate = LocalDate.now(),
    val durationText: String? = null,
    val ai: QuickAiEntry? = null,
) {
    fun suggestion(): QuickEntrySuggestion {
        val parsed = ai?.suggestion(baseDate) ?: QuickEntry.parse(text, baseDate, literals)
        return parsed.corrected(dateOverride, timeOverride).copy(
            title = if (task && parsed.location.isNotBlank()) "${parsed.title} at ${parsed.location}" else parsed.title,
            location = if (task) "" else parsed.location,
            reminderMinutes = parsed.reminderMinutes.takeUnless { removeReminder },
            durationMinutes = if (durationText == null) parsed.durationMinutes else if (durationText.isEmpty()) null else durationText.toIntOrNull() ?: 0,
            repeatCount = countText?.toIntOrNull() ?: if (countText == null) parsed.repeatCount else 0,
        )
    }
}

fun QuickEntrySuggestion.quickProblem(task: Boolean, now: ZonedDateTime): String? {
    val reminder = reminderMinutes?.let { reminderTrigger(date, if (task) null else time, it.toLong(), now.zone) }
    return when {
        task && (time != null || ambiguousTime || durationMinutes != null) -> "Tasks use due dates. Choose Event for a time or duration."
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

data class QuickRow(
    val id: String = UUID.randomUUID().toString(), val source: String,
    val input: QuickInput, val typeChosen: Boolean = false, val selected: Boolean = true,
    val status: String = "pending",
) {
    fun problem(now: ZonedDateTime): String? = when {
        !typeChosen -> "Choose Task or Event."
        input.text.length > 500 -> "Use at most 500 characters per entry."
        else -> input.suggestion().quickProblem(input.task, now)
    }
    val count: Int get() = if (input.task || input.suggestion().repeat == RepeatRule.NONE) 1 else input.suggestion().repeatCount
}

object QuickBatch {
    private val bullet = Regex("^\\s*(?:[-*•]\\s*|\\d+[.)]\\s+)")
    fun lines(text: String): List<String> = text.lineSequence().filter { it.isNotBlank() }.map { it.replaceFirst(bullet, "").trim() }.toList()
    fun problem(text: String): String? = when {
        text.length > 25_000 -> "Use at most 25,000 characters per list."
        lines(text).isEmpty() -> "Enter one entry per line."
        lines(text).size > 50 -> "Review up to 50 entries at a time."
        else -> null
    }
    fun review(text: String, today: LocalDate, previous: List<QuickRow>): List<QuickRow> {
        require(problem(text) == null)
        val unused = previous.toMutableList()
        return lines(text).map { line ->
            val index = unused.indexOfFirst { it.source == line }
            if (index >= 0) unused.removeAt(index) else {
                val input = QuickInput(text = line, baseDate = today)
                val s = input.suggestion()
                QuickRow(source = line, input = input, typeChosen = s.time != null || s.ambiguousTime || s.durationMinutes != null)
            }
        }
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

fun reconcileQuickRows(rows: List<QuickRow>, items: List<ItineraryItem>, tasks: List<PlannerTask>): List<QuickRow> = rows.map { row ->
    if (row.status != "attempted") row else row.copy(status = if (if (row.input.task) tasks.any { it.id == row.id } else items.any { it.draftToken == row.id }) "saved" else "pending")
}

/** Write intent before the DB operation and receipt after. Retry always uses the same row identity. */
suspend fun saveQuickRows(
    rows: List<QuickRow>, onPersist: (List<QuickRow>) -> Unit,
    onSave: suspend (QuickEntrySuggestion, Boolean, String) -> Unit,
) {
    var current = rows
    for (row in rows.filter { it.selected && it.status != "saved" }) {
        val s = row.input.suggestion()
        require(row.problem(ZonedDateTime.now()) == null)
        current = current.map { if (it.id == row.id) it.copy(status = "attempted") else it }
        onPersist(current)
        onSave(s, row.input.task, row.id)
        current = current.map { if (it.id == row.id) it.copy(status = "saved") else it }
        onPersist(current)
    }
}
