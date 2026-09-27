package com.example.itinerary

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TaskRemindersTest {
    private class Alarms : ReminderAlarms {
        val scheduled = mutableMapOf<String, PlannerTask>()
        val cancelled = mutableListOf<String>()
        override fun schedule(item: ItineraryItem, reminder: Reminder) = error("Unexpected event alarm")
        override fun cancel(reminderId: Long) = error("Unexpected event cancellation")
        override fun scheduleTask(task: PlannerTask) {
            if (task.done || task.reminderAt == null) { cancelTask(task.id); return }
            if (task.reminderAt > System.currentTimeMillis()) scheduled[task.id] = task
        }
        override fun cancelTask(id: String) { cancelled += id; scheduled.remove(id) }
    }
    private fun fixture(test: suspend (Repository, AppDatabase, Alarms) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val alarms = Alarms()
        try { test(Repository(db, AttachmentStore(context), alarms), db, alarms) }
        finally { db.close() }
    }
    private fun task() = PlannerTask(title = "Reminder task", reminderAt = System.currentTimeMillis() + 3_600_000)

    @Test fun editCompletionDeletionAndUndoUpdateAlarms() = fixture { repo, _, alarms ->
        val task = task()
        repo.saveTask(task)
        assertEquals(task, alarms.scheduled[task.id])
        val edited = task.copy(title = "Updated", reminderAt = task.reminderAt!! + 60_000)
        repo.saveTask(edited, create = false)
        assertEquals(edited, alarms.scheduled[task.id])
        repo.setTaskDone(task.id, true)
        assertTrue(alarms.scheduled.isEmpty())
        repo.setTaskDone(task.id, false)
        assertEquals(edited, alarms.scheduled[task.id])
        repo.deleteTask(task.id)
        assertTrue(alarms.scheduled.isEmpty())
        repo.undoDeletion(repo.pendingDeletions.value.single().token)
        assertEquals(edited, alarms.scheduled[task.id])
        repo.saveTask(edited.copy(reminderAt = null), create = false)
        assertTrue(alarms.scheduled.isEmpty())
        assertTrue(repo.snapshot().items.isEmpty())
    }

    @Test fun unrelatedEditsKeepPendingRemindersButTimeChangesAndRestoreCancelOldOnes() = fixture { repo, _, alarms ->
        val task = task().copy(reminderAt = System.currentTimeMillis() - 1000)
        repo.saveTask(task)
        alarms.cancelled.clear()
        repo.saveTask(task.copy(notes = "Changed notes"), create = false)
        repo.rescheduleAllReminders()
        assertTrue("Unrelated edits must not cancel a late queued alarm", alarms.cancelled.isEmpty())
        repo.saveTask(task.copy(reminderAt = task.reminderAt!! - 1000), create = false)
        assertEquals(listOf(task.id), alarms.cancelled)
        alarms.cancelled.clear()
        repo.replaceAll(repo.snapshot())
        assertEquals(listOf(task.id), alarms.cancelled)
    }

    @Test fun replaceAndReloadReconcileTasksWithoutKeepingOldAlarms() = fixture { repo, _, alarms ->
        val old = task(); repo.saveTask(old)
        val replacement = task()
        val snapshot = DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), tasks = listOf(replacement))
        repo.replaceAll(snapshot)
        assertEquals(setOf(replacement.id), alarms.scheduled.keys)
        assertTrue(old.id in alarms.cancelled)
        alarms.scheduled.clear()
        repo.rescheduleAllReminders()
        assertEquals(replacement, alarms.scheduled[replacement.id])
    }

    @Test fun deliveryRejectsStaleCompletedDeletedAndEarlyAlarms() = fixture { repo, _, _ ->
        val due = task().copy(reminderAt = System.currentTimeMillis() - 1000)
        repo.saveTask(due)
        var deliveries = 0
        repo.deliverTaskReminder(due.id, due.reminderAt!! + 1) { deliveries++ }
        assertEquals(0, deliveries)
        repo.deliverTaskReminder(due.id, due.reminderAt) { deliveries++ }
        assertEquals(1, deliveries)
        repo.setTaskDone(due.id, true)
        repo.deliverTaskReminder(due.id, due.reminderAt) { deliveries++ }
        repo.setTaskDone(due.id, false)
        repo.saveTask(due.copy(reminderAt = null), create = false)
        repo.deliverTaskReminder(due.id, due.reminderAt) { deliveries++ }
        val future = task(); repo.saveTask(future)
        repo.deliverTaskReminder(future.id, future.reminderAt!!) { deliveries++ }
        repo.deleteTask(due.id)
        repo.deliverTaskReminder(due.id, due.reminderAt) { deliveries++ }
        assertEquals(1, deliveries)
    }

    @Test fun version19TaskSurvivesMigrationAndReminderSurvivesReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "task-reminders-migration.db")
        android.database.sqlite.SQLiteDatabase.deleteDatabase(file)
        var db = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath).build()
        db.openHelper.writableDatabase
        db.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(file.absolutePath, null, 0).use { old ->
            old.execSQL("DROP TABLE tasks")
            old.execSQL("CREATE TABLE tasks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, dueDate TEXT, priority TEXT NOT NULL, notes TEXT NOT NULL, done INTEGER NOT NULL)")
            old.execSQL("INSERT INTO tasks VALUES ('legacy', 'Keep task', '2026-09-26', 'HIGH', 'Keep notes', 0)")
            listOf("paymentLink", "paymentReference", "bpayBillerCode", "bpayReference").forEach {
                old.execSQL("ALTER TABLE items DROP COLUMN $it")
            }
            old.version = 19
        }
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .addMigrations(MIGRATION_19_20, MIGRATION_20_21).build()
        db = open()
        try {
            val migrated = db.taskDao().all().single()
            assertEquals(PlannerTask("legacy", "Keep task", java.time.LocalDate.of(2026, 9, 26), TaskPriority.HIGH, "Keep notes"), migrated)
            val withReminder = migrated.copy(reminderAt = System.currentTimeMillis() + 60_000)
            db.taskDao().update(withReminder)
            db.close()
            db = open()
            assertEquals(withReminder, db.taskDao().all().single())
        } finally { db.close(); android.database.sqlite.SQLiteDatabase.deleteDatabase(file) }
    }

    @Test fun overdueReconciliationKeepsExistingAndroidPendingIntent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val task = task().copy(reminderAt = System.currentTimeMillis() - 1000)
        val scheduler = com.example.itinerary.reminders.ReminderScheduler(context)
        val intent = com.example.itinerary.reminders.TaskReminderReceiver.intent(context, task.id)
            .putExtra("trigger", task.reminderAt)
        val immutable = android.app.PendingIntent.FLAG_IMMUTABLE
        val original = android.app.PendingIntent.getBroadcast(context, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or immutable)
        fun pending() = android.app.PendingIntent.getBroadcast(context, 0, intent,
            android.app.PendingIntent.FLAG_NO_CREATE or immutable)
        try {
            assertNotNull(pending())
            scheduler.scheduleTask(task)
            assertEquals("Reconciliation cancelled a queued overdue alarm", original, pending())
            scheduler.scheduleTask(task.copy(done = true))
            assertNull("Completion must still cancel the alarm", pending())
        } finally { scheduler.cancelTask(task.id) }
    }

    @Test fun eventReloadKeepsOverduePendingIntentButAnEditCancelsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scheduler = com.example.itinerary.reminders.ReminderScheduler(context)
        val id = 1_234_567_890L
        val event = ItineraryItem(id = 1, tripId = 1, date = java.time.LocalDate.now().minusDays(1),
            startTime = null, title = "Overdue event")
        val reminder = Reminder(id = id, itemId = 1, amount = 0, unit = ReminderUnit.MINUTES)
        val intent = com.example.itinerary.reminders.reminderIntent(context, event, reminder)
        val immutable = android.app.PendingIntent.FLAG_IMMUTABLE
        fun pending() = android.app.PendingIntent.getBroadcast(context, id.toInt(), intent,
            android.app.PendingIntent.FLAG_NO_CREATE or immutable)
        assertNull("Reserved test alarm id already exists", pending())
        val original = android.app.PendingIntent.getBroadcast(context, id.toInt(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or immutable)
        try {
            scheduler.reconcile(event, reminder)
            assertEquals(original, pending())
            scheduler.schedule(event, reminder)
            assertNull("Editing to an elapsed time must cancel the obsolete alarm", pending())
        } finally { scheduler.cancel(id) }
    }

    @Test fun failedTrashCleanupDoesNotBlockReminderSchedulingAndCanBeRetried() = fixture { repo, db, alarms ->
        val task = task(); repo.saveTask(task)
        val expired = DeletedEntry(label = "Expired fixture", deletedAt = System.currentTimeMillis() - TRASH_RETENTION_MS - 1000,
            payload = DeletedCodec.encode(DeletedContents(emptyList(), emptyList(), emptyList(), emptyList())))
        db.deletedDao().insert(expired)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_cleanup BEFORE DELETE ON recently_deleted BEGIN SELECT RAISE(ABORT, 'injected cleanup failure'); END")
        alarms.scheduled.clear()
        repo.rescheduleAllReminders()
        assertEquals(task, alarms.scheduled[task.id])
        assertEquals(setOf("cleanup"), repo.maintenanceIssues.value)
        assertEquals(expired, db.deletedDao().all().single())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_cleanup")
        repo.retryMaintenance()
        assertTrue(repo.maintenanceIssues.value.isEmpty())
        assertTrue(db.deletedDao().all().isEmpty())
    }

    @Test fun reminderCodecPreservesArchivesAndReadsLegacyTasks() {
        val task = task()
        val encoded = TaskCodec.encode(listOf(task))
        assertEquals(listOf(task), TaskCodec.decode(encoded))
        val archive = DeletedContents(emptyList(), emptyList(), emptyList(), emptyList(), listOf(task))
        assertEquals(archive, DeletedCodec.decode(DeletedCodec.encode(archive)))
        encoded.getJSONObject(0).remove("reminderAt")
        assertNull(TaskCodec.decode(encoded).single().reminderAt)
        for (invalid in listOf(-1L, "tomorrow", 2.5)) {
            encoded.getJSONObject(0).put("reminderAt", invalid)
            try { TaskCodec.decode(encoded); fail("Accepted invalid reminder $invalid") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
