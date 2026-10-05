package com.example.itinerary

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
 *  answers 404 as a server without the Notes app does; [beforePut] and [beforePost] run first (a change there while a sync
 *  runs, or a pass held there); a PUT to a note in [refused] answers 403; [noTag]: the list comes without an ETag. */
class FakeNotes(private val user: String, private val password: String) : Dispatcher() {
    data class N(val title: String, val content: String, val category: String, val favorite: Boolean, val etag: String, val readonly: Boolean = false)
    val notes = ConcurrentHashMap<Long, N>()
    val requests = CopyOnWriteArrayList<String>()
    @Volatile var missing = false
    @Volatile var beforePut: ((Long) -> Unit)? = null
    @Volatile var beforeDavDelete: ((Long) -> Unit)? = null
    @Volatile var beforePost: (() -> Unit)? = null
    @Volatile var refused: Set<Long> = emptySet()
    @Volatile var noTag = false
    // GETs of the list answered 304 (not changed).
    @Volatile var conditional = 0
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
        val davRoot = "/remote.php/dav/files/$user/Notes/"
        if (path.startsWith(davRoot)) {
            val id = path.substringAfterLast('/').removeSuffix(".txt").toLongOrNull()
            return synchronized(this) {
                fun tag(n: N) = "\"d${n.etag}\""
                when (request.method) {
                    "PROPFIND" -> {
                        val category = path.removePrefix(davRoot).trim('/')
                        val files = notes.filter { it.value.category == category }.entries.joinToString("") { (k, n) ->
                            "<d:response><d:href>${path}$k.txt</d:href><d:propstat><d:prop><d:resourcetype/><oc:fileid>$k</oc:fileid><d:getetag>${tag(n)}</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                        }
                        MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\">$files</d:multistatus>")
                    }
                    "GET", "DELETE" -> {
                        if (id == null) return@synchronized MockResponse().setResponseCode(404)
                        if (request.method == "DELETE") beforeDavDelete?.invoke(id)
                        val n = notes[id] ?: return@synchronized MockResponse().setResponseCode(404)
                        if (request.getHeader("If-Match") != tag(n)) return@synchronized MockResponse().setResponseCode(412)
                        if (request.method == "GET") MockResponse().setHeader("ETag", tag(n)).setBody(n.content)
                        else { notes.remove(id); MockResponse().setResponseCode(204) }
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }
        if (path == "/index.php/apps/notes/api/v1/settings")
            return if (missing) MockResponse().setResponseCode(404) else MockResponse().setBody("{\"notesPath\":\"Notes\",\"fileSuffix\":\".txt\"}")
        val base = "/index.php/apps/notes/api/v1/notes"
        if (missing || !path.startsWith(base)) return MockResponse().setResponseCode(404)
        val id = path.removePrefix(base).trim('/').toLongOrNull()
        val body = request.body.readUtf8()
        // Outside the lock, so a held request doesn't hold the others up.
        if (request.method == "POST") beforePost?.invoke()
        return synchronized(this) {
            when {
                request.method == "GET" && id == null -> {
                    // The list's ETag, as the Notes app sends it; If-None-Match with it answers 304 with no notes.
                    val list = JSONArray().apply { notes.toSortedMap().forEach { (k, v) -> put(json(k, v)) } }.toString()
                    val tag = "\"L${list.hashCode()}\""
                    if (request.getHeader("If-None-Match") == tag) { conditional++; MockResponse().setResponseCode(304) }
                    else MockResponse().setBody(list).apply { if (!noTag) setHeader("ETag", tag) }
                }
                request.method == "GET" && id != null -> notes[id]?.let { MockResponse().setBody(json(id, it).toString()) }
                    ?: MockResponse().setResponseCode(404)
                request.method == "POST" && id == null -> {
                    val o = JSONObject(body)
                    val made = add(o.getString("title").ifBlank { o.getString("content").lineSequence().first() }, o.getString("content"), o.optString("category"), o.optBoolean("favorite"))
                    MockResponse().setBody(json(made, notes[made]!!).toString())
                }
                request.method == "PUT" && id != null -> {
                    beforePut?.invoke(id)
                    if (id in refused) return@synchronized MockResponse().setResponseCode(403)
                    val current = notes[id] ?: return@synchronized MockResponse().setResponseCode(404)
                    if (request.getHeader("If-Match") != null && request.getHeader("If-Match") != "\"${current.etag}\"") return@synchronized MockResponse().setResponseCode(412)
                    val o = JSONObject(body)
                    edit(id) { it.copy(title = o.getString("title"), content = o.getString("content"), category = o.optString("category"), favorite = o.optBoolean("favorite")) }
                    MockResponse().setBody(json(id, notes[id]!!).toString())
                }
                // The actual Notes API does not support If-Match on DELETE.
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

            // Deleted in Planner: held during Undo, then its backing file is deleted conditionally.
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

            // Links forgotten (a restored backup): matching content relinks without duplicates.
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

    // "Sync changes automatically": check() passes only when something changed, and asks Nextcloud with the list's ETag
    // (304, no notes) otherwise; AutoSync's loop checks on its own and stops when cancelled.
    @Test fun autoSyncChecksCheaplyAndStops() = runBlocking {
        val base = context
        val dir = File(base.cacheDir, "note-sync-3").apply { deleteRecursively(); mkdirs() }
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
        val prefs = base.getSharedPreferences("note_sync_test3", 0).apply { edit().clear().commit() }
        val accounts = NextcloudAccountStore(isolated, "planner.nextcloud.note-sync-test3")
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val repo = Repository(db, AttachmentStore(isolated), alarms)
            accounts.save(NextcloudAccount.create(server.url("/").toString(), "qa", "qa-test-password"))
            val sync = NoteSync(db, accounts, NotesApi(http), repo.asNoteStore(), prefs,
                pendingDeleted = { repo.pendingDeletions.value.flatMap { it.notes }.mapTo(HashSet()) { it.id } })
            fun content(t: String) = runBlocking { repo.allNotes() }.singleOrNull { Notes.label(it) == t }?.content
            fun others() = fake.requests.filterNot { it == "GET /index.php/apps/notes/api/v1/notes" }

            sync.check(); assertTrue("off: nothing", fake.requests.isEmpty())
            sync.setEnabled(true)
            val id = fake.add("Shopping", "Shopping\nbread")
            sync.check() // never synced: a full pass
            assertEquals("Shopping\nbread", content("Shopping"))
            val after = fake.requests.size
            sync.check() // nothing changed anywhere: one request, answered 304
            assertEquals(after + 1, fake.requests.size); assertEquals(1, fake.conditional)
            fake.edit(id) { it.copy(content = "Shopping\nbread\nmilk") }
            sync.check() // changed there: pulled
            assertEquals("Shopping\nbread\nmilk", content("Shopping"))
            repo.saveNote(runBlocking { repo.allNotes() }.single().copy(content = "Shopping\nbread\nmilk\neggs"), create = false)
            sync.check() // changed here: sent, without waiting for the list
            assertEquals("Shopping\nbread\nmilk\neggs", fake.notes[id]!!.content)
            assertTrue(others().any { it == "PUT /index.php/apps/notes/api/v1/notes/$id" })

            // The loop (every second here, every minute in Planner) brings a change in by itself...
            val calendars = CalendarSync(db, accounts)
            val auto = com.example.itinerary.AutoSync(calendars, sync, scope)
            val watching = scope.launch { auto.watch(base, intervalMs = 1_000) }
            fake.edit(id) { it.copy(content = "Shopping\ncheese") }
            val end = android.os.SystemClock.uptimeMillis() + 10_000
            while (content("Shopping") != "Shopping\ncheese" && android.os.SystemClock.uptimeMillis() < end) Thread.sleep(100)
            assertEquals("Shopping\ncheese", content("Shopping"))
            // ...and stops when Planner leaves the screen.
            watching.cancel(); watching.join(); Thread.sleep(300)
            val stopped = fake.requests.size
            fake.edit(id) { it.copy(content = "Shopping\nlater") }
            Thread.sleep(3_000)
            assertEquals(stopped, fake.requests.size); assertEquals("Shopping\ncheese", content("Shopping"))
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            db.close(); server.shutdown(); accounts.clear(); dir.deleteRecursively()
            base.deleteSharedPreferences("note_sync_test3")
        }
    }

    // One isolated Planner and Notes app per test below. [wired]: changes in the repository ask for a pass, as in the app.
    private class Rig(val fake: FakeNotes, val db: AppDatabase, val repo: Repository, val sync: NoteSync, val isolated: android.content.Context,
                      val dir: File, val scope: kotlinx.coroutines.CoroutineScope) {
        @Volatile var wired = false
        fun note(label: String) = runBlocking { repo.allNotes() }.single { Notes.label(it) == label }
        fun remote(title: String) = fake.notes.entries.single { it.value.title == title }
    }

    private fun rig(name: String, test: suspend (Rig) -> Unit) = runBlocking {
        val base = context
        val dir = File(base.cacheDir, name).apply { deleteRecursively(); mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(dir, "nobackup").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
            // A restore applies the backup's settings: never to the app's own.
            override fun getSharedPreferences(n: String, mode: Int) = base.getSharedPreferences("${name}_$n", mode)
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
        val prefs = base.getSharedPreferences("${name}_note_sync", 0).apply { edit().clear().commit() }
        val accounts = NextcloudAccountStore(isolated, "planner.nextcloud.$name")
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            lateinit var rig: Rig
            val repo = Repository(db, AttachmentStore(isolated), alarms, onChanged = { if (rig.wired) rig.sync.request(delayMs = 200) })
            accounts.save(NextcloudAccount.create(server.url("/").toString(), "qa", "qa-test-password"))
            val sync = NoteSync(db, accounts, NotesApi(http), repo.asNoteStore(), prefs,
                pendingDeleted = { repo.pendingDeletions.value.flatMap { it.notes }.mapTo(HashSet()) { it.id } }, scope = scope)
            rig = Rig(fake, db, repo, sync, isolated, dir, scope)
            sync.setEnabled(true)
            test(rig)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            db.close(); server.shutdown(); accounts.clear(); dir.deleteRecursively()
            listOf("note_sync", "settings", "backup_status").forEach { base.deleteSharedPreferences("${name}_$it") }
        }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 15_000
        while (!condition() && android.os.SystemClock.uptimeMillis() < end) Thread.sleep(100)
        assertTrue(what, condition())
    }

    // Bug hunt 3 Oct, SY-1: a restore confirmed while a notes pass runs waits for it. Before, the pass carried on across
    // the restore and put back a link for a note the backup doesn't have, so the next pass deleted it on Nextcloud too.
    @Test fun aRestoreWaitsForANotesPassUnderWay() = rig("note-sync-restore") { r ->
        r.fake.add("Kept", "Kept\nfrom before")
        assertTrue(r.sync.sync())
        val backup = BackupManager(r.isolated, r.repo, AttachmentStore(r.isolated), SettingsRepository(r.isolated), notes = r.sync)
        val zip = File(r.dir, "backup.zip")
        backup.export(android.net.Uri.fromFile(zip), trackStatus = false)
        val staged = backup.stage(android.net.Uri.fromFile(zip))
        // A note the backup doesn't have, on its way up when the restore is confirmed.
        r.repo.saveNote(PlannerNote(title = "Not in the backup", content = "typed after the backup"), create = true)
        val reached = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        r.fake.beforePost = { r.fake.beforePost = null; reached.countDown(); release.await(20, java.util.concurrent.TimeUnit.SECONDS) }
        val pass = r.scope.async { r.sync.sync() }
        assertTrue(reached.await(20, java.util.concurrent.TimeUnit.SECONDS))
        val restoring = r.scope.async { backup.restore(staged) }
        Thread.sleep(1_000)
        assertFalse("The restore waits for the pass", restoring.isCompleted)
        release.countDown()
        assertTrue(pass.await()); restoring.await()
        // No links (the next pass links by content), so nothing is deleted on Nextcloud: the note comes back from there.
        assertTrue(r.db.sentNoteDao().all().isEmpty())
        assertTrue(r.sync.sync())
        assertTrue(r.fake.requests.toString(), r.fake.requests.none { it.startsWith("DELETE") })
        assertEquals(setOf("Kept", "Not in the backup"), r.fake.notes.values.map { it.title }.toSet())
        assertEquals(listOf("Kept", "Not in the backup"), r.repo.allNotes().map { Notes.label(it) }.sorted())
        assertEquals("typed after the backup", r.note("Not in the backup").content)
    }

    // SY-3: an edit saved while a pass that a check (not a request) started is under way is sent straight after it, not
    // at the next check.
    @Test fun anEditSavedDuringACheckIsSentAfterIt() = rig("note-sync-follow") { r ->
        r.repo.saveNote(PlannerNote(title = "A", content = "a1"), create = true)
        r.repo.saveNote(PlannerNote(title = "B", content = "b1"), create = true)
        assertTrue(r.sync.sync())
        r.repo.saveNote(r.note("A").copy(content = "a2"), create = false)
        val reached = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        r.fake.beforePut = { r.fake.beforePut = null; reached.countDown(); release.await(20, java.util.concurrent.TimeUnit.SECONDS) }
        val checking = r.scope.async { r.sync.check() }
        assertTrue(reached.await(20, java.util.concurrent.TimeUnit.SECONDS))
        r.wired = true
        r.repo.saveNote(r.note("B").copy(content = "b2"), create = false)
        release.countDown()
        checking.await()
        assertEquals("a2", r.remote("A").value.content)
        waitFor("B is sent after the check's pass") { r.remote("B").value.content == "b2" }
    }

    // SY-2: a note Nextcloud refuses isn't written again every minute; after the second try the checks only ask whether
    // the list changed (304), until the connection comes back or something changes.
    @Test fun aCheckBacksOffFromANoteNextcloudRefuses() = rig("note-sync-refused") { r ->
        val id = r.fake.add("Shared", "Shared\nfrom a colleague")
        assertTrue(r.sync.sync())
        r.repo.saveNote(r.note("Shared").copy(content = "Shared\nmine"), create = false)
        r.fake.refused = setOf(id)
        fun puts() = r.fake.requests.count { it == "PUT /index.php/apps/notes/api/v1/notes/$id" }
        r.sync.check(); r.sync.check()
        assertEquals(2, puts())
        val asked = r.fake.requests.size
        r.sync.check(); r.sync.check()
        assertEquals(2, puts())
        assertEquals(asked + 2, r.fake.requests.size)
        r.sync.check(fresh = true)
        assertEquals(3, puts())
        // A change there still comes in at the next check.
        r.fake.add("Other", "Other\nnew there")
        r.sync.check()
        assertEquals("Other\nnew there", r.note("Other").content)
        // Accepted at last: sent, and the checks are cheap again.
        r.fake.refused = emptySet()
        r.sync.check(fresh = true)
        assertEquals("Shared\nmine", r.fake.notes[id]!!.content)
    }

    // SY-2: a server that sends no ETag for the list can't be asked cheaply; full passes still back off.
    @Test fun aCheckBacksOffWithoutAListETag() = rig("note-sync-notag") { r ->
        r.fake.noTag = true
        r.fake.add("Plain", "Plain\nbody")
        r.sync.check(); r.sync.check(); r.sync.check()
        assertEquals("Plain\nbody", r.note("Plain").content)
        val asked = r.fake.requests.size
        r.sync.check(); r.sync.check()
        assertEquals(asked, r.fake.requests.size)
        // A change here still goes at once.
        r.repo.saveNote(PlannerNote(title = "Mine", content = "typed"), create = true)
        r.sync.check()
        assertEquals("typed", r.remote("Mine").value.content)
    }

    // Bug hunt 3 Oct (b), S5-3: a note edited again while the follow-up pass sends its last edit gets one more pass at
    // once; it used to be taken for a note that can't sync and wait for the next check.
    @Test fun aNoteEditedAgainDuringTheFollowUpIsSentAfterIt() = rig("note-sync-follow-again") { r ->
        r.repo.saveNote(PlannerNote(title = "A", content = "a1"), create = true)
        r.repo.saveNote(PlannerNote(title = "B", content = "b1"), create = true)
        assertTrue(r.sync.sync())
        r.repo.saveNote(r.note("B").copy(content = "b2"), create = false)
        val first = java.util.concurrent.CountDownLatch(1); val firstGo = java.util.concurrent.CountDownLatch(1)
        val second = java.util.concurrent.CountDownLatch(1); val secondGo = java.util.concurrent.CountDownLatch(1)
        // The first pass is held at B's PUT, the follow-up at A's.
        r.fake.beforePut = { _ ->
            r.fake.beforePut = { _ -> r.fake.beforePut = null; second.countDown(); secondGo.await(20, java.util.concurrent.TimeUnit.SECONDS) }
            first.countDown(); firstGo.await(20, java.util.concurrent.TimeUnit.SECONDS)
        }
        val pass = r.scope.async { r.sync.sync() }
        assertTrue(first.await(20, java.util.concurrent.TimeUnit.SECONDS))
        r.wired = true
        r.repo.saveNote(r.note("A").copy(content = "a2"), create = false)
        firstGo.countDown()
        assertTrue(pass.await())
        // The follow-up pass, sending a2: A is edited again meanwhile.
        assertTrue(second.await(20, java.util.concurrent.TimeUnit.SECONDS))
        r.repo.saveNote(r.note("A").copy(content = "a3"), create = false)
        secondGo.countDown()
        waitFor("a3 is sent after the follow-up pass") { r.remote("A").value.content == "a3" }
    }

    // Bug hunt 3 Oct (b), R5-4: the notes a restore brings back are sent once it is done (automatic sync on). The pass
    // the restore asked for used to be noted only, with no pass running to follow it up.
    @Test fun restoredNotesAreSentOnceTheRestoreIsDone() = rig("note-sync-restore-pass") { r ->
        r.repo.saveNote(PlannerNote(title = "From the backup", content = "restored"), create = true)
        val backup = BackupManager(r.isolated, r.repo, AttachmentStore(r.isolated), SettingsRepository(r.isolated), notes = r.sync)
        val zip = File(r.dir, "backup.zip")
        backup.export(android.net.Uri.fromFile(zip), trackStatus = false)
        val staged = backup.stage(android.net.Uri.fromFile(zip))
        assertTrue("nothing sent yet", r.fake.notes.isEmpty())
        r.wired = true
        backup.restore(staged)
        waitFor("the restored note is sent after the restore") { r.fake.notes.values.any { it.content == "restored" } }
    }
}
