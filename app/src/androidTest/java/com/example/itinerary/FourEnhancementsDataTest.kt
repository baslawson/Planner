package com.example.itinerary

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

class FourEnhancementsDataTest {
    private lateinit var context: Context
    private lateinit var dir: File
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository
    private lateinit var settings: SettingsRepository
    private lateinit var backup: BackupManager
    private var changes = 0
    private val day = LocalDate.of(2000, 1, 1)
    private fun event() = ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(23, 30), title = "Duration", durationMinutes = 120)
    @Before fun setup() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(base.cacheDir, "four-enhancements-tests").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences("four_test_$name", mode)
        }
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        db.openHelper.writableDatabase.execSQL("INSERT INTO sqlite_sequence(name, seq) VALUES ('reminders', 910000000)")
        val store = AttachmentStore(context)
        repo = Repository(db, store, ReminderScheduler(context), onChanged = { changes++ })
        settings = SettingsRepository(context)
        backup = BackupManager(context, repo, store, settings)
    }
    @After fun cleanup() {
        db.close()
        dir.deleteRecursively()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteSharedPreferences("four_test_settings")
    }
    @Test fun openingRangeReloadsAndInvalidValueFallsBack() {
        assertEquals(AgendaRange.UPCOMING, settings.agendaRange.value)
        for (range in AgendaRange.entries) {
            settings.setAgendaRange(range)
            assertEquals(range, SettingsRepository(context).agendaRange.value)
            assertEquals(range, settings.snapshot().agendaRange)
        }
        context.getSharedPreferences("settings", 0).edit().putString("agenda_range", "INVALID").commit()
        assertEquals(AgendaRange.UPCOMING, SettingsRepository(context).agendaRange.value)
    }
    @Test fun movingOneOccurrenceKeepsLatestFieldsChildrenAndOtherDates() = runBlocking {
        repo.saveItem(event(), added = listOf(Attachment(itemId = 0, name = "Link", fileName = "", mimeType = Links.MIME_TYPE, url = "https://example.com")),
            addedReminders = listOf(Reminder(itemId = 0, amount = 15, unit = ReminderUnit.MINUTES)),
            options = EventSaveOptions(RepeatRule.WEEKLY, 3))
        val first = repo.snapshot().items.first()
        repo.saveItem(first.copy(title = "Latest title"))
        val before = repo.snapshot()
        repo.moveToTomorrow(first.id, day.plusDays(2))
        val after = repo.snapshot()
        assertEquals(before.items.map { if (it.id == first.id) it.copy(date = day.plusDays(3)) else it }, after.items)
        assertEquals(before.attachments, after.attachments)
        assertEquals(before.reminders, after.reminders)
        assertEquals(3, after.items.size)
        assertEquals(3, changes)
    }
    @Test fun durationAndRangeBackupRoundTripAndSaveValidation() = runBlocking {
        repo.saveItem(event(), options = EventSaveOptions(RepeatRule.DAILY, 3))
        settings.setAgendaRange(AgendaRange.ALL)
        val before = repo.snapshot()
        val file = File(dir, "backup.zip")
        backup.export(Uri.fromFile(file))
        repo.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList()))
        settings.setAgendaRange(AgendaRange.TODAY)
        backup.restore(backup.stage(Uri.fromFile(file)))
        assertEquals(before, repo.snapshot())
        assertEquals(AgendaRange.ALL, settings.agendaRange.value)
        assertTrue(repo.snapshot().items.all { it.durationMinutes == 120 })
        for (invalid in listOf(event().copy(durationMinutes = 0), event().copy(durationMinutes = 1441), event().copy(startTime = null))) {
            try { repo.saveItem(invalid); fail("Expected invalid duration to be rejected") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(before, repo.snapshot())
    }
    @Test fun moveFailureRollsBackAndDoesNotNotifyWidget() = runBlocking {
        repo.saveItem(event())
        val before = repo.snapshot()
        val notifications = changes
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_move BEFORE UPDATE ON items BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        try { repo.moveToTomorrow(before.items.single().id, day); fail("Expected trigger failure") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(before, repo.snapshot())
        assertEquals(notifications, changes)
    }
}
