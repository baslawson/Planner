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
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** All files/settings/database are isolated. Past dates and high reminder IDs cannot touch user alarms. */
class EventEnhancementsRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository
    private lateinit var store: AttachmentStore
    private lateinit var context: Context
    private lateinit var sandbox: File
    private lateinit var backup: BackupManager
    private val day = LocalDate.of(2000, 1, 31)
    private fun event() = ItineraryItem(tripId = 0, date = day, startTime = LocalTime.NOON, title = "Monthly event")

    @Before fun setup() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        sandbox = File(base.cacheDir, "enhancements-tests").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(sandbox, "files").apply { mkdirs() }
            override fun getCacheDir() = File(sandbox, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("enhancements_test_$name", mode)
        }
        db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        db.openHelper.writableDatabase.execSQL("INSERT INTO sqlite_sequence(name, seq) VALUES ('reminders', 900000000)")
        store = AttachmentStore(context)
        repo = Repository(db, store, ReminderScheduler(context))
        backup = BackupManager(context, repo, store, SettingsRepository(context))
    }
    @After fun close() {
        db.close()
        sandbox.deleteRecursively()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun attachment(): Attachment {
        store.writableFileFor("ticket.txt").writeText("original ticket bytes")
        return Attachment(itemId = 0, name = "Ticket", fileName = "ticket.txt", mimeType = "text/plain")
    }
    private fun reminder() = Reminder(itemId = 0, amount = 5, unit = ReminderUnit.MINUTES)

    @Test fun bulkDeletionKeepsOtherOccurrencesAndUndoRestoresEverything() = runBlocking {
        repo.saveItem(event(), added = listOf(attachment()), addedReminders = listOf(reminder()),
            options = EventSaveOptions(RepeatRule.DAILY, 3))
        repo.saveItem(event().copy(title = "Paid bill", category = "Bills", paid = true, billAmountMinor = 1234))
        val before = repo.snapshot()
        val ids = setOf(before.items.first().id, before.items.last().id)
        repo.deleteEventsWithUndo(ids + Long.MAX_VALUE)
        assertEquals(before.items.filterNot { it.id in ids }, repo.snapshot().items)
        assertEquals(1, repo.pendingDeletions.value.size)
        assertEquals(ids, repo.pendingDeletions.value.single().items.map { it.id }.toSet())
        assertTrue(store.fileFor("ticket.txt").exists())
        repo.undoDeletion(repo.pendingDeletions.value.single().token)
        assertEquals(before, repo.snapshot())
        repo.deleteEventsWithUndo(ids)
        repo.finishDeletion(repo.pendingDeletions.value.single().token)
        assertTrue(store.fileFor("ticket.txt").exists())
        repo.deleteEventsWithUndo(repo.snapshot().items.mapTo(hashSetOf()) { it.id })
        repo.finishDeletion(repo.pendingDeletions.value.single().token)
        assertTrue(store.fileFor("ticket.txt").exists()) // Retained for Recently deleted.
        repo.snapshot().deleted.forEach { repo.permanentlyDelete(it.id) }
        assertFalse(store.fileFor("ticket.txt").exists())
    }

    @Test fun bulkDeletionRollsBackAllRowsWhenOneFails() = runBlocking {
        repo.saveItem(event(), added = listOf(attachment()), addedReminders = listOf(reminder()),
            options = EventSaveOptions(RepeatRule.DAILY, 3))
        val before = repo.snapshot()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_bulk BEFORE DELETE ON items WHEN OLD.id = ${before.items[1].id} BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        try {
            repo.deleteEventsWithUndo(before.items.mapTo(hashSetOf()) { it.id })
            fail("Expected deletion failure")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(before, repo.snapshot())
        assertTrue(repo.pendingDeletions.value.isEmpty())
        assertTrue(store.fileFor("ticket.txt").exists())
    }

    @Test fun repeatsEditOneThenAllAndDuplicateWithChildren() = runBlocking {
        repo.saveItem(event(), added = listOf(attachment()), addedReminders = listOf(reminder()),
            options = EventSaveOptions(RepeatRule.MONTHLY, 3))
        val original = repo.snapshot()
        assertEquals(listOf(day, LocalDate.of(2000, 2, 29), LocalDate.of(2000, 3, 31)), original.items.map { it.date })
        assertEquals(1, original.items.map { it.seriesId }.distinct().size)
        assertNotNull(original.items.first().seriesId)
        assertEquals(3, original.reminders.map { it.id }.distinct().size)
        assertEquals(3, original.attachments.size)
        val middle = original.items[1]
        repo.saveItem(middle.copy(title = "Just one"))
        assertEquals(listOf("Monthly event", "Just one", "Monthly event"), repo.snapshot().items.map { it.title })
        repo.saveItem(middle.copy(title = "Whole series", date = middle.date.plusDays(1)),
            options = EventSaveOptions(entireSeries = true))
        val moved = repo.snapshot()
        assertEquals(original.items.map { it.date.plusDays(1) }, moved.items.map { it.date })
        assertTrue(moved.items.all { it.title == "Whole series" })
        assertEquals(3, moved.reminders.size)
        assertEquals(3, moved.attachments.size)
        val source = moved.items.first()
        repo.saveItem(source.copy(id = 0, seriesId = null, repeatRule = "NONE", title = "Copy"),
            added = moved.attachments.filter { it.itemId == source.id },
            addedReminders = moved.reminders.filter { it.itemId == source.id })
        val copied = repo.snapshot()
        val copy = copied.items.single { it.title == "Copy" }
        assertNull(copy.seriesId)
        assertEquals(1, copied.attachments.count { it.itemId == copy.id })
        assertEquals(1, copied.reminders.count { it.itemId == copy.id })
        repo.deleteWithUndo(source, true)
        assertEquals(listOf(copy), repo.snapshot().items)
        repo.finishDeletion(repo.pendingDeletions.value.single().token)
        assertEquals("original ticket bytes", store.fileFor("ticket.txt").readText())
        repo.deleteItem(copy)
        assertTrue(store.fileFor("ticket.txt").exists()) // Retained for Recently deleted.
        repo.snapshot().deleted.forEach { repo.permanentlyDelete(it.id) }
        assertFalse(store.fileFor("ticket.txt").exists())
    }

    @Test fun undoRestoresExactIdsAttachmentsRemindersAndHandlesMultipleDeletes() = runBlocking {
        repo.saveItem(event(), added = listOf(attachment()), addedReminders = listOf(reminder()),
            options = EventSaveOptions(RepeatRule.WEEKLY, 2))
        val before = repo.snapshot()
        repo.deleteWithUndo(before.items[0])
        repo.deleteWithUndo(before.items[1])
        assertTrue(repo.snapshot().items.isEmpty())
        val tokens = repo.pendingDeletions.value.map { it.token }
        repo.undoDeletion(tokens[1])
        repo.undoDeletion(tokens[0])
        assertEquals(before, repo.snapshot())
        assertTrue(repo.pendingDeletions.value.isEmpty())
        repo.deleteWithUndo(before.items.first(), true)
        repo.finishDeletion(repo.pendingDeletions.value.single().token)
        assertTrue(repo.snapshot().items.isEmpty())
        assertTrue(store.fileFor("ticket.txt").exists()) // Retained for Recently deleted.
        repo.snapshot().deleted.forEach { repo.permanentlyDelete(it.id) }
        assertFalse(store.fileFor("ticket.txt").exists())
    }

    @Test fun removingSharedAttachmentRetainsOtherOccurrencesAndPendingUndo() = runBlocking {
        repo.saveItem(event(), added = listOf(attachment()), options = EventSaveOptions(RepeatRule.DAILY, 2))
        val before = repo.snapshot()
        repo.deleteWithUndo(before.items[0])
        repo.saveItem(before.items[1], removed = before.attachments.filter { it.itemId == before.items[1].id })
        assertTrue(store.fileFor("ticket.txt").exists())
        repo.undoDeletion(repo.pendingDeletions.value.single().token)
        assertEquals("original ticket bytes", store.fileFor("ticket.txt").readText())
        assertEquals(1, repo.snapshot().attachments.size)
    }

    @Test fun seriesInsertAndSeriesEditRollBackOnFailure() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_second BEFORE INSERT ON items WHEN (SELECT COUNT(*) FROM items) = 1 BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        try {
            repo.saveItem(event(), options = EventSaveOptions(RepeatRule.DAILY, 3))
            fail("Expected failure")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertTrue(repo.snapshot().items.isEmpty())
        assertTrue(repo.snapshot().trips.isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_second")
        repo.saveItem(event(), options = EventSaveOptions(RepeatRule.DAILY, 3))
        val before = repo.snapshot()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_update BEFORE UPDATE ON items WHEN OLD.id = ${before.items[1].id} BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        try {
            repo.saveItem(before.items.first().copy(title = "Changed"), options = EventSaveOptions(entireSeries = true))
            fail("Expected failure")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(before, repo.snapshot())
    }

    @Test fun backupRoundTripPreservesSeriesAndLegacyBackupStillImports() = runBlocking {
        repo.saveItem(event(), added = listOf(attachment()), addedReminders = listOf(reminder()),
            options = EventSaveOptions(RepeatRule.MONTHLY, 3))
        val before = repo.snapshot()
        val zip = File(sandbox, "backup.zip")
        backup.export(Uri.fromFile(zip))
        repo.deleteWithUndo(before.items.first(), true)
        backup.restore(backup.stage(Uri.fromFile(zip)))
        assertEquals(before, repo.snapshot())
        assertTrue(repo.pendingDeletions.value.isEmpty())
        assertEquals("original ticket bytes", store.fileFor("ticket.txt").readText())
        val json = ZipFile(zip).use { archive -> JSONObject(archive.getInputStream(archive.getEntry("data.json")).bufferedReader().readText()) }
        assertEquals(com.example.itinerary.data.BackupManager.FORMAT_VERSION, json.getInt("formatVersion"))
        json.put("formatVersion", 3)
        val items = json.getJSONArray("items")
        for (i in 0 until items.length()) { items.getJSONObject(i).remove("seriesId"); items.getJSONObject(i).remove("repeatRule") }
        val legacy = File(sandbox, "legacy.zip")
        ZipOutputStream(legacy.outputStream()).use {
            it.putNextEntry(ZipEntry("data.json")); it.write(json.toString().toByteArray()); it.closeEntry()
            it.putNextEntry(ZipEntry("attachments/ticket.txt")); it.write("original ticket bytes".toByteArray()); it.closeEntry()
        }
        backup.restore(backup.stage(Uri.fromFile(legacy)))
        assertTrue(repo.snapshot().items.all { it.seriesId == null && it.repeatRule == "NONE" })
        assertEquals(before.items.map { it.title }, repo.snapshot().items.map { it.title })
    }
}
