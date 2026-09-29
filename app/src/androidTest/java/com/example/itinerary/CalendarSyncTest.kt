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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile

/** Calendar sync step 1 over real TLS against a local CalDAV fixture, with an isolated database and login store. */
class CalendarSyncTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: MockWebServer
    private lateinit var client: NextcloudClient
    private lateinit var account: NextcloudAccount
    private lateinit var accounts: NextcloudAccountStore
    private lateinit var database: AppDatabase
    private lateinit var sync: CalendarSync
    private lateinit var sandbox: File
    private lateinit var context: Context
    private val fixture = CalDavFixture()
    private val phone by lazy { FakePhone() } // lazy: it uses [zone], declared below
    private var clock = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
    private var zone: ZoneId = ZoneId.of("Australia/Perth")
    private var changes = 0
    private val alias = "planner.nextcloud.calendar-instrumentation"
    private val home = "/remote.php/dav/calendars/bas/"

    @Before fun setUp() {
        val base = instrumentation.targetContext
        sandbox = File(base.cacheDir, "calendar-sync-instrumentation").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(sandbox, "files").apply { mkdirs() }
            override fun getCacheDir() = File(sandbox, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(sandbox, "no_backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("calendar_test_$name", mode)
        }
        database = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.dispatcher = fixture
        server.start()
        client = NextcloudClient(OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build())
        account = NextcloudAccount.create(server.url("/").toString(), "bas", "test-password-only")
        accounts = NextcloudAccountStore(context, alias)
        accounts.save(account)
        sync = CalendarSync(database, accounts, client, onChanged = { changes++ }, now = { clock }, zone = { zone }, phone = phone)
    }

    @After fun tearDown() {
        database.close()
        server.shutdown()
        listOf("settings", "backup_status").forEach { instrumentation.targetContext.deleteSharedPreferences("calendar_test_$it") }
        sandbox.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }

    private val dao get() = database.outsideDao()
    private fun sources() = runBlocking { dao.sources() }
    private fun source(name: String) = sources().single { it.name == name }
    private fun shown() = runBlocking { sync.shown.first() }
    private fun reports() = fixture.requests.filter { it.method == "REPORT" }

    @Test fun findsOnlyEventCalendarsAndStartsThemUnticked() = runBlocking {
        assertTrue(sync.sync())
        assertEquals(listOf("Personal", "Work"), sources().map { it.name })
        assertTrue(sources().none { it.enabled })
        assertEquals(0xFFE01B24.toInt(), source("Personal").color)
        assertNull(source("Work").color)
        assertEquals("$home" + "personal/", source("Personal").href)
        assertTrue(reports().isEmpty()) // nothing ticked, nothing downloaded
        val propfind = fixture.requests.single()
        assertEquals("PROPFIND", propfind.method)
        assertEquals("1", propfind.depth)
        assertTrue(propfind.body.contains("calendar-color") && propfind.body.contains("getctag"))
        assertEquals("Tick the calendars to show in Planner.", sync.state.value.message)
        assertTrue(shown().isEmpty())
    }

    @Test fun tickedCalendarIsDownloadedAndOnlyAgainWhenItChanges() = runBlocking {
        sync.sync()
        sync.setEnabled(source("Personal").id, true)
        sync.sync()
        val report = reports().single()
        assertEquals(home + "personal/", report.path)
        assertTrue(report.body.contains("<c:expand start=\"20260630T160000Z\" end=\"20271031T160000Z\"/>"))
        assertTrue(report.body.contains("<c:time-range start=\"20260630T160000Z\" end=\"20271031T160000Z\"/>"))
        val events = shown().values.map { it.event }
        assertEquals(setOf("Dentist", "School holidays"), events.map { it.title }.toSet()) // the cancelled one is left out
        assertTrue(shown().keys.all { it < 0 })
        val dentist = events.single { it.title == "Dentist" }
        assertEquals(LocalDate.of(2026, 10, 5), dentist.date)
        assertEquals(LocalTime.of(9, 0), dentist.startTime)
        assertEquals(60, dentist.durationMinutes)
        assertEquals("Personal", shown().values.first().calendar)
        assertNotNull(source("Personal").lastSynced)
        assertEquals("Calendars are up to date.", sync.state.value.message)
        assertTrue(changes > 0)

        sync.sync() // unchanged on the server: not downloaded again
        assertEquals(1, reports().size)
        fixture.ctags["personal"] = "p2"
        fixture.events["personal"] = listOf(event("Moved", "20261006T020000Z", "20261006T030000Z"))
        sync.sync()
        assertEquals(2, reports().size)
        assertEquals(listOf("Moved"), shown().values.map { it.event.title })
        zone = ZoneId.of("Europe/London") // a new time zone downloads again, so times stay right
        sync.sync()
        assertEquals(3, reports().size)
        assertEquals(LocalTime.of(3, 0), shown().values.single().event.startTime)
    }

    @Test fun untickingRemovesEventsAtOnce() = runBlocking {
        sync.sync()
        sync.setEnabled(source("Personal").id, true)
        sync.sync()
        assertEquals(2, dao.countFor(source("Personal").id))
        sync.setEnabled(source("Personal").id, false)
        assertEquals(0, dao.countFor(source("Personal").id))
        assertTrue(shown().isEmpty())
        assertNull(source("Personal").ctag)
    }

    @Test fun wrongPasswordAndOfflineKeepWhatWasDownloaded() = runBlocking {
        sync.sync()
        sync.setEnabled(source("Personal").id, true)
        sync.sync()
        fixture.code = 401
        sync.sync()
        assertTrue(sync.state.value.error)
        assertEquals("Nextcloud rejected the login. Check your username and app password.", sync.state.value.message)
        assertEquals(2, shown().size)
        fixture.code = null
        fixture.reportCode = 500
        fixture.ctags["personal"] = "p3"
        sync.sync()
        assertEquals("Synced, but 1 calendar couldn't be downloaded.", sync.state.value.message)
        assertEquals("Nextcloud couldn't send the calendars (HTTP 500). Try again later.", source("Personal").lastError)
        assertEquals(2, shown().size)
        server.shutdown()
        sync.sync()
        assertTrue(sync.state.value.error)
        assertEquals("Couldn't reach Nextcloud. Check your connection and server address.", sync.state.value.message)
        assertEquals(2, shown().size)
    }

    @Test fun calendarsGoneFromTheServerOrFromAnotherLoginAreRemoved() = runBlocking {
        sync.sync()
        sync.setEnabled(source("Personal").id, true)
        sync.sync()
        dao.insertSource(CalendarSource(account = "https://other.example/|someone", href = "/x/", name = "Other login", enabled = true))
        fixture.calendars.remove("personal")
        sync.sync()
        assertEquals(listOf("Work"), sources().map { it.name })
        assertTrue(shown().isEmpty())
        assertEquals(0, runBlocking { database.query("SELECT * FROM outside_events", null).use { it.count } })
    }

    @Test fun openingTheAppSyncsAtMostEveryFifteenMinutes() = runBlocking {
        sync.syncIfDue() // nothing ticked yet: no request at all
        assertTrue(fixture.requests.isEmpty())
        sync.sync()
        sync.setEnabled(source("Personal").id, true)
        val before = fixture.requests.size
        sync.syncIfDue() // the sync just now counts
        assertEquals(before, fixture.requests.size)
        clock += 16 * 60 * 1000L
        sync.syncIfDue()
        assertTrue(fixture.requests.size > before)
        val after = fixture.requests.size
        clock += 5 * 60 * 1000L
        sync.syncIfDue()
        assertEquals(after, fixture.requests.size)
        accounts.clear()
        clock += 60 * 60 * 1000L
        sync.syncIfDue() // no login: nothing
        assertEquals(after, fixture.requests.size)
    }

    @Test fun onlyTheCalendarHomeIsRead() {
        assertThrows(IllegalArgumentException::class.java) {
            client.calendarEvents(account, "/remote.php/dav/files/bas/Planner/", Instant.EPOCH, Instant.EPOCH.plusSeconds(60))
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.calendarEvents(account, home, Instant.EPOCH, Instant.EPOCH.plusSeconds(60))
        }
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun repositoryRefusesToChangeOutsideEvents() = runBlocking {
        val repo = Repository(database, AttachmentStore(context), ReminderScheduler(context))
        val outside = ItineraryItem(id = -5, tripId = OutsideCalendars.TRIP_ID, date = LocalDate.of(2000, 1, 2), startTime = null, title = "Theirs")
        suspend fun refused(block: suspend () -> Unit) {
            try { block(); fail("Changed an outside event") } catch (e: IllegalArgumentException) { assertEquals(OutsideCalendars.READ_ONLY, e.message) }
        }
        refused { repo.saveItem(outside) }
        refused { repo.saveItem(outside.copy(id = 0)) }
        refused { repo.deleteWithUndo(outside) }
        refused { repo.deleteEventsWithUndo(setOf(-5L)) }
        refused { repo.moveToTomorrow(-5) }
        refused { repo.setSkipped(-5, true) }
        refused { repo.setPaid(-5, true) }
        // A copy is an ordinary new event.
        repo.saveItem(outside.copy(id = 0, tripId = 0))
        assertEquals(listOf("Theirs"), repo.snapshot().items.map { it.title })
    }

    @Test fun backupsKeepTickedCalendarsButNoEventsOrPassword() = runBlocking {
        sync.sync()
        sync.setEnabled(source("Personal").id, true)
        sync.sync()
        val settings = SettingsRepository(context)
        val attachments = AttachmentStore(context)
        val repo = Repository(database, attachments, ReminderScheduler(context))
        val backup = BackupManager(context, repo, attachments, settings, sync)
        val file = File(context.cacheDir, "calendar-backup.zip")
        backup.export(Uri.fromFile(file), trackStatus = false)
        val json = ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry("data.json")).readBytes().toString(Charsets.UTF_8) }
        val calendars = JSONObject(json).getJSONArray("calendars")
        assertEquals(1, calendars.length())
        assertEquals(home + "personal/", calendars.getJSONObject(0).getString("href"))
        assertEquals("Personal", calendars.getJSONObject(0).getString("name"))
        assertFalse(json.contains("Dentist"))
        assertFalse(json.contains("School holidays"))
        assertFalse(json.contains("test-password-only"))

        sync.clearAll() // as after disconnecting
        assertTrue(sources().isEmpty())
        backup.restore(backup.stage(Uri.fromFile(file)))
        assertEquals(listOf("Personal"), sources().filter { it.enabled }.map { it.name })
        assertTrue(shown().isEmpty()) // events come back from the server, not the backup
        sync.sync()
        assertEquals(2, shown().size)
        assertEquals(listOf("Personal", "Work"), sources().map { it.name })
    }

    @Test fun olderBackupsLeaveCalendarsAlone() = runBlocking {
        sync.sync()
        sync.setEnabled(source("Work").id, true)
        val settings = SettingsRepository(context)
        val attachments = AttachmentStore(context)
        val repo = Repository(database, attachments, ReminderScheduler(context))
        val file = File(context.cacheDir, "old-backup.zip")
        BackupManager(context, repo, attachments, settings).export(Uri.fromFile(file), trackStatus = false) // no calendars key
        val old = File(context.cacheDir, "old-backup-stripped.zip")
        java.util.zip.ZipOutputStream(old.outputStream()).use { out ->
            ZipFile(file).use { zip ->
                val json = JSONObject(zip.getInputStream(zip.getEntry("data.json")).readBytes().toString(Charsets.UTF_8))
                json.remove("calendars")
                out.putNextEntry(java.util.zip.ZipEntry("data.json")); out.write(json.toString().toByteArray()); out.closeEntry()
            }
        }
        val backup = BackupManager(context, repo, attachments, settings, sync)
        backup.restore(backup.stage(Uri.fromFile(old)))
        assertEquals(listOf("Work"), sources().filter { it.enabled }.map { it.name })
    }

    // ---- Step 3: the phone's own calendars (a fake phone; no real calendar storage is touched) ----

    private fun millis(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun phoneCalendarsNeedPermissionAndStartUnticked() = runBlocking {
        phone.allowed = false
        sync.refreshPhone()
        assertTrue(sources().isEmpty())
        phone.allowed = true
        sync.refreshPhone()
        assertEquals(listOf("Family", "Work phone"), sources().map { it.name })
        assertTrue(sources().all { it.kind == OutsideCalendars.KIND_PHONE && !it.enabled })
        assertEquals("me@gmail.com", source("Family").detail)
        assertTrue(source("Work phone").detail!!.contains("DAVx⁵"))
        assertTrue(phone.read.isEmpty()) // nothing ticked, no events read
        sync.setPhoneEnabled(source("Family").id, true)
        assertEquals(listOf(listOf(11L)), phone.read)
        val shownNow = shown().values.toList()
        assertEquals(listOf("Swimming"), shownNow.map { it.event.title })
        assertEquals(OutsideCalendars.KIND_PHONE, shownNow.single().kind)
        assertEquals(LocalTime.of(16, 0), shownNow.single().event.startTime)
        assertEquals(LocalDate.of(2026, 10, 3), shownNow.single().event.date)
        assertNotNull(source("Family").lastSynced)
    }

    @Test fun phoneChangesAreReadAndUnchangedOnesLeaveTheRowsAlone() = runBlocking {
        phone.allowed = true
        sync.refreshPhone()
        sync.setPhoneEnabled(source("Family").id, true)
        val before = changes
        sync.refreshPhone() // nothing changed on the phone
        assertEquals(before, changes)
        phone.events[11L] = listOf(PhoneInstance(11, millis(LocalDate.of(2026, 10, 4), 9), millis(LocalDate.of(2026, 10, 4), 10), false,
            "Swimming (moved)", null, null))
        sync.refreshPhone()
        assertTrue(changes > before)
        assertEquals(listOf("Swimming (moved)"), shown().values.map { it.event.title })
        phone.calendars.removeAll { it.id == 11L } // the calendar left the phone
        sync.refreshPhone()
        assertEquals(listOf("Work phone"), sources().map { it.name })
        assertTrue(shown().isEmpty())
    }

    @Test fun losingThePermissionRemovesPhoneEvents() = runBlocking {
        phone.allowed = true
        sync.refreshPhone()
        sync.setPhoneEnabled(source("Family").id, true)
        assertEquals(1, shown().size)
        phone.allowed = false
        sync.refreshPhone()
        assertTrue(shown().isEmpty())
        assertEquals(CalendarSync.PERMISSION_NEEDED, source("Family").lastError)
        assertTrue(sync.phoneState.value.error)
        phone.allowed = true // allowed again: back without ticking again
        sync.refreshPhone()
        assertNull(source("Family").lastError)
        assertEquals(1, shown().size)
    }

    @Test fun nextcloudAndPhoneCalendarsLeaveEachOtherAlone() = runBlocking {
        phone.allowed = true
        sync.refreshPhone()
        sync.setPhoneEnabled(source("Family").id, true)
        sync.sync() // Nextcloud: finds its own calendars, must not remove the phone's
        sync.setEnabled(source("Personal").id, true)
        sync.sync()
        assertEquals(listOf("Family", "Personal", "Work", "Work phone"), sources().map { it.name })
        assertEquals(setOf("Swimming", "Dentist", "School holidays"), shown().values.map { it.event.title }.toSet())
        // Backups keep only the Nextcloud ticks; restoring one leaves the phone's calendars as they are.
        assertEquals(listOf("Personal"), sync.choices().map { it.name })
        sync.restoreChoices(sync.choices())
        assertTrue(source("Family").enabled)
        assertEquals(listOf("Swimming"), shown().values.map { it.event.title })
        // Disconnecting Nextcloud removes only Nextcloud calendars.
        sync.sync()
        sync.clearNextcloud()
        assertEquals(listOf("Family", "Work phone"), sources().map { it.name })
        assertEquals(listOf("Swimming"), shown().values.map { it.event.title })
    }

    private inner class FakePhone : PhoneCalendarReader {
        @Volatile var allowed = false
        val calendars = CopyOnWriteArrayList(listOf(PhoneCalendar(11, "Family", "me@gmail.com", "com.google", 0xFF3366CC.toInt()),
            PhoneCalendar(12, "Work phone", "cloud", "bitfire.at.davdroid", null)))
        val events = java.util.concurrent.ConcurrentHashMap(mapOf(
            11L to listOf(PhoneInstance(11, millis(LocalDate.of(2026, 10, 3), 16), millis(LocalDate.of(2026, 10, 3), 17), false, "Swimming", "Pool", null),
                PhoneInstance(11, millis(LocalDate.of(2026, 10, 6), 16), millis(LocalDate.of(2026, 10, 6), 17), false, "Called off", null, null, cancelled = true)),
            12L to listOf(PhoneInstance(12, millis(LocalDate.of(2026, 10, 3), 8), millis(LocalDate.of(2026, 10, 3), 9), false, "Standup", null, null)),
        ))
        val read = CopyOnWriteArrayList<List<Long>>()
        override fun permitted() = allowed
        override fun calendars(): List<PhoneCalendar> = if (allowed) calendars.toList() else emptyList()
        override fun instances(calendarIds: Collection<Long>, from: Instant, until: Instant): List<PhoneInstance> {
            if (!allowed) return emptyList()
            read += calendarIds.sorted()
            return calendarIds.flatMap { events[it].orEmpty() }.filter { it.begin < until.toEpochMilli() && it.end > from.toEpochMilli() }
        }
    }

    private fun event(title: String, start: String, end: String, extra: String = "") =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:${title.hashCode()}\r\nDTSTART:$start\r\nDTEND:$end\r\nSUMMARY:$title\r\n${extra}END:VEVENT\r\nEND:VCALENDAR\r\n"

    private class Request(val method: String, val path: String, val depth: String?, val body: String)

    // A calendar home with two event calendars, a task list, the scheduling inbox, the trash bin and an entry on another
    // host that must be ignored. [code] answers every request with that status; [reportCode] only event downloads.
    private inner class CalDavFixture : Dispatcher() {
        val requests = CopyOnWriteArrayList<Request>()
        val calendars = linkedMapOf("personal" to "Personal", "work" to "Work")
        val ctags = mutableMapOf("personal" to "p1", "work" to "w1")
        val events = mutableMapOf(
            "personal" to listOf(
                event("Dentist", "20261005T010000Z", "20261005T020000Z"),
                event("School holidays", "20261010", "20261020").replace("DTSTART:", "DTSTART;VALUE=DATE:").replace("DTEND:", "DTEND;VALUE=DATE:"),
                event("Called off", "20261007T010000Z", "20261007T020000Z", "STATUS:CANCELLED\r\n"),
            ),
            "work" to listOf(event("Standup", "20261005T000000Z", "20261005T001500Z")),
        )
        @Volatile var code: Int? = null
        @Volatile var reportCode: Int? = null

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl!!.encodedPath
            requests += Request(request.method.orEmpty(), path, request.getHeader("Depth"), request.body.readUtf8())
            code?.let { return MockResponse().setResponseCode(it) }
            if (request.getHeader("Authorization") != Credentials.basic("bas", "test-password-only", Charsets.UTF_8))
                return MockResponse().setResponseCode(401)
            return when {
                request.method == "PROPFIND" && path == home -> MockResponse().setResponseCode(207).setBody(multistatus(
                    """<d:response><d:href>$home</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>""" +
                        calendars.entries.joinToString("") { (slug, name) ->
                            val colour = if (slug == "personal") "<x1:calendar-color>#E01B24FF</x1:calendar-color>" else ""
                            val components = if (slug == "personal") "<cal:supported-calendar-component-set><cal:comp name=\"VEVENT\"/></cal:supported-calendar-component-set>" else ""
                            """<d:response><d:href>$home$slug/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><d:displayname>$name</d:displayname>$colour<cs:getctag>${ctags[slug]}</cs:getctag>$components</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
                        } +
                        """<d:response><d:href>${home}tasks/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><d:displayname>Tasks</d:displayname><cal:supported-calendar-component-set><cal:comp name="VTODO"/></cal:supported-calendar-component-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>""" +
                        """<d:response><d:href>${home}inbox/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:schedule-inbox/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>""" +
                        """<d:response><d:href>${home}trashbin/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><nc:trash-bin/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>""" +
                        """<d:response><d:href>https://evil.example${home}stolen/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><d:displayname>Stolen</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""))
                request.method == "REPORT" -> {
                    reportCode?.let { return MockResponse().setResponseCode(it) }
                    val slug = path.removePrefix(home).trimEnd('/')
                    val list = events[slug] ?: return MockResponse().setResponseCode(404)
                    MockResponse().setResponseCode(207).setBody(multistatus(list.mapIndexed { i, data ->
                        """<d:response><d:href>$home$slug/$i.ics</d:href><d:propstat><d:prop><d:getetag>"$i"</d:getetag><cal:calendar-data>${data.replace("&", "&amp;").replace("<", "&lt;")}</cal:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
                    }.joinToString("")))
                }
                else -> MockResponse().setResponseCode(405)
            }
        }

        private fun multistatus(body: String) = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:x1="http://apple.com/ns/ical/" xmlns:nc="http://nextcloud.com/ns">$body</d:multistatus>"""
    }
}
