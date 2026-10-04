package com.example.itinerary

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

// S6-5: what sync writes from Nextcloud reaches the Repository's change hook as not local (the widget refreshes, nothing
// is sent or un-backed-off); what is done in Planner still is local. S6-7: a deletion's Undo is on offer once it commits.
class SyncWritesDataTest {
    private val day = LocalDate.of(2026, 10, 4)

    @Test fun pulledChangesAreNotLocalChanges() = runBlocking<Unit> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "sync-writes-data").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = dir }
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        val changes = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val repo = Repository(db, AttachmentStore(context), object : ReminderAlarms {
            override fun schedule(item: ItineraryItem, reminder: Reminder) {}
            override fun cancel(reminderId: Long) {}
        }, onChanged = { local -> changes += local })
        try {
            db.tripDao().upsert(Trip(id = 1, name = "Fixture", destination = "", startDate = day, endDate = day))
            fun last() = synchronized(changes) { changes.last() }
            val planner = repo.asPlannerStore()
            val id = planner.add(ItineraryItem(tripId = 1, date = day, startTime = null, title = "From Nextcloud"))
            assertFalse(last())
            planner.update(id) { it.copy(title = "Changed on Nextcloud") }
            assertFalse(last())
            repo.saveItem(db.itemDao().byId(id)!!.copy(title = "Changed in Planner"))
            assertTrue(last())
            val tasks = repo.asTaskStore()
            val taskId = tasks.add(PlannerTask(title = "Task from Nextcloud"))
            assertFalse(last())
            repo.saveTask(db.taskDao().byId(taskId)!!.copy(title = "Task changed in Planner"), create = false)
            assertTrue(last())
            assertTrue(tasks.archive(taskId) { true })
            assertFalse(last())
            // S6-7: deleted with Undo: on offer by the time the delete is done, and the event gone.
            repo.deleteWithUndo(id)
            assertTrue(last())
            assertNull(db.itemDao().byId(id))
            assertEquals(listOf(id), repo.pendingDeletions.value.flatMap { it.items }.map { it.id })
        } finally { db.close(); dir.deleteRecursively() }
    }
}
