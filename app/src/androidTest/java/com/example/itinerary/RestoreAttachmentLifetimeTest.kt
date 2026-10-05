package com.example.itinerary

import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Restores must own attachment files through replacement, including waits for sync. */
class RestoreAttachmentLifetimeTest {
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

    @Test fun restoreRecreatesFilesRemovedWhileWaitingForNotesSync() = runBlocking {
        val root = File(base.cacheDir, "restore-lifetime-sync").apply { deleteRecursively(); mkdirs() }
        val prefix = "restore_lifetime_sync_"
        val context = isolated(root, prefix)
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            val store = AttachmentStore(context)
            val repo = Repository(db, store, alarms)
            val notes = NoteSync(db, NextcloudAccountStore(context), NotesApi(OkHttpClient()), repo.asNoteStore(), NoteSync.prefs(context))
            val backup = BackupManager(context, repo, store, SettingsRepository(context), notes = notes)
            val a = Attachment(98101, 0, "Original", "restore-a.txt", "text/plain")
            val b = Attachment(98102, 0, "Second", "restore-b.txt", "text/plain")
            store.writableFileFor(a.fileName).writeText("Original A")
            store.writableFileFor(b.fileName).writeText("Original B")
            val note = repo.saveNote(PlannerNote(title = "Restore lifetime", attachments = listOf(a, b)), create = true)
            val zip = File(root, "backup.zip")
            backup.export(Uri.fromFile(zip))
            // No-overlap control checks the archive and real byte streams, not just attachment records.
            repo.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList()))
            backup.restore(backup.stage(Uri.fromFile(zip)))
            assertEquals("Original A", store.fileFor(a.fileName).readText())
            assertEquals("Original B", store.fileFor(b.fileName).readText())
            repo.saveNote(note.copy(attachments = listOf(a)), create = false)
            repo.releaseTaskFiles(listOf(b.fileName))
            val staged = backup.stage(Uri.fromFile(zip))
            lateinit var restoring: Deferred<Unit>
            notes.paused {
                restoring = async(Dispatchers.IO) { backup.restore(staged) }
                // Give the actual file phase a chance to run; the old implementation copied B before waiting.
                delay(250)
                assertFalse("Restore must acquire the Notes pause before installing any files", store.fileFor(b.fileName).exists())
                assertFalse(restoring.isCompleted)
                repo.saveNote(note.copy(attachments = emptyList()), create = false)
                repo.releaseTaskFiles(listOf(a.fileName))
                assertFalse("Normal cleanup removed the last current owner's file", store.fileFor(a.fileName).exists())
            }
            withTimeout(10_000) { restoring.await() }
            assertEquals(setOf(a.fileName, b.fileName), repo.allNotes().single().attachments.map { it.fileName }.toSet())
            assertEquals("Original A", store.fileFor(a.fileName).readText())
            assertEquals("Original B", store.fileFor(b.fileName).readText())
        } finally {
            db.close(); root.deleteRecursively()
            listOf("settings", "backup_status", "note_sync").forEach { base.deleteSharedPreferences(prefix + it) }
        }
    }

    // S16-1: a restore cut short (Planner ended mid-copy) left the file half written under its own name: restoring the
    // same backup again writes it whole, and a copy never takes the name until it is complete.
    @Test fun aHalfWrittenFileFromAnInterruptedRestoreIsWrittenAgain() = runBlocking {
        val root = File(base.cacheDir, "restore-interrupted").apply { deleteRecursively(); mkdirs() }
        val prefix = "restore_interrupted_"
        val context = isolated(root, prefix)
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            val store = AttachmentStore(context)
            val repo = Repository(db, store, alarms)
            val backup = BackupManager(context, repo, store, SettingsRepository(context))
            val photo = Attachment(98201, 0, "Photo", "restore-half.bin", "application/octet-stream")
            val bytes = ByteArray(200_000) { (it * 31 % 251).toByte() }
            store.writableFileFor(photo.fileName).writeBytes(bytes)
            repo.saveNote(PlannerNote(title = "Interrupted restore", attachments = listOf(photo)), create = true)
            val zip = File(root, "backup.zip")
            backup.export(Uri.fromFile(zip))
            repo.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList()))
            // What the process left when it ended mid-copy.
            store.writableFileFor(photo.fileName).writeBytes(bytes.copyOf(bytes.size / 2))
            backup.restore(backup.stage(Uri.fromFile(zip)))
            assertArrayEquals("The restored file is still the half-written one", bytes, store.fileFor(photo.fileName).readBytes())
            assertTrue(store.fileFor(photo.fileName).parentFile!!.listFiles()!!.none { it.name.endsWith(".part") })
        } finally {
            db.close(); root.deleteRecursively()
            listOf("settings", "backup_status").forEach { base.deleteSharedPreferences(prefix + it) }
        }
    }

    @Test fun normalCleanupWaitsForInstallationAndThenSeesRestoredOwnership() = runBlocking {
        val root = File(base.cacheDir, "restore-lifetime-lease").apply { deleteRecursively(); mkdirs() }
        val context = isolated(root, "restore_lifetime_lease_")
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        val installed = CompletableDeferred<Unit>()
        val finishCopy = CompletableDeferred<Unit>()
        try {
            val store = AttachmentStore(context)
            val repo = Repository(db, store, alarms)
            val attachment = Attachment(98103, 0, "Leased", "restore-leased.txt", "text/plain")
            val data = DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList(),
                notes = listOf(PlannerNote(title = "Restored owner", attachments = listOf(attachment))))
            val restoring = async {
                repo.restoreWithFiles(data, {
                    store.writableFileFor(attachment.fileName).writeText("Leased bytes")
                    installed.complete(Unit)
                    finishCopy.await()
                }, { store.fileFor(attachment.fileName).delete() })
            }
            withTimeout(10_000) { installed.await() }
            // UNDISPATCHED reaches the mutation mutex immediately; it cannot wait on this callback itself.
            val cleanup = async(start = CoroutineStart.UNDISPATCHED) { repo.releaseTaskFiles(listOf(attachment.fileName)) }
            assertFalse("Cleanup must be suspended behind restore's mutation ownership", cleanup.isCompleted)
            assertEquals("Leased bytes", store.fileFor(attachment.fileName).readText())
            finishCopy.complete(Unit)
            withTimeout(10_000) { restoring.await(); cleanup.await() }
            assertEquals("Leased bytes", store.fileFor(attachment.fileName).readText())
            assertEquals(attachment.fileName, repo.allNotes().single().attachments.single().fileName)
        } finally {
            finishCopy.complete(Unit)
            db.close(); root.deleteRecursively()
        }
    }

    @Test fun cancellationWhileWaitingDoesNotInstallFiles() = runBlocking {
        val root = File(base.cacheDir, "restore-lifetime-cancel").apply { deleteRecursively(); mkdirs() }
        val context = isolated(root, "restore_lifetime_cancel_")
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        val owned = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val store = AttachmentStore(context)
            val repo = Repository(db, store, alarms)
            val held = async { repo.withSnapshotFiles { owned.complete(Unit); release.await() } }
            withTimeout(10_000) { owned.await() }
            var installed = false
            var rolledBack = false
            val restore = async(start = CoroutineStart.UNDISPATCHED) {
                repo.restoreWithFiles(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList()),
                    { installed = true }, { rolledBack = true })
            }
            assertFalse(restore.isCompleted)
            restore.cancelAndJoin()
            assertFalse(installed)
            assertFalse(rolledBack)
            release.complete(Unit)
            withTimeout(10_000) { held.await() }
        } finally {
            release.complete(Unit)
            db.close(); root.deleteRecursively()
        }
    }

    @Test fun postCommitMaintenanceFailureKeepsFilesAndFinishesRestoreSteps() = runBlocking {
        val root = File(base.cacheDir, "restore-lifetime-committed").apply { deleteRecursively(); mkdirs() }
        val context = isolated(root, "restore_lifetime_committed_")
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            var attemptedSchedule = false
            val failingAlarms = object : ReminderAlarms {
                override fun schedule(item: ItineraryItem, reminder: Reminder) {}
                override fun cancel(reminderId: Long) {}
                override fun scheduleNote(note: PlannerNote) {
                    attemptedSchedule = true
                    // Ordinary maintenance exceptions are caught internally; exercise the escaping failure boundary.
                    throw AssertionError("Injected post-commit alarm maintenance failure")
                }
            }
            val store = AttachmentStore(context)
            val repo = Repository(db, store, failingAlarms)
            val attachment = Attachment(98104, 0, "Committed", "restore-committed.txt", "text/plain")
            val note = PlannerNote(title = "Committed owner", attachments = listOf(attachment),
                reminderAt = System.currentTimeMillis() + 86_400_000)
            val data = DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), notes = listOf(note))
            var rolledBack = false
            var finishedRestoreSteps = false
            try {
                repo.restoreWithFiles(data,
                    { store.writableFileFor(attachment.fileName).writeText("Committed bytes") },
                    { rolledBack = true; store.fileFor(attachment.fileName).delete() },
                    { finishedRestoreSteps = true })
                fail("Injected maintenance failure must be reported")
            } catch (e: BackupException) {
                assertEquals("The backup data was restored, but some cleanup did not finish.", e.message)
            }
            assertTrue(attemptedSchedule)
            assertFalse("A committed attachment must never be rolled back", rolledBack)
            assertTrue("Settings/sync restore steps must still run", finishedRestoreSteps)
            assertEquals(note, repo.allNotes().single())
            assertEquals("Committed bytes", store.fileFor(attachment.fileName).readText())
        } finally { db.close(); root.deleteRecursively() }
    }

    @Test fun failedReplacementRollsBackOnlyNewFiles() = runBlocking {
        val root = File(base.cacheDir, "restore-lifetime-rollback").apply { deleteRecursively(); mkdirs() }
        val context = isolated(root, "restore_lifetime_rollback_")
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            val store = AttachmentStore(context)
            val repo = Repository(db, store, alarms)
            val original = repo.saveNote(PlannerNote(title = "Original"), create = true)
            val existing = store.writableFileFor("restore-existing.txt").apply { writeText("Keep original bytes") }
            val created = store.writableFileFor("restore-new.txt")
            // Duplicate IDs fail validation before database replacement, after the file phase.
            val duplicate = PlannerNote(title = "Duplicate")
            val invalid = DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), notes = listOf(duplicate, duplicate))
            try {
                repo.restoreWithFiles(invalid, { created.writeText("New bytes") }, { created.delete() })
                fail("Invalid replacement must fail")
            } catch (_: IllegalArgumentException) { }
            assertFalse(created.exists())
            assertEquals("Keep original bytes", existing.readText())
            assertEquals(original, repo.allNotes().single())
        } finally { db.close(); root.deleteRecursively() }
    }
}
