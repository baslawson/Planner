package com.example.itinerary

import android.app.NotificationManager
import android.os.SystemClock
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** A note's reminder: set, moved, snoozed, cleared and cancelled with the note; and a real one rings with Done. */
class NoteRemindersTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    // What the repository asked the alarms to do, in order.
    private class Recorder : ReminderAlarms {
        val log = mutableListOf<String>()
        override fun schedule(item: ItineraryItem, reminder: Reminder) {}
        override fun cancel(reminderId: Long) {}
        override fun scheduleNote(note: PlannerNote) { log += "set ${note.id} ${note.reminderAt?.let { note.snoozedUntil ?: it }}" }
        override fun cancelNote(id: String) { log += "cancel $id" }
    }

    @Test fun alarmsFollowTheNote() = runBlocking {
        val alarms = Recorder()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = Repository(db, AttachmentStore(context), alarms)
            val at = System.currentTimeMillis() + 3_600_000L
            val note = repo.saveNote(PlannerNote(title = "Rent", reminderAt = at), create = true)
            assertEquals(listOf("set ${note.id} $at"), alarms.log); alarms.log.clear()
            // A new time: the old alarm goes first.
            repo.saveNote(note.copy(reminderAt = at + 60_000), create = false)
            assertEquals(listOf("cancel ${note.id}", "set ${note.id} ${at + 60_000}"), alarms.log); alarms.log.clear()
            // Words only: the alarm is set again as it was, not cancelled.
            repo.updateNote(note.id) { it.copy(content = "Transfer by Friday") }
            assertEquals(listOf("set ${note.id} ${at + 60_000}"), alarms.log); alarms.log.clear()
            // Deleted: cancelled; Undo: set again.
            repo.deleteNote(note.id)
            assertEquals(listOf("cancel ${note.id}"), alarms.log); alarms.log.clear()
            repo.undoDeletion(repo.pendingDeletions.value.single().token)
            assertEquals(listOf("set ${note.id} ${at + 60_000}"), alarms.log); alarms.log.clear()

            // Snooze and Done act only on a reminder that has come round, at its own time.
            val due = System.currentTimeMillis() - 1_000
            db.noteDao().update(repo.note(note.id)!!.copy(reminderAt = due))
            assertFalse(repo.actOnNoteReminder(note.id, due + 5))
            val later = System.currentTimeMillis() + 600_000
            assertTrue(repo.actOnNoteReminder(note.id, due, snoozeUntil = later))
            assertEquals(later, repo.note(note.id)!!.snoozedUntil)
            assertEquals(listOf("cancel ${note.id}", "set ${note.id} $later"), alarms.log); alarms.log.clear()
            // Saving with the same reminder keeps the snooze; a changed one ends it.
            assertEquals(later, repo.saveNote(repo.note(note.id)!!.copy(title = "Rent!"), create = false).snoozedUntil)
            assertNull(repo.saveNote(repo.note(note.id)!!.copy(reminderAt = later + 1), create = false).snoozedUntil)
            db.noteDao().update(repo.note(note.id)!!.copy(reminderAt = due, snoozedUntil = null))
            assertTrue(repo.actOnNoteReminder(note.id, due))
            assertNull(repo.note(note.id)!!.reminderAt)

            // Delivered only while the note still has that reminder at that time.
            db.noteDao().update(repo.note(note.id)!!.copy(reminderAt = due))
            var shown = 0
            repo.deliverNoteReminder(note.id, due + 1) { shown++ }
            repo.deliverNoteReminder(note.id, due) { shown++ }
            assertEquals(1, shown)
        } finally { db.close() }
    }

    @Test fun aRealReminderRingsAndDoneClearsIt() = runBlocking {
        val app = context.applicationContext as ItineraryApp
        val manager = context.getSystemService(NotificationManager::class.java)
        val note = app.repository.saveNote(PlannerNote(title = "QA note reminder", content = "Bring the **blue** folder",
            reminderAt = System.currentTimeMillis() + 5_000), create = true)
        // The words are shown with the phone unlocked; its screen may go off during the wait (NoteWordsTest has locked).
        val savedLocked = com.example.itinerary.reminders.NoteWords.locked
        com.example.itinerary.reminders.NoteWords.locked = { false }
        try {
            fun shown() = manager.activeNotifications.firstOrNull { it.tag == "note:${note.id}" }
            val end = SystemClock.uptimeMillis() + 120_000
            while (shown() == null && SystemClock.uptimeMillis() < end) Thread.sleep(500)
            assertNotNull("The reminder didn't ring within 2 minutes", shown())
            val posted = shown()!!.notification
            assertEquals("QA note reminder", posted.extras.getString(android.app.Notification.EXTRA_TITLE))
            assertEquals("Bring the blue folder", posted.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
            assertEquals(listOf("Done", "Snooze"), posted.actions.map { it.title.toString() })
            // Done, as tapped in the notification.
            posted.actions.first().actionIntent.send()
            val cleared = SystemClock.uptimeMillis() + 15_000
            while ((app.repository.note(note.id)?.reminderAt != null || shown() != null) && SystemClock.uptimeMillis() < cleared) Thread.sleep(250)
            assertNull(app.repository.note(note.id)!!.reminderAt)
            assertNull(shown())
        } finally {
            com.example.itinerary.reminders.NoteWords.locked = savedLocked
            manager.cancel("note:${note.id}", 0)
            app.repository.deleteNote(note.id)
        }
    }
}
