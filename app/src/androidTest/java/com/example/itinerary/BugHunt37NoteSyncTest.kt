package com.example.itinerary

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File

// Bug hunt 37 N1: a note the Nextcloud Notes app can't read is listed with error: true, readonly: true and "Error: <Exception>"
// as its words. Sync leaves it alone on both sides: not taken, sent over, copied, brought in, nor deleted.
class BugHunt37NoteSyncTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val alarms = object : ReminderAlarms {
        override fun schedule(item: ItineraryItem, reminder: Reminder) {}
        override fun cancel(reminderId: Long) {}
    }

    // FakeNotes, with the notes in [broken] listed as the Notes app lists one it can't read.
    private class Unreadable(val inner: FakeNotes) : Dispatcher() {
        @Volatile var broken: Set<Long> = emptySet()
        override fun dispatch(request: RecordedRequest): MockResponse {
            val response = inner.dispatch(request)
            if (request.method != "GET" || request.requestUrl!!.encodedPath != "/index.php/apps/notes/api/v1/notes" || broken.isEmpty()) return response
            val list = JSONArray(response.getBody()?.readUtf8() ?: return response)
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                if (o.getLong("id") in broken) o.put("error", true).put("readonly", true).put("etag", "broken")
                    .put("content", "Error: OCA\\Notes\\Service\\NoteNotFoundException")
            }
            return response.setBody(list.toString())
        }
    }

    @Test fun aNoteNextcloudCantReadIsLeftAlone() = runBlocking {
        val base = context
        val name = "bug-hunt-37-notes"
        val dir = File(base.cacheDir, name).apply { deleteRecursively(); mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(dir, "nobackup").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
        }
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val server = MockWebServer()
        val fake = FakeNotes("qa", "qa-test-password")
        val unreadable = Unreadable(fake)
        server.dispatcher = unreadable
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.start()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val http = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build()
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        val prefs = base.getSharedPreferences("${name}_note_sync", 0).apply { edit().clear().commit() }
        val accounts = NextcloudAccountStore(isolated, "planner.nextcloud.$name")
        try {
            val repo = Repository(db, AttachmentStore(isolated), alarms)
            accounts.save(NextcloudAccount.create(server.url("/").toString(), "qa", "qa-test-password"))
            val sync = NoteSync(db, accounts, NotesApi(http), repo.asNoteStore(), prefs,
                pendingDeleted = { repo.pendingDeletions.value.flatMap { it.notes }.mapTo(HashSet()) { it.id } })
            sync.setEnabled(true)
            fun local() = runBlocking { repo.allNotes() }
            fun note(label: String) = local().single { Notes.label(it) == label }
            fun noErrorText() = assertTrue("A note took Nextcloud's error text", local().none { it.content.startsWith("Error:") })
            fun noCopies() = assertTrue("A copy was made", local().none { it.title.endsWith("(my copy)") || it.title.endsWith("(Nextcloud copy)") })

            val shopping = fake.add("Shopping", "milk\neggs")
            assertTrue(sync.sync())
            assertEquals("milk\neggs", note("Shopping").content)

            // Unreadable there: Planner's note keeps its words, and its link.
            unreadable.broken = setOf(shopping)
            assertTrue(sync.sync())
            assertNull(sync.state.value.error)
            assertEquals("milk\neggs", note("Shopping").content)
            noErrorText(); noCopies()
            assertEquals(1, sync.state.value.skipped)
            assertEquals(listOf(shopping), db.sentNoteDao().all().map { it.remoteId })

            // Edited here: not sent over it, and no "(my copy)".
            repo.saveNote(note("Shopping").copy(content = "milk\neggs\nbread"), create = false)
            // Nor by the half-second send of an edit, which leaves it to a pass.
            assertFalse(sync.sendEdits())
            assertTrue(fake.requests.none { it == "PUT /index.php/apps/notes/api/v1/notes/$shopping" })
            assertTrue(sync.sync())
            assertTrue(fake.requests.none { it == "PUT /index.php/apps/notes/api/v1/notes/$shopping" })
            assertEquals("milk\neggs", fake.notes[shopping]!!.content)
            assertEquals("milk\neggs\nbread", note("Shopping").content)
            noCopies()

            // A new note that can't be read: not brought in (nor linked).
            val diary = fake.add("Diary", "Dear diary")
            unreadable.broken = setOf(shopping, diary)
            assertTrue(sync.sync())
            assertTrue(local().none { Notes.label(it) == "Diary" })
            noErrorText()
            assertTrue(db.sentNoteDao().all().none { it.remoteId == diary })
            assertEquals(2, sync.state.value.skipped)

            // Deleted here while unreadable there: not deleted there yet, the link kept.
            repo.deleteNote(note("Shopping").id); repo.finishDeletion(repo.pendingDeletions.value.single().token)
            assertTrue(sync.sync())
            assertTrue(fake.requests.none { it == "DELETE /index.php/apps/notes/api/v1/notes/$shopping" })
            assertNotNull(fake.notes[shopping])
            assertEquals(listOf(shopping), db.sentNoteDao().all().map { it.remoteId })

            // Readable again: synced as usual.
            unreadable.broken = emptySet()
            assertTrue(sync.sync())
            assertNull(fake.notes[shopping])
            assertEquals("Dear diary", note("Diary").content)
            assertEquals(0, sync.state.value.skipped)
            assertNull(sync.state.value.error)
            // And an edit to a readable note is sent straight away again.
            repo.saveNote(note("Diary").copy(content = "Dear diary,\nhello"), create = false)
            assertTrue(sync.sendEdits())
            assertEquals("Dear diary,\nhello", fake.notes.values.single { it.title == "Diary" }.content)
        } finally {
            db.close(); server.shutdown(); accounts.clear(); dir.deleteRecursively()
            base.deleteSharedPreferences("${name}_note_sync")
        }
    }
}
