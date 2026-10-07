package com.example.itinerary

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

/** Bug hunt 22: reminders' ringing (P1, P2) and the outbox for MyBudget (L3). */
class BugHunt22Test {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun titled(title: String, n: StatusBarNotification) = n.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title

    // P2: "turned off" is a sound that rang changed to one that doesn't, the timed ones too; one ring to another goes on.
    @Test fun aRingingSoundChangedToOneThatDoesNotRingGoesQuiet() = runBlocking {
        val quieted = mutableListOf<String>()
        val alarms = object : ReminderAlarms {
            override fun schedule(item: ItineraryItem, reminder: Reminder) {}
            override fun cancel(reminderId: Long) {}
            override fun ringOffTask(id: String) { quieted += "task:$id" }
            override fun ringOffNote(id: String) { quieted += "note:$id" }
            override fun ringOff(reminderId: Long) { quieted += "event:$reminderId" }
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = Repository(db, AttachmentStore(context), alarms)
            val at = System.currentTimeMillis() + 600_000
            repo.saveTask(PlannerTask(id = "qa-p2", title = "QA P2", reminderAt = at, ringSeconds = 60))
            repo.saveTask(repo.task("qa-p2")!!.copy(ringSeconds = -1), false)
            assertEquals("1 minute to notification only", listOf("task:qa-p2"), quieted); quieted.clear()
            repo.saveTask(repo.task("qa-p2")!!.copy(ringUntilDismissed = true, ringSeconds = 0), false)
            repo.saveTask(repo.task("qa-p2")!!.copy(ringUntilDismissed = false, ringSeconds = 30), false)
            assertTrue("until stopped to 30 s still rings", quieted.isEmpty())
            val note = repo.saveNote(PlannerNote(title = "QA P2 note", reminderAt = at, ringSeconds = 10), true)
            repo.saveNote(repo.note(note.id)!!.copy(ringSeconds = -1), false)
            assertEquals(listOf("note:${note.id}"), quieted); quieted.clear()
            // An event's reminder, changed in place by the editor (its copy comes back with its id).
            val start = LocalDateTime.now().plusDays(1).withSecond(0).withNano(0)
            val id = repo.saveItemId(ItineraryItem(tripId = 0, date = start.toLocalDate(), startTime = start.toLocalTime(), title = "QA P2 event"),
                addedReminders = listOf(Reminder(itemId = 0, amount = 15, unit = ReminderUnit.MINUTES, ringSeconds = 30)))
            val item = repo.snapshot().items.single { it.id == id }
            val reminder = repo.snapshot().reminders.single { it.itemId == id }
            repo.saveItemId(item, addedReminders = listOf(reminder.copy(ringSeconds = -1)), removedReminders = listOf(reminder))
            assertEquals(listOf("event:${reminder.id}"), quieted)
            assertEquals(-1, repo.snapshot().reminders.single { it.itemId == id }.ringSeconds)
        } finally { db.close() }
    }

    // L3: paid then undone before MyBudget heard of it: both go; one MyBudget has open stays, and so does its Undone.
    @Test fun anUnsentPaymentAndItsUndoCancelOut() {
        val outbox = BudgetOutbox(context)
        outbox.clear(); outbox.inFlight = null
        try {
            val add = BudgetLink.Message.Add("pay-1", "planner-bill-1", "Power", 5000, java.time.LocalDate.now())
            val other = BudgetLink.Message.Add("pay-2", "planner-bill-2", "Water", 3000, java.time.LocalDate.now())
            outbox.add(listOf(add, other))
            outbox.add(listOf(BudgetLink.Message.Undone("pay-1")))
            assertEquals(listOf(other), outbox.pending())
            outbox.inFlight = other
            outbox.add(listOf(BudgetLink.Message.Undone("pay-2")))
            assertEquals(listOf(other, BudgetLink.Message.Undone("pay-2")), outbox.pending())
            outbox.add(listOf(BudgetLink.Message.Undone("never-sent")))
            assertEquals(BudgetLink.Message.Undone("never-sent"), outbox.pending().last())
        } finally { outbox.clear(); outbox.inFlight = null }
    }

    // P1: a timed ring (10 s) coming while one rings until stopped (a wake-up alarm) gives way: it is its normal
    // notification, and the alarm rings on.
    @Test fun aTimedRingDoesNotSilenceOneRingingUntilStopped() = runBlocking {
        manager.cancelAll()
        val first = LocalDateTime.now().withSecond(0).withNano(0).plusMinutes(1)
        val wake = "QA wake-up alarm"; val short = "QA short ring"
        app.repository.saveItem(ItineraryItem(tripId = 0, date = first.toLocalDate(), startTime = first.toLocalTime(), title = wake),
            addedReminders = listOf(Reminder(itemId = 0, amount = 0, unit = ReminderUnit.MINUTES, ringUntilDismissed = true)))
        val second = first.plusMinutes(1)
        app.repository.saveItem(ItineraryItem(tripId = 0, date = second.toLocalDate(), startTime = second.toLocalTime(), title = short),
            addedReminders = listOf(Reminder(itemId = 0, amount = 0, unit = ReminderUnit.MINUTES, ringSeconds = 10)))
        val reminders = app.repository.snapshot().reminders
        ins.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        try {
            fun waitFor(what: String, seconds: Long, found: () -> Boolean) {
                val end = SystemClock.uptimeMillis() + seconds * 1000
                while (SystemClock.uptimeMillis() < end) { if (found()) return; Thread.sleep(200) }
                fail("$what: " + manager.activeNotifications.joinToString { "${it.notification.channelId}/${it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)}" })
            }
            waitFor("the wake-up alarm didn't ring", 150) { manager.activeNotifications.any { titled(wake, it) && it.notification.channelId == ALARM_CHANNEL_ID } }
            waitFor("the short one didn't come", 150) { manager.activeNotifications.any { titled(short, it) && it.notification.channelId == REMINDER_CHANNEL_ID } }
            Thread.sleep(15_000) // past the short one's 10 s
            val ringing = manager.activeNotifications.filter { it.notification.channelId == ALARM_CHANNEL_ID }
            assertEquals("the wake-up alarm stopped ringing", listOf(wake), ringing.map { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() })
        } finally {
            reminders.forEach { AlarmService.stopIfRinging(context, it.id) }
            Thread.sleep(1_000)
            manager.cancelAll()
        }
    }
}
