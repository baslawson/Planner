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
                raw.execSQL("ALTER TABLE calendar_sources DROP COLUMN note") // added in version 35
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

    // D14-1: a ring choice alone never cancels the reminder (its notification or a late alarm); turned off it goes quiet.
    @Test fun ringModeChangesKeepTheReminderAndSnoozesSurvive() = runBlocking {
        val cancelled = mutableListOf<String>()
        val quieted = mutableListOf<String>()
        val alarms = object : ReminderAlarms {
            override fun schedule(item: ItineraryItem, reminder: Reminder) {}
            override fun cancel(reminderId: Long) {}
            override fun cancelTask(id: String) { cancelled += "task:$id" }
            override fun cancelNote(id: String) { cancelled += "note:$id" }
            override fun ringOffTask(id: String) { quieted += "task:$id" }
            override fun ringOffNote(id: String) { quieted += "note:$id" }
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
            assertTrue(cancelled.isEmpty()); assertTrue(quieted.isEmpty())
            assertEquals(later, repo.snapshot().tasks.single().snoozedUntil)
            assertEquals(later, repo.note(note.id)!!.snoozedUntil)
            repo.saveTask(repo.snapshot().tasks.single().copy(ringUntilDismissed = false), false)
            repo.updateNote(note.id) { it.copy(ringUntilDismissed = false) }
            assertTrue(cancelled.isEmpty())
            assertEquals(listOf("task:${task.id}", "note:${note.id}"), quieted)
            quieted.clear()
            // A new time is a new reminder: the old one is cancelled.
            repo.saveTask(repo.snapshot().tasks.single().copy(reminderAt = later + 60_000, ringUntilDismissed = true), false)
            assertEquals(listOf("task:${task.id}"), cancelled); assertTrue(quieted.isEmpty())
            cancelled.clear()
            repo.saveTask(repo.snapshot().tasks.single().copy(notes = "Words only"), false)
            repo.updateNote(note.id) { it.copy(content = "Words only") }
            assertTrue(cancelled.isEmpty())
        } finally { db.close() }
    }

    // D15-2: a reminder whose time has passed (no snooze), through the editor's saveNote and note sync too; turned on, the
    // reminder is set again with the new choice rather than cancelled.
    @Test fun ringOnlyChangesOnPassedRemindersAcrossWritePaths() = runBlocking {
        val log = mutableListOf<String>()
        val alarms = object : ReminderAlarms {
            override fun schedule(item: ItineraryItem, reminder: Reminder) {}
            override fun cancel(reminderId: Long) {}
            override fun scheduleTask(task: PlannerTask) { log += "schedule task ring=${task.ringUntilDismissed}" }
            override fun scheduleNote(note: PlannerNote) { log += "schedule note ring=${note.ringUntilDismissed}" }
            override fun cancelTask(id: String) { log += "cancel task" }
            override fun cancelNote(id: String) { log += "cancel note" }
            override fun ringOffTask(id: String) { log += "quiet task" }
            override fun ringOffNote(id: String) { log += "quiet note" }
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = Repository(db, AttachmentStore(context), alarms)
            val due = System.currentTimeMillis() - 60_000
            repo.saveTask(PlannerTask(id = "qa-passed-task", title = "QA passed task", reminderAt = due))
            val note = repo.saveNote(PlannerNote(title = "QA passed note", reminderAt = due), true)
            log.clear()
            repo.saveTask(repo.task("qa-passed-task")!!.copy(ringUntilDismissed = true), false)
            assertEquals(listOf("schedule task ring=true"), log); log.clear()
            repo.saveTask(repo.task("qa-passed-task")!!.copy(ringUntilDismissed = false), false)
            assertEquals(listOf("quiet task", "schedule task ring=false"), log); log.clear()
            repo.saveNote(repo.note(note.id)!!.copy(ringUntilDismissed = true), false)
            assertEquals(listOf("schedule note ring=true"), log); log.clear()
            repo.saveNote(repo.note(note.id)!!.copy(ringUntilDismissed = false), false)
            assertEquals(listOf("quiet note", "schedule note ring=false"), log); log.clear()
            repo.saveNote(repo.note(note.id)!!.copy(ringUntilDismissed = true), false); log.clear()
            val current = repo.note(note.id)!!
            assertTrue(repo.putSyncedNote(current.copy(ringUntilDismissed = false), current))
            assertEquals(listOf("quiet note", "schedule note ring=false"), log)
        } finally { db.close() }
    }

    // U15-1: a ring choice an earlier build stored without its reminder is cleared at start, and never read back from a file.
    @Test fun ringChoiceWithoutReminderFromEarlierBuildsIsCleared() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = Repository(db, AttachmentStore(context), object : ReminderAlarms {
                override fun schedule(item: ItineraryItem, reminder: Reminder) {}
                override fun cancel(reminderId: Long) {}
            })
            val due = System.currentTimeMillis() + 3_600_000
            db.taskDao().insert(PlannerTask(id = "qa-legacy", title = "QA legacy", ringUntilDismissed = true))
            db.taskDao().insert(PlannerTask(id = "qa-kept", title = "QA kept", reminderAt = due, ringUntilDismissed = true))
            db.noteDao().insert(Notes.clean(PlannerNote(id = "qa-legacy-note", title = "QA legacy note", reminderAt = due, ringUntilDismissed = true)).copy(reminderAt = null))
            assertTrue(db.noteDao().byId("qa-legacy-note")!!.ringUntilDismissed)
            repo.clearRingWithoutReminder()
            assertFalse(repo.task("qa-legacy")!!.ringUntilDismissed)
            assertTrue(repo.task("qa-kept")!!.ringUntilDismissed)
            assertFalse(repo.note("qa-legacy-note")!!.ringUntilDismissed)
            val legacyTask = TaskCodec.encode(listOf(PlannerTask(id = "x", title = "x", ringUntilDismissed = true)))
            assertFalse(TaskCodec.decode(legacyTask).single().ringUntilDismissed)
            val legacyNote = NoteCodec.encode(listOf(PlannerNote(id = "y", title = "y", ringUntilDismissed = true)))
            assertFalse(NoteCodec.decode(legacyNote).single().ringUntilDismissed)
            assertTrue(TaskCodec.decode(TaskCodec.encode(listOf(PlannerTask(id = "z", title = "z", reminderAt = due, ringUntilDismissed = true)))).single().ringUntilDismissed)
        } finally { db.close() }
    }

    // U14-2: no reminder, no ringing: the choice doesn't wait unseen for the next reminder.
    @Test fun ringChoiceEndsWithItsReminder() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = Repository(db, AttachmentStore(context), object : ReminderAlarms {
                override fun schedule(item: ItineraryItem, reminder: Reminder) {}
                override fun cancel(reminderId: Long) {}
            })
            val due = System.currentTimeMillis() - 1000
            val task = PlannerTask(title = "QA ring end task", reminderAt = due, ringUntilDismissed = true)
            repo.saveTask(task)
            assertTrue(repo.snapshot().tasks.single().ringUntilDismissed)
            assertFalse(repo.snapshot().tasks.single().duplicateForEditing().ringUntilDismissed)
            repo.saveTask(repo.snapshot().tasks.single().copy(reminderAt = null), false)
            assertFalse(repo.snapshot().tasks.single().ringUntilDismissed)
            val note = repo.saveNote(PlannerNote(title = "QA ring end note", reminderAt = due, ringUntilDismissed = true), true)
            assertFalse(Notes.copyOf(note).ringUntilDismissed)
            assertTrue(repo.actOnNoteReminder(note.id, due))
            assertFalse(repo.note(note.id)!!.ringUntilDismissed)
            val other = repo.saveNote(PlannerNote(title = "QA ring end note 2", reminderAt = due, ringUntilDismissed = true), true)
            repo.saveNote(other.copy(reminderAt = null), false)
            assertFalse(repo.note(other.id)!!.ringUntilDismissed)
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
