package com.example.itinerary

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

// Bug hunt 2026-10-03b: editors' drafts keep their files through the unused-file clean-up. An isolated database and
// files folder; the user's own drafts are not touched.
class DraftFilesKeptDataTest {
    private fun fixture(test: suspend (Repository, AttachmentStore, android.content.Context) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "draft-files-kept").apply { deleteRecursively(); mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("draft_files_test_$name", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store = AttachmentStore(context)
        val repo = Repository(db, store, object : ReminderAlarms {
            override fun schedule(item: ItineraryItem, reminder: Reminder) {}
            override fun cancel(reminderId: Long) {}
        })
        try { test(repo, store, context) }
        finally { EditorDraftStore(context).clear(); NoteDraftStore(context).clearAll(); db.close(); dir.deleteRecursively() }
    }

    // E5-1: an event editor still open on an event that was archived elsewhere (it saves the attachments as a new
    // event) keeps the files when Recently deleted is emptied: a draft on disk, and one still waiting to be written.
    @Test fun anEventDraftKeepsItsFilesWhenRecentlyDeletedIsEmptied() = fixture { repo, store, context ->
        val file = store.writableFileFor("ticket.txt").apply { writeText("ticket bytes") }
        val attachment = Attachment(itemId = 0, name = "Ticket", fileName = file.name, mimeType = "text/plain")
        repo.saveItem(ItineraryItem(tripId = 0, date = LocalDate.now(), startTime = LocalTime.NOON, title = "Archived"), added = listOf(attachment))
        val saved = repo.snapshot().items.single()
        val drafts = EditorDraftStore(context)
        drafts.write(JSONObject().put("existingAttachments", DraftCodec.attachments(repo.snapshot().attachments)))
        repo.deleteWithUndo(saved); repo.finishDeletion(repo.pendingDeletions.value.single().token)
        repo.permanentlyDelete(repo.snapshot().deleted.single().id)
        assertTrue(file.exists())
        // Scheduled: still waiting, or written by the time of the clean-up; kept either way.
        drafts.clear()
        val second = store.writableFileFor("photo.jpg").apply { writeText("photo bytes") }
        repo.saveItem(ItineraryItem(tripId = 0, date = LocalDate.now(), startTime = LocalTime.NOON, title = "Second"),
            added = listOf(attachment.copy(name = "Photo", fileName = second.name)))
        drafts.schedule(JSONObject().put("added", DraftCodec.attachments(listOf(attachment.copy(fileName = second.name))))
            .put("state", JSONObject().put("pendingPhoto", file.name))) {}
        repo.deleteWithUndo(repo.snapshot().items.single()); repo.finishDeletion(repo.pendingDeletions.value.single().token)
        repo.permanentlyDelete(repo.snapshot().deleted.single().id)
        assertTrue(second.exists())
        drafts.clear()
        repo.releaseTaskFiles(listOf(file.name, second.name))
        assertFalse(file.exists()); assertFalse(second.exists())
    }

    // E5-5: a note draft waiting to be written is dropped by clear (Discard, Save and close), so it can't come back after.
    @Test fun aClearedNoteDraftDoesNotComeBack() = fixture { _, _, context ->
        val drafts = NoteDraftStore(context)
        val note = PlannerNote(title = "Discarded")
        drafts.schedule(NoteDraftStore.Draft(note, creating = true, base = null, pendingPhoto = null)) {}
        assertEquals("Discarded", drafts.read(note.id)?.note?.title) // a reader sees it while it waits
        drafts.clear(note.id)
        Thread.sleep(1500)
        drafts.flush()
        assertNull(drafts.read(note.id))
    }
}
