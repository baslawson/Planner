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

class BugFixRegressionTest {
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
        dir = File(base.cacheDir, "bug-fix-regressions").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences("bug_fix_$name", mode)
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
        InstrumentationRegistry.getInstrumentation().targetContext.deleteSharedPreferences("bug_fix_settings")
    }
    @Test fun committedSaveSurvivesWidgetFailureAndRetryDoesNotDuplicate() = runBlocking {
        var broken = true
        var updates = 0
        val store = AttachmentStore(context)
        store.writableFileFor("saved.txt").writeText("Keep me")
        val failing = Repository(db, store, ReminderScheduler(context), onChanged = {
            updates++
            if (broken) throw IllegalStateException("Injected widget error")
        })
        failing.saveItem(event(), added = listOf(Attachment(itemId = 0, name = "Saved", fileName = "saved.txt", mimeType = "text/plain")))
        assertEquals(1, failing.snapshot().items.size)
        assertEquals(setOf("widget"), failing.maintenanceIssues.value)
        broken = false
        failing.retryMaintenance()
        assertEquals(2, updates)
        assertEquals(1, failing.snapshot().items.size)
        assertTrue(store.fileFor("saved.txt").exists())
        assertTrue(failing.maintenanceIssues.value.isEmpty())
    }

    @Test fun restoredFilesAndAllSettingsSurviveFollowUpErrors() = runBlocking {
        val store = AttachmentStore(context)
        store.writableFileFor("restored.txt").writeText("Restored content")
        repo.saveItem(event(), added = listOf(Attachment(itemId = 0, name = "Restored", fileName = "restored.txt", mimeType = "text/plain")))
        settings.setAgendaRange(AgendaRange.ALL)
        settings.setCalendarCollapsed(false)
        val original = repo.snapshot()
        val file = File(dir, "restore.zip")
        backup.export(Uri.fromFile(file))
        val staged = backup.stage(Uri.fromFile(file))
        repo.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList()))
        settings.setAgendaRange(AgendaRange.TODAY)
        settings.setCalendarCollapsed(true)
        val failing = Repository(db, store, ReminderScheduler(context), onChanged = { error("Injected widget error") })
        val failingSettings = SettingsRepository(context) { error("Injected settings widget error") }
        BackupManager(context, failing, store, failingSettings).restore(staged)
        assertEquals(original, failing.snapshot())
        assertEquals("Restored content", store.fileFor("restored.txt").readText())
        assertEquals(AgendaRange.ALL, failingSettings.agendaRange.value)
        assertFalse(failingSettings.calendarCollapsed.value)
        assertEquals(setOf("widget"), failing.maintenanceIssues.value)
    }

    private class FakeAlarms : com.example.itinerary.reminders.ReminderAlarms {
        var broken = true
        val scheduled = mutableListOf<ItineraryItem>()
        val cancelled = mutableListOf<Long>()
        var attempts = 0
        override fun schedule(item: ItineraryItem, reminder: Reminder) {
            attempts++
            if (broken) error("Injected schedule error")
            scheduled.add(item)
        }
        override fun cancel(reminderId: Long) {
            if (broken) error("Injected cancel error")
            cancelled.add(reminderId)
        }
    }
    @Test fun removingBillsClearsPaidStateAndReconcilesOnlyAffectedReminders() = runBlocking {
        val alarms = FakeAlarms().apply { broken = false }
        var widgets = 0
        val repository = Repository(db, AttachmentStore(context), alarms, onChanged = { widgets++ })
        for (item in listOf(
            event().copy(title = "Paid", category = "Bills", paid = true),
            event().copy(title = "Skipped", category = "Bills", paid = true, skipped = true, seriesId = "series"),
            event().copy(title = "Food", category = "Food"),
        )) repository.saveItem(item, addedReminders = listOf(Reminder(itemId = 0, amount = 5, unit = ReminderUnit.MINUTES)))
        val skipped = repository.snapshot().items.single { it.title == "Skipped" }
        repository.saveItem(skipped.copy(seriesId = "series", skipped = true))
        val before = repository.snapshot()
        alarms.scheduled.clear()
        alarms.cancelled.clear()
        widgets = 0
        repository.removeCategories(setOf("Bills"))
        val after = repository.snapshot()
        assertEquals(before.items.map { if (it.category == "Bills") it.copy(category = Categories.OTHER, paid = false) else it }, after.items)
        assertEquals(before.reminders, after.reminders)
        assertEquals(listOf("Paid"), alarms.scheduled.map { it.title })
        assertFalse(alarms.scheduled.single().paid)
        val foodId = before.items.single { it.title == "Food" }.id
        assertFalse(before.reminders.single { it.itemId == foodId }.id in alarms.cancelled)
        assertEquals(1, widgets)
        repository.removeCategories(emptySet())
        assertEquals(1, widgets)
    }

    @Test fun reminderFailureDoesNotStopOtherWorkAndRetryUsesCurrentEvent() = runBlocking {
        val alarms = FakeAlarms()
        var widgets = 0
        val failing = Repository(db, AttachmentStore(context), alarms, onChanged = { widgets++ })
        failing.saveItem(event(), addedReminders = listOf(
            Reminder(itemId = 0, amount = 5, unit = ReminderUnit.MINUTES),
            Reminder(itemId = 0, amount = 10, unit = ReminderUnit.MINUTES)))
        assertEquals(2, alarms.attempts)
        assertEquals(1, widgets)
        assertEquals(setOf("reminders"), failing.maintenanceIssues.value)
        val saved = failing.snapshot().items.single()
        failing.moveToTomorrow(saved.id, day.plusDays(5))
        alarms.broken = false
        failing.retryMaintenance()
        assertEquals(2, alarms.scheduled.size)
        assertTrue(alarms.scheduled.all { it.date == day.plusDays(6) })
        assertEquals(1, failing.snapshot().items.size)
        assertTrue(failing.maintenanceIssues.value.isEmpty())
    }
    @Test fun deleteAndUndoSurviveAlarmCancellationFailure() = runBlocking {
        val alarms = FakeAlarms().apply { broken = false }
        val failing = Repository(db, AttachmentStore(context), alarms)
        failing.saveItem(event(), addedReminders = listOf(Reminder(itemId = 0, amount = 5, unit = ReminderUnit.MINUTES)))
        val saved = failing.snapshot().items.single()
        alarms.broken = true
        failing.deleteWithUndo(saved)
        assertTrue(failing.snapshot().items.isEmpty())
        assertEquals(1, failing.pendingDeletions.value.size)
        alarms.broken = false
        failing.retryMaintenance()
        assertEquals(1, alarms.cancelled.size)
        assertEquals(1, alarms.scheduled.size) // No resurrection by retry.
        failing.undoDeletion(failing.pendingDeletions.value.single().token)
        assertEquals(saved, failing.snapshot().items.single())
        assertEquals(2, alarms.scheduled.size)
    }

    private fun providerContext(queryError: Exception? = null, mimeError: Exception? = null, readError: Boolean = false): Context {
        val source = File(dir, "source.txt").apply { writeText("Imported content") }
        val provider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor {
                queryError?.let { throw it }
                return android.database.MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("source.txt")) }
            }
            override fun getType(uri: Uri): String { mimeError?.let { throw it }; return "text/plain" }
            override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor {
                if (readError) throw java.io.FileNotFoundException("Unavailable")
                return android.os.ParcelFileDescriptor.open(source, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        return object : ContextWrapper(context) {
            override fun getContentResolver() = android.content.ContentResolver.wrap(provider)
        }
    }
    @Test fun metadataAndReadErrorsReturnNullWithoutFiles() = runBlocking {
        for (ctx in listOf(providerContext(queryError = SecurityException("Denied")), providerContext(mimeError = IllegalStateException("Provider failed")), providerContext(readError = true))) {
            assertNull(AttachmentStore(ctx).import(Uri.parse("content://probe/document")))
        }
        assertTrue(File(context.filesDir, "attachments").listFiles().orEmpty().isEmpty())
    }
    @Test fun successfulImportAndCancellationKeepTheirDifferentOutcomes() = runBlocking {
        val ctx = providerContext()
        val store = AttachmentStore(ctx)
        val imported = store.import(Uri.parse("content://probe/document"))!!
        assertEquals("Imported content", store.fileFor(imported.fileName).readText())
        try {
            AttachmentStore(providerContext(queryError = kotlinx.coroutines.CancellationException("Cancelled"))).import(Uri.parse("content://probe/document"))
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(1, File(context.filesDir, "attachments").listFiles()!!.size)
    }
    @Test fun overnightCalendarLabelsReferToDisplayedDay() {
        val label = com.example.itinerary.ui.eventEndLabel(day, LocalTime.of(23,30), 120, TimeFormat.HOUR_24, context, day.plusDays(1))
        assertEquals("Started yesterday · Until 01:30 · 2 h", label)
        val original = com.example.itinerary.ui.eventEndLabel(day, LocalTime.of(23,30), 120, TimeFormat.HOUR_24, context)
        assertEquals("Until 01:30 (next day) · 2 h", original)
        assertEquals("Until 10:00 · 1 h", com.example.itinerary.ui.eventEndLabel(day, LocalTime.of(9,0), 60, TimeFormat.HOUR_24, context, day))
    }
}
