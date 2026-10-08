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

    fun toEvent(task: PlannerTask, today: LocalDate, zone: ZoneId = ZoneId.systemDefault(), hasTimeBlocks: Boolean = false,
                waiting: Int = 0): Converted<ItineraryItem> {
        val dropped = mutableListOf<String>()
        // RS-5: said as what is missing, as the other lines are.
        val date = task.dueDate ?: today.also { dropped += "The due date (there was none): it goes on today. Change the day before saving." }
        val repeat = if (TaskRepeat.of(task.repeat) == TaskRepeat.AFTER_COMPLETION) {
            dropped += "The repeat (${TaskRepeat.label(task.repeat, task.repeatDays)}): events can't repeat after completion."
            RepeatRule.NONE.name
        } else task.repeat
        // Hunt 23: said as the other way round says it ("The series' end"): the series gets the event editor's 12.
        if (repeat != RepeatRule.NONE.name) dropped += "Repeating with no end: a repeating event has a set number, 12 to start with. Change it before saving."
        if (task.priority != TaskPriority.NORMAL) dropped += "The ${task.priority.label.lowercase()} priority."
        if (task.prerequisiteIds.isNotEmpty()) dropped += "The tasks it waits for."
        if (hasTimeBlocks) dropped += "Its time blocks stay in the calendar, without their task."
        // RS-3: tasks waiting for it stop waiting (a plain delete leaves them blocked), so it is said.
        if (waiting > 0) dropped += if (waiting == 1) "1 task that waits for it stops waiting." else "$waiting tasks that wait for it stop waiting."
        val reminder = task.reminderAt?.let { at ->
            reminderBefore(date, null, at, zone)?.withSound(task.sound)?.also { r ->
                val fires = reminderTrigger(date, null, r, zone).toInstant().toEpochMilli()
                val off = (fires - at) / 60_000
                if (off != 0L) dropped += "The reminder: ${kotlin.math.abs(off)} min ${if (off < 0) "earlier" else "later"}, as the clock change leaves no way to say its time."
            } ?: null.also { dropped += "The reminder: it is after the event's start (09:00 for an all-day event)." }
        }
        val event = ItineraryItem(tripId = 0, date = date, startTime = null, title = task.title, notes = task.notes,
            checklist = task.checklist, repeatRule = repeat)
        return Converted(event, listOfNotNull(reminder), dropped)
    }

    /**
     * [wholeSeries]: a repeating event becomes one repeating task; otherwise only this occurrence does, and the series
     * keeps its other events. A multi-day event is due on its last day: done by the end. [occurrence]: for a whole series,
     * the one the task is due on (its next), whose day and reminder it takes; the tapped [event] otherwise (CV-2).
     */
    fun toTask(event: ItineraryItem, reminders: List<Reminder>, attachments: List<Attachment>, wholeSeries: Boolean,
               zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis(), seriesCount: Int = 1,
               occurrence: ItineraryItem = event, idSeed: String? = null, firstComing: ItineraryItem? = null,
               seriesStart: LocalDate? = null): Converted<PlannerTask> {
        val dropped = mutableListOf<String>()
        if (event.startTime != null) dropped += "The time" + (if (event.durationMinutes != null) " and length" else "") + ": tasks have a due day only."
        if (event.endDate != null) dropped += "The days before the last: the task is due on the last day."
        if (event.location.isNotBlank()) dropped += "The place (${event.location})."
        if (event.category != Categories.OTHER) dropped += "The category (${event.category})."
        if (event.bufferBeforeMinutes > 0 || event.bufferAfterMinutes > 0) dropped += "The travel or setup time around it."
        if (event.linkedTaskId != null) dropped += "Its link to the task it was time for."
        if (wholeSeries && seriesCount > 1) dropped += "All $seriesCount events of the series go to Recently deleted, past ones too."
        // CX-2: a coming one passed over because its reminder has gone is said, not left out quietly.
        if (firstComing != null && firstComing.date != occurrence.date) {
            val day = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")
            dropped += "The one on ${firstComing.date.format(day)}: its reminder has passed, so the task is due from ${occurrence.date.format(day)}."
        }
        val sorted = reminders.sortedByDescending { reminderTrigger(occurrence.date, occurrence.startTime, it, zone).toInstant() }
        val first = sorted.firstOrNull()
        if (sorted.size > 1) dropped += "${sorted.size - 1} more reminder${if (sorted.size > 2) "s" else ""}: a task has one."
        val repeating = event.repeatRule != RepeatRule.NONE.name
        if (repeating && wholeSeries) dropped += "The series' end: a repeating task goes on until you stop it."
        // A reminder already gone would stop the task saving ("Choose a future reminder"): left out, and said (TE-10).
        val reminderAt = first?.let { reminderTrigger(occurrence.date, occurrence.startTime, it, zone).toInstant().toEpochMilli() }
            ?.takeIf { it > now } ?: null.also { if (first != null) dropped += "The reminder: its time has passed." }
        // The same id each time this conversion is read, so a rebuilt window knows the new-task draft as its own (TE-6).
        val id = idSeed?.let { java.util.UUID.nameUUIDFromBytes(it.toByteArray()).toString() } ?: java.util.UUID.randomUUID().toString()
        val task = PlannerTask(id = id, title = event.title, notes = event.notes, dueDate = occurrence.endDate ?: occurrence.date,
            checklist = event.checklist, attachments = attachments.map { it.copy(id = 0, itemId = 0) },
            repeat = if (repeating && wholeSeries) event.repeatRule else TaskRepeat.NONE.name, reminderAt = reminderAt, ringUntilDismissed = reminderAt != null && first?.ringUntilDismissed == true,
            ringSeconds = if (reminderAt != null) first?.ringSeconds ?: 0 else 0,
            repeatAnchorDay = if (wholeSeries) monthDay(event.repeatRule, seriesStart) else 0)
        return Converted(task, dropped = dropped)
    }

    /**
     * Bug hunt 19, P6: the month day a monthly or yearly series keeps to (the 31st through 30 Apr, 29 Feb through 28 Feb),
     * from its first date; 0 (the due date's own day) for other repeats.
     */
    fun monthDay(repeat: String, start: LocalDate?): Int {
        val kind = RepeatRule.parse(repeat)?.kind ?: return 0
        if (start == null || kind !in setOf(RepeatRule.Kind.MONTHLY, RepeatRule.Kind.EVERY_N_MONTHS, RepeatRule.Kind.YEARLY)) return 0
        return start.dayOfMonth
    }

    /**
     * The occurrence a whole series made into a task is due on: the next one (its last, when all have passed), or a later
     * one when the next one's reminder has passed, so the repeating task keeps a reminder (CV-2, CW-3).
     */
    fun dueOccurrence(series: List<ItineraryItem>, reminders: List<Reminder>, today: LocalDate, now: Long,
                      zone: ZoneId = ZoneId.systemDefault()): ItineraryItem {
        val coming = series.filter { !(it.endDate ?: it.date).isBefore(today) }.sortedBy { it.date }
        return coming.firstOrNull { o -> reminders.isEmpty() || reminders.any { reminderTrigger(o.date, o.startTime, it, zone).toInstant().toEpochMilli() > now } }
            ?: coming.firstOrNull() ?: series.maxBy { it.date }
    }

    /** A reminder [at] as one before the event's start ([time], or 09:00 all day), in its plainest unit; null when after it. */
    fun reminderBefore(date: LocalDate, time: LocalTime?, at: Long, zone: ZoneId): Reminder? {
        val start = date.atTime(time ?: LocalTime.of(9, 0)).atZone(zone)
        val target = Instant.ofEpochMilli(at)
        if (target.isAfter(start.toInstant())) return null
        val wall = ChronoUnit.MINUTES.between(target.atZone(zone).toLocalDateTime(), start.toLocalDateTime())
        val elapsed = ChronoUnit.MINUTES.between(target, start.toInstant())
        // Prefer a calendar-day label when it gives the exact time. Every other time can now be represented
        // by elapsed hours/minutes, including the second occurrence of a clock time when clocks go back.
        if (wall >= 0 && wall % 1440 == 0L) {
            val days = Reminder(itemId = 0, amount = (wall / 1440).toInt(), unit = ReminderUnit.DAYS)
            if (reminderTrigger(date, time, days, zone).toInstant() == target) return days
        }
        return if (elapsed % 60 == 0L) Reminder(itemId = 0, amount = (elapsed / 60).toInt(), unit = ReminderUnit.HOURS)
            else Reminder(itemId = 0, amount = elapsed.toInt(), unit = ReminderUnit.MINUTES)
    }
}
