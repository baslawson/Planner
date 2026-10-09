package com.example.itinerary

import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Races reproduced during the full bug hunt; actual repository, sync and backup methods. */
class SyncBackupRaceTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val alarms = object : ReminderAlarms {
        override fun schedule(item: ItineraryItem, reminder: Reminder) {}
        override fun cancel(reminderId: Long) {}
    }
    private fun isolated(root: File) = object : ContextWrapper(base) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "nobackup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("hunt_sb_$name", mode)
    }

    // As Nextcloud Notes' own app: a note deleted in Planner (its Undo gone) is deleted there with the Notes API, also when
    // it was changed there just before (the last change wins); the link goes and nothing comes back.
    @Test fun localDeletionDeletesThereAsNextcloudNotesDoes() = runBlocking {
        val root = File(base.cacheDir, "hunt-sb-delete").apply { deleteRecursively(); mkdirs() }
        val context = isolated(root)
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val http = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        val fake = FakeNotes("qa", "qa-test-password")
        server.dispatcher = fake // Notes DELETE ignores If-Match; only DAV provides the conditional operation.
        server.start()
        val alias = "planner.nextcloud.hunt-sb-delete"
        val accounts = NextcloudAccountStore(context, alias)
        val prefs = context.getSharedPreferences("note_sync", 0).apply { edit().clear().commit() }
        try {
            val repo = Repository(db, AttachmentStore(context), alarms)
            accounts.save(NextcloudAccount.create(server.url("/").toString(), "qa", "qa-test-password"))
            val sync = NoteSync(db, accounts, NotesApi(http), repo.asNoteStore(), prefs,
                pendingDeleted = { repo.pendingDeletions.value.flatMap { it.notes }.mapTo(HashSet()) { it.id } })
            val id = fake.add("Remote", "original words")
            sync.setEnabled(true)
            assertTrue(sync.sync())
            val local = repo.allNotes().single()
            repo.deleteNote(local.id)
            repo.pendingDeletions.value.toList().forEach { repo.finishDeletion(it.token) }
            fake.edit(id) { it.copy(content = "new remote words saved before the delete went") }
            assertTrue(sync.sync())
            assertFalse("Deleted there too", fake.notes.containsKey(id))
            assertTrue("With the Notes API", fake.requests.any { it.startsWith("DELETE /index.php/apps/notes/api/v1/notes/$id") })
            assertTrue(db.sentNoteDao().all().isEmpty())
            assertTrue(sync.sync())
            assertTrue("Nothing comes back", repo.allNotes().isEmpty())
            assertEquals(0, sync.state.value.keptRemote)
        } finally {
            db.close(); server.shutdown(); root.deleteRecursively()
            base.deleteSharedPreferences("hunt_sb_note_sync")
            base.deleteSharedPreferences("hunt_sb_nextcloud")
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    @Test fun stagedBackupsKeepIndependentAttachmentBytes() = runBlocking {
        val root = File(base.cacheDir, "hunt-sb-stage").apply { deleteRecursively(); mkdirs() }
        val context = isolated(root)
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            val store = AttachmentStore(context)
            val repo = Repository(db, store, alarms)
            val backup = BackupManager(context, repo, store, SettingsRepository(context))
            val attachment = Attachment(99001, 0, "Photo", "hunt-photo.txt", "text/plain")
            store.writableFileFor(attachment.fileName).writeText("A original file")
            repo.saveNote(PlannerNote(title = "Metadata A", content = "A", attachments = listOf(attachment)), create = true)
            val a = File(root, "A.zip")
            val b = File(root, "B.zip")
            backup.export(Uri.fromFile(a))
            // A valid second archive reusing the attachment's original name but with other bytes.
            ZipFile(a).use { zip -> ZipOutputStream(b.outputStream()).use { out ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    out.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "attachments/hunt-photo.txt") out.write("B replacement file".toByteArray())
                    else zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            } }
            repo.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList()))
            assertFalse(store.fileFor(attachment.fileName).exists())
            val stagedA = backup.stage(Uri.fromFile(a))
            val stagedB = backup.stage(Uri.fromFile(b))
            backup.discard(stagedB)
            backup.restore(stagedA)
            assertEquals("Metadata A", repo.allNotes().single().title)
            assertEquals("Restoring A must use A's own file", "A original file", store.fileFor(attachment.fileName).readText())

            backup.discard(stagedB)
        } finally {
            db.close(); root.deleteRecursively()
            listOf("settings", "backup_status").forEach { base.deleteSharedPreferences("hunt_sb_$it") }
        }
    }
    @Test fun snapshotFileLeaseExcludesLastReferenceCleanup() = runBlocking {
        val root = File(base.cacheDir, "hunt-sb-lease").apply { deleteRecursively(); mkdirs() }
        val context = isolated(root)
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            val store = AttachmentStore(context)
            val repo = Repository(db, store, alarms)
            val file = Attachment(99002, 0, "Photo", "lease-photo.txt", "text/plain")
            store.writableFileFor(file.fileName).writeText("leased original")
            val note = repo.saveNote(PlannerNote(title = "Lease", attachments = listOf(file)), create = true)
            lateinit var cleanup: kotlinx.coroutines.Deferred<Unit>
            val captured = repo.withSnapshotFiles { snapshot ->
                assertEquals(note.id, snapshot.notes.single().id)
                cleanup = async(start = CoroutineStart.UNDISPATCHED) {
                    repo.saveNote(note.copy(attachments = emptyList()), create = false)
                    repo.releaseTaskFiles(listOf(file.fileName))
                }
                assertFalse("Mutation must wait while snapshot files are being copied", cleanup.isCompleted)
                store.fileFor(file.fileName).readText()
            }
            cleanup.await()
            assertFalse(store.fileFor(file.fileName).exists())
            assertEquals("leased original", captured)
        } finally { db.close(); root.deleteRecursively() }
    }

    @Test fun staleAccountUpdatesCannotReconnectOrReplaceNewLogin() {
        val root = File(base.cacheDir, "hunt-sb-account").apply { deleteRecursively(); mkdirs() }
        val context = isolated(root)
        val alias = "planner.nextcloud.hunt-sb-account"
        val a = NextcloudAccount.create("https://example.com", "old", "test-password")
        val b = NextcloudAccount.create("https://example.com", "new", "other-test-password")
        val first = NextcloudAccountStore(context, alias)
        val second = NextcloudAccountStore(context, alias)
        try {
            first.save(a)
            second.clear()
            assertNull(first.updateIfCurrent(a) { it.backedUpAt("2026-10-05T00:00:00Z") })
            assertNull(second.load())
            second.save(b)
            assertNull(first.updateIfCurrent(a) { it.withFolder("Old folder") })
            assertEquals("new", second.load()!!.username)
            val newFolder = second.updateIfCurrent(b) { it.withFolder("New folder") }!!
            assertNull(first.updateIfCurrent(b) { it.backedUpAt("2026-10-05T00:00:00Z") })
            assertEquals("New folder", second.load()!!.folderPath)
            assertNotNull(first.updateIfCurrent(newFolder) { it.backedUpAt("2026-10-05T00:00:00Z") })
            assertEquals("2026-10-05T00:00:00Z", second.load()!!.lastBackup)
        } finally {
            first.clear(); root.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

}
