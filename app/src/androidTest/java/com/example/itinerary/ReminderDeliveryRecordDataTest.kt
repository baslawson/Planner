package com.example.itinerary

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.MissedReminders
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

// R-L2: a task reminder and an event's snooze that rang are recorded, so setting the clock back doesn't ring them again
// (the scheduler skips a recorded time: DeliveredAlarms). No real alarms.
class ReminderDeliveryRecordDataTest {
    @Test fun taskReminderAndSnoozeAreRecordedWhenTheyRing() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "delivery-record").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        val recorded = mutableMapOf<String, Long>()
        try {
            val repo = Repository(db, AttachmentStore(object : ContextWrapper(base) { override fun getFilesDir() = dir }), object : ReminderAlarms {
                override fun schedule(item: ItineraryItem, reminder: Reminder) {}
                override fun cancel(reminderId: Long) {}
                override fun markDelivered(key: String, trigger: Long) { recorded[key] = trigger }
            })
            val now = System.currentTimeMillis()
            val task = PlannerTask(title = "Call", reminderAt = now - 1_000)
            repo.saveTask(task)
            var shown = 0
            repo.deliverTaskReminder(task.id, task.reminderAt!!) { shown++ }
            assertEquals(1, shown)
            assertEquals(task.reminderAt, recorded[MissedReminders.taskKey(task.id)])

            repo.saveItem(ItineraryItem(tripId = 0, date = LocalDate.now().plusDays(2), startTime = LocalTime.of(9, 0), title = "Dentist"),
                addedReminders = listOf(Reminder(itemId = 0, amount = 1, unit = ReminderUnit.HOURS)))
            val reminder = repo.snapshot().reminders.single()
            val until = System.currentTimeMillis() + 60_000
            assertTrue(repo.snoozeReminder(reminder.id, until))
            repo.deliverReminder(reminder.id, until) { _, _ -> shown++ }
            assertEquals(2, shown)
            assertEquals(until, recorded[MissedReminders.eventKey(reminder.id)])
        } finally { db.close(); dir.deleteRecursively() }
    }
}
