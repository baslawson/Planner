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

class FiveConveniencesDataTest {
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
        dir = File(base.cacheDir, "five-conveniences-tests").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences("five_test_$name", mode)
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
        InstrumentationRegistry.getInstrumentation().targetContext.deleteSharedPreferences("five_test_settings")
    }
    @Test fun undoMovePreservesLaterEditsChildrenAndOccurrenceIdentity() = runBlocking {
        val task = ChecklistEntry(text = "Bring tickets")
        repo.saveItem(event().copy(checklist = listOf(task)), added = listOf(Attachment(itemId = 0, name = "Link", fileName = "", mimeType = Links.MIME_TYPE, url = "https://example.com")),
            addedReminders = listOf(Reminder(itemId = 0, amount = 15, unit = ReminderUnit.MINUTES)), options = EventSaveOptions(RepeatRule.WEEKLY,3))
        val before = repo.snapshot()
        val first = before.items.first()
        repo.moveToTomorrow(first.id, day.plusDays(2))
        val token = repo.pendingMoves.value.single().token
        val moved = repo.snapshot().items.single { it.id == first.id }
        repo.saveItem(moved.copy(title = "Edited after move", checklist = listOf(task.copy(done = true))))
        assertTrue(repo.undoMove(token))
        val after = repo.snapshot()
        assertEquals(first.copy(title = "Edited after move", checklist = listOf(task.copy(done = true))), after.items.single { it.id == first.id })
        assertEquals(before.items.filter { it.id != first.id }, after.items.filter { it.id != first.id })
        assertEquals(before.attachments,after.attachments)
        assertEquals(before.reminders,after.reminders)
        assertTrue(repo.pendingMoves.value.isEmpty())
    }
    @Test fun undoDoesNotOverwriteAnotherDateOrResurrectDeletion() = runBlocking {
        repo.saveItem(event())
        val first = repo.snapshot().items.single()
        repo.moveToTomorrow(first.id,day)
        val token = repo.pendingMoves.value.single().token
        val moved = repo.snapshot().items.single()
        repo.saveItem(moved.copy(date = day.plusDays(7)))
        assertFalse(repo.undoMove(token))
        assertEquals(day.plusDays(7),repo.snapshot().items.single().date)
        repo.moveToTomorrow(first.id,day)
        val deletedToken = repo.pendingMoves.value.single().token
        repo.deleteItem(repo.snapshot().items.single())
        assertFalse(repo.undoMove(deletedToken))
        assertTrue(repo.snapshot().items.isEmpty())
    }
    @Test fun moveNoOpAndUndoDatabaseFailureKeepCorrectState() = runBlocking {
        repo.saveItem(event().copy(date = day.plusDays(1)))
        val first = repo.snapshot().items.single()
        repo.moveToTomorrow(first.id,day)
        assertTrue(repo.pendingMoves.value.isEmpty())
        repo.moveToTomorrow(first.id,day.plusDays(1))
        val token = repo.pendingMoves.value.single().token
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_undo BEFORE UPDATE ON items BEGIN SELECT RAISE(ABORT, 'test'); END")
        try { repo.undoMove(token); fail("Expected database error") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(day.plusDays(2), repo.snapshot().items.single().date)
        assertEquals(token,repo.pendingMoves.value.single().token)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_undo")
        assertTrue(repo.undoMove(token))
        assertEquals(day.plusDays(1),repo.snapshot().items.single().date)
    }
    @Test fun checklistsRoundTripThroughRoomBackupAndUndo() = runBlocking {
        val tasks = listOf(ChecklistEntry(text="Passport",done=true),ChecklistEntry(text="Tickets"))
        repo.saveItem(event().copy(checklist=tasks))
        val first = repo.snapshot().items.single()
        assertEquals(tasks, first.checklist)
        repo.deleteWithUndo(first)
        repo.undoDeletion(repo.pendingDeletions.value.single().token)
        assertEquals(tasks,repo.snapshot().items.single().checklist)
        val file = File(dir,"checklist.zip")
        backup.export(Uri.fromFile(file))
        val staged = backup.stage(Uri.fromFile(file))
        repo.moveToTomorrow(first.id,day)
        backup.restore(staged)
        assertTrue(repo.pendingMoves.value.isEmpty())
        assertEquals(tasks,repo.snapshot().items.single().checklist)
        assertEquals(first,repo.snapshot().items.single())
        assertEquals(tasks, ChecklistCodec.decode(ChecklistCodec.encode(tasks)))
        try { repo.saveItem(event().copy(checklist=listOf(ChecklistEntry(text=" ")))); fail("Expected blank task rejection") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun olderBackupDefaultsToEmptyChecklistAndMalformedTasksAreRejected() = runBlocking {
        repo.saveItem(event().copy(checklist=listOf(ChecklistEntry(text="Keep"))))
        val file = File(dir,"source.zip")
        backup.export(Uri.fromFile(file))
        val json = java.util.zip.ZipFile(file).use { zip -> org.json.JSONObject(zip.getInputStream(zip.getEntry("data.json")).reader().readText()) }
        assertEquals(com.example.itinerary.data.BackupManager.FORMAT_VERSION,json.getInt("formatVersion"))
        json.put("formatVersion",5)
        json.getJSONArray("items").getJSONObject(0).remove("checklist")
        fun write(): File {
            val out = File(dir,"legacy.zip")
            java.util.zip.ZipOutputStream(out.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("data.json")); zip.write(json.toString().toByteArray()); zip.closeEntry()
            }
            return out
        }
        backup.restore(backup.stage(Uri.fromFile(write())))
        assertTrue(repo.snapshot().items.single().checklist.isEmpty())
        val before = repo.snapshot()
        json.getJSONArray("items").getJSONObject(0).put("checklist",org.json.JSONArray("[{\"id\":\"bad\",\"text\":\" \",\"done\":false}]"))
        try { backup.stage(Uri.fromFile(write())); fail("Expected malformed task rejection") } catch (_: BackupException) { }
        assertEquals(before,repo.snapshot())
    }
    @Test fun sharingIncludesOnlyPublicEventSummaryAndFullDate() {
        val intent = com.example.itinerary.ui.eventShareIntent(context,"Dinner",day,LocalTime.of(23,30),120,"Main Street",TimeFormat.HOUR_24)
        assertEquals(android.content.Intent.ACTION_SEND,intent.action)
        assertEquals("text/plain",intent.type)
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)!!
        assertTrue(text.contains("Dinner"))
        assertTrue(text.contains("2000"))
        assertTrue(text.contains("23:30–01:30 (next day)"))
        assertTrue(text.endsWith("Main Street"))
        assertFalse(intent.hasExtra(android.content.Intent.EXTRA_STREAM))
        val allDay = com.example.itinerary.ui.eventShareIntent(context,"Day",day,null,null,"",TimeFormat.HOUR_12).getStringExtra(android.content.Intent.EXTRA_TEXT)!!
        assertTrue(allDay.endsWith("All day"))
    }
}
