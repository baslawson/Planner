package com.example.itinerary

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import com.example.itinerary.widget.TodayWidget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

class PlanningFiveDataTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val alarms = object : ReminderAlarms {
        override fun schedule(item: ItineraryItem, reminder: Reminder) {}
        override fun cancel(reminderId: Long) {}
    }
    @Test fun linksDuplicationDeletionAndBackup() = runBlocking {
        val base = instrumentation.targetContext
        val dir = File(base.cacheDir, "planning-five-data").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("planning_five_$name", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            val store = AttachmentStore(context)
            val repo = Repository(db, store, alarms)
            store.fileFor("check.txt").apply { parentFile!!.mkdirs(); writeText("Original attachment") }
            val task = PlannerTask(title = "Prepare", dueDate = LocalDate.now(), done = true,
                attachments = listOf(Attachment(itemId = 0, name = "check", fileName = "check.txt", mimeType = "text/plain")),
                checklist = listOf(ChecklistEntry("one", "Pack", true)))
            repo.saveTask(task)
            val copy = task.duplicateForEditing().copy(dueDate = LocalDate.now())
            repo.saveTask(copy)
            assertTrue(store.fileFor("check.txt").exists())
            assertEquals(listOf(copy.id), repo.widgetTasks(LocalDate.now()).map { it.id })
            val block = ItineraryItem(tripId = 0, date = LocalDate.now(), startTime = LocalTime.of(14,0), durationMinutes = 60,
                title = task.title, linkedTaskId = copy.id)
            repo.saveItem(block)
            val saved = repo.snapshot().items.single()
            assertEquals(copy.id, saved.linkedTaskId)
            assertEquals(copy.id, DraftCodec.item(DraftCodec.item(saved)).linkedTaskId)
            repo.deleteTask(copy.id)
            assertEquals(copy.id, repo.snapshot().items.single().linkedTaskId)
            repo.undoDeletion(repo.pendingDeletions.value.single().token)
            assertTrue(repo.snapshot().tasks.any { it.id == copy.id })
            repo.deleteWithUndo(saved)
            repo.undoDeletion(repo.pendingDeletions.value.single().token)
            assertEquals(copy.id, repo.snapshot().items.single().linkedTaskId)
            repo.setTaskDone(copy.id, true)
            repo.setTaskDone(copy.id, true)
            assertTrue(repo.widgetTasks(LocalDate.now()).isEmpty())
            assertEquals(saved, repo.snapshot().items.single())
            val backup = BackupManager(context, repo, store, SettingsRepository(context))
            val file = File(dir, "backup.zip")
            backup.export(Uri.fromFile(file), trackStatus = false)
            val staged = backup.stage(Uri.fromFile(file))
            assertEquals(2, staged.tasks)
            backup.restore(staged)
            assertEquals(copy.id, repo.snapshot().items.single().linkedTaskId)
            assertThrows(Exception::class.java) { runBlocking { repo.saveItem(block.copy(linkedTaskId = "missing")) } }
            assertEquals(1, repo.snapshot().items.size)
        } finally {
            db.close(); dir.deleteRecursively()
            base.deleteSharedPreferences("planning_five_settings"); base.deleteSharedPreferences("planning_five_backup_status")
        }
    }
    @Test fun migrationAndColdDatabaseReopen() = runBlocking {
        val context = instrumentation.targetContext
        val name = "planning-five-migration.db"
        context.deleteDatabase(name)
        try {
            var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            val task = PlannerTask(title = "Persisted")
            val repo = Repository(db, AttachmentStore(context), alarms)
            repo.saveTask(task)
            repo.saveItem(ItineraryItem(tripId=0, date=LocalDate.now(), startTime=LocalTime.NOON, title="Block", durationMinutes=30, linkedTaskId=task.id))
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals(task.id, db.itemDao().all().single().linkedTaskId)
            db.close()
            // Convert the isolated fixture to the exact prior schema by dropping only the new column.
            val raw = android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, 0)
            raw.execSQL("ALTER TABLE items DROP COLUMN linkedTaskId")
            raw.execSQL("ALTER TABLE items DROP COLUMN bufferBeforeMinutes")
            raw.execSQL("ALTER TABLE items DROP COLUMN bufferAfterMinutes")
            raw.execSQL("ALTER TABLE tasks DROP COLUMN prerequisiteIds")
            raw.execSQL("ALTER TABLE items DROP COLUMN endDate") // added in version 24
            raw.execSQL("ALTER TABLE tasks DROP COLUMN snoozedUntil") // added in version 29
            // Calendar sync tables added in versions 25–27 and reminder deliveries in 29; the upgrade creates them again.
            listOf("sent_events", "outside_events", "calendar_sources", "reminder_deliveries").forEach { raw.execSQL("DROP TABLE $it") }
            raw.execSQL("DELETE FROM room_master_table")
            raw.execSQL("ALTER TABLE tasks DROP COLUMN ringUntilDismissed")
            raw.execSQL("ALTER TABLE notes DROP COLUMN ringUntilDismissed")
            raw.version = 21; raw.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*ALL_MIGRATIONS).build()
            assertNull(db.itemDao().all().single().linkedTaskId)
            assertEquals(task, db.taskDao().all().single())
            db.close()
        } finally { context.deleteDatabase(name) }
    }
    @Test fun widgetRendersTasksAndEventsWithSeparateCompletionTarget() {
        val context = instrumentation.targetContext
        val day = LocalDate.now()
        val tasks = listOf(PlannerTask(title="Overdue test", dueDate=day.minusDays(1)), PlannerTask(title="Future",dueDate=day.plusDays(1)), PlannerTask(title="Done",dueDate=day,done=true))
        val events = listOf(ItineraryItem(tripId=1,date=day,startTime=LocalTime.NOON,title="Appointment"))
        instrumentation.runOnMainSync {
            val view = TodayWidget.render(context, events, day, TimeFormat.HOUR_24, 400, tasks).apply(context, null)
            assertEquals(View.GONE, view.findViewById<View>(R.id.widget_empty).visibility)
            assertEquals("Overdue test", view.findViewById<TextView>(R.id.widget_task_title).text.toString())
            assertTrue(view.findViewById<TextView>(R.id.widget_task_due).text.startsWith("Overdue"))
            assertEquals("Complete Overdue test", view.findViewById<View>(R.id.widget_task_done).contentDescription)
            assertEquals(2, view.findViewById<LinearLayout>(R.id.widget_rows).childCount)
            assertEquals("Appointment", view.findViewById<TextView>(R.id.widget_event_title).text.toString())
            // R-6: a task waiting on a prerequisite gets no Complete, as in the app.
            val blocked = TodayWidget.render(context, events, day, TimeFormat.HOUR_24, 400, tasks, setOf(tasks[0].id)).apply(context, null)
            assertEquals("Overdue test is waiting on prerequisites", blocked.findViewById<View>(R.id.widget_task_done).contentDescription)
            assertTrue(blocked.findViewById<TextView>(R.id.widget_task_due).text.endsWith("Waiting on prerequisites"))
        }
    }
}
