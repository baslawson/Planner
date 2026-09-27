package com.example.itinerary

import android.content.ContextWrapper
import android.os.Bundle
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlin.system.measureNanoTime

class CodebaseOptimizationTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val silent = object : ReminderAlarms {
        override fun schedule(item: ItineraryItem, reminder: Reminder) {}
        override fun cancel(reminderId: Long) {}
    }
    private suspend fun widgetEvents(repo: Repository, day: LocalDate) =
        repo.widgetEvents(day)

    @Test fun measureSaveDeleteAndWidgetAtTwoSizes() = runBlocking<Unit> {
        val day = LocalDate.of(2026, 9, 27)
        for (size in listOf(50, 3000)) {
            val dir = File(ins.targetContext.cacheDir, "codebase-benchmark").apply { mkdirs() }
            val context = object : ContextWrapper(ins.targetContext) { override fun getFilesDir() = dir }
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            val repo = Repository(db, AttachmentStore(context), silent)
            try {
                val events = (1L..size.toLong()).map { id -> ItineraryItem(id=id, tripId=1,
                    date=if (id <= 3) day else day.minusDays(id), startTime=LocalTime.of(9,0),
                    title="Fixture $id", notes="Notes ".repeat(200)) }
                db.withTransaction {
                    db.tripDao().upsert(Trip(id=1, name="Fixture", destination="", startDate=day, endDate=day))
                    db.itemDao().insertAll(events)
                    events.forEach { db.attachmentDao().insert(Attachment(itemId=it.id, name="OCR", fileName="fixture-${it.id}", mimeType="text/plain", recognizedText="OCR ".repeat(200))) }
                }
                repeat(2) { repo.saveItem(events.first().copy(title="Warm $it")); widgetEvents(repo,day) }
                val saves = (1..5).map { n -> measureNanoTime { repo.saveItem(events.first().copy(title="Save $n")) } }
                val widgets = (1..5).map { measureNanoTime { assertEquals(3, widgetEvents(repo, day).size) } }
                val deletes = (0..4).map { n -> measureNanoTime { repo.deleteEventsWithUndo(setOf(10L+n)) } }
                assertEquals(size-5, db.itemDao().all().size)
                fun median(values: List<Long>) = values.sorted()[2]/1e6
                ins.sendStatus(0, Bundle().apply { putString("measurement", "size=$size saveMs=${median(saves)} deleteMs=${median(deletes)} widgetMs=${median(widgets)}") })
            } finally { db.close(); dir.deleteRecursively() }
        }
    }
}
