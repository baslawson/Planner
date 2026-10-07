package com.example.itinerary

import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Bug hunt 18: a backup's files are read outside the repository lock (D1), a reminder doesn't wait on the lock for
 *  ever (A2), and a failed restore leaves no staged copy behind (D3). */
class BugHunt18DataTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val alarms = object : ReminderAlarms {
        override fun schedule(item: ItineraryItem, reminder: Reminder) {}
        override fun cancel(reminderId: Long) {}
    }
    private fun isolated(root: File, prefix: String) = object : ContextWrapper(base) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
    }
    private fun <T> withRepo(name: String, block: suspend (Repository, AttachmentStore, BackupManager, File) -> T) = runBlocking {
        val root = File(base.cacheDir, "hunt18-$name").apply { deleteRecursively(); mkdirs() }
        val prefix = "hunt18_${name}_"
        val context = isolated(root, prefix)
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            val store = AttachmentStore(context)
            val repo = Repository(db, store, alarms)
            block(repo, store, BackupManager(context, repo, store, SettingsRepository(context)), root)
        } finally {
            db.close(); root.deleteRecursively()
            listOf("settings", "backup_status").forEach { base.deleteSharedPreferences(prefix + it) }
        }
    }

    // R18-D1: while a backup reads its files, saves go ahead (the lock is free) and a file let go of meanwhile stays
    // until the backup is done with it, then goes.
    @Test fun aBackupsFilesOutliveTheirOwnerUntilItIsDone() = withRepo("pinned") { repo, store, _, _ ->
        val a = Attachment(98201, 0, "Pinned", "hunt18-pinned.txt", "text/plain")
        store.writableFileFor(a.fileName).writeText("Pinned A")
        val note = repo.saveNote(PlannerNote(title = "Pinned", attachments = listOf(a)), create = true)
        repo.withPinnedFiles({ snapshot -> snapshot to listOf(a.fileName) }) {
            withTimeout(5_000) {
                repo.saveNote(note.copy(attachments = emptyList()), create = false)
                repo.releaseTaskFiles(listOf(a.fileName))
            }
            assertTrue("a file the backup still reads was deleted", store.fileFor(a.fileName).exists())
        }
        assertFalse("the file let go of during the backup was never deleted", store.fileFor(a.fileName).exists())
    }

    // R18-D1: the export still writes every file into the zip, and a restore brings them back.
    @Test fun anExportStillCarriesItsFiles() = withRepo("export") { repo, store, backup, root ->
        val a = Attachment(98202, 0, "Exported", "hunt18-export.txt", "text/plain")
        store.writableFileFor(a.fileName).writeText("Exported A")
        repo.saveNote(PlannerNote(title = "Exported", attachments = listOf(a)), create = true)
        val zip = File(root, "backup.zip")
        backup.export(Uri.fromFile(zip))
        java.util.zip.ZipFile(zip).use { z ->
            assertEquals("Exported A", z.getInputStream(z.getEntry("attachments/${a.fileName}")).readBytes().toString(Charsets.UTF_8))
        }
        assertTrue(File(root, "cache").listFiles().orEmpty().none { it.name.startsWith("backup-files-") })
    }

    // R18-A2: a reminder waits for a change in progress only so long, then is still shown.
    @Test fun aReminderIsShownEvenWhileTheLockIsHeld() = withRepo("delivery") { repo, _, _, _ ->
        val at = System.currentTimeMillis() + 1_000
        val task = PlannerTask(title = "Delivered", reminderAt = at).also { repo.saveTask(it) }
        delay(1_200)
        val release = CompletableDeferred<Unit>()
        val held = CompletableDeferred<Unit>()
        val holder = CoroutineScope(Dispatchers.IO).launch { repo.withSnapshotFiles { held.complete(Unit); release.await() } }
        held.await()
        var shown = false
        val started = System.currentTimeMillis()
        withTimeout(DELIVERY_LOCK_WAIT_MS + 5_000) { repo.deliverTaskReminder(task.id, at) { shown = true } }
        assertTrue("the reminder wasn't shown while the lock was held", shown)
        assertTrue(System.currentTimeMillis() - started >= DELIVERY_LOCK_WAIT_MS - 200)
        release.complete(Unit); holder.join()
    }

    // R18-S1: version 34 upgrades to 35 (a calendar's note) keeping what's there.
    @Test fun version34UpgradesWithTheCalendarNote() = runBlocking {
        val name = "hunt18-upgrade.db"
        base.deleteDatabase(name)
        try {
            var db = Room.databaseBuilder(base, AppDatabase::class.java, name).build()
            val task = PlannerTask(title = "Kept through the upgrade")
            db.taskDao().insert(task); db.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(base.getDatabasePath(name).path, null, 0).use { raw ->
                raw.execSQL("ALTER TABLE calendar_sources DROP COLUMN note")
                listOf("reminders", "tasks", "notes").forEach { raw.execSQL("ALTER TABLE $it DROP COLUMN ringSeconds") } // added in version 36
                raw.execSQL("DELETE FROM room_master_table"); raw.version = 34
            }
            db = Room.databaseBuilder(base, AppDatabase::class.java, name).addMigrations(*ALL_MIGRATIONS).build()
            assertEquals(task, db.taskDao().all().single())
            val columns = db.openHelper.writableDatabase.query("PRAGMA table_info(calendar_sources)").use { c ->
                generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("name")) else null }.toList() }
            assertTrue("no note column after the upgrade: $columns", "note" in columns)
            db.close()
            // Reopened as an ordinary version 35 database (Room checks the table matches what it expects).
            db = Room.databaseBuilder(base, AppDatabase::class.java, name).build()
            assertEquals(task, db.taskDao().all().single()); db.close()
        } finally { base.deleteDatabase(name) }
    }

    // R18-D3: a restore that fails still deletes its staged copy of the backup.
    @Test fun aFailedRestoreDeletesItsStagedCopy() = withRepo("staged") { repo, store, backup, root ->
        val a = Attachment(98203, 0, "Staged", "hunt18-staged.txt", "text/plain")
        store.writableFileFor(a.fileName).writeText("Staged A")
        repo.saveNote(PlannerNote(title = "Staged", attachments = listOf(a)), create = true)
        val zip = File(root, "backup.zip")
        backup.export(Uri.fromFile(zip))
        val staged = backup.stage(Uri.fromFile(zip))
        staged.file.writeText("not a zip any more")
        assertThrows(BackupException::class.java) { runBlocking { backup.restore(staged) } }
        assertFalse("a failed restore left its staged copy", staged.file.exists())
    }
}
