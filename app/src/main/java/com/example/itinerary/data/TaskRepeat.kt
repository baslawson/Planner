package com.example.itinerary.data

import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class TaskRepeat(val label: String) {
    NONE("Never"), DAILY("Daily"), WEEKDAYS("Weekdays"), WEEKLY("Weekly"), FORTNIGHTLY("Fortnightly"), MONTHLY("Monthly"), YEARLY("Yearly"),
    EVERY_N_DAYS("Every few days"), EVERY_N_WEEKS("Every few weeks"), EVERY_N_MONTHS("Every few months"), DAYS_OF_WEEK("On chosen weekdays"), MONTHLY_WEEKDAY("Monthly on a weekday"),
    AFTER_COMPLETION("Days after completion");

    /** Kinds whose value is stored with them, as for events: EVERY_N_DAYS:3, DAYS_OF_WEEK:MON,WED. */
    val detailed: Boolean get() = name in RepeatRule.customKinds.map { it.name }

    companion object {
        /** The kind of a stored task repeat, which may carry a value after a colon. */
        fun of(repeat: String): TaskRepeat = valueOf(repeat.substringBefore(':'))
        fun valid(repeat: String): Boolean = runCatching { of(repeat) }.getOrNull()?.let { kind ->
            if (kind.detailed) RepeatRule.parse(repeat) != null else ':' !in repeat
        } ?: false
        /** What the task list shows: "Every 3 days", "Days after completion (5)". */
        fun label(repeat: String, repeatDays: Int): String {
            val kind = of(repeat)
            return when {
                kind.detailed -> RepeatRule.parse(repeat)?.label ?: kind.label
                kind == AFTER_COMPLETION -> "${kind.label} ($repeatDays)"
                else -> kind.label
            }
        }
    }
}

/** Calendar repeats advance from their due date; late completions skip missed occurrences. */
fun PlannerTask.nextOccurrence(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): PlannerTask? {
    val rule = TaskRepeat.of(repeat)
    if (rule == TaskRepeat.NONE) return null
    val base = dueDate ?: today
    val anchor = repeatAnchorDay.takeIf { it > 0 } ?: base.dayOfMonth
    val next = when (rule) {
        TaskRepeat.NONE -> return null
        TaskRepeat.AFTER_COMPLETION -> today.plusDays(repeatDays.toLong())
        TaskRepeat.EVERY_N_DAYS, TaskRepeat.EVERY_N_WEEKS, TaskRepeat.EVERY_N_MONTHS, TaskRepeat.DAYS_OF_WEEK, TaskRepeat.MONTHLY_WEEKDAY ->
            RepeatRule.valueOf(repeat).nextAfter(base, maxOf(base, today))
        TaskRepeat.WEEKDAYS -> generateSequence(maxOf(base, today).plusDays(1)) { it.plusDays(1) }.first { it.dayOfWeek.value <= 5 }
        TaskRepeat.DAILY, TaskRepeat.WEEKLY, TaskRepeat.FORTNIGHTLY -> {
            val step = when (rule) {
                TaskRepeat.FORTNIGHTLY -> 14L
                TaskRepeat.WEEKLY -> 7L
                else -> 1L
            }
            base.plusDays((maxOf(0L, ChronoUnit.DAYS.between(base, today)) / step + 1) * step)
        }
        TaskRepeat.YEARLY -> {
            var year = maxOf(base.year + 1, today.year)
            fun inYear(value: Int): LocalDate {
                val month = java.time.YearMonth.of(value, base.monthValue)
                return month.atDay(minOf(anchor, month.lengthOfMonth()))
            }
            var date = inYear(year)
            if (date <= today) { year++; date = inYear(year) }
            date
        }
        TaskRepeat.MONTHLY -> {
            var month = java.time.YearMonth.from(base).plusMonths(1)
            if (month < java.time.YearMonth.from(today)) month = java.time.YearMonth.from(today)
            var date = month.atDay(minOf(anchor, month.lengthOfMonth()))
            if (date <= today) { month = month.plusMonths(1); date = month.atDay(minOf(anchor, month.lengthOfMonth())) }
            date
        }
    }
    // From the base reminder, never a snooze, so snoozing one occurrence doesn't shift the rest.
    val nextReminder = reminderAt?.let { timestamp ->
        val localReminder = Instant.ofEpochMilli(timestamp).atZone(zone)
        // An undated task has no due-date offset: keep its reminder clock time on the new due date.
        val reminderBase = dueDate ?: localReminder.toLocalDate()
        localReminder.plusDays(ChronoUnit.DAYS.between(reminderBase, next)).toInstant().toEpochMilli()
    }
    return copy(id = UUID.randomUUID().toString(), dueDate = next, done = false, reminderAt = nextReminder, snoozedUntil = null,
        repeatAnchorDay = anchor, nextTaskId = null, checklist = checklist.map { it.copy(done = false) })
}
