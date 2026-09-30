package com.example.itinerary

import android.content.ContextWrapper
import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.AlarmLedger
import com.example.itinerary.reminders.MissedReminders
import com.example.itinerary.reminders.ReminderAlarms
import com.example.itinerary.reminders.handleMissedReminders
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

// The boot handling of reminders missed while the phone was off, against a real (in-memory) database; nothing is posted.
class MissedRemindersDataTest {
    @Test fun bootShowsMissedOnceAndRecordsEventDelivery() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "missed-reminders").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        val prefs = base.getSharedPreferences("missed_reminders_test", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        try {
            val repo = Repository(db, AttachmentStore(object : ContextWrapper(base) { override fun getFilesDir() = dir }), object : ReminderAlarms {
                override fun schedule(item: ItineraryItem, reminder: Reminder) {}
                override fun cancel(reminderId: Long) {}
            })
            repo.saveItem(ItineraryItem(tripId = 0, date = LocalDate.now().plusDays(3), startTime = LocalTime.of(9, 0), title = "Dentist"),
                addedReminders = listOf(Reminder(itemId = 0, amount = 1, unit = ReminderUnit.HOURS)))
            val item = repo.snapshot().items.single(); val reminder = repo.snapshot().reminders.single()
            val trigger = reminderTrigger(item.date, item.startTime, 60).toInstant().toEpochMilli()
            val task = PlannerTask(title = "Call mechanic", reminderAt = trigger - 3_600_000)
            repo.saveTask(task)
            val ledger = AlarmLedger(prefs)
            ledger.set(MissedReminders.eventKey(reminder.id), trigger)
            ledger.set(MissedReminders.taskKey(task.id), trigger - 3_600_000)
            ledger.set(MissedReminders.taskKey("later"), trigger + 86_400_000)
            // A boot an hour after the event reminder was due.
            val now = trigger + 3_600_000
            val shown = mutableListOf<MissedReminders.Missed>()
            handleMissedReminders(repo, ledger, now) { shown += it }
            assertEquals(listOf("Dentist", "Call mechanic"), shown.map { (it as? MissedReminders.Event)?.item?.title ?: (it as MissedReminders.Task).task.title })
            assertEquals(ReminderDeliveries.key(item, reminder), db.reminderDao().deliveries(listOf(reminder.id)).single().key)
            assertEquals(setOf(MissedReminders.taskKey("later")), ledger.all().keys)
            // A second reboot shows nothing again.
            shown.clear()
            handleMissedReminders(repo, ledger, now + 60_000) { shown += it }
            assertTrue(shown.isEmpty())
        } finally { db.close(); dir.deleteRecursively(); prefs.edit().clear().commit() }
    }
}
