package com.example.itinerary

import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipFile

class SixTaskFeaturesDataTest {
    private fun fixture(test: suspend (Repository, AppDatabase, BackupManager, AttachmentStore, android.content.Context) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "six-task-data").apply { deleteRecursively(); mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("six_task_test_$name", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store = AttachmentStore(context)
        val repo = Repository(db, store, object : ReminderAlarms {
            override fun schedule(item: ItineraryItem, reminder: Reminder) {}
            override fun cancel(reminderId: Long) {}
        })
        try { test(repo, db, BackupManager(context, repo, store, SettingsRepository(context)), store, context) }
        finally { db.close(); dir.deleteRecursively() }
    }
    @Test fun simultaneousCompletionAndReopenCreateOnlyOneSuccessor() = fixture { repo, _, _, _, _ ->
        val task = PlannerTask(title = "Repeat", dueDate = LocalDate.now(), repeat = "WEEKLY", checklist = listOf(ChecklistEntry(text = "Step", done = true)))
        repo.saveTask(task)
        coroutineScope { List(10) { async { repo.setTaskDone(task.id, true) } }.awaitAll() }
        val completed = repo.snapshot().tasks.single { it.id == task.id }
        val next = repo.snapshot().tasks.single { it.id != task.id }
        assertEquals(next.id, completed.nextTaskId); assertTrue(completed.done)
        assertFalse(next.done); assertFalse(next.checklist.single().done)
        assertEquals(LocalDate.now().plusWeeks(1), next.dueDate)
        repo.setTaskDone(task.id, false); repo.setTaskDone(task.id, true)
        assertEquals(2, repo.snapshot().tasks.size)
        repo.deleteTask(next.id); repo.setTaskDone(task.id, false); repo.setTaskDone(task.id, true)
        assertEquals(1, repo.snapshot().tasks.size)
    }
    @Test fun successorFailureRollsBackCompletion() = fixture { repo, db, _, _, _ ->
        val task = PlannerTask(title = "Rollback", repeat = "DAILY")
        repo.saveTask(task)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_repeat BEFORE INSERT ON tasks BEGIN SELECT RAISE(ABORT, 'injected repeat failure'); END")
        try { repo.setTaskDone(task.id, true); fail("Expected insert failure") } catch (_: android.database.sqlite.SQLiteException) {}
        assertEquals(listOf(task), repo.snapshot().tasks)
    }
    @Test fun notificationActionsRejectStaleTimesAndCompletionProducesNextTask() = fixture { repo, _, _, _, _ ->
        val old = System.currentTimeMillis() - 1000
        val task = PlannerTask(title = "Action", reminderAt = old, repeat = "DAILY")
        repo.saveTask(task)
        assertFalse(repo.actOnTaskReminder(task.id, old - 1))
        val future = System.currentTimeMillis() + 600000
        assertTrue(repo.actOnTaskReminder(task.id, old, future))
        assertEquals(future, repo.snapshot().tasks.single().reminderAt)
        assertFalse(repo.actOnTaskReminder(task.id, old))
        assertFalse(repo.actOnTaskReminder(task.id, future)) // early action
        repo.saveTask(task, create = false)
        assertTrue(repo.actOnTaskReminder(task.id, old)); assertFalse(repo.actOnTaskReminder(task.id, old))
        assertEquals(2, repo.snapshot().tasks.size)
    }
    @Test fun sharedAttachmentsSurviveRepeatsTrashAndDraftsThenAreCleaned() = fixture { repo, _, _, store, context ->
        val file = store.writableFileFor("shared.txt").apply { writeText("shared bytes") }
        val a = Attachment(itemId = 0, name = "Document", fileName = file.name, mimeType = "text/plain")
        val task = PlannerTask(title = "Files", repeat = "DAILY", attachments = listOf(a))
        repo.saveTask(task); repo.setTaskDone(task.id, true)
        repo.deleteTask(task.id); repo.permanentlyDelete(repo.snapshot().deleted.single().id)
        assertTrue(file.exists())
        val next = repo.snapshot().tasks.single()
        repo.deleteTask(next.id)
        val draft = TaskDraftStore(context)
        draft.write("new", JSONObject().put("title", "Unfinished").put("attachments", DraftCodec.attachments(listOf(a))))
        repo.permanentlyDelete(repo.snapshot().deleted.single().id)
        assertTrue(file.exists()); assertEquals("Unfinished", TaskDraftStore(context).read("new")!!.getString("title"))
        draft.clear("new"); repo.releaseTaskFiles(listOf(file.name)); assertFalse(file.exists())
    }
    @Test fun backupRestoresTaskFilesRecurrenceChecklistAndBillPaymentDetails() = fixture { repo, _, backup, store, context ->
        val file = store.writableFileFor("invoice.txt").apply { writeText("Invoice attachment bytes") }
        val task = PlannerTask(title = "Backup task", repeat = "MONTHLY", repeatAnchorDay = 31,
            checklist = listOf(ChecklistEntry(text = "One", done = true)),
            attachments = listOf(Attachment(itemId = 0, name = "Invoice", fileName = file.name, mimeType = "text/plain")))
        repo.saveTask(task)
        val plan = Trip(id = 99, name = "Bill plan", destination = "", startDate = LocalDate.now(), endDate = LocalDate.now())
        val bill = ItineraryItem(id = 99, tripId = 99, date = LocalDate.now(), startTime = null, title = "Bill", category = "Bills",
            paymentLink = "https://example.com/pay", paymentReference = "Reference 123", bpayBillerCode = "00123", bpayReference = "009876")
        val initial = repo.snapshot().copy(trips = listOf(plan), items = listOf(bill))
        repo.replaceAll(initial)
        val archive = File(context.cacheDir, "backup.zip")
        backup.export(Uri.fromFile(archive))
        ZipFile(archive).use { assertNotNull(it.getEntry("attachments/invoice.txt")) }
        repo.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList()))
        assertFalse(file.exists())
        backup.restore(backup.stage(Uri.fromFile(archive)))
        assertEquals(initial, repo.snapshot()); assertEquals("Invoice attachment bytes", file.readText())
        repo.deleteTask(task.id)
        val deleted = repo.snapshot()
        backup.export(Uri.fromFile(archive))
        repo.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList()))
        backup.restore(backup.stage(Uri.fromFile(archive)))
        assertEquals(deleted, repo.snapshot()); assertTrue(file.exists())
        repo.restoreDeleted(deleted.deleted.single().id)
        assertEquals(task, repo.snapshot().tasks.single())
        assertEquals(bill, DraftCodec.item(DraftCodec.item(bill)))
    }
    @Test fun malformedRepeatAndAttachmentPathAreRejected() = fixture { _, _, _, _, _ ->
        for (task in listOf(PlannerTask(title = "Bad", repeat = "HOURLY"), PlannerTask(title = "Bad", repeatDays = 0),
            PlannerTask(title = "Bad", attachments = listOf(Attachment(itemId = 0, name = "Bad", fileName = "../secret", mimeType = "text/plain"))))) {
            try { TaskCodec.decode(TaskCodec.encode(listOf(task))); fail("Accepted malformed task") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun agendaTypePreferenceSurvivesRepositoryRecreation() = fixture { _, _, _, _, context ->
        val settings = SettingsRepository(context)
        for (mask in 0..7) {
            val selected = AgendaType.entries.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            settings.setAgendaTypes(selected)
            assertEquals(selected, SettingsRepository(context).agendaTypes.value)
        }
        settings.setAgendaTypes(setOf(AgendaType.TASKS))
        settings.toggleAgendaType(AgendaType.TASKS)
        assertEquals(emptySet<AgendaType>(), SettingsRepository(context).agendaTypes.value)
        settings.toggleAgendaType(AgendaType.EVENTS)
        assertEquals(setOf(AgendaType.EVENTS), SettingsRepository(context).agendaTypes.value)
        settings.setAgendaTypes(emptySet())
        assertEquals(emptySet<AgendaType>(), SettingsRepository(context).agendaTypes.value)
        val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        for (legacy in listOf("EVERYTHING", "TASKS", "BILLS", "EVENTS")) {
            prefs.edit().remove("agenda_types").putString("agenda_type", legacy).commit()
            val expected = if (legacy == "EVERYTHING") AgendaType.entries.toSet() else setOf(AgendaType.valueOf(legacy))
            assertEquals(expected, SettingsRepository(context).agendaTypes.value)
        }
    }
    @Test fun newRepeatOptionsSurviveBackupAndArchivedTaskRestore() = fixture { repo, _, backup, _, context ->
        val tasks=listOf("FORTNIGHTLY","YEARLY").map { PlannerTask(title="Repeat $it",repeat=it,
            dueDate=LocalDate.of(2028,2,29),repeatAnchorDay=29) }
        tasks.forEach { repo.saveTask(it) }
        repo.deleteTask(tasks.first().id)
        val before=repo.snapshot()
        val file=File(context.cacheDir,"repeat-options.zip")
        backup.export(Uri.fromFile(file))
        ZipFile(file).use { zip ->
            val root=JSONObject(zip.getInputStream(zip.getEntry("data.json")).bufferedReader().readText())
            assertEquals(13,root.getInt("formatVersion"))
        }
        repo.replaceAll(DataSnapshot(emptyList(),emptyList(),emptyList(),emptyList()))
        backup.restore(backup.stage(Uri.fromFile(file)))
        assertEquals(before,repo.snapshot())
        repo.restoreDeleted(before.deleted.single().id)
        assertEquals(tasks.toSet(),repo.snapshot().tasks.toSet())
    }
}
