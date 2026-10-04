package com.example.itinerary

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Bug hunt 4 Oct (b): note drafts kept per note (N6-1), the one draft file of earlier versions still read, and where
 * Duplicate puts copies (N6-3, N6-4). An isolated database and files folder; the user's own drafts are not touched.
 */
class NoteDraftsDataTest {
    private fun fixture(test: suspend (Repository, android.content.Context) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "note-drafts-data").apply { deleteRecursively(); mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("note_drafts_test_$name", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repo = Repository(db, AttachmentStore(context), object : ReminderAlarms {
            override fun schedule(item: ItineraryItem, reminder: Reminder) {}
            override fun cancel(reminderId: Long) {}
        })
        try { test(repo, context) }
        finally { NoteDraftStore(context).clearAll(); db.close(); dir.deleteRecursively() }
    }
    private fun draft(id: String, file: String? = null) = NoteDraftStore.Draft(
        PlannerNote(id = id, title = id, attachments = listOfNotNull(file?.let { Attachment(itemId = 0, name = it, fileName = it, mimeType = "image/jpeg") })),
        creating = true, base = null, pendingPhoto = null)

    // N6-1: two windows' editors keep their own drafts; clearing one (opening a saved note, Discard) leaves the other.
    @Test fun eachNoteKeepsItsOwnDraft() = fixture { _, context ->
        val drafts = NoteDraftStore(context)
        drafts.write(draft("shared-email", "photo-a.jpg"))
        Thread.sleep(1100) // newest first, by the time each was written
        drafts.write(draft("own-window", "photo-b.jpg"))
        assertEquals(listOf("own-window", "shared-email"), drafts.readAll().map { it.note.id })
        assertEquals(setOf("photo-a.jpg", "photo-b.jpg"), drafts.files())
        drafts.clear("own-window")
        assertNull(drafts.read("own-window"))
        assertEquals("shared-email", drafts.read("shared-email")?.note?.title)
        assertEquals(setOf("photo-a.jpg"), drafts.files())
    }

    // N6-1: the Notes page leaves the draft of a note open in an editor (any window) alone.
    @Test fun aDraftOfAnOpenNoteIsNotRecovered() = fixture { _, context ->
        val drafts = NoteDraftStore(context)
        drafts.write(draft("open-elsewhere"))
        val editor = Any()
        assertTrue(NoteDraftStore.claim("open-elsewhere", editor))
        try {
            assertFalse("one editor per note", NoteDraftStore.claim("open-elsewhere", Any()))
            assertNull(drafts.recoverable(null))
            assertNull(drafts.recoverable("open-elsewhere"))
        } finally { NoteDraftStore.release("open-elsewhere", editor) }
        assertEquals("open-elsewhere", drafts.recoverable(null)?.note?.id)
    }

    // The one draft file of earlier versions: read as its note's draft, behind a newer one, and gone once that note's
    // draft is cleared (not another note's).
    @Test fun theOldSingleDraftIsStillRead() = fixture { _, context ->
        val drafts = NoteDraftStore(context)
        drafts.write(draft("from-before", "old.jpg"))
        val folder = File(context.filesDir, "note-drafts")
        val single = File(context.filesDir, "note-draft.json")
        folder.listFiles()!!.single().renameTo(single)
        assertEquals("from-before", drafts.read("from-before")?.note?.id)
        assertEquals("from-before", drafts.recoverable(null)?.note?.id)
        assertEquals(setOf("old.jpg"), drafts.files())
        drafts.clear("another")
        assertTrue(single.exists())
        drafts.write(draft("from-before").let { it.copy(note = it.note.copy(content = "newer")) })
        assertEquals("newer", drafts.read("from-before")?.note?.content)
        assertEquals(1, drafts.readAll().size)
        drafts.clear("from-before")
        assertFalse(single.exists())
        assertNull(drafts.read("from-before"))
    }

    // N6-3: pinned notes duplicated together give copies in the order chosen; N6-4: a copy goes right after its original
    // when another note shares the original's place.
    @Test fun duplicatesGoNextToTheirOriginalsInOrder() = fixture { repo, _ ->
        val p1 = repo.saveNote(PlannerNote(title = "P1", pinned = true), create = true)
        val p2 = repo.saveNote(PlannerNote(title = "P2", pinned = true), create = true)
        val a = repo.saveNote(PlannerNote(title = "A"), create = true)
        repo.placeNotes(mapOf(p1.id to -20L, p2.id to -19L, a.id to -10L))
        repo.duplicateNotes(listOf(p1.id, p2.id))
        fun page() = runBlocking { repo.notes.first().filter { !it.archived }.sortedWith(Notes.order).map { it.title } }
        assertEquals(listOf("P1", "P2", "P1 (copy)", "P2 (copy)", "A"), page())

        val c = repo.saveNote(PlannerNote(title = "C"), create = true)
        Thread.sleep(5)
        val tied = repo.saveNote(PlannerNote(title = "T"), create = true)
        val d = repo.saveNote(PlannerNote(title = "D"), create = true)
        // T and C share a place (a restored note keeps its old one); T, newer, shows first.
        repo.placeNotes(mapOf(tied.id to -3L, c.id to -3L, d.id to -2L))
        repo.duplicateNotes(listOf(tied.id))
        val order = page()
        assertEquals(listOf("T", "T (copy)", "C", "D"), order.filter { it in setOf("T", "T (copy)", "C", "D") })
    }
}

