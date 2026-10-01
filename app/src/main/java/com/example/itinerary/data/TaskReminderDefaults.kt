package com.example.itinerary.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

// Keep the suggested day and time together when the next hour crosses midnight.
fun taskReminderDefault(dueDate: LocalDate?, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
    val nextHour = now.plusHours(1).withSecond(0).withNano(0)
    return if (dueDate != null && dueDate > nextHour.toLocalDate())
        dueDate.atTime(nextHour.toLocalTime()).atZone(now.zone)
    else nextHour
}

fun taskReminderInstant(date: LocalDate, time: LocalTime, now: ZonedDateTime = ZonedDateTime.now()): Long {
    val first = date.atTime(time).atZone(now.zone)
    // During the repeated daylight-saving hour, prefer the future occurrence if the first passed.
    val later = first.withLaterOffsetAtOverlap()
    val selected = if (!first.isAfter(now) && later.isAfter(now)) later else first
    return selected.toInstant().toEpochMilli()
}

enum class TaskReminderPreset { ON_THE_DAY, DAY_BEFORE, LATER_TODAY, TOMORROW }

/**
 * The quick choices in a task's Reminders section, with when each would ring: on the due date (and the day before) at
 * 09:00, or without one in an hour and tomorrow at 09:00. Only times still ahead are offered; a due date whose 09:00
 * times have both passed falls back to the choices for no due date.
 */
fun taskReminderPresets(dueDate: LocalDate?, now: ZonedDateTime = ZonedDateTime.now()): List<Pair<TaskReminderPreset, Long>> {
    val nine = LocalTime.of(9, 0)
    fun at(date: LocalDate) = taskReminderInstant(date, nine, now)
    val due = if (dueDate == null) emptyList() else listOf(TaskReminderPreset.ON_THE_DAY to at(dueDate), TaskReminderPreset.DAY_BEFORE to at(dueDate.minusDays(1)))
    val ahead = due.filter { it.second > now.toInstant().toEpochMilli() }
    if (ahead.isNotEmpty()) return ahead
    val inAnHour = now.plusHours(1).withSecond(0).withNano(0)
    return listOfNotNull(
        (TaskReminderPreset.LATER_TODAY to inAnHour.toInstant().toEpochMilli()).takeIf { inAnHour.toLocalDate() == now.toLocalDate() },
        TaskReminderPreset.TOMORROW to at(now.toLocalDate().plusDays(1)),
    )
}

/** When [preset] rings if it is chosen [now] (an editor may have been open a while); null once it is no longer offered. */
fun taskReminderPresetAt(preset: TaskReminderPreset, dueDate: LocalDate?, now: ZonedDateTime = ZonedDateTime.now()): Long? =
    taskReminderPresets(dueDate, now).firstOrNull { it.first == preset }?.second
