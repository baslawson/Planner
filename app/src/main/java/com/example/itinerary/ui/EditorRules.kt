package com.example.itinerary.ui

import com.example.itinerary.data.Attachment
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.MultiDay
import com.example.itinerary.data.Reminder
import com.example.itinerary.data.RepeatRule
import com.example.itinerary.data.eventsOnDay
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

// Pure rules behind the event editor, kept here so they can be unit tested without Compose.
object EditorRules {
    const val DEFAULT_REPEAT_COUNT = 12

    // The held end date counts only while it makes a span Save would accept; a longer one (e.g. after picking a
    // far earlier start) is dropped rather than shown and then refused.
    fun spanEnd(date: LocalDate, endDate: LocalDate?, time: LocalTime?, category: String): LocalDate? =
        endDate?.takeIf { MultiDay.valid(date, it, time, category) }

    // Moving the start from the single-date picker carries a held span along, so its length is kept.
    fun movedEndDate(oldDate: LocalDate, newDate: LocalDate, endDate: LocalDate?): LocalDate? =
        endDate?.let { if (it > oldDate) it.plusDays(ChronoUnit.DAYS.between(oldDate, newDate)) else it }

    // A template that doesn't repeat stores a count of 1; the field keeps a usable count for a repeat picked later.
    fun templateCount(repeat: RepeatRule, count: Int, current: String): String =
        if (repeat != RepeatRule.NONE) count.toString() else current.takeIf { it.toIntOrNull() in 2..365 } ?: DEFAULT_REPEAT_COUNT.toString()

    // Choosing a repeat while the count is unusable starts from the default instead of an error.
    fun countForRepeat(repeat: RepeatRule, current: String): String =
        if (repeat == RepeatRule.NONE || current.toIntOrNull() in 2..365) current else DEFAULT_REPEAT_COUNT.toString()

    // Repository checks give readable reasons; anything else gets the generic retry text.
    fun saveError(e: Exception, billTask: Boolean): String =
        (e as? com.example.itinerary.data.PaymentUpdateException)?.message
            ?: (e as? IllegalArgumentException)?.message?.takeIf { it.isNotBlank() && it != "Failed requirement." }
            ?: "Couldn't save the ${if (billTask) "bill" else "event"}. Your changes are still here; try again."

    // After a save, the calendar stays put when the event still shows on the chosen day (a later day of a trip, or
    // the morning after an overnight event); otherwise it follows the event to its first day.
    fun followDate(item: ItineraryItem, selected: LocalDate): LocalDate? =
        item.date.takeIf { eventsOnDay(listOf(item), selected).isEmpty() }

    // Removed saved reminders are matched by id: a snooze while the editor is open changes the record.
    fun keptReminders(existing: List<Reminder>, removed: List<Reminder>): List<Reminder> =
        existing.filter { kept -> removed.none { it.id == kept.id } }

    // Saved attachments likewise, by id: the editor shouldn't depend on the record staying the same while it is open.
    fun keptAttachments(existing: List<Attachment>, removed: List<Attachment>): List<Attachment> =
        existing.filter { kept -> removed.none { it.id == kept.id } }

    // What an event editor would store on Save, to tell whether anything is unsaved. Attachments are the ones this edit
    // [added] and [removed] (not the saved ones shown: a host may pass those live, and text recognised in the background
    // isn't an edit); [reminders] are the ones shown (kept + added). [count] is the occurrence count while a new series
    // is being made, else ""; [texts] are the raw number fields (duration, buffers, bill amount), so a half-typed value
    // counts too.
    data class EventEdit(val item: ItineraryItem, val added: List<Attachment>, val removed: List<Attachment>, val reminders: List<Reminder>,
        val repeat: String, val count: String, val duplicating: Boolean, val texts: List<String> = emptyList())

    // Unsaved = something Save would store differs from the last saved state (or what a new editor opened with); a
    // recovered draft always is. Added records compare by what they hold, not their ids, and a reminder's ring switched
    // on and off again is no change, nor is a snooze; removed ones by id. The series choice alone (This event / Entire
    // series) isn't passed in: it changes nothing until something else does.
    fun eventUnsaved(saved: EventEdit, now: EventEdit, recovered: Boolean = false): Boolean {
        fun EventEdit.stored() = copy(added = added.map { it.copy(id = 0, itemId = 0) }, removed = removed.sortedBy { it.id }.map { Attachment(it.id, 0, "", "", "") },
            reminders = reminders.map { Reminder(itemId = 0, amount = it.amount, unit = it.unit, ringUntilDismissed = it.ringUntilDismissed) }
                .sortedWith(compareBy({ it.offsetMinutes }, { it.unit }, { it.ringUntilDismissed })))
        return recovered || saved.stored() != now.stored()
    }

    // E10: the event as [stored] now differs from what the editor opened with or last saved ([baseline]) in what two-way
    // sync brings from Nextcloud (title, dates, time, length, place, notes): changed underneath the open editor, whose Save
    // would otherwise write its older copy back. Planner-only details (paid from a notification…) don't count, nor does a
    // new event (nothing stored yet) or one gone meanwhile.
    fun changedElsewhere(baseline: ItineraryItem, stored: ItineraryItem?): Boolean =
        baseline.id != 0L && stored != null && stored.id == baseline.id && com.example.itinerary.data.ServerEvents.apply(baseline, stored) != baseline

    // The same for a task. Save trims the title, notes and checklist, so whitespace there alone stores nothing new.
    fun taskUnsaved(saved: com.example.itinerary.data.PlannerTask, now: com.example.itinerary.data.PlannerTask, recovered: Boolean = false): Boolean {
        fun com.example.itinerary.data.PlannerTask.stored() = copy(title = title.trim(), notes = notes.trim(),
            checklist = checklist.map { it.copy(text = it.text.trim()) })
        return recovered || saved.stored() != now.stored()
    }
}
