package com.example.itinerary

import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Notes in the database: saving, pinning and archiving, delete with Undo and Recently deleted, backups, and the v31 upgrade. */
class NotesDataTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val alarms = object : ReminderAlarms {
        override fun schedule(item: ItineraryItem, reminder: Reminder) {}
        override fun cancel(reminderId: Long) {}
    }

    @Test fun saveUpdateDeleteRestoreAndBackup() = runBlocking {
        val base = context
        val dir = File(base.cacheDir, "notes-data").apply { deleteRecursively(); mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("notes_data_$name", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            val store = AttachmentStore(isolated)
            val repo = Repository(db, store, alarms)
            // Saving cleans the note and stamps the time it changed; an unchanged save keeps that time.
            val draft = PlannerNote(title = " Groceries ", content = "- [ ] milk\n- [ ] bread", notebook = "Home ", color = Notes.colors[2], modified = 1)
            val saved = repo.saveNote(draft, create = true)
            assertEquals("Groceries", saved.title); assertEquals("Home", saved.notebook)
            assertTrue(saved.modified > 1)
            assertEquals(saved, repo.saveNote(saved, create = false))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.saveNote(PlannerNote(), create = true) } }
            assertThrows(IllegalStateException::class.java) { runBlocking { repo.saveNote(PlannerNote(title = "Gone"), create = false) } }

            // Pin and archive don't count as a change to the note; ticking a line does.
            val pinned = repo.updateNote(saved.id) { it.copy(pinned = true) }!!
            assertTrue(pinned.pinned); assertEquals(saved.modified, pinned.modified)
            Thread.sleep(5)
            val ticked = repo.updateNote(saved.id) { it.copy(content = Markdown.toggle(it.content, 0)) }!!
            assertEquals("- [x] milk\n- [ ] bread", ticked.content); assertTrue(ticked.modified > saved.modified)
            repo.updateNote(saved.id) { it.copy(archived = true) }
            assertTrue(repo.note(saved.id)!!.archived)

            // Delete: gone, with Undo; Undo brings it back as it was.
            val before = repo.note(saved.id)!!
            repo.deleteNote(saved.id)
            assertNull(repo.note(saved.id))
            val pending = repo.pendingDeletions.value.single()
            assertEquals(listOf(before), pending.notes)
            assertEquals("Groceries", repo.snapshot().deleted.single().label)
            repo.undoDeletion(pending.token)
            assertEquals(before, repo.note(saved.id))
            assertTrue(repo.snapshot().deleted.isEmpty())

            // Delete and let the Undo pass: Recently deleted restores it.
            repo.deleteNote(saved.id); repo.finishDeletion(repo.pendingDeletions.value.single().token)
            val entry = repo.snapshot().deleted.single()
            assertEquals(listOf(before), DeletedCodec.decode(entry.payload).notes)
            repo.restoreDeleted(entry.id)
            assertEquals(before, repo.note(saved.id))

            // The codec keeps every field; an older entry without notes still reads.
            assertEquals(listOf(before), NoteCodec.decode(NoteCodec.encode(listOf(before))))
            assertTrue(DeletedCodec.decode("""{"trips":[],"items":[],"attachments":[],"reminders":[]}""").notes.isEmpty())
            assertThrows(Exception::class.java) { NoteCodec.decode(JSONArray(NoteCodec.encode(listOf(before, before)).toString())) }

            // Backups carry notes (format 17) and put them back.
            val second = repo.saveNote(PlannerNote(title = "Ideas", content = "**Paint** the fence", pinned = true), create = true)
            val backup = BackupManager(isolated, repo, store, SettingsRepository(isolated))
            val file = File(dir, "backup.zip")
            backup.export(Uri.fromFile(file), trackStatus = false)
            repo.deleteNote(second.id); repo.finishDeletion(repo.pendingDeletions.value.single().token)
            repo.updateNote(saved.id) { it.copy(title = "Changed since") }
            val staged = backup.stage(Uri.fromFile(file))
            assertEquals(2, staged.notes)
            backup.restore(staged)
            assertEquals(setOf(before, second), repo.snapshot().notes.toSet())
        } finally {
            db.close(); dir.deleteRecursively()
            base.deleteSharedPreferences("notes_data_settings"); base.deleteSharedPreferences("notes_data_backup_status")
        }
    }

    @Test fun attachedFilesFollowTheNote() = runBlocking {
        val base = context
        val dir = File(base.cacheDir, "notes-files").apply { deleteRecursively(); mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("notes_files_$name", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            val store = AttachmentStore(isolated)
            val repo = Repository(db, store, alarms)
            fun file(name: String, text: String) = store.fileFor(name).apply { parentFile?.mkdirs(); writeText(text) }
            file("kept.txt", "receipt"); file("dropped.txt", "draft only")
            val receipt = Attachment(itemId = 0, name = "Receipt", fileName = "kept.txt", mimeType = "text/plain")
            val note = repo.saveNote(PlannerNote(title = "Tagged", tags = listOf("money", "#home"), attachments = listOf(receipt)), create = true)
            assertEquals(listOf("money", "home"), note.tags)
            // A file the editor let go of that no note uses is removed; the saved note's file stays.
            repo.releaseTaskFiles(listOf("kept.txt", "dropped.txt"))
            assertTrue(store.fileFor("kept.txt").exists()); assertFalse(store.fileFor("dropped.txt").exists())

            // In Recently deleted the file stays; deleted forever, it goes.
            repo.deleteNote(note.id); repo.finishDeletion(repo.pendingDeletions.value.single().token)
            assertTrue(store.fileFor("kept.txt").exists())
            repo.restoreDeleted(repo.snapshot().deleted.single().id)
            assertEquals(listOf(receipt), repo.note(note.id)!!.attachments)

            // A backup carries the file; restored, the note opens it again.
            val backup = BackupManager(isolated, repo, store, SettingsRepository(isolated))
            val zip = File(dir, "backup.zip")
            backup.export(Uri.fromFile(zip), trackStatus = false)
            repo.deleteNote(note.id); repo.finishDeletion(repo.pendingDeletions.value.single().token)
            repo.permanentlyDelete(repo.snapshot().deleted.single().id)
            assertFalse(store.fileFor("kept.txt").exists())
            val staged = backup.stage(Uri.fromFile(zip))
            assertEquals(1, staged.notes); assertEquals(1, staged.attachments)
            backup.restore(staged)
            assertEquals("receipt", store.fileFor(repo.snapshot().notes.single().attachments.single().fileName).readText())
        } finally {
            db.close(); dir.deleteRecursively()
            base.deleteSharedPreferences("notes_files_settings"); base.deleteSharedPreferences("notes_files_backup_status")
        }
    }

    @Test fun version30UpgradesWithEmptyNotesTables() = runBlocking {
        val name = "notes-upgrade.db"
        context.deleteDatabase(name)
        try {
            var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            val repo = Repository(db, AttachmentStore(context), alarms)
            val task = PlannerTask(title = "Kept through the upgrade")
            repo.saveTask(task)
            db.close()
            // Back to version 30: no notes table.
            val raw = android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, 0)
            raw.execSQL("DROP TABLE notes"); raw.execSQL("DROP TABLE sent_notes"); raw.execSQL("DELETE FROM room_master_table"); raw.version = 30; raw.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*ALL_MIGRATIONS).build()
            assertEquals(task, db.taskDao().all().single())
            assertTrue(db.noteDao().all().isEmpty())
            assertTrue(db.sentNoteDao().all().isEmpty()) // added in 32
            val note = PlannerNote(title = "First note")
            db.noteDao().insert(note); db.close()
            // Reopened as an ordinary version 31 database (Room checks the table matches what it expects).
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals(note, db.noteDao().all().single()); db.close()
        } finally { context.deleteDatabase(name) }
    }
}
