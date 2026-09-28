package com.example.itinerary

import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class TasksDataTest {
    private fun fixture(test: suspend (Repository,AppDatabase,BackupManager,File) -> Unit) = runBlocking {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(base.cacheDir,"task-data-tests").apply { deleteRecursively();mkdirs() }
        val context=object:ContextWrapper(base) {
            override fun getFilesDir()=File(dir,"files").apply { mkdirs() }
            override fun getCacheDir()=File(dir,"cache").apply { mkdirs() }
            override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences("task_test_$name",mode)
        }
        val db=Room.inMemoryDatabaseBuilder(base,AppDatabase::class.java).build()
        val store=AttachmentStore(context)
        val repo=Repository(db,store,object:ReminderAlarms {
            override fun schedule(item:ItineraryItem,reminder:Reminder) { fail("Tasks must not schedule event alarms") }
            override fun cancel(reminderId:Long) { fail("No reminder expected") }
        })
        try { test(repo,db,BackupManager(context,repo,store,SettingsRepository(context)),dir) }
        finally { db.close();dir.deleteRecursively() }
    }
    private fun task()=PlannerTask(title="Call mechanic",dueDate=LocalDate.of(2026,9,26),priority=TaskPriority.HIGH,notes="Ask about tyres")
    @Test fun taskCrudPreservesCompletionAndDoesNotCreateEventsOrPlans()=fixture { repo,_,_,_ ->
        val task=task();repo.saveTask(task);repo.setTaskDone(task.id,true)
        repo.saveTask(task.copy(title="Changed",notes="New note"),create=false)
        val saved=repo.snapshot();assertTrue(saved.tasks.single().done);assertEquals("Changed",saved.tasks.single().title)
        assertTrue(saved.items.isEmpty());assertTrue(saved.trips.isEmpty());assertTrue(saved.reminders.isEmpty())
        repo.setTaskDone(task.id,false);assertFalse(repo.snapshot().tasks.single().done)
        repo.deleteTask(task.id)
        try { repo.saveTask(task,create=false);fail("Deleted task resurrected") } catch (_: IllegalStateException) { }
        assertTrue(repo.snapshot().tasks.isEmpty())
    }
    @Test fun retryingCreateDoesNotDuplicateOrOverwriteChangedTask()=fixture { repo,_,_,_ ->
        val task=task();repo.saveTask(task);repo.saveTask(task)
        assertEquals(listOf(task),repo.snapshot().tasks)
        repo.saveTask(task.copy(title="Later edit"),create=false)
        try { repo.saveTask(task);fail("Stale create overwrote a later edit") } catch (_: IllegalStateException) { }
        assertEquals("Later edit",repo.snapshot().tasks.single().title)
    }
    @Test fun taskDeletionUndoTrashAndIdCollisionKeepAllFields()=fixture { repo,db,_,_ ->
        val task=task().copy(done=true);repo.saveTask(task)
        val before=repo.snapshot();repo.deleteTask(task.id)
        val pending=repo.pendingDeletions.value.single();assertEquals(listOf(task),pending.tasks)
        assertEquals(listOf(task),DeletedCodec.decode(repo.snapshot().deleted.single().payload).tasks)
        repo.undoDeletion(pending.token);assertEquals(before,repo.snapshot())
        repo.deleteTask(task.id);val token=repo.pendingDeletions.value.single().token;repo.finishDeletion(token)
        db.taskDao().insert(task.copy(title="Other occupant"));repo.restoreDeleted(token)
        assertEquals(2,repo.snapshot().tasks.size)
        assertEquals(task.copy(id=repo.snapshot().tasks.single { it.title==task.title }.id),repo.snapshot().tasks.single { it.title==task.title })
        assertTrue(repo.snapshot().deleted.isEmpty())
    }
    @Test fun failedDeletionRollsBackArchiveAndKeepsTask()=fixture { repo,db,_,_ ->
        val task=task();repo.saveTask(task);val before=repo.snapshot()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_task_delete BEFORE DELETE ON tasks BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        try { repo.deleteTask(task.id);fail("Deletion should fail") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(before,repo.snapshot());assertTrue(repo.pendingDeletions.value.isEmpty())
    }
    @Test fun permanentDeletionAndExpiryRemoveOnlyArchivedTasks()=fixture { repo,db,_,_ ->
        val one=task();val two=task().copy(title="Keep me")
        repo.saveTask(one);repo.saveTask(two);repo.deleteTask(one.id)
        repo.permanentlyDelete(repo.snapshot().deleted.single().id)
        assertEquals(listOf(two),repo.snapshot().tasks);assertTrue(repo.snapshot().deleted.isEmpty())
        repo.deleteTask(two.id);val entry=repo.snapshot().deleted.single()
        db.deletedDao().delete(entry.id);db.deletedDao().insert(entry.copy(deletedAt=System.currentTimeMillis()-TRASH_RETENTION_MS-1000))
        repo.rescheduleAllReminders();assertTrue(repo.snapshot().deleted.isEmpty());assertTrue(repo.pendingDeletions.value.isEmpty())
    }
    @Test fun backupRestoresDatedUndatedCompletedAndDeletedTasksAndReadsOlderBackups()=fixture { repo,_,backup,dir ->
        val live=task().copy(reminderAt=System.currentTimeMillis()+3_600_000);val anytime=task().copy(title="Anytime",dueDate=null,priority=TaskPriority.LOW,done=true)
        val removed=task().copy(title="Removed")
        repo.saveTask(live);repo.saveTask(anytime);repo.saveTask(removed);repo.deleteTask(removed.id)
        val before=repo.snapshot();val file=File(dir,"tasks.zip");backup.export(Uri.fromFile(file))
        assertEquals(2,backup.stage(Uri.fromFile(file)).tasks)
        repo.replaceAll(DataSnapshot(emptyList(),emptyList(),emptyList(),emptyList()))
        backup.restore(backup.stage(Uri.fromFile(file)));assertEquals(before,repo.snapshot())
        repo.restoreDeleted(before.deleted.single().id);assertEquals(3,repo.snapshot().tasks.size)
        val old=ZipFile(file).use { zip -> JSONObject(zip.getInputStream(zip.getEntry("data.json")).bufferedReader().readText()) }
        assertEquals(com.example.itinerary.data.BackupManager.FORMAT_VERSION,old.getInt("formatVersion"))
        old.put("formatVersion",9);old.remove("tasks");old.put("recentlyDeleted",JSONArray())
        val legacy=File(dir,"legacy.zip");writeJson(legacy,old)
        backup.restore(backup.stage(Uri.fromFile(legacy)));assertTrue(repo.snapshot().tasks.isEmpty())
    }
    @Test fun malformedTaskBackupIsRejectedWithoutChangingCurrentData()=fixture { repo,_,backup,dir ->
        repo.saveTask(task());val before=repo.snapshot();val file=File(dir,"valid.zip");backup.export(Uri.fromFile(file))
        val text=ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry("data.json")).bufferedReader().readText() }
        val bad=File(dir,"bad.zip")
        for (kind in listOf("date","priority","duplicate","missing","type")) {
            val root=JSONObject(text)
            when(kind) {
                "date" -> root.getJSONArray("tasks").getJSONObject(0).put("dueDate","2026-02-30")
                "priority" -> root.getJSONArray("tasks").getJSONObject(0).put("priority","Urgent")
                "duplicate" -> root.getJSONArray("tasks").put(root.getJSONArray("tasks").getJSONObject(0))
                "missing" -> root.remove("tasks")
                "type" -> root.put("tasks","broken")
            }
            writeJson(bad,root)
            try { backup.stage(Uri.fromFile(bad));fail("Accepted $kind") } catch (_: BackupException) { }
            assertEquals(before,repo.snapshot())
        }
    }
    private fun writeJson(file:File,value:JSONObject) {
        ZipOutputStream(file.outputStream()).use { it.putNextEntry(ZipEntry("data.json"));it.write(value.toString().toByteArray());it.closeEntry() }
    }
}
