package com.example.itinerary

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Calendar sync step 6 (two-way) over real TLS against a CalDAV fixture with ETags, a ctag, time-range and multiget
 *  queries and conditional writes. Checks each case of the plan and that another calendar is never touched. */
class CalendarTwoWayTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: MockWebServer
    private lateinit var database: AppDatabase
    private lateinit var repo: Repository
    private lateinit var sync: CalendarSync
    private lateinit var sandbox: File
    private lateinit var context: Context
    private val dav = FakeCalDav("/remote.php/dav/calendars/bas/", "bas", "test-password-only")
    private var clock = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
    private val alias = "planner.nextcloud.twoway-instrumentation"
    private val home = "/remote.php/dav/calendars/bas/"
    private val synced = "${home}planner/"
    private val other = "${home}work/"
    private val day = LocalDate.of(2026, 10, 5)
    private var zone: java.time.ZoneId = ZoneOffset.UTC

    @Before fun setUp() {
        val base = instrumentation.targetContext
        sandbox = File(base.cacheDir, "calendar-twoway-instrumentation").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(sandbox, "files").apply { mkdirs() }
            override fun getCacheDir() = File(sandbox, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(sandbox, "no_backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences("twoway_test_$name", mode)
        }
        database = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        repo = Repository(database, AttachmentStore(context), ReminderScheduler(context))
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.dispatcher = dav
        server.start()
        val accounts = NextcloudAccountStore(context, alias)
        accounts.save(NextcloudAccount.create(server.url("/").toString(), "bas", "test-password-only"))
        sync = CalendarSync(database, accounts, NextcloudClient(OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build()),
            now = { clock }, zone = { zone }, planner = repo.asPlannerStore(),
            pendingDeleted = { repo.pendingDeletions.value.flatMap { it.items }.mapTo(HashSet()) { it.id } })
        dav.put("${other}untouchable.ics", ics("w1", "Work thing", "20261006T090000Z"))
    }

    @After fun tearDown() {
        database.close()
        server.shutdown()
        listOf("settings", "backup_status").forEach { instrumentation.targetContext.deleteSharedPreferences("twoway_test_$it") }
        sandbox.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }

    private fun ics(uid: String, title: String, start: String, vararg extra: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Nextcloud//EN\r\n" +
        "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTART:$start\r\nDURATION:PT1H\r\nSUMMARY:$title\r\n${extra.joinToString("") { "$it\r\n" }}END:VEVENT\r\nEND:VCALENDAR\r\n"
    private fun items() = runBlocking { repo.snapshot().items }
    private fun item(title: String) = items().single { it.title == title }
    private fun rows() = runBlocking { database.sentDao().all() }
    private fun conflicts() = rows().filter { it.problem == SentEvent.CONFLICT }
    private fun plannerFile(title: String) = dav.files.entries.single { it.key.startsWith(synced) && it.value.second.contains("SUMMARY:$title") }
    private fun writes() = dav.requests.filter { it.first == "PUT" || it.first == "DELETE" }
    private suspend fun start() {
        sync.sync()
        sync.setSendTarget(runBlocking { database.outsideDao().sources() }.single { it.name == "Planner" }.id)
    }
    private suspend fun save(title: String, time: Int = 9) = repo.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(time, 0),
        durationMinutes = 60, title = title, checklist = listOf(ChecklistEntry("c", "Only in Planner", false))))
    private suspend fun syncAgain() { clock += CalendarSync.MIN_INTERVAL_MS; sync.sync(); sync.send() }
    private fun assertOtherCalendarUntouched() {
        assertTrue(writes().none { it.second.startsWith(other) })
        assertEquals("\"w-orig\"", dav.files["${other}untouchable.ics"]!!.first)
    }

    @Test fun eventsCreatedOnNextcloudBecomePlannerEventsAndKeepWhatPlannerDoesntHave() = runBlocking {
        dav.put("${synced}from-web.ics", ics("web-1", "Made on the web", "20261007T090000Z",
            "ATTENDEE;CN=Alex:mailto:alex@example.com", "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Reminder", "TRIGGER:-PT15M", "END:VALARM"))
        dav.put("${synced}weekly.ics", ics("rep-1", "Weekly on the web", "20261008T090000Z", "RRULE:FREQ=WEEKLY;COUNT=3"))
        dav.put("${synced}ancient.ics", ics("old-1", "Long ago", "20240101T090000Z"))
        start()
        assertEquals(listOf("Made on the web"), items().map { it.title })
        // The repeating one is shown read-only, the one from long ago is left alone.
        assertEquals(List(3) { "Weekly on the web" }, sync.shown.first().values.map { it.event.title })
        assertTrue(writes().isEmpty()) // bringing it in writes nothing back
        // Edited in Planner: only the managed properties change there, attendee and alarm stay.
        repo.saveItem(item("Made on the web").copy(title = "Made on the web (moved)", startTime = LocalTime.of(11, 0)))
        sync.send()
        val file = dav.files["${synced}from-web.ics"]!!.second
        assertTrue(file.contains("SUMMARY:Made on the web (moved)") && file.contains("DTSTART:20261007T110000Z"))
        assertTrue(file.contains("ATTENDEE;CN=Alex:mailto:alex@example.com") && file.contains("DESCRIPTION:Reminder") && file.contains("UID:web-1"))
        assertEquals("PUT", writes().single().first)
        assertNotNull(writes().single().third) // If-Match
        // A backup keeps which file it is: after a restore, an edit still updates that file (not a new one).
        val backup = BackupManager(context, repo, AttachmentStore(context), SettingsRepository(context), sync)
        val zip = File(context.cacheDir, "twoway-backup.zip")
        backup.export(android.net.Uri.fromFile(zip), trackStatus = false)
        sync.restoreSend(null, emptyList())
        backup.restore(backup.stage(android.net.Uri.fromFile(zip)))
        repo.saveItem(item("Made on the web (moved)").copy(location = "After restore"))
        sync.send()
        assertTrue(dav.files["${synced}from-web.ics"]!!.second.contains("LOCATION:After restore"))
        assertEquals(1, dav.files.count { it.key.startsWith(synced) && it.value.second.contains("Made on the web") })
        assertOtherCalendarUntouched()
    }

    @Test fun changesOnNextcloudComeIntoPlannerAndPlannerOnlyDetailsStay() = runBlocking {
        save("QA Dentist")
        start()
        val (path, _) = plannerFile("QA Dentist")
        dav.edit(path) { it.replace("SUMMARY:QA Dentist", "SUMMARY:QA Dentist (moved)").replace("DTSTART:20261005T090000Z", "DTSTART:20261005T150000Z") }
        val writesBefore = writes().size
        syncAgain()
        val moved = item("QA Dentist (moved)")
        assertEquals(LocalTime.of(15, 0), moved.startTime)
        assertEquals("Only in Planner", moved.checklist.single().text)
        assertEquals(writesBefore, writes().size) // taken in, not echoed back
        assertTrue(conflicts().isEmpty())
        // Nothing changed on the server: its change marker says so and nothing is listed again.
        val listings = dav.requests.count { it.first == "PROPFIND" && it.second == synced }
        syncAgain()
        assertEquals(listings, dav.requests.count { it.first == "PROPFIND" && it.second == synced })
        assertOtherCalendarUntouched()
    }

    @Test fun deletedOnNextcloudGoesToRecentlyDeleted() = runBlocking {
        save("QA Cancelled there")
        start()
        dav.files.remove(plannerFile("QA Cancelled there").key); dav.bump()
        syncAgain()
        assertTrue(items().none { it.title == "QA Cancelled there" })
        assertEquals(1, repo.snapshot().deleted.size)
        assertTrue(repo.pendingDeletions.value.isEmpty()) // no Undo message for a deletion made elsewhere
        assertTrue(rows().none { it.uid != null })
        repo.restoreDeleted(repo.snapshot().deleted.single().id) // restoring it sends it again
        sync.send()
        assertEquals(1, dav.files.count { it.key.startsWith(synced) && it.value.second.contains("QA Cancelled there") })
    }

    @Test fun changedInBothPlacesIsAConflictUntilChosen() = runBlocking {
        save("QA A"); save("QA B", 10); save("QA C", 11)
        start()
        listOf("QA A", "QA B", "QA C").forEach { title ->
            dav.edit(plannerFile(title).key) { it.replace("SUMMARY:$title", "SUMMARY:$title web") }
            repo.saveItem(item(title).copy(location = "Planner room"))
        }
        val before = writes().size
        syncAgain()
        assertEquals(3, conflicts().size)
        assertEquals(before, writes().size) // nothing written while undecided
        assertTrue(sync.sendState.value.message!!.startsWith("3 events were changed in both places"))
        fun conflictFor(title: String) = conflicts().single { it.conflict!!.contains("SUMMARY:$title web") }
        // Keep Planner's: Planner's version goes there (and keeps nothing of theirs but what Planner doesn't manage).
        sync.resolve(conflictFor("QA A").id, CalendarSync.Resolution.PLANNER)
        assertTrue(dav.files.values.any { it.second.contains("SUMMARY:QA A\r\n") && it.second.contains("LOCATION:Planner room") })
        // Keep Nextcloud's: theirs comes into Planner.
        sync.resolve(conflictFor("QA B").id, CalendarSync.Resolution.NEXTCLOUD)
        assertEquals("", item("QA B web").location)
        // Keep both: two Planner events, two files.
        sync.resolve(conflictFor("QA C").id, CalendarSync.Resolution.BOTH)
        sync.send()
        assertEquals(setOf("QA C", "QA C web"), items().map { it.title }.filter { it.startsWith("QA C") }.toSet())
        assertEquals(1, dav.files.count { it.value.second.contains("SUMMARY:QA C web") })
        assertEquals(1, dav.files.count { it.value.second.contains("SUMMARY:QA C\r\n") })
        assertTrue(conflicts().isEmpty())
        assertOtherCalendarUntouched()
    }

    @Test fun deletedOnOneSideChangedOnTheOther() = runBlocking {
        save("QA Deleted here"); save("QA Deleted there", 10)
        start()
        // Deleted in Planner (Undo passed) while edited on Nextcloud → conflict; keeping Nextcloud's brings it back.
        dav.edit(plannerFile("QA Deleted here").key) { it.replace("SUMMARY:QA Deleted here", "SUMMARY:QA Deleted here web") }
        repo.deleteWithUndo(item("QA Deleted here")); repo.finishDeletion(repo.pendingDeletions.value.single().token)
        // Edited in Planner while deleted on Nextcloud → conflict; keeping Planner's puts it back there.
        repo.saveItem(item("QA Deleted there").copy(location = "Still on"))
        dav.files.remove(plannerFile("QA Deleted there").key); dav.bump()
        syncAgain()
        assertEquals(2, conflicts().size)
        assertTrue(dav.files.values.any { it.second.contains("QA Deleted here web") }) // nothing is deleted there while undecided
        sync.resolve(conflicts().single { it.conflict != "" }.id, CalendarSync.Resolution.NEXTCLOUD)
        assertEquals("QA Deleted here web", items().single { it.title.startsWith("QA Deleted here") }.title)
        sync.resolve(conflicts().single().id, CalendarSync.Resolution.PLANNER)
        assertTrue(dav.files.values.any { it.second.contains("SUMMARY:QA Deleted there") && it.second.contains("LOCATION:Still on") })
        assertTrue(conflicts().isEmpty())
    }

    private fun plannerFiles() = dav.files.keys.filter { it.startsWith(synced) }

    @Test fun reconnectingLinksTheFilesAlreadyThereInsteadOfCopyingThem() = runBlocking {
        dav.put("${synced}from-web.ics", ics("web-1", "QA Made on the web", "20261007T090000Z"))
        save("QA Kept"); save("QA Edited there", 10); save("QA Edited here", 11)
        repo.saveItem(ItineraryItem(tripId = 0, date = day.plusMonths(20), startTime = null, title = "QA Far ahead"))
        start()
        assertEquals(5, items().size); assertEquals(5, plannerFiles().size)
        // Disconnect (the record of what was sent goes), change a little on each side, then connect the same calendar.
        sync.clearNextcloud()
        dav.edit(plannerFile("QA Edited there").key) { it.replace("DTSTART:20261005T100000Z", "DTSTART:20261005T160000Z") }
        repo.saveItem(item("QA Edited here").copy(location = "Room 2"))
        val before = writes().size
        start()
        assertEquals(5, items().size) // nothing brought in twice
        assertEquals(5, plannerFiles().size) // nothing sent twice
        assertEquals(before, writes().size)
        // Edited on one side while apart: which to keep is the user's choice, then it's settled in place.
        assertEquals(2, conflicts().size)
        conflicts().forEach { sync.resolve(it.id, CalendarSync.Resolution.PLANNER) }
        assertEquals(5, plannerFiles().size)
        assertTrue(plannerFile("QA Edited here").value.second.contains("LOCATION:Room 2"))
        // Linked for good: an edit updates its own file.
        repo.saveItem(item("QA Kept").copy(notes = "After reconnecting"))
        sync.send()
        assertTrue(plannerFile("QA Kept").value.second.contains("DESCRIPTION:After reconnecting"))
        assertEquals(5, plannerFiles().size)
        assertOtherCalendarUntouched()
    }

    @Test fun aLostReplyNeverMakesASecondFile() = runBlocking {
        start()
        dav.loseReplies = 1
        save("QA Lost reply")
        sync.send() // the file arrives, the reply doesn't (OkHttp may retry, which then finds it there)
        assertNotNull(rows().single().uid)
        assertTrue(rows().single().problem in setOf(null, SentEvent.PENDING))
        sync.send(); syncAgain(); syncAgain()
        assertEquals(1, items().size)
        assertEquals(1, plannerFiles().size)
        assertNull(rows().single().problem)
        repo.saveItem(item("QA Lost reply").copy(location = "Found"))
        sync.send()
        assertTrue(plannerFile("QA Lost reply").value.second.contains("LOCATION:Found"))
        assertEquals(1, plannerFiles().size)
    }

    @Test fun madeRepeatingOnNextcloudStaysReadOnlyAndIsNotSentAgain() = runBlocking {
        save("QA Now weekly")
        start()
        dav.edit(plannerFile("QA Now weekly").key) { it.replace("SUMMARY:QA Now weekly", "SUMMARY:QA Now weekly\r\nRRULE:FREQ=WEEKLY;COUNT=3") }
        val before = writes().size
        syncAgain(); syncAgain()
        assertEquals(before, writes().size)
        assertEquals(1, plannerFiles().size)
        assertEquals(1, items().size) // Planner's event stays as it was
        assertEquals(SentEvent.DETACHED, rows().single().problem)
        assertEquals(3, sync.shown.first().values.count { it.event.title == "QA Now weekly" })
        // Edited in Planner: still not sent (Nextcloud's is the series now).
        repo.saveItem(item("QA Now weekly").copy(location = "Gym"))
        syncAgain()
        assertEquals(before, writes().size)
        assertEquals(1, items().size)
    }

    @Test fun aNewTimeZoneAloneSendsNothing() = runBlocking {
        save("QA Travelling")
        start()
        val before = writes().size
        zone = java.time.ZoneId.of("Australia/Perth")
        syncAgain()
        assertEquals(before, writes().size)
        // A row from before this change (fingerprint in the phone's zone) is accepted once and brought up to date.
        val row = rows().single()
        database.sentDao().put(row.copy(fingerprint = CalendarSync.zonedFingerprint(item("QA Travelling"), zone)))
        syncAgain()
        assertEquals(before, writes().size)
        assertEquals(CalendarSync.fingerprint(item("QA Travelling")), rows().single().fingerprint)
        // A real edit is still sent, with the times of the phone's zone now — even one moved by exactly the zone's offset
        // (17:00 in Perth is the 09:00 UTC it was).
        repo.saveItem(item("QA Travelling").copy(startTime = LocalTime.of(17, 0)))
        sync.send()
        assertEquals(before + 1, writes().size)
        assertTrue(plannerFile("QA Travelling").value.second.contains("DTSTART:20261005T090000Z"))
    }

    @Test fun aLostReplyThenDeletedInPlannerIsDeletedOnNextcloudToo() = runBlocking {
        save("QA Kept")
        start()
        save("QA Lost then deleted", 10)
        sync.send()
        // As if the reply had been lost: pending, its version unknown.
        val row = rows().single { it.itemId == item("QA Lost then deleted").id }
        database.sentDao().put(row.copy(etag = null, ics = null, problem = SentEvent.PENDING))
        repo.deleteWithUndo(item("QA Lost then deleted")); repo.finishDeletion(repo.pendingDeletions.value.single().token)
        syncAgain(); dav.bump(); syncAgain()
        assertEquals(listOf("QA Kept"), items().map { it.title }) // not brought back in
        assertTrue(dav.files.values.none { it.second.contains("QA Lost then deleted") })
        assertTrue(rows().none { it.problem != null })
        assertOtherCalendarUntouched()
    }

    @Test fun reconnectingLinksPastEventsBeforeThePullWindowToo() = runBlocking {
        // The window starts on 1 July (three months back); this one is from March and already on Nextcloud.
        repo.saveItem(ItineraryItem(tripId = 0, date = LocalDate.of(2026, 3, 5), startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "QA Long ago"))
        val uid = "planner-00000001-aaaa-bbbb-cccc-dddddddddddd@planner"
        dav.put("${synced}$uid.ics", ics(uid, "QA Long ago", "20260305T090000Z"))
        start()
        val before = writes().size
        repo.saveItem(item("QA Long ago").copy(location = "Room 3"))
        sync.send()
        // Its own file is updated, not a second one written.
        assertEquals(before + 1, writes().size)
        assertEquals(1, dav.files.count { it.key.startsWith(synced) && it.value.second.contains("QA Long ago") })
        assertTrue(dav.files["${synced}$uid.ics"]!!.second.contains("LOCATION:Room 3"))
        assertOtherCalendarUntouched()
    }

    @Test fun anEventInYear9999NeitherBreaksThePullNorComesIn() = runBlocking {
        dav.put("${synced}far.ics", ics("web-far", "QA Year 9999", "99990105T090000Z"))
        dav.put("${synced}near.ics", ics("web-near", "QA Near", "20261007T090000Z"))
        save("QA Moved far")
        start()
        assertEquals("Calendars are up to date.", sync.state.value.message)
        assertEquals(setOf("QA Near", "QA Moved far"), items().map { it.title }.toSet())
        // A synced event moved to 9999 on Nextcloud: read-only from then on; Planner's event stays and the pull still works.
        dav.edit(plannerFile("QA Moved far").key) { it.replace("DTSTART:20261005T090000Z", "DTSTART:99991005T090000Z") }
        syncAgain()
        assertEquals("Calendars are up to date.", sync.state.value.message)
        assertEquals(SentEvent.DETACHED, rows().single { it.itemId == item("QA Moved far").id }.problem)
        assertEquals(day, item("QA Moved far").date)
        assertOtherCalendarUntouched()
    }

    @Test fun offlineKeepsEverythingAndOtherCalendarsAreNeverWritten() = runBlocking {
        save("QA Offline")
        start()
        dav.code = 503
        repo.saveItem(item("QA Offline").copy(location = "Later"))
        syncAgain()
        assertTrue(sync.state.value.error || sync.sendState.value.error)
        assertEquals(1, items().size)
        dav.code = null
        syncAgain()
        assertTrue(plannerFile("QA Offline").value.second.contains("LOCATION:Later"))
        assertOtherCalendarUntouched()
    }
}
