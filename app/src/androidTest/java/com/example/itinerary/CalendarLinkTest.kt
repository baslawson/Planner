package com.example.itinerary

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile

/** Calendar sync step 4 over real TLS: subscribing by link, "not modified", errors, redirects, removal and backups. */
class CalendarLinkTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: MockWebServer
    private lateinit var database: AppDatabase
    private lateinit var sync: CalendarSync
    private lateinit var sandbox: File
    private lateinit var context: Context
    private val feed = Feed()
    private var clock = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
    private val alias = "planner.nextcloud.link-instrumentation"

    @Before fun setUp() {
        val base = instrumentation.targetContext
        sandbox = File(base.cacheDir, "calendar-link-instrumentation").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(sandbox, "files").apply { mkdirs() }
            override fun getCacheDir() = File(sandbox, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(sandbox, "no_backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences("link_test_$name", mode)
        }
        database = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.dispatcher = feed
        server.start()
        val http = OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build()
        sync = CalendarSync(database, NextcloudAccountStore(context, alias), NextcloudClient(http), now = { clock },
            zone = { ZoneId.of("Australia/Perth") }, linkClient = CalendarLinkClient(http))
    }

    @After fun tearDown() {
        database.close()
        server.shutdown()
        listOf("settings", "backup_status").forEach { instrumentation.targetContext.deleteSharedPreferences("link_test_$it") }
        sandbox.deleteRecursively()
    }

    private fun link(path: String = "/holidays.ics?token=secret-123") = server.url(path).toString().replaceFirst("https://", "webcal://")
    private fun sources() = runBlocking { database.outsideDao().sources() }
    private fun shown() = runBlocking { sync.shown.first() }.values.map { it.event.title }.toSet()
    private fun source() = sources().single { it.kind == OutsideCalendars.KIND_LINK }
    private fun gets() = feed.requests.count { it.first == "GET" }

    @Test fun subscribingDownloadsOnceAndUsesTheCalendarsNameAndColour() = runBlocking {
        sync.addLink(link(), null)
        assertEquals("Holidays", source().name)
        assertEquals(0xFF1BADF8.toInt(), source().color)
        assertEquals("localhost", source().detail)
        assertTrue(source().enabled)
        assertTrue(source().href.startsWith("https://"))
        assertEquals(setOf("Christmas Day", "Weekly quiz"), shown())
        assertEquals(1, gets())
        val info = runBlocking { sync.shown.first() }.values.first()
        assertEquals(OutsideCalendars.KIND_LINK, info.kind)
        // A name of its own wins; the same link twice is refused.
        sync.addLink(link("/other.ics"), "School")
        assertEquals(setOf("Holidays", "School"), sources().map { it.name }.toSet())
        val twice = runCatching { sync.addLink(link(), null) }.exceptionOrNull()!!
        assertEquals("You're already subscribed to this calendar.", twice.message)
    }

    @Test fun unchangedCalendarsAreNotDownloadedAgain() = runBlocking {
        sync.addLink(link(), null)
        sync.refreshLinks()
        assertEquals("\"v1\"", feed.requests.last().second) // asked with the ETag…
        assertEquals(setOf("Christmas Day", "Weekly quiz"), shown()) // …and the events stayed
        feed.version = 2
        sync.refreshLinks()
        assertEquals(setOf("Christmas Day", "Boxing Day", "Weekly quiz"), shown())
        assertNull(source().lastError)
    }

    @Test fun openingTheAppUpdatesAtMostHourly() = runBlocking {
        sync.addLink(link(), null)
        assertEquals(1, gets())
        sync.syncIfDue() // the first open checks (the server answers "not modified")
        assertEquals(2, gets())
        clock += 20 * 60 * 1000L
        sync.syncIfDue()
        assertEquals(2, gets())
        clock += 50 * 60 * 1000L // 70 minutes after the last check
        sync.syncIfDue()
        assertEquals(3, gets())
        assertEquals(setOf("Christmas Day", "Weekly quiz"), shown())
    }

    @Test fun failuresKeepTheLastEventsAndNameOnlyTheHost() = runBlocking {
        sync.addLink(link(), null)
        feed.code = 404
        sync.refreshLinks()
        assertEquals("localhost no longer has this calendar.", source().lastError)
        assertEquals(setOf("Christmas Day", "Weekly quiz"), shown())
        assertTrue(sync.linkState.value.error)
        feed.code = null
        feed.body = "<html>Please sign in</html>"
        feed.version = 3
        sync.refreshLinks()
        assertEquals("This isn't a calendar file.", source().lastError)
        assertEquals(setOf("Christmas Day", "Weekly quiz"), shown())
        server.shutdown()
        sync.refreshLinks()
        assertEquals("Couldn't reach localhost. Check your connection.", source().lastError)
        assertFalse(source().lastError!!.contains("secret-123"))
    }

    @Test fun refusedLinksAndRedirects() = runBlocking {
        val http = runCatching { sync.addLink(server.url("/holidays.ics").toString().replaceFirst("https://", "http://"), null) }.exceptionOrNull()!!
        assertTrue(http.message!!.startsWith("Use an https://"))
        assertEquals(0, gets())
        // https → https redirects are followed; a redirect to http is not.
        sync.addLink(link("/moved.ics"), "Moved")
        assertEquals(setOf("Christmas Day", "Weekly quiz"), shown())
        val insecure = runCatching { sync.addLink(link("/insecure.ics"), null) }.exceptionOrNull()!!
        assertEquals("localhost redirected to an address that isn't https, so it wasn't followed.", insecure.message)
        feed.body = "BEGIN:VCALENDAR\r\n" + "X".repeat(CalendarFileImport.MAX_BYTES) + "\r\nEND:VCALENDAR\r\n"
        val large = runCatching { sync.addLink(link("/big.ics"), null) }.exceptionOrNull()!!
        assertEquals("This calendar is too large (maximum 10 MB).", large.message)
        assertEquals(listOf("Moved"), sources().map { it.name })
    }

    @Test fun untickRemoveAndBackups() = runBlocking {
        sync.addLink(link(), null)
        sync.setLinkEnabled(source().id, false)
        assertTrue(shown().isEmpty())
        sync.setLinkEnabled(source().id, true)
        assertEquals(setOf("Christmas Day", "Weekly quiz"), shown())
        // Backups keep the link (ticked or not), never its events; restoring brings it back to download again.
        val settings = SettingsRepository(context)
        val attachments = AttachmentStore(context)
        val repo = Repository(database, attachments, com.example.itinerary.reminders.ReminderScheduler(context))
        val backup = BackupManager(context, repo, attachments, settings, sync)
        val file = File(context.cacheDir, "link-backup.zip")
        backup.export(Uri.fromFile(file), trackStatus = false)
        val json = ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry("data.json")).readBytes().toString(Charsets.UTF_8) }
        val saved = JSONObject(json).getJSONArray("calendars").getJSONObject(0)
        assertEquals("link", saved.getString("account"))
        assertTrue(saved.getString("href").startsWith("https://") && saved.getString("href").contains("token=secret-123"))
        assertFalse(json.contains("Christmas Day"))
        sync.removeLink(source().id)
        assertTrue(sources().isEmpty() && shown().isEmpty())
        backup.restore(backup.stage(Uri.fromFile(file)))
        assertEquals("Holidays", source().name)
        assertTrue(shown().isEmpty())
        sync.refreshLinks()
        assertEquals(setOf("Christmas Day", "Weekly quiz"), shown())
        // Disconnecting Nextcloud leaves subscriptions alone.
        sync.clearNextcloud()
        assertEquals("Holidays", source().name)
    }

    // Bug hunt 18, R18-S1: repeating events whose rule Planner can't follow are noted on the calendar's row instead of
    // vanishing without a word; the note goes once the calendar has none.
    @Test fun repeatsPlannerCantFollowAreNotedOnTheRow() = runBlocking {
        feed.body = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nX-WR-CALNAME:Club\r\n" +
            "BEGIN:VEVENT\r\nUID:m\r\nDTSTART:20250106T090000Z\r\nRRULE:FREQ=MONTHLY;BYDAY=1MO,3MO\r\nSUMMARY:Committee\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:s\r\nDTSTART:20250101T090000Z\r\nRRULE:FREQ=MONTHLY;BYDAY=MO;BYSETPOS=-1\r\nSUMMARY:Social\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:q\r\nDTSTART:20240103T190000Z\r\nDURATION:PT2H\r\nRRULE:FREQ=WEEKLY\r\nSUMMARY:Weekly quiz\r\nEND:VEVENT\r\n" +
            "END:VCALENDAR\r\n"
        sync.addLink(link(), null)
        assertEquals("2 repeating events repeat in a way Planner can't show.", source().note)
        assertEquals(setOf("Weekly quiz"), shown())
        feed.body = null; feed.version = 2
        sync.refreshLinks()
        assertEquals(setOf("Christmas Day", "Boxing Day", "Weekly quiz"), shown())
        assertNull(source().note)
        assertNull(source().lastError)
    }

    // A holiday feed with an ETag; [version] changes it, [code]/[body] force an answer.
    private inner class Feed : Dispatcher() {
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        @Volatile var version = 1
        @Volatile var code: Int? = null
        @Volatile var body: String? = null
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request.method.orEmpty() to request.getHeader("If-None-Match")
            code?.let { return MockResponse().setResponseCode(it) }
            val path = request.requestUrl!!.encodedPath
            if (path == "/moved.ics") return MockResponse().setResponseCode(302).setHeader("Location", "/holidays.ics")
            if (path == "/insecure.ics") return MockResponse().setResponseCode(302).setHeader("Location", "http://localhost/holidays.ics")
            val etag = "\"v$version\""
            if (body == null && request.getHeader("If-None-Match") == etag) return MockResponse().setResponseCode(304)
            val text = body ?: ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nX-WR-CALNAME:Holidays\r\nX-APPLE-CALENDAR-COLOR:#1BADF8\r\n" +
                "BEGIN:VEVENT\r\nUID:x\r\nDTSTART;VALUE=DATE:20261225\r\nSUMMARY:Christmas Day\r\nEND:VEVENT\r\n" +
                (if (version >= 2) "BEGIN:VEVENT\r\nUID:y\r\nDTSTART;VALUE=DATE:20261226\r\nSUMMARY:Boxing Day\r\nEND:VEVENT\r\n" else "") +
                "BEGIN:VEVENT\r\nUID:q\r\nDTSTART:20240103T190000Z\r\nDURATION:PT2H\r\nRRULE:FREQ=WEEKLY\r\nSUMMARY:Weekly quiz\r\nEND:VEVENT\r\n" +
                "END:VCALENDAR\r\n")
            return MockResponse().setResponseCode(200).setHeader("ETag", etag).setHeader("Content-Type", "text/calendar; charset=utf-8").setBody(text)
        }
    }
}
