package com.example.itinerary.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** What a task or an event becomes in the other kind: [result] with its [reminders], and in [dropped] what can't come along. */
data class Converted<T>(val result: T, val reminders: List<Reminder> = emptyList(), val dropped: List<String> = emptyList())

/**
 * Make a task into an event and an event into a task (wish list #1). The editor of the other kind opens on [Converted.result];
 * only its Save makes it, and the original then goes to Recently deleted with an Undo that takes both back
 * (Repository.replaceTaskWithEvent / replaceEventsWithTask). Title, notes, checklist, attachments (the same files) and the
 * repeat carry over where the other kind has them; [Converted.dropped] says plainly what doesn't.
 */
object TaskEventConversion {
    /** A bill is its own kind, a completed task is done with, and another calendar's event isn't Planner's to change. */
    fun canMakeEvent(task: PlannerTask) = !task.done
    fun canMakeTask(event: ItineraryItem) = event.category != "Bills"

    fun toEvent(task: PlannerTask, today: LocalDate, zone: ZoneId = ZoneId.systemDefault(), hasTimeBlocks: Boolean = false): Converted<ItineraryItem> {
        val dropped = mutableListOf<String>()
        val date = task.dueDate ?: today.also { dropped += "No due date: it goes on today. Change the day before saving." }
        val repeat = if (TaskRepeat.of(task.repeat) == TaskRepeat.AFTER_COMPLETION) {
            dropped += "The repeat (${TaskRepeat.label(task.repeat, task.repeatDays)}): events can't repeat after completion."
            RepeatRule.NONE.name
        } else task.repeat
        if (task.priority != TaskPriority.NORMAL) dropped += "The ${task.priority.label.lowercase()} priority."
        if (task.prerequisiteIds.isNotEmpty()) dropped += "The tasks it waits for."
        if (hasTimeBlocks) dropped += "Its time blocks stay in the calendar, without their task."
        val reminder = task.reminderAt?.let { at ->
            reminderBefore(date, null, at, zone) ?: null.also { dropped += "The reminder: it is after the event's start (09:00 for an all-day event)." }
        }
        val event = ItineraryItem(tripId = 0, date = date, startTime = null, title = task.title, notes = task.notes,
            checklist = task.checklist, repeatRule = repeat)
        return Converted(event, listOfNotNull(reminder), dropped)
    }

    /**
     * [wholeSeries]: a repeating event becomes one repeating task; otherwise only this occurrence does, and the series
     * keeps its other events. A multi-day event is due on its last day: done by the end.
     */
    fun toTask(event: ItineraryItem, reminders: List<Reminder>, attachments: List<Attachment>, wholeSeries: Boolean,
               zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis(), seriesCount: Int = 1,
               due: LocalDate? = null, idSeed: String? = null): Converted<PlannerTask> {
        val dropped = mutableListOf<String>()
        if (event.startTime != null) dropped += "The time" + (if (event.durationMinutes != null) " and length" else "") + ": tasks have a due day only."
        if (event.endDate != null) dropped += "The days before the last: the task is due on the last day."
        if (event.location.isNotBlank()) dropped += "The place (${event.location})."
        if (event.category != Categories.OTHER) dropped += "The category (${event.category})."
        if (event.bufferBeforeMinutes > 0 || event.bufferAfterMinutes > 0) dropped += "The travel or setup time around it."
        if (event.linkedTaskId != null) dropped += "Its link to the task it was time for."
        if (wholeSeries && seriesCount > 1) dropped += "All $seriesCount events of the series go to Recently deleted, past ones too."
        val sorted = reminders.sortedBy { it.offsetMinutes }
        val first = sorted.firstOrNull()
        if (sorted.size > 1) dropped += "${sorted.size - 1} more reminder${if (sorted.size > 2) "s" else ""}: a task has one."
        if (first?.ringUntilDismissed == true) dropped += "Ringing until stopped: a task's reminder is a notification."
        val repeating = event.repeatRule != RepeatRule.NONE.name
        if (repeating && wholeSeries) dropped += "The series' end: a repeating task goes on until you stop it."
        // A reminder already gone would stop the task saving ("Choose a future reminder"): left out, and said (TE-10).
        val reminderAt = first?.let { reminderTrigger(event.date, event.startTime, it.offsetMinutes, zone).toInstant().toEpochMilli() }
            ?.takeIf { it > now } ?: null.also { if (first != null) dropped += "The reminder: its time has passed." }
        // The same id each time this conversion is read, so a rebuilt window knows the new-task draft as its own (TE-6).
        val id = idSeed?.let { java.util.UUID.nameUUIDFromBytes(it.toByteArray()).toString() } ?: java.util.UUID.randomUUID().toString()
        val task = PlannerTask(id = id, title = event.title, notes = event.notes, dueDate = due ?: event.endDate ?: event.date,
            checklist = event.checklist, attachments = attachments.map { it.copy(id = 0, itemId = 0) },
            repeat = if (repeating && wholeSeries) event.repeatRule else TaskRepeat.NONE.name, reminderAt = reminderAt)
        return Converted(task, dropped = dropped)
    }

    /** A reminder [at] as one before the event's start ([time], or 09:00 all day), in its plainest unit; null when after it. */
    fun reminderBefore(date: LocalDate, time: LocalTime?, at: Long, zone: ZoneId): Reminder? {
        // Counted on the clock, then checked against how the event works its reminders out (days, then minutes), so a
        // clock change in between can't move it by an hour (TE-2).
        val wall = ChronoUnit.MINUTES.between(Instant.ofEpochMilli(at).atZone(zone).toLocalDateTime(), date.atTime(time ?: LocalTime.of(9, 0)))
        val elapsed = ChronoUnit.MINUTES.between(Instant.ofEpochMilli(at).atZone(zone), date.atTime(time ?: LocalTime.of(9, 0)).atZone(zone))
        val minutes = listOf(wall, wall - 60, wall + 60, wall - 30, wall + 30).filter { it >= 0 }
            .firstOrNull { reminderTrigger(date, time, it, zone).toInstant().toEpochMilli() == at } ?: elapsed.takeIf { it >= 0 } ?: return null
        return when {
            minutes % 1440 == 0L -> Reminder(itemId = 0, amount = (minutes / 1440).toInt(), unit = ReminderUnit.DAYS)
            minutes % 60 == 0L -> Reminder(itemId = 0, amount = (minutes / 60).toInt(), unit = ReminderUnit.HOURS)
            else -> Reminder(itemId = 0, amount = minutes.toInt(), unit = ReminderUnit.MINUTES)
        }
    }
}
