package com.example.itinerary

import android.content.Context
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
import java.time.LocalDate
import java.time.LocalTime

class WorkflowFourDataTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val alarms = object : ReminderAlarms {
        override fun schedule(item: ItineraryItem, reminder: Reminder) {}
        override fun cancel(reminderId: Long) {}
    }
    @Test fun migrationPreservesOldRowsAndReopensNewFields() = runBlocking {
        val name="workflow-four.db"
        context.deleteDatabase(name)
        try {
            var db=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
            val repo=Repository(db,AttachmentStore(context),alarms)
            val task=PlannerTask(title="Original")
            repo.saveTask(task)
            repo.saveItem(ItineraryItem(tripId=0,date=LocalDate.now(),startTime=LocalTime.NOON,title="Original event",durationMinutes=30))
            val event=repo.snapshot().items.single()
            db.close()
            val raw=android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,0)
            raw.execSQL("ALTER TABLE items DROP COLUMN bufferBeforeMinutes")
            raw.execSQL("ALTER TABLE items DROP COLUMN bufferAfterMinutes")
            raw.execSQL("ALTER TABLE tasks DROP COLUMN prerequisiteIds")
            raw.execSQL("ALTER TABLE items DROP COLUMN endDate") // added in version 24
            raw.execSQL("ALTER TABLE tasks DROP COLUMN snoozedUntil") // added in version 29
            // Calendar sync tables added in versions 25–27 and reminder deliveries in 29; the upgrade creates them again.
            listOf("sent_events", "outside_events", "calendar_sources", "reminder_deliveries").forEach { raw.execSQL("DROP TABLE $it") }
            raw.execSQL("DELETE FROM room_master_table");raw.version=22;raw.close()
            db=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(*ALL_MIGRATIONS).build()
            assertEquals(event,db.itemDao().all().single());assertEquals(task,db.taskDao().all().single())
            val changed=event.copy(bufferBeforeMinutes=15,bufferAfterMinutes=20)
            db.itemDao().upsert(changed)
            val dependent=PlannerTask(title="Next",prerequisiteIds=listOf(task.id))
            db.taskDao().insert(dependent);db.close()
            db=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
            assertEquals(changed,db.itemDao().all().single())
            assertEquals(dependent,db.taskDao().byId(dependent.id));db.close()
        } finally { context.deleteDatabase(name) }
    }
    @Test fun dependenciesDraftsBackupAndSavedSearches() = runBlocking {
        val base=context
        val dir=File(base.cacheDir,"workflow-four-data").apply { mkdirs() }
        val isolated=object:ContextWrapper(base) {
            override fun getFilesDir()=File(dir,"files").apply { mkdirs() }
            override fun getCacheDir()=File(dir,"cache").apply { mkdirs() }
            override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences("workflow_four_$name",mode)
        }
        val db=Room.inMemoryDatabaseBuilder(base,AppDatabase::class.java).build()
        try {
            val store=AttachmentStore(isolated)
            val repo=Repository(db,store,alarms)
            val settings=SettingsRepository(isolated)
            val first=PlannerTask(title="Confirm dates")
            val second=PlannerTask(title="Book hotel",prerequisiteIds=listOf(first.id),repeat="DAILY",dueDate=LocalDate.now())
            repo.saveTask(first);repo.saveTask(second)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.setTaskDone(second.id,true) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.saveTask(first.copy(prerequisiteIds=listOf(second.id)),false) } }
            assertFalse(repo.snapshot().tasks.first { it.id==second.id }.done)
            repo.deleteTask(first.id)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.setTaskDone(second.id,true) } }
            repo.undoDeletion(repo.pendingDeletions.value.single().token)
            repo.setTaskDone(first.id,true);repo.setTaskDone(second.id,true);repo.setTaskDone(second.id,true)
            assertEquals(3,repo.snapshot().tasks.size)
            assertEquals(listOf(first.id),repo.snapshot().tasks.single { it.id !in listOf(first.id,second.id) }.prerequisiteIds)
            val searches=listOf(SavedSearch("This week's work","this week",setOf("Tasks"),false))
            settings.setSavedSearches(searches)
            assertEquals(searches,SettingsRepository(isolated).savedSearches.value)
            assertThrows(IllegalArgumentException::class.java) { settings.setSavedSearches(searches+searches.single()) }
            val event=ItineraryItem(tripId=0,date=LocalDate.now(),startTime=LocalTime.NOON,title="Travel",durationMinutes=60,
                bufferBeforeMinutes=30,bufferAfterMinutes=15)
            repo.saveItem(event)
            assertEquals(event,DraftCodec.item(DraftCodec.item(event)))
            assertEquals(second,TaskCodec.decode(TaskCodec.encode(listOf(second))).single())
            val legacy=TaskCodec.encode(listOf(first)).getJSONObject(0).apply { remove("prerequisiteIds") }
            assertTrue(TaskCodec.decode(JSONArray().put(legacy)).single().prerequisiteIds.isEmpty())
            val backup=BackupManager(isolated,repo,store,settings)
            val file=File(dir,"backup.zip")
            backup.export(Uri.fromFile(file),trackStatus=false)
            val staged=backup.stage(Uri.fromFile(file))
            settings.setSavedSearches(emptyList());backup.restore(staged)
            assertEquals(searches,settings.savedSearches.value)
            assertEquals(30,repo.snapshot().items.single().bufferBeforeMinutes)
            assertEquals(listOf(first.id),repo.snapshot().tasks.first { it.id==second.id }.prerequisiteIds)
            val saved=repo.snapshot().items.single()
            repo.deleteWithUndo(saved);repo.undoDeletion(repo.pendingDeletions.value.single().token)
            assertEquals(saved,repo.snapshot().items.single())
        } finally {
            db.close();dir.deleteRecursively()
            base.deleteSharedPreferences("workflow_four_settings");base.deleteSharedPreferences("workflow_four_backup_status")
        }
    }
}
