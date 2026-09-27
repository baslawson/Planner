package com.example.itinerary

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.system.measureNanoTime

class OptimizationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun targetedDuplicateQueryMatchesSnapshotAndMeasuresCost() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repo = Repository(db, AttachmentStore(context), object : ReminderAlarms {
            override fun schedule(item: ItineraryItem, reminder: Reminder) {}
            override fun cancel(reminderId: Long) {}
        })
        try {
            val day = LocalDate.of(2000, 1, 1)
            val bill = ItineraryItem(id = 1, tripId = 1, date = day, startTime = null, title = "Electricity bill",
                category = "Bills", billAmountMinor = 10000)
            db.withTransaction {
                db.tripDao().upsert(Trip(id = 1, name = "Fixture", destination = "", startDate = day, endDate = day))
                db.itemDao().insertAll((1L..1200L).map { id ->
                    bill.copy(id = id, category = if (id <= 3) "Bills" else "Other",
                        date = if (id == 3L) day.plusMonths(1) else day, notes = "Notes ".repeat(100))
                })
                for (id in 1L..1200L) db.attachmentDao().insert(Attachment(itemId = id, name = "Document",
                    fileName = "fixture-$id", mimeType = "text/plain", recognizedText = "Recognized invoice text ".repeat(100)))
            }
            val candidate = bill.copy(id = 0, title = "  ELECTRICITY  bill ")
            val expected = Bills.duplicates(candidate, repo.snapshot().items)
            assertEquals(expected, repo.duplicateBills(candidate, EventSaveOptions(), day))
            assertEquals(listOf(2L), repo.duplicateBills(bill, EventSaveOptions(), day).map { it.id })
            assertEquals(listOf(1L, 2L, 3L), repo.duplicateBills(candidate,
                EventSaveOptions(repeat = RepeatRule.MONTHLY, count = 2), day).map { it.id })
            db.itemDao().upsert(bill.copy(seriesId = "series"))
            db.itemDao().upsert(bill.copy(id = 3, date = day.plusMonths(1), seriesId = "series"))
            assertEquals(listOf(2L), repo.duplicateBills(bill.copy(seriesId = "series"),
                EventSaveOptions(entireSeries = true), day).map { it.id })
            val old = (1..5).map { measureNanoTime { Bills.duplicates(candidate, repo.snapshot().items) } }.sorted()[2]
            val optimized = (1..5).map { measureNanoTime { repo.duplicateBills(candidate, EventSaveOptions(), day) } }.sorted()[2]
            Log.i("Optimization", "Duplicate check: 1200 events + 1200 OCR records; snapshot median ${old / 1e6} ms; targeted median ${optimized / 1e6} ms")
        } finally { db.close() }
    }

    @Test fun thumbnailCacheReusesDecodingAndInvalidatesReplacedFiles() {
        val root = File(context.cacheDir, "optimization-thumbnails").apply { mkdirs() }
        val local = object : ContextWrapper(context) { override fun getFilesDir() = root }
        val store = AttachmentStore(local)
        fun write(color: Int, width: Int = 1600, height: Int = 1200) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            store.writableFileFor("image.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            write(Color.RED)
            val cold = (1..5).map {
                store.clearThumbnails()
                measureNanoTime { assertNotNull(store.thumbnail("image.png", 160)) }
            }.sorted()[2]
            val first = store.thumbnail("image.png", 160)!!
            val warm = (1..5).map { measureNanoTime { assertSame(first, store.thumbnail("image.png", 160)) } }.sorted()[2]
            assertNotSame(first, store.thumbnail("image.png", 80))
            write(Color.BLUE)
            val replacement = store.thumbnail("image.png", 160)!!
            assertNotSame(first, replacement)
            assertEquals(Color.BLUE, replacement.getPixel(0, 0))
            assertFalse(first.isRecycled)
            write(Color.GREEN, 8000, 40)
            val narrow = store.thumbnail("image.png", 160)!!
            assertTrue(narrow.width <= 320)
            store.delete("image.png")
            assertNull(store.thumbnail("image.png", 160))
            Log.i("Optimization", "Thumbnail 1600x1200: cold median ${cold / 1e6} ms; cached median ${warm / 1e6} ms")
        } finally { store.clearThumbnails(); root.deleteRecursively() }
    }
}
