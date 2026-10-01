package com.example.itinerary

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.AlarmWindow
import com.example.itinerary.reminders.MissedReminders
import com.example.itinerary.reminders.ReminderAlarms
import com.example.itinerary.reminders.eventReminderAt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

// Android 12+ refuses an app's 501st alarm: the repository keeps only the nearest AlarmWindow.LIMIT armed (no real alarms).
class ReminderAlarmWindowDataTest {
    // Arms as ReminderScheduler does: only a future time, and only up to the horizon.
    private class Alarms : ReminderAlarms {
        val armed = mutableMapOf<String, Long>()
        var horizon: Long? = null
        private fun arm(key: String, at: Long?) {
            if (at != null && at > System.currentTimeMillis() && AlarmWindow.arms(at, horizon)) armed[key] = at else armed.remove(key)
        }
        override fun schedule(item: ItineraryItem, reminder: Reminder) = arm(MissedReminders.eventKey(reminder.id), eventReminderAt(item, reminder))
        override fun scheduleTask(task: PlannerTask) = arm(MissedReminders.taskKey(task.id), task.activeReminderAt?.takeIf { !task.done })
        override fun cancelTask(id: String) { armed.remove(MissedReminders.taskKey(id)) }
        override fun cancel(reminderId: Long) { armed.remove(MissedReminders.eventKey(reminderId)) }
        override fun armHorizon() = horizon
        override fun setArmHorizon(horizon: Long?) { this.horizon = horizon }
        override fun armedCount() = armed.size
    }

    @Test fun onlyTheNearestRemindersAreArmed() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "alarm-window").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        val alarms = Alarms()
        try {
            val repo = Repository(db, AttachmentStore(object : ContextWrapper(base) { override fun getFilesDir() = dir }), alarms)
            val start = LocalDate.now().plusDays(1)
            fun reminder() = listOf(Reminder(itemId = 0, amount = 1, unit = ReminderUnit.HOURS))
            // A year of a daily event: 365 alarms, all of them armed.
            repo.saveItem(ItineraryItem(tripId = 0, date = start, startTime = LocalTime.of(9, 0), title = "Daily"),
                addedReminders = reminder(), options = EventSaveOptions(RepeatRule.DAILY, 365))
            assertEquals(365, alarms.armed.size); assertNull(alarms.horizon)
            // A second series goes over the limit: the nearest LIMIT of both are armed, the rest wait.
            repo.saveItem(ItineraryItem(tripId = 0, date = start, startTime = LocalTime.of(18, 0), title = "Weekly"),
                addedReminders = reminder(), options = EventSaveOptions(RepeatRule.WEEKLY, 100))
            val all = repo.snapshot().let { s -> s.reminders.map { r -> eventReminderAt(s.items.single { it.id == r.itemId }, r) } }
            assertEquals(465, all.size)
            assertEquals(AlarmWindow.LIMIT, alarms.armed.size)
            assertEquals(all.sorted().take(AlarmWindow.LIMIT).toSet(), alarms.armed.values.toSet())
            // A task reminder sooner than all of them is armed at once; the latest armed one waits again.
            val task = PlannerTask(title = "Call", reminderAt = System.currentTimeMillis() + 3_600_000)
            repo.saveTask(task)
            assertEquals(task.reminderAt, alarms.armed[MissedReminders.taskKey(task.id)])
            assertEquals(AlarmWindow.LIMIT, alarms.armed.size)
            // Opening the app chooses the same ones.
            repo.rescheduleAllReminders()
            assertEquals(AlarmWindow.LIMIT, alarms.armed.size)
            assertEquals((all + task.reminderAt!!).sorted().take(AlarmWindow.LIMIT).toSet(), alarms.armed.values.toSet())
            // The weekly series gone: the next rescheduling (app open, or the next alarm going off) arms all again.
            repo.deleteEventsWithUndo(repo.snapshot().items.filter { it.title == "Weekly" }.mapTo(hashSetOf()) { it.id })
            assertTrue(alarms.armed.size < AlarmWindow.LIMIT)
            repo.refillReminders()
            assertEquals(366, alarms.armed.size); assertNull(alarms.horizon)
        } finally { db.close(); dir.deleteRecursively() }
    }
}
