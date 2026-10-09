package com.example.itinerary

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.decodeCertificatePem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Notes sync against a real Nextcloud with the Notes app (the test server in C:\Users\bas\nextcloud-test, see TESTING.md):
 * Planner's own sync code on one side, the Notes API on the other, called as Nextcloud's web editor does (only the words,
 * with If-Match), so what is checked there is what the browser shows. Skipped unless run with the server's details:
 *   -e nextcloudUrl https://10.0.2.2:8443/ -e nextcloudUser qa -e nextcloudPassword … -e nextcloudCert <PEM, base64 of the file>
 * It deletes every note of that login first: only ever point it at a test server.
 */
class RealNextcloudNotesTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val alarms = object : ReminderAlarms {
        override fun schedule(item: ItineraryItem, reminder: Reminder) {}
        override fun cancel(reminderId: Long) {}
    }

    /** The Notes app as the browser uses it. */
    private class Browser(private val http: OkHttpClient, private val base: String, private val user: String, private val password: String) {
        private val json = "application/json; charset=utf-8".toMediaType()
        private fun call(r: Request.Builder): Pair<Int, String> = http.newCall(r.header("Authorization", Credentials.basic(user, password))
            .header("Accept", "application/json").build()).execute().use { it.code to it.body!!.string() }
        private fun url(id: Long? = null) = base + "index.php/apps/notes/api/v1/notes" + (id?.let { "/$it" } ?: "")
        fun list(): List<JSONObject> = JSONArray(call(Request.Builder().url(url())).second).let { a -> List(a.length()) { a.getJSONObject(it) } }
        fun get(id: Long): JSONObject? = call(Request.Builder().url(url(id))).let { (c, b) -> if (c == 200) JSONObject(b) else null }
        fun byTitle(title: String) = list().single { it.getString("title") == title }
        fun create(title: String, content: String): Long =
            JSONObject(call(Request.Builder().url(url()).post(JSONObject().put("title", title).put("content", content).toString().toRequestBody(json))).second).getLong("id")
        // As the web editor saves: only the words, with If-Match.
        fun type(id: Long, content: String) {
            val etag = get(id)!!.getString("etag")
            val (code, _) = call(Request.Builder().url(url(id)).header("If-Match", "\"$etag\"").put(JSONObject().put("content", content).toString().toRequestBody(json)))
            assertEquals("The browser's save", 200, code)
        }
        fun delete(id: Long) = call(Request.Builder().url(url(id)).delete()).first
        fun deleteAll() = list().forEach { delete(it.getLong("id")) }
    }

    @Test fun syncsBothWaysWithARealNextcloud() = runBlocking {
        val server = args.getString("nextcloudUrl"); val user = args.getString("nextcloudUser"); val password = args.getString("nextcloudPassword")
        val pem = args.getString("nextcloudCert")?.let { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) }
        assumeTrue("Needs a test Nextcloud (see the class comment)", server != null && user != null && password != null && pem != null)
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(pem!!.decodeCertificatePem()).build()
        val http = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build()
        val browser = Browser(http, server!!.trimEnd('/') + "/", user!!, password!!)
        browser.deleteAll()

        val dir = File(context.cacheDir, "real-nextcloud").apply { deleteRecursively(); mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(dir, "nobackup").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val prefs = context.getSharedPreferences("real_nextcloud_sync", 0).apply { edit().clear().commit() }
        val accounts = NextcloudAccountStore(isolated, "planner.nextcloud.real-test")
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            lateinit var sync: NoteSync
            var wired = false
            val repo = Repository(db, AttachmentStore(isolated), alarms, onChanged = { if (wired) sync.request() })
            accounts.save(NextcloudAccount.create(server, user, password))
            sync = NoteSync(db, accounts, NotesApi(http), repo.asNoteStore(), prefs,
                pendingDeleted = { repo.pendingDeletions.value.flatMap { it.notes }.mapTo(HashSet()) { it.id } }, scope = scope)
            fun local() = runBlocking { repo.allNotes() }
            fun mine(label: String) = local().single { Notes.label(it) == label }
            sync.setEnabled(true)

            // 1. First sync: Planner's note goes up, Nextcloud's comes in.
            repo.saveNote(PlannerNote(title = "Phone first", content = "from the phone"), create = true)
            val serverFirst = browser.create("Server first", "Server first\nfrom the browser")
            assertTrue(sync.sync()); assertNull(sync.state.value.error)
            assertEquals("from the phone", browser.byTitle("Phone first").getString("content"))
            assertEquals("Server first\nfrom the browser", mine("Server first").content)

            // 2. The reported problem: an edit in Planner reaches the note the browser shows, on its own (sendEdits),
            // with Planner's edit time.
            repo.saveNote(mine("Phone first").copy(content = "from the phone\nedited on the phone"), create = false)
            assertTrue(sync.sendEdits())
            val phoneFirst = browser.byTitle("Phone first")
            assertEquals("edited on the phone", phoneFirst.getString("content").lines().last())
            assertEquals(mine("Phone first").modified / 1000, phoneFirst.getLong("modified"))

            // 3. Edited in the browser: it comes in.
            browser.type(serverFirst, "Server first\nedited in the browser")
            assertTrue(sync.sync())
            assertEquals("Server first\nedited in the browser", mine("Server first").content)

            // 4. Edited on both sides: the newer edit wins. The browser's 2 s later: it comes in...
            repo.saveNote(mine("Server first").copy(content = "Server first\nolder phone edit"), create = false)
            Thread.sleep(2_500)
            browser.type(serverFirst, "Server first\nnewer browser edit")
            assertTrue(sync.sync())
            assertEquals("Server first\nnewer browser edit", mine("Server first").content)
            assertEquals("Server first\nnewer browser edit", browser.get(serverFirst)!!.getString("content"))
            // ...the phone's 2 s later: it goes up.
            browser.type(serverFirst, "Server first\nolder browser edit")
            Thread.sleep(2_500)
            repo.saveNote(mine("Server first").copy(content = "Server first\nnewer phone edit"), create = false)
            assertTrue(sync.sync())
            assertEquals("Server first\nnewer phone edit", browser.get(serverFirst)!!.getString("content"))
            assertTrue(local().none { it.title.contains("conflict") })

            // 5. As the app wires it: typing in Planner reaches the browser within seconds, without Sync now.
            wired = true
            repo.saveNote(mine("Phone first").copy(content = "from the phone\ntyped"), create = false)
            val end = android.os.SystemClock.uptimeMillis() + 10_000
            while (browser.byTitle("Phone first").getString("content") != "from the phone\ntyped" && android.os.SystemClock.uptimeMillis() < end) Thread.sleep(200)
            assertEquals("from the phone\ntyped", browser.byTitle("Phone first").getString("content"))
            wired = false
            Thread.sleep(1_000)

            // 6. A link an older version made to a duplicate there (Nextcloud names the second "Shopping (2)") is cleared once,
            // and the note paired by its title: its edit reaches "Shopping", not the duplicate.
            // As on the user's phone: one Planner note, linked to the duplicate, with both on Nextcloud.
            val shopping = browser.create("Shopping", "Shopping\nbread")
            val duplicate = browser.create("Shopping", "Shopping\nbread")
            assertEquals("Shopping (2)", browser.get(duplicate)!!.getString("title"))
            val account = accounts.load()!!
            val shoppingHere = repo.saveNote(PlannerNote(content = "Shopping\nbread"), create = true)
            db.sentNoteDao().put(NoteMapping.row(shoppingHere, CalendarSync.accountKey(account), NotesApi(http).list(account).single { it.id == duplicate }))
            prefs.edit().remove(NoteSync.KEY_RELINKED).commit()
            Thread.sleep(1_100)
            repo.saveNote(mine("Shopping").copy(content = "Shopping\nbread\nmilk"), create = false)
            assertTrue(sync.sync())
            assertEquals("Shopping\nbread\nmilk", browser.get(shopping)!!.getString("content"))
            assertEquals("Shopping\nbread", browser.get(duplicate)!!.getString("content"))
            assertEquals(shopping, db.sentNoteDao().all().single { it.noteId == shoppingHere.id }.remoteId)
            // The duplicate comes in as a note of its own, which the user can delete.
            assertEquals(1, local().count { it.content == "Shopping\nbread" })

            // 7. Deleted in Planner (its Undo gone): deleted there. Deleted in the browser: to Recently deleted here.
            repo.deleteNote(mine("Phone first").id); repo.finishDeletion(repo.pendingDeletions.value.single().token)
            assertTrue(sync.sync())
            assertTrue(browser.list().none { it.getString("title") == "Phone first" })
            assertEquals(200, browser.delete(serverFirst))
            assertTrue(sync.sync())
            assertTrue(local().none { Notes.label(it) == "Server first" })
            assertTrue(repo.snapshot().deleted.any { DeletedCodec.decode(it.payload).notes.any { n -> n.content.endsWith("newer phone edit") } })

            // 8. Settled: another sync changes nothing on either side.
            val before = browser.list().map { it.getString("etag") }.sorted() to local().map { it.content }.sorted()
            assertTrue(sync.sync())
            assertEquals(before, browser.list().map { it.getString("etag") }.sorted() to local().map { it.content }.sorted())
            assertNull(sync.state.value.error)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            runCatching { browser.deleteAll() }
            db.close(); accounts.clear(); dir.deleteRecursively()
            context.deleteSharedPreferences("real_nextcloud_sync")
        }
    }
}
