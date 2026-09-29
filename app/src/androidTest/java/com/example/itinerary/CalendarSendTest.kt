package com.example.itinerary

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile

/** Calendar sync step 5 (Planner → Nextcloud) over real TLS against a CalDAV fixture that enforces If-Match /
 *  If-None-Match like Nextcloud. The rule under test: Planner only ever changes events it created, in the chosen calendar. */
class CalendarSendTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: MockWebServer
    private lateinit var database: AppDatabase
    private lateinit var repo: Repository
    private lateinit var sync: CalendarSync
    private lateinit var accounts: NextcloudAccountStore
    private lateinit var sandbox: File
    private lateinit var context: Context
    private val dav = Dav()
    private var clock = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Australia/Perth")
    private val alias = "planner.nextcloud.send-instrumentation"
    private val home = "/remote.php/dav/calendars/bas/"
    private val personal = "${home}personal/"
    private val soon = LocalDate.of(2026, 10, 5)
    private val past = LocalDate.of(2026, 9, 1)

    @Before fun setUp() {
        val base = instrumentation.targetContext
        sandbox = File(base.cacheDir, "calendar-send-instrumentation").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(sandbox, "files").apply { mkdirs() }
            override fun getCacheDir() = File(sandbox, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(sandbox, "no_backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences("send_test_$name", mode)
        }
        database = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        repo = Repository(database, AttachmentStore(context), ReminderScheduler(context))
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.dispatcher = dav
        server.start()
        val client = NextcloudClient(OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build())
        accounts = NextcloudAccountStore(context, alias)
        accounts.save(NextcloudAccount.create(server.url("/").toString(), "bas", "test-password-only"))
        sync = CalendarSync(database, accounts, client, now = { clock }, zone = { zone },
            pendingDeleted = { repo.pendingDeletions.value.flatMap { it.items }.mapTo(HashSet()) { it.id } })
        // A foreign event (not Planner's) that must never be touched.
        dav.files["${personal}someone-else.ics"] = "e-foreign" to ics("foreign-1", "Their meeting", "20261006T010000Z")
    }

    @After fun tearDown() {
        database.close()
        server.shutdown()
        listOf("settings", "backup_status").forEach { instrumentation.targetContext.deleteSharedPreferences("send_test_$it") }
        sandbox.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }

    private fun sources() = runBlocking { database.outsideDao().sources() }
    private fun source(name: String) = sources().single { it.name == name }
    private fun rows() = runBlocking { database.sentDao().all() }
    private fun items() = runBlocking { repo.snapshot().items }
    private fun mine() = dav.files.filterKeys { it.startsWith("${personal}planner-") }
    private fun writes() = dav.requests.filter { it.method == "PUT" || it.method == "DELETE" }
    private suspend fun save(title: String, date: LocalDate = soon, time: LocalTime? = LocalTime.of(9, 0), category: String = "Other") {
        repo.saveItem(ItineraryItem(tripId = 0, date = date, startTime = time, durationMinutes = time?.let { 60 }, title = title,
            location = "Room 1", notes = "Bring the forms", category = category, billAmountMinor = if (category == "Bills") 1000 else null))
    }
    private suspend fun start() { sync.sync(); sync.setSendTarget(source("Personal").id) }
    private fun assertForeignUntouched() {
        assertEquals("e-foreign", dav.files["${personal}someone-else.ics"]!!.first)
        assertTrue(writes().none { it.path.endsWith("someone-else.ics") })
        assertTrue("Only Planner's own files are written", writes().all { it.path.startsWith("${personal}planner-") && it.path.endsWith("@planner.ics") })
    }

    @Test fun onlyWritableCalendarsCanBeChosen() = runBlocking {
        sync.sync()
        assertTrue(source("Personal").writable)
        assertFalse(source("Shared with me").writable)
        sync.setSendTarget(source("Shared with me").id)
        assertTrue(sources().none { it.sendHere })
        sync.setSendTarget(source("Personal").id)
        assertTrue(source("Personal").sendHere)
    }

    @Test fun sendsUpcomingEventsNotesPastOnesAndLeavesBillsAndSkippedDatesOut() = runBlocking {
        save("QA Upcoming")
        save("QA Past", date = past)
        save("QA Bill", category = "Bills", time = null)
        repo.saveItem(ItineraryItem(tripId = 0, date = soon, startTime = null, title = "QA Weekly"), options = EventSaveOptions(repeat = RepeatRule.WEEKLY, count = 2))
        items().filter { it.title == "QA Weekly" }.minBy { it.date }.let { repo.setSkipped(it.id, true) }
        start()
        val sent = mine().values.map { it.second }
        assertEquals(2, sent.size) // QA Upcoming and the not-skipped Weekly date
        assertTrue(sent.any { it.contains("SUMMARY:QA Upcoming") && it.contains("LOCATION:Room 1") && it.contains("DESCRIPTION:Bring the forms") })
        assertTrue(sent.none { it.contains("QA Bill") || it.contains("QA Past") })
        assertTrue(writes().all { it.method == "PUT" && it.ifNoneMatch == "*" && it.ifMatch == null })
        assertNull(rows().single { r -> r.itemId == items().single { it.title == "QA Past" }.id }.uid) // noted, not sent
        // Editing the past event sends it.
        items().single { it.title == "QA Past" }.let { repo.saveItem(it.copy(title = "QA Past edited")) }
        sync.send()
        assertTrue(mine().values.any { it.second.contains("SUMMARY:QA Past edited") })
        assertForeignUntouched()
    }

    @Test fun updatesOnlyWhenSomethingSentChangedAndOnlyIfUnchangedThere() = runBlocking {
        save("QA Dentist")
        start()
        val before = writes().size
        sync.send()
        assertEquals(before, writes().size) // nothing changed, nothing sent
        val row = rows().single { it.uid != null }
        items().single { it.title == "QA Dentist" }.let { repo.saveItem(it.copy(location = "Room 2")) }
        sync.send()
        val update = writes().last()
        assertEquals("PUT", update.method)
        assertEquals(row.etag, update.ifMatch)
        assertTrue(mine().values.single().second.contains("LOCATION:Room 2"))
        assertNotEquals(row.etag, rows().single { it.uid != null }.etag)
        // A Planner-only detail (a checklist) isn't sent.
        items().single { it.title == "QA Dentist" }.let { repo.saveItem(it.copy(checklist = listOf(ChecklistEntry("a", "Forms", false)))) }
        val count = writes().size
        sync.send()
        assertEquals(count, writes().size)
        assertForeignUntouched()
    }

    @Test fun deletingWaitsForUndoThenDeletesOnlyIfUnchanged() = runBlocking {
        save("QA Cancelled")
        save("QA Undone")
        start()
        assertEquals(2, mine().size)
        repo.deleteWithUndo(items().single { it.title == "QA Cancelled" })
        sync.send()
        assertEquals(2, mine().size) // Undo still on offer
        repo.deleteWithUndo(items().single { it.title == "QA Undone" })
        repo.undoDeletion(repo.pendingDeletions.value.single { it.items.single().title == "QA Undone" }.token)
        repo.finishDeletion(repo.pendingDeletions.value.single().token)
        sync.send()
        assertEquals(listOf("QA Undone"), mine().values.map { Regex("SUMMARY:(.*)").find(it.second)!!.groupValues[1].trim() })
        assertEquals("DELETE", writes().last().method)
        assertNotNull(writes().last().ifMatch)
        // Becoming a bill removes the copy too.
        items().single { it.title == "QA Undone" }.let { repo.saveItem(it.copy(category = "Bills", billAmountMinor = 500, startTime = null, durationMinutes = null)) }
        sync.send()
        assertTrue(mine().isEmpty())
        assertTrue(rows().isEmpty())
        assertForeignUntouched()
    }

    @Test fun copiesChangedOrDeletedOnNextcloudAreLeftAlone() = runBlocking {
        save("QA Theirs now")
        save("QA Gone there")
        start()
        val (changedPath, changed) = mine().entries.first { it.value.second.contains("QA Theirs now") }.let { it.key to it.value }
        dav.files[changedPath] = "e-edited-on-web" to changed.second.replace("QA Theirs now", "Edited on the web")
        dav.files.remove(mine().keys.first { dav.files[it]!!.second.contains("QA Gone there") })
        items().forEach { repo.saveItem(it.copy(location = "Moved")) }
        sync.send()
        assertEquals("Edited on the web version kept", "e-edited-on-web", dav.files[changedPath]!!.first)
        assertTrue(mine().values.none { it.second.contains("QA Gone there") }) // not created again
        assertEquals(setOf(SentEvent.CHANGED, SentEvent.DELETED), rows().mapNotNull { it.problem }.toSet())
        // Step 6: not an error any more; the next check turns these into an update or a conflict to choose.
        assertFalse(sync.sendState.value.error)
        assertEquals("2 events were changed on Nextcloud; Planner will check at the next sync.", sync.sendState.value.message)
        // Further edits and even deleting it in Planner never touch their version.
        items().single { it.title == "QA Theirs now" }.let { repo.saveItem(it.copy(title = "Again")); repo.deleteItem(it) }
        repo.finishDeletion(repo.pendingDeletions.value.single().token)
        sync.send()
        assertEquals("e-edited-on-web", dav.files[changedPath]!!.first)
        assertForeignUntouched()
    }

    @Test fun offlineChangesAreSentLater() = runBlocking {
        start()
        dav.code = 503
        save("QA Offline")
        sync.send()
        assertTrue(sync.sendState.value.error)
        assertTrue(sync.sendState.value.message!!.endsWith("The rest will be sent later."))
        assertTrue(mine().isEmpty())
        dav.code = null
        sync.send()
        assertEquals(1, mine().size)
        assertFalse(sync.sendState.value.error)
    }

    @Test fun offAndSwitchingLeaveCopiesAndBackupsDontSendTwice() = runBlocking {
        save("QA Kept")
        start()
        // Backups carry the record, so restoring doesn't send everything again.
        val settings = SettingsRepository(context)
        val attachments = AttachmentStore(context)
        val backup = BackupManager(context, repo, attachments, settings, sync)
        val file = File(context.cacheDir, "send-backup.zip")
        backup.export(Uri.fromFile(file), trackStatus = false)
        val json = JSONObject(ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry("data.json")).readBytes().toString(Charsets.UTF_8) })
        assertEquals(personal, json.getJSONObject("calendarSend").getString("href"))
        assertEquals(1, json.getJSONObject("calendarSend").getJSONArray("sent").length())
        sync.restoreSend(null, emptyList())
        backup.restore(backup.stage(Uri.fromFile(file)))
        val puts = writes().count { it.method == "PUT" }
        sync.send()
        assertEquals(puts, writes().count { it.method == "PUT" })
        assertTrue(source("Personal").sendHere)
        // Off: nothing more is sent, and the copy stays.
        sync.setSendTarget(null)
        items().forEach { repo.saveItem(it.copy(title = "QA Kept edited")) }
        sync.send()
        assertEquals(puts, writes().count { it.method == "PUT" })
        assertEquals(1, mine().size)
        // Disconnecting forgets the record but leaves the copy.
        sync.clearNextcloud()
        assertEquals(1, mine().size)
        assertTrue(rows().isEmpty())
        assertForeignUntouched()
    }

    private fun ics(uid: String, title: String, start: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:$uid\r\nDTSTART:$start\r\nSUMMARY:$title\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

    private class Request(val method: String, val path: String, val ifMatch: String?, val ifNoneMatch: String?)

    // Nextcloud-like CalDAV: two calendars (one read-only), event files with ETags, and If-Match / If-None-Match honoured.
    private inner class Dav : Dispatcher() {
        val files = ConcurrentHashMap<String, Pair<String, String>>() // path → (etag, body)
        val requests = CopyOnWriteArrayList<Request>()
        @Volatile var code: Int? = null
        private var next = 0
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl!!.encodedPath
            requests += Request(request.method.orEmpty(), path, request.getHeader("If-Match"), request.getHeader("If-None-Match"))
            code?.let { return MockResponse().setResponseCode(it) }
            if (request.getHeader("Authorization") != Credentials.basic("bas", "test-password-only", Charsets.UTF_8)) return MockResponse().setResponseCode(401)
            fun ms(body: String) = MockResponse().setResponseCode(207).setBody("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/">$body</d:multistatus>""")
            val current = files[path]
            return when (request.method) {
                "PROPFIND" -> if (path == home) ms(listOf(Triple("personal", "Personal", true), Triple("shared", "Shared with me", false)).joinToString("") { (slug, name, write) ->
                    val privileges = if (write) "<d:privilege><d:read/></d:privilege><d:privilege><d:write/></d:privilege>" else "<d:privilege><d:read/></d:privilege>"
                    """<d:response><d:href>$home$slug/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><d:displayname>$name</d:displayname><cs:getctag>${files.size}-$next</cs:getctag><d:current-user-privilege-set>$privileges</d:current-user-privilege-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
                }) else if (current != null) ms("""<d:response><d:href>$path</d:href><d:propstat><d:prop><d:getetag>${current.first}</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>""")
                    else MockResponse().setResponseCode(404)
                "REPORT" -> ms(files.filterKeys { it.startsWith(path) }.entries.joinToString("") { (p, v) ->
                    """<d:response><d:href>$p</d:href><d:propstat><d:prop><d:getetag>${v.first}</d:getetag><cal:calendar-data>${v.second.replace("&", "&amp;").replace("<", "&lt;")}</cal:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
                })
                "PUT" -> {
                    val ifMatch = request.getHeader("If-Match"); val ifNone = request.getHeader("If-None-Match")
                    when {
                        !path.startsWith(personal) -> MockResponse().setResponseCode(403)
                        ifNone == "*" && current != null -> MockResponse().setResponseCode(412)
                        ifMatch != null && current == null -> MockResponse().setResponseCode(404)
                        ifMatch != null && ifMatch != current!!.first -> MockResponse().setResponseCode(412)
                        else -> { val etag = "\"e${++next}\""; files[path] = etag to request.body.readUtf8()
                            MockResponse().setResponseCode(if (current == null) 201 else 204).setHeader("ETag", etag) }
                    }
                }
                "DELETE" -> when {
                    current == null -> MockResponse().setResponseCode(404)
                    request.getHeader("If-Match") != null && request.getHeader("If-Match") != current.first -> MockResponse().setResponseCode(412)
                    else -> { files.remove(path); MockResponse().setResponseCode(204) }
                }
                else -> MockResponse().setResponseCode(405)
            }
        }
    }
}
