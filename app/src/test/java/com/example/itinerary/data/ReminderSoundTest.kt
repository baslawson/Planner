package com.example.itinerary.data

import com.example.itinerary.reminders.LockedAlarm
import com.example.itinerary.reminders.LockedAlarmCodec
import com.example.itinerary.reminders.LockedSnapshot
import com.example.itinerary.ui.EditorRules
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

// Reminder sound (bugnotes 7 Oct): each reminder's choice, Settings' default, and the switch it replaces.
class ReminderSoundTest {
    // Stored as "Until I stop it" (ringUntilDismissed) plus a length (ringSeconds); what was stored before reads as one of
    // the two it could be.
    @Test fun theTwoStoredFieldsAreOneChoice() {
        assertEquals(ReminderSound.DEFAULT, ReminderSound.of(false, 0))
        assertEquals(ReminderSound.UNTIL_STOPPED, ReminderSound.of(true, 0))
        assertEquals(ReminderSound.UNTIL_STOPPED, ReminderSound.of(true, 30)) // the flag wins
        assertEquals(ReminderSound.NOTIFICATION, ReminderSound.of(false, -1))
        assertEquals(ReminderSound.SECONDS_10, ReminderSound.of(false, 10))
        assertEquals(ReminderSound.SECONDS_30, ReminderSound.of(false, 30))
        assertEquals(ReminderSound.MINUTE, ReminderSound.of(false, 60))
        assertEquals("a length no build offers", ReminderSound.DEFAULT, ReminderSound.of(false, 45))
        ReminderSound.entries.forEach { assertEquals(it, ReminderSound.of(it.ring, it.seconds)) }
        assertEquals(ReminderSound.MINUTE, Reminder(itemId = 1, amount = 1, unit = ReminderUnit.HOURS, ringSeconds = 60).sound)
        assertEquals(ReminderSound.UNTIL_STOPPED, Reminder(itemId = 1, amount = 1, unit = ReminderUnit.HOURS).withSound(ReminderSound.UNTIL_STOPPED).sound)
        assertEquals(0, Reminder(itemId = 1, amount = 1, unit = ReminderUnit.HOURS, ringSeconds = 30).withSound(ReminderSound.UNTIL_STOPPED).ringSeconds)
    }

    @Test fun storedLengthsAreCleaned() {
        assertEquals(30, ReminderSound.cleanSeconds(false, 30))
        assertEquals(0, ReminderSound.cleanSeconds(true, 30))
        assertEquals(0, ReminderSound.cleanSeconds(false, 45))
        assertEquals(-1, ReminderSound.cleanSeconds(false, -1))
        assertEquals("no reminder, no choice", 0, ReminderSound.cleanSeconds(false, 30, hasReminder = false))
    }

    // Default follows the setting; a reminder's own choice wins over it; "Until I stop it" always rings until stopped.
    @Test fun defaultIsTheSettingAndAnOwnChoiceWins() {
        assertEquals(ReminderSound.SECONDS_10, ReminderSound.resolve(false, 0, ReminderSound.SECONDS_10))
        assertEquals(ReminderSound.NOTIFICATION, ReminderSound.resolve(false, 0, ReminderSound.NOTIFICATION))
        assertEquals(ReminderSound.UNTIL_STOPPED, ReminderSound.resolve(false, 0, ReminderSound.UNTIL_STOPPED))
        assertEquals(ReminderSound.SECONDS_30, ReminderSound.resolve(false, 30, ReminderSound.NOTIFICATION))
        assertEquals(ReminderSound.NOTIFICATION, ReminderSound.resolve(false, -1, ReminderSound.UNTIL_STOPPED))
        assertEquals(ReminderSound.UNTIL_STOPPED, ReminderSound.resolve(true, 0, ReminderSound.NOTIFICATION))
        assertEquals("a setting can't be Default", ReminderSound.SETTING_DEFAULT, ReminderSound.resolve(false, 0, ReminderSound.DEFAULT))
        // What AlarmService is told: seconds, 0 until stopped, null no ring.
        assertEquals(10, ReminderSound.SECONDS_10.alarmSeconds); assertEquals(60, ReminderSound.MINUTE.alarmSeconds)
        assertEquals(0, ReminderSound.UNTIL_STOPPED.alarmSeconds)
        assertNull(ReminderSound.NOTIFICATION.alarmSeconds); assertNull(ReminderSound.DEFAULT.alarmSeconds)
    }

    // The old switch "Play reminder sounds in silent and vibrate mode": off → notification sound only; on (its default),
    // or never set (a new install) → 10 s. Once chosen, the new setting is read and the old switch no longer matters.
    @Test fun theOldSwitchBecomesTheSetting() {
        assertEquals(ReminderSound.NOTIFICATION, ReminderSound.fromSetting(null, oldSwitch = false))
        assertEquals(ReminderSound.SECONDS_10, ReminderSound.fromSetting(null, oldSwitch = true))
        assertEquals(ReminderSound.SECONDS_10, ReminderSound.fromSetting(null, oldSwitch = null))
        assertEquals(ReminderSound.MINUTE, ReminderSound.fromSetting("MINUTE", oldSwitch = false))
        assertEquals(ReminderSound.UNTIL_STOPPED, ReminderSound.fromSetting("UNTIL_STOPPED", oldSwitch = true))
        assertEquals("Default isn't a setting", ReminderSound.NOTIFICATION, ReminderSound.fromSetting("DEFAULT", oldSwitch = false))
        assertEquals("unknown name", ReminderSound.SECONDS_10, ReminderSound.fromSetting("SECONDS_5", oldSwitch = null))
        assertFalse(ReminderSound.DEFAULT in ReminderSound.settingChoices)
        assertEquals(5, ReminderSound.settingChoices.size)
    }

    @Test fun theListSaysWhatDefaultIs() {
        assertEquals("Default (10 s)", ReminderSound.DEFAULT.choiceLabel(ReminderSound.SECONDS_10))
        assertEquals("Default (notification sound only)", ReminderSound.DEFAULT.choiceLabel(ReminderSound.NOTIFICATION))
        assertEquals("Default (until I stop it)", ReminderSound.DEFAULT.choiceLabel(ReminderSound.UNTIL_STOPPED))
        assertEquals("30 seconds", ReminderSound.SECONDS_30.choiceLabel(ReminderSound.SECONDS_10))
        assertEquals("Until I stop it", ReminderSound.UNTIL_STOPPED.choiceLabel(ReminderSound.SECONDS_10))
    }

    // The locked-reboot snapshot keeps each alarm's length, so a timed ring before the first unlock rings as chosen; a
    // snapshot written before reads as Default.
    @Test fun theLockedSnapshotKeepsTheLength() {
        val event = LockedAlarm.Event(7, 2_000_000_000_000, "Dentist", "", "2026-10-05", "09:30", "1 hour before", false, false,
            "s", null, ringSeconds = 30)
        val alarms = listOf(event, LockedAlarm.Task("task", 2_000_000_000_000, "Task", false, -1), LockedAlarm.Note("note", 2_000_000_000_000, false, 60))
        val snapshot = LockedSnapshot("HOUR_24", alarms)
        assertEquals(snapshot, LockedAlarmCodec.decode(LockedAlarmCodec.encode(snapshot)))
        val older = "planner-locked-alarms\t1\tHOUR_24\n" +
            "e\t7\t2000000000000\tDentist\t\t2026-10-05\t09:30\t1 hour before\t0\t0\ts\t\\0\n" +
            "t\ttask\t2000000000000\tTask\t1\nn\tnote\t2000000000000\t0\n"
        assertEquals(listOf(event.copy(ringSeconds = 0), LockedAlarm.Task("task", 2_000_000_000_000, "Task", true),
            LockedAlarm.Note("note", 2_000_000_000_000)), LockedAlarmCodec.decode(older)!!.alarms)
        // And from what the scheduler stores: an event's reminder brings its length.
        val item = ItineraryItem(id = 3, tripId = 0, date = LocalDate.of(2026, 10, 5), startTime = java.time.LocalTime.of(9, 30), title = "Dentist")
        val reminder = Reminder(id = 7, itemId = 3, amount = 1, unit = ReminderUnit.HOURS, ringSeconds = 60)
        assertEquals(60, LockedAlarm.Event.of(item, reminder, 1L).ringSeconds)
    }

    // A note's choice merges as part of its reminder: changed on one side only, it is kept; changed on both, a conflict.
    @Test fun mergesCopiesAndRepeatsKeepTheChoice() {
        val base = PlannerNote(title = "Note", reminderAt = 1000)
        val merged = mergeNotes(base, base.copy(ringSeconds = 30), base.copy(content = "Other words"))!!
        assertEquals(30, merged.ringSeconds); assertEquals("Other words", merged.content)
        assertNull(mergeNotes(base, base.copy(ringSeconds = 30), base.copy(ringSeconds = 60)))
        assertEquals("a copy has no reminder", 0, Notes.copyOf(base.copy(ringSeconds = 30)).ringSeconds)
        assertEquals("no reminder, no length", 0, Notes.clean(PlannerNote(title = "x", ringSeconds = 30)).ringSeconds)
        assertEquals(60, Notes.clean(base.copy(ringSeconds = 60)).ringSeconds)
        val day = LocalDate.of(2026, 10, 5)
        val task = PlannerTask(title = "Repeat", dueDate = day, repeat = "DAILY", reminderAt = 2_000_000_000_000, ringSeconds = 60)
        assertEquals(60, task.nextOccurrence(day, zone = ZoneId.of("UTC"))!!.ringSeconds)
    }

    // A task made into an event and back keeps its reminder's sound.
    @Test fun conversionKeepsTheChoice() {
        val zone = ZoneId.of("Australia/Perth")
        val due = LocalDate.of(2026, 10, 9)
        val at = due.minusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val task = PlannerTask(title = "Shopping", dueDate = due, reminderAt = at, ringSeconds = 30)
        val event = TaskEventConversion.toEvent(task, LocalDate.of(2026, 10, 4), zone)
        assertEquals(30, event.reminders.single().ringSeconds)
        val item = event.result.copy(id = 3, startTime = java.time.LocalTime.of(14, 0))
        val back = TaskEventConversion.toTask(item, event.reminders.map { it.copy(itemId = 3) }, emptyList(), wholeSeries = false,
            zone = zone, now = LocalDate.of(2026, 10, 4).atStartOfDay(zone).toInstant().toEpochMilli())
        assertEquals(30, back.result.ringSeconds); assertFalse(back.result.ringUntilDismissed)
    }

    // The editors count a changed sound as a change to save, and one made elsewhere as a change underneath.
    @Test fun editorsSeeAChangedSound() {
        val task = PlannerTask(id = "t", title = "Reminder", reminderAt = 1000)
        assertTrue(EditorRules.taskUnsaved(task, task.copy(ringSeconds = 30)))
        assertFalse("no reminder, nothing to save", EditorRules.taskUnsaved(task.copy(reminderAt = null), task.copy(reminderAt = null, ringSeconds = 30)))
        assertTrue(EditorRules.taskChangedElsewhere(task, task.copy(ringSeconds = -1)))
        assertFalse(EditorRules.taskChangedElsewhere(task, task))
        val item = ItineraryItem(id = 1, tripId = 0, date = LocalDate.of(2026, 10, 5), startTime = null, title = "Dentist")
        val reminder = Reminder(id = 4, itemId = 1, amount = 1, unit = ReminderUnit.HOURS)
        fun edit(r: Reminder) = EditorRules.EventEdit(item, emptyList(), emptyList(), listOf(r), "NONE", "", false)
        assertTrue(EditorRules.eventUnsaved(edit(reminder), edit(reminder.copy(ringSeconds = 60))))
        assertFalse(EditorRules.eventUnsaved(edit(reminder), edit(reminder.copy(snoozedUntil = 5))))
    }
}
