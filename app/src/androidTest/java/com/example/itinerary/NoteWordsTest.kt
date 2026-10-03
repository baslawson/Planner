package com.example.itinerary

import android.app.Notification
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.PlannerNote
import com.example.itinerary.reminders.MissedReminders
import com.example.itinerary.reminders.NoteWords
import com.example.itinerary.reminders.postMissedReminders
import com.example.itinerary.reminders.postNoteReminder
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * D6-3 (R-2): a note reminder posted while the phone is locked (faked through NoteWords' seam) has no word of the note
 * in the notification itself, not only in its public version; once unlocked it is shown again with them. The same for
 * a missed note reminder. The notes aren't in the database: these notifications are built from the note given.
 */
class NoteWordsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private var locked = true
    private val savedLocked = NoteWords.locked
    private val note = PlannerNote(id = "qa-note-words", title = "QA private title", content = "QA private words")

    @Before fun fakeLockedPhone() { NoteWords.locked = { locked } }

    @After fun restore() {
        NoteWords.locked = savedLocked
        manager.cancel("note:${note.id}", 0)
    }

    private fun shown(): Notification? = manager.activeNotifications.firstOrNull { it.tag == "note:${note.id}" }?.notification
    private fun words(n: Notification?): String = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
        .joinToString(" ") { n?.extras?.getCharSequence(it)?.toString().orEmpty() }

    private fun await(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!check() && System.currentTimeMillis() < end) Thread.sleep(100)
        assertTrue(what, check())
    }

    @Test fun aReminderPostedLockedHasNoWordsUntilTheUnlock() {
        postNoteReminder(context, note.id, System.currentTimeMillis(), note)
        await("posted") { shown() != null }
        assertEquals("Note reminder", shown()!!.extras.getString(Notification.EXTRA_TITLE))
        assertFalse(words(shown()), words(shown()).contains("QA private"))
        // Still locked: nothing changes.
        NoteWords.reveal(context)
        assertFalse(words(shown()).contains("QA private"))
        locked = false
        NoteWords.reveal(context)
        await("shown again with its words") { shown()?.extras?.getString(Notification.EXTRA_TITLE) == "QA private title" }
        assertTrue(words(shown()).contains("QA private words"))
        assertEquals("Note reminder", shown()!!.publicVersion?.extras?.getString(Notification.EXTRA_TITLE))
    }

    @Test fun aReminderDismissedBeforeTheUnlockIsntShownAgain() {
        postNoteReminder(context, note.id, System.currentTimeMillis(), note)
        await("posted") { shown() != null }
        manager.cancel("note:${note.id}", 0)
        await("dismissed") { shown() == null }
        locked = false
        NoteWords.reveal(context)
        Thread.sleep(500)
        assertNull(shown())
    }

    @Test fun aMissedNotePostedLockedHasNoWordsUntilTheUnlock() {
        val now = System.currentTimeMillis()
        postMissedReminders(context, listOf(MissedReminders.Note(note, now - 60_000)), now)
        await("posted") { shown() != null }
        assertEquals("Missed note reminder", shown()!!.extras.getString(Notification.EXTRA_TITLE))
        assertFalse(words(shown()).contains("QA private"))
        locked = false
        NoteWords.reveal(context)
        await("shown again with its name") { shown()?.extras?.getString(Notification.EXTRA_TITLE) == "QA private title" }
    }

    @Test fun unlockedTheWordsAreShownAtOnce() {
        locked = false
        postNoteReminder(context, note.id, System.currentTimeMillis(), note)
        await("posted") { shown() != null }
        assertEquals("QA private title", shown()!!.extras.getString(Notification.EXTRA_TITLE))
    }
}
