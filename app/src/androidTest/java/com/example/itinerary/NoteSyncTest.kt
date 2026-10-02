package com.example.itinerary

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A small Nextcloud Notes app (API v1): notes with ETags, GET list, POST, PUT honouring If-Match (412), DELETE. [missing]
 *  answers 404 as a server without the Notes app does; [beforePut] runs first (a change there while a sync runs). */
class FakeNotes(private val user: String, private val password: String) : Dispatcher() {
    data class N(val title: String, val content: String, val category: String, val favorite: Boolean, val etag: String, val readonly: Boolean = false)
    val notes = ConcurrentHashMap<Long, N>()
    val requests = CopyOnWriteArrayList<String>()
    @Volatile var missing = false
    @Volatile var beforePut: ((Long) -> Unit)? = null
    private var nextId = 100L
    private var version = 0
    @Synchronized fun add(title: String, content: String, category: String = "", favorite: Boolean = false, readonly: Boolean = false): Long {
        val id = ++nextId; notes[id] = N(title, content, category, favorite, "e${++version}", readonly); return id
    }
    @Synchronized fun edit(id: Long, change: (N) -> N) { notes[id] = change(notes[id]!!).copy(etag = "e${++version}") }
    private fun json(id: Long, n: N) = JSONObject().put("id", id).put("etag", n.etag).put("title", n.title).put("content", n.content)
        .put("category", n.category).put("favorite", n.favorite).put("modified", 1_790_000_000L).put("readonly", n.readonly)
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
        requests += "${request.method} $path"
        if (request.getHeader("Authorization") != Credentials.basic(user, password, Charsets.UTF_8)) return MockResponse().setResponseCode(401)
        val base = "/index.php/apps/notes/api/v1/notes"
        if (missing || !path.startsWith(base)) return MockResponse().setResponseCode(404)
        val id = path.removePrefix(base).trim('/').toLongOrNull()
        val body = request.body.readUtf8()
        return synchronized(this) {
            when {
                request.method == "GET" && id == null -> MockResponse().setBody(JSONArray().apply { notes.toSortedMap().forEach { (k, v) -> put(json(k, v)) } }.toString())
                request.method == "POST" && id == null -> {
                    val o = JSONObject(body)
                    val made = add(o.getString("title").ifBlank { o.getString("content").lineSequence().first() }, o.getString("content"), o.optString("category"), o.optBoolean("favorite"))
                    MockResponse().setBody(json(made, notes[made]!!).toString())
                }
                request.method == "PUT" && id != null -> {
                    beforePut?.invoke(id)
                    val current = notes[id] ?: return@synchronized MockResponse().setResponseCode(404)
                    if (request.getHeader("If-Match") != null && request.getHeader("If-Match") != "\"${current.etag}\"") return@synchronized MockResponse().setResponseCode(412)
                    val o = JSONObject(body)
                    edit(id) { it.copy(title = o.getString("title"), content = o.getString("content"), category = o.optString("category"), favorite = o.optBoolean("favorite")) }
                    MockResponse().setBody(json(id, notes[id]!!).toString())
                }
                request.method == "DELETE" && id != null -> if (notes.remove(id) != null) MockResponse() else MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(405)
            }
        }
    }
}

class NoteSyncTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val alarms = object : ReminderAlarms {
        override fun schedule(item: ItineraryItem, reminder: Reminder) {}
        override fun cancel(reminderId: Long) {}
    }

    @Test fun twoWaySyncWithTheNotesApp() = runBlocking {
        val base = context
        val dir = File(base.cacheDir, "note-sync").apply { deleteRecursively(); mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(dir, "nobackup").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
        }
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val server = MockWebServer()
        val fake = FakeNotes("qa", "qa-test-password")
        server.dispatcher = fake
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.start()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val http = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build()
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        val prefs = base.getSharedPreferences("note_sync_test", 0).apply { edit().clear().commit() }
        val accounts = NextcloudAccountStore(isolated, "planner.nextcloud.note-sync-test")
        try {
            val repo = Repository(db, AttachmentStore(isolated), alarms)
            accounts.save(NextcloudAccount.create(server.url("/").toString(), "qa", "qa-test-password"))
            val sync = NoteSync(db, accounts, NotesApi(http), repo.asNoteStore(), prefs,
                pendingDeleted = { repo.pendingDeletions.value.flatMap { it.notes }.mapTo(HashSet()) { it.id } })
            fun local() = runBlocking { repo.allNotes() }
            fun byTitle(t: String) = local().single { Notes.label(it) == t }
            fun remoteByTitle(t: String) = fake.notes.entries.single { it.value.title == t }

            // Off: nothing happens.
            assertTrue(sync.sync()); assertTrue(fake.requests.isEmpty())
            sync.setEnabled(true)

            // First pass: Planner's note goes up, Nextcloud's comes in (category → notebook, favourite → pinned).
            val groceries = repo.saveNote(PlannerNote(title = "Groceries", content = "- [ ] milk", color = Notes.colors[1], tags = listOf("food")), create = true)
            val recipeId = fake.add("Recipe", "Recipe\n1. Boil water", category = "Home", favorite = true)
            assertTrue(sync.sync())
            assertEquals(2, fake.notes.size)
            assertEquals("- [ ] milk", remoteByTitle("Groceries").value.content)
            val recipe = byTitle("Recipe")
            // Its title was just its first line, so it stays the note's first line, not a title as well.
            assertEquals("", recipe.title); assertEquals("Home", recipe.notebook); assertTrue(recipe.pinned)
            assertNull(sync.state.value.error)

            // Edited here: sent with If-Match; colour and tags stay on the phone.
            repo.saveNote(byTitle("Groceries").copy(content = "- [x] milk\n- [ ] eggs"), create = false)
            assertTrue(sync.sync())
            val groceriesRemote = remoteByTitle("Groceries")
            assertEquals("- [x] milk\n- [ ] eggs", groceriesRemote.value.content)
            assertTrue(fake.requests.any { it == "PUT /index.php/apps/notes/api/v1/notes/${groceriesRemote.key}" })
            assertEquals(Notes.colors[1], byTitle("Groceries").color); assertEquals(listOf("food"), byTitle("Groceries").tags)

            // Edited there: it comes in.
            fake.edit(recipeId) { it.copy(content = "Recipe\n1. Boil water\n2. Add pasta", category = "Kitchen") }
            assertTrue(sync.sync())
            assertEquals("Recipe\n1. Boil water\n2. Add pasta", byTitle("Recipe").content); assertEquals("Kitchen", byTitle("Recipe").notebook)

            // Changed in both places: Nextcloud's in the note, Planner's as a conflict copy, which then goes up.
            repo.saveNote(byTitle("Groceries").copy(content = "phone version"), create = false)
            fake.edit(groceriesRemote.key) { it.copy(content = "server version") }
            assertTrue(sync.sync())
            assertEquals("server version", byTitle("Groceries").content)
            assertEquals("phone version", byTitle("Groceries (conflict copy)").content)
            assertEquals(1, sync.state.value.conflicts)
            assertTrue(sync.sync())
            assertEquals("phone version", remoteByTitle("Groceries (conflict copy)").value.content)

            // Changed there while a send was on its way (412): nothing overwritten; settled as a conflict next pass.
            repo.saveNote(byTitle("Recipe").copy(content = "Recipe\nmine"), create = false)
            fake.beforePut = { id -> fake.beforePut = null; fake.edit(id) { it.copy(content = "Recipe\ntheirs") } }
            assertTrue(sync.sync())
            assertEquals("Recipe\ntheirs", fake.notes[recipeId]!!.content)
            assertTrue(sync.sync())
            assertEquals("Recipe\ntheirs", byTitle("Recipe").content)
            assertTrue(local().any { it.content == "Recipe\nmine" && it.title.endsWith("(conflict copy)") })

            // Deleted on Nextcloud: to Recently deleted here.
            fake.notes.remove(recipeId)
            assertTrue(sync.sync())
            assertTrue(local().none { it.content == "Recipe\ntheirs" })
            assertTrue(repo.snapshot().deleted.any { DeletedCodec.decode(it.payload).notes.any { n -> n.content == "Recipe\ntheirs" } })

            // Deleted in Planner: not while its Undo is on offer, then on Nextcloud too.
            repo.deleteNote(byTitle("Groceries").id)
            assertTrue(sync.sync())
            assertTrue(fake.notes.values.any { it.title == "Groceries" })
            repo.finishDeletion(repo.pendingDeletions.value.single().token)
            assertTrue(sync.sync())
            assertTrue(fake.notes.values.none { it.title == "Groceries" })

            // Too long for Planner, or read-only there: left as it is on Nextcloud.
            val long = fake.add("Huge", "x".repeat(Notes.MAX_CONTENT + 1))
            val shared = fake.add("Shared", "Shared\nfrom a colleague", readonly = true)
            assertTrue(sync.sync())
            assertTrue(local().none { Notes.label(it) == "Huge" })
            repo.saveNote(byTitle("Shared").copy(content = "Shared\nmy edit"), create = false)
            val puts = fake.requests.count { it.startsWith("PUT") }
            assertTrue(sync.sync())
            assertEquals(puts, fake.requests.count { it.startsWith("PUT") })
            assertEquals("Shared\nfrom a colleague", fake.notes[shared]!!.content)
            assertEquals("Shared\nfrom a colleague", byTitle("Shared").content)
            assertTrue(local().any { it.content == "Shared\nmy edit" })
            assertEquals("x".repeat(Notes.MAX_CONTENT + 1), fake.notes[long]!!.content)

            // Links forgotten (a restored backup): the same notes link up again by their words, nothing doubled.
            assertTrue(sync.sync())
            val counts = local().size to fake.notes.size
            sync.forget()
            assertTrue(sync.sync())
            assertEquals(counts, local().size to fake.notes.size)

            // No Notes app on the server: said plainly.
            fake.missing = true
            assertFalse(sync.sync())
            assertTrue(sync.state.value.error!!.contains("Notes app isn't enabled"))
            fake.missing = false

            // Switched off: links forgotten, notes left in both places.
            sync.setEnabled(false)
            assertEquals(counts, local().size to fake.notes.size)
            assertTrue(db.sentNoteDao().all().isEmpty())
        } finally {
            db.close(); server.shutdown(); accounts.clear(); dir.deleteRecursively()
            base.deleteSharedPreferences("note_sync_test")
        }
    }

    // Bug hunt 2 Oct: S-1 (a pass cancelled itself through its own writes), S-5 (a refused delete stopped every pass),
    // S-6 (pinning a read-only note made a conflict copy), S-9 (too-long notes skipped silently).
    @Test fun requestedPassesFinishAndStop() = runBlocking {
        val base = context
        val dir = File(base.cacheDir, "note-sync-2").apply { deleteRecursively(); mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(dir, "nobackup").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
        }
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val server = MockWebServer()
        val fake = FakeNotes("qa", "qa-test-password")
        server.dispatcher = fake
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.start()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val http = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build()
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        val prefs = base.getSharedPreferences("note_sync_test2", 0).apply { edit().clear().commit() }
        val accounts = NextcloudAccountStore(isolated, "planner.nextcloud.note-sync-test2")
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            lateinit var sync: NoteSync
            // As in the app: every change in the repository asks for a pass.
            val repo = Repository(db, AttachmentStore(isolated), alarms, onChanged = { sync.request(delayMs = 200) })
            accounts.save(NextcloudAccount.create(server.url("/").toString(), "qa", "qa-test-password"))
            sync = NoteSync(db, accounts, NotesApi(http), repo.asNoteStore(), prefs, scope = scope)
            repeat(5) { fake.add("Remote $it", "Remote $it\nbody") }
            repo.saveNote(PlannerNote(title = "Mine", content = "typed"), create = true)
            sync.setEnabled(true)
            sync.request(delayMs = 0)
            // All five come in and Planner's goes up in one requested pass (it used to cancel itself after the first write).
            val end = android.os.SystemClock.uptimeMillis() + 20_000
            while ((repo.allNotes().size < 6 || db.sentNoteDao().all().size < 6) && android.os.SystemClock.uptimeMillis() < end) Thread.sleep(100)
            assertEquals(6, repo.allNotes().size); assertEquals(6, db.sentNoteDao().all().size); assertEquals(6, fake.notes.size)
            // ...and then it stops: no pass every few seconds.
            Thread.sleep(1_500)
            val lists = fake.requests.count { it == "GET /index.php/apps/notes/api/v1/notes" }
            Thread.sleep(3_000)
            assertEquals(lists, fake.requests.count { it == "GET /index.php/apps/notes/api/v1/notes" })

            // A read-only shared note: pinned here stays pinned here, no conflict copy; deleted here, left there.
            val sharedId = fake.add("Shared", "Shared\nfrom a colleague", readonly = true)
            assertTrue(sync.sync())
            val shared = repo.allNotes().single { Notes.label(it) == "Shared" }
            repo.updateNote(shared.id) { it.copy(pinned = true) }
            assertTrue(sync.sync())
            assertEquals(0, sync.state.value.conflicts)
            assertTrue(repo.allNotes().single { Notes.label(it) == "Shared" }.pinned)
            assertEquals(7, repo.allNotes().size)
            repo.deleteNote(shared.id); repo.finishDeletion(repo.pendingDeletions.value.single().token)
            assertTrue(sync.sync())
            assertNotNull(fake.notes[sharedId])
            assertTrue(fake.requests.none { it == "DELETE /index.php/apps/notes/api/v1/notes/$sharedId" })

            // Too long for Planner: counted, and said.
            fake.add("Huge", "x".repeat(Notes.MAX_CONTENT + 1))
            assertTrue(sync.sync())
            assertEquals(1, sync.state.value.skipped)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            db.close(); server.shutdown(); accounts.clear(); dir.deleteRecursively()
            base.deleteSharedPreferences("note_sync_test2")
        }
    }
}
