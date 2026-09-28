package com.example.itinerary

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Multi-day all-day events in storage: validation, series, moving, the day query, backups and drafts. */
class MultiDayDataTest {
    private fun fixture(test: suspend (Repository, BackupManager, File) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "multi-day-tests").apply { deleteRecursively(); mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences("multi_day_$name", mode)
        }
        val alarms = object : ReminderAlarms {
            override fun schedule(item: ItineraryItem, reminder: Reminder) {}
            override fun cancel(reminderId: Long) {}
        }
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        val store = AttachmentStore(context); val repo = Repository(db, store, alarms)
        try { test(repo, BackupManager(context, repo, store, SettingsRepository(context)), dir) }
        finally { db.close(); dir.deleteRecursively(); context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit() }
    }
    private val oct3 = LocalDate.of(2026, 10, 3)
    private fun trip() = ItineraryItem(tripId = 0, date = oct3, startTime = null, title = "QA trip", endDate = oct3.plusDays(4))

    @Test fun invalidSpansAreRefusedAndValidOnesSaved() = fixture { repo, _, _ ->
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.saveItem(trip().copy(startTime = LocalTime.NOON)) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.saveItem(trip().copy(category = "Bills")) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.saveItem(trip().copy(endDate = oct3)) } }
        assertTrue(repo.snapshot().items.isEmpty())
        repo.saveItem(trip())
        assertEquals(oct3.plusDays(4), repo.snapshot().items.single().endDate)
    }

    @Test fun eachOccurrenceOfASeriesKeepsTheSpan() = fixture { repo, _, _ ->
        repo.saveItem(trip(), options = EventSaveOptions(RepeatRule.WEEKLY, 3))
        val members = repo.snapshot().items.sortedBy { it.date }
        assertEquals(listOf(oct3, oct3.plusWeeks(1), oct3.plusWeeks(2)), members.map { it.date })
        assertTrue(members.all { it.dayCount == 5 })
        // Editing the whole series to a 2-day span changes every occurrence's length, not its start.
        val first = members.first()
        repo.saveItem(first.copy(endDate = first.date.plusDays(1)), options = EventSaveOptions(entireSeries = true))
        val edited = repo.snapshot().items.sortedBy { it.date }
        assertEquals(members.map { it.date }, edited.map { it.date })
        assertTrue(edited.all { it.dayCount == 2 })
    }

    @Test fun moveToTomorrowShiftsBothDatesAndUndoRestoresThem() = fixture { repo, _, _ ->
        repo.saveItem(trip())
        val id = repo.snapshot().items.single().id
        repo.moveToTomorrow(id, today = oct3.minusDays(3)) // tomorrow = 1 Oct
        repo.snapshot().items.single().let { assertEquals(oct3.minusDays(2), it.date); assertEquals(oct3.plusDays(2), it.endDate) }
        assertTrue(repo.undoMove(repo.pendingMoves.value.single().token))
        repo.snapshot().items.single().let { assertEquals(oct3, it.date); assertEquals(oct3.plusDays(4), it.endDate) }
    }

    @Test fun theDayQueryFindsATripThatStartedDaysEarlier() = fixture { repo, _, _ ->
        repo.saveItem(trip())
        repo.saveItem(ItineraryItem(tripId = 0, date = oct3.minusDays(3), startTime = null, title = "QA long ago"))
        assertEquals(listOf("QA trip"), repo.widgetEvents(oct3.plusDays(2)).map { it.title })
        assertEquals(listOf("QA trip"), repo.widgetEvents(oct3.plusDays(4)).map { it.title })
        assertTrue(repo.widgetEvents(oct3.plusDays(5)).isEmpty())
    }

    @Test fun backupsAndDraftsKeepTheEndDate() = fixture { repo, backup, dir ->
        repo.saveItem(trip()); repo.saveItem(ItineraryItem(tripId = 0, date = oct3, startTime = null, title = "QA one day"))
        val before = repo.snapshot()
        val file = File(dir, "multi-day.zip"); backup.export(Uri.fromFile(file))
        repo.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList()))
        backup.restore(backup.stage(Uri.fromFile(file)))
        assertEquals(before, repo.snapshot())
        val saved = before.items.single { it.title == "QA trip" }
        assertEquals(saved, DraftCodec.item(DraftCodec.item(saved)))
        assertEquals(before.items.single { it.title == "QA one day" }, DraftCodec.item(DraftCodec.item(before.items.single { it.title == "QA one day" })))
    }
}
