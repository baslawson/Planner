package com.example.itinerary

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class TaskNoteRingingDataTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun newChoicesRoundTripThroughBackupCodecsAndOldFilesDefaultOff() {
        val task = PlannerTask(title = "QA codec task", ringUntilDismissed = true, reminderAt = 2_000_000_000_000L)
        val note = PlannerNote(title = "QA codec note", ringUntilDismissed = true, reminderAt = task.reminderAt)
        assertEquals(task, TaskCodec.decode(TaskCodec.encode(listOf(task))).single())
        assertEquals(note, NoteCodec.decode(NoteCodec.encode(listOf(note))).single())
        val oldTask = TaskCodec.encode(listOf(task)); oldTask.getJSONObject(0).remove("ringUntilDismissed")
        val oldNote = NoteCodec.encode(listOf(note)); oldNote.getJSONObject(0).remove("ringUntilDismissed")
        assertFalse(TaskCodec.decode(oldTask).single().ringUntilDismissed)
        assertFalse(NoteCodec.decode(oldNote).single().ringUntilDismissed)
    }

    @Test fun version33MigrationPreservesExistingDataAndDiskReopenPreservesRingChoice() = runBlocking {
        val file = java.io.File(context.cacheDir, "qa-ringing-migration.db")
        android.database.sqlite.SQLiteDatabase.deleteDatabase(file)
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath).addMigrations(*ALL_MIGRATIONS).build()
        var db = open()
        val task = PlannerTask(title = "QA legacy task", reminderAt = 2_000_000_000_000L)
        val note = PlannerNote(title = "QA legacy note", reminderAt = task.reminderAt)
        try {
            db.taskDao().insert(task); db.noteDao().insert(note); db.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(file.absolutePath, null, 0).use { raw ->
                raw.execSQL("ALTER TABLE tasks DROP COLUMN ringUntilDismissed")
                raw.execSQL("ALTER TABLE notes DROP COLUMN ringUntilDismissed")
                raw.execSQL("DELETE FROM room_master_table"); raw.version = 33
            }
            db = open()
            assertEquals(task, db.taskDao().all().single()); assertEquals(note, db.noteDao().all().single())
            db.taskDao().update(task.copy(ringUntilDismissed = true))
            db.noteDao().update(note.copy(ringUntilDismissed = true))
            db.close(); db = open()
            assertTrue(db.taskDao().all().single().ringUntilDismissed)
            assertTrue(db.noteDao().all().single().ringUntilDismissed)
        } finally { db.close(); android.database.sqlite.SQLiteDatabase.deleteDatabase(file) }
    }

    @Test fun ringModeChangesCancelOldAlarmButWordsKeepItAndSnoozesSurvive() = runBlocking {
        val cancelled = mutableListOf<String>()
        val alarms = object : ReminderAlarms {
            override fun schedule(item: ItineraryItem, reminder: Reminder) {}
            override fun cancel(reminderId: Long) {}
            override fun cancelTask(id: String) { cancelled += "task:$id" }
            override fun cancelNote(id: String) { cancelled += "note:$id" }
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = Repository(db, AttachmentStore(context), alarms)
            val due = System.currentTimeMillis() - 1000
            val task = PlannerTask(title = "QA cancellation task", reminderAt = due)
            repo.saveTask(task)
            val note = repo.saveNote(PlannerNote(title = "QA cancellation note", reminderAt = due), true)
            val later = System.currentTimeMillis() + 600_000
            repo.actOnTaskReminder(task.id, due, later); repo.actOnNoteReminder(note.id, due, later)
            cancelled.clear()
            repo.saveTask(repo.snapshot().tasks.single().copy(ringUntilDismissed = true), false)
            repo.saveNote(repo.note(note.id)!!.copy(ringUntilDismissed = true), false)
            assertEquals(listOf("task:${task.id}", "note:${note.id}"), cancelled)
            assertEquals(later, repo.snapshot().tasks.single().snoozedUntil)
            assertEquals(later, repo.note(note.id)!!.snoozedUntil)
            cancelled.clear()
            repo.saveTask(repo.snapshot().tasks.single().copy(notes = "Words only"), false)
            repo.updateNote(note.id) { it.copy(content = "Words only") }
            assertTrue(cancelled.isEmpty())
        } finally { db.close() }
    }

    @Test fun noteDraftAndRecentlyDeletedKeepRingChoice() = runBlocking {
        val app = context.applicationContext as ItineraryApp
        val note = app.repository.saveNote(PlannerNote(title = "QA ring draft", ringUntilDismissed = true,
            reminderAt = System.currentTimeMillis() + 3_600_000), true)
        val store = NoteDraftStore(context)
        try {
            store.write(NoteDraftStore.Draft(note, false, note, null))
            assertTrue(store.read(note.id)!!.note.ringUntilDismissed)
            app.repository.deleteNote(note.id)
            app.repository.undoDeletion(app.repository.pendingDeletions.value.single().token)
            assertTrue(app.repository.note(note.id)!!.ringUntilDismissed)
        } finally { store.clear(note.id) }
    }

    @HarnessStage @Test fun seedColdStartChoices() = runBlocking {
        val app = context.applicationContext as ItineraryApp
        app.repository.saveTask(PlannerTask(id = "qa-cold-ring-task", title = "QA cold task", ringUntilDismissed = true, reminderAt = System.currentTimeMillis() + 3_600_000))
        app.repository.saveNote(PlannerNote(id = "qa-cold-ring-note", title = "QA cold note", ringUntilDismissed = true, reminderAt = System.currentTimeMillis() + 3_600_000), true)
        Unit
    }
    @HarnessStage @Test fun inspectColdStartChoices() = runBlocking {
        val app = context.applicationContext as ItineraryApp
        assertTrue(app.repository.snapshot().tasks.single { it.id == "qa-cold-ring-task" }.ringUntilDismissed)
        assertTrue(app.repository.note("qa-cold-ring-note")!!.ringUntilDismissed)
    }
}
