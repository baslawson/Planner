package com.example.itinerary

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

    // Edited in Planner while a pull is running (after it read Planner's events): the pull doesn't save Nextcloud's version
    // over the edit or move the event to Recently deleted; each is a conflict to choose.
    @Test fun anEditMadeWhileThePullRunsIsNeverOverwritten() = runBlocking {
        save("QA Moved there"); save("QA Deleted there", 10)
        start()
        dav.edit(plannerFile("QA Moved there").key) { it.replace("SUMMARY:QA Moved there", "SUMMARY:QA Moved there web") }
        dav.files.remove(plannerFile("QA Deleted there").key); dav.bump()
        // An event after the window, not sent yet: the pull then reads the files there too, after it read Planner's events.
        repo.saveItem(ItineraryItem(tripId = 0, date = day.plusMonths(20), startTime = null, title = "QA Far ahead"))
        val after = "20271101" // the day after the window ends (clock 1 October 2026)
        dav.onQuery = { start -> if (start >= after) runBlocking {
            dav.onQuery = null
            listOf("QA Moved there", "QA Deleted there").forEach { repo.saveItem(item(it).copy(location = "Typed meanwhile")) }
        } }
        clock += CalendarSync.MIN_INTERVAL_MS
        sync.sync()
        assertTrue(dav.queries.any { it >= after }) // the edits really came in during the pull
        assertEquals(listOf("Typed meanwhile", "Typed meanwhile"),
            listOf("QA Moved there", "QA Deleted there").map { item(it).location })
        assertEquals(2, conflicts().size)
        assertTrue(repo.snapshot().deleted.isEmpty())
    }

    // Deleted in Planner and sent before the next pull, while Nextcloud changed it: the refused delete keeps the row, so
    // the pull makes it a conflict instead of bringing the file back as a new Planner event.
    @Test fun aDeleteRefusedByNextcloudBecomesAConflictNotANewEvent() = runBlocking {
        save("QA Deleted here edited there")
        start()
        val path = plannerFile("QA Deleted here edited there").key
        dav.edit(path) { it.replace("SUMMARY:QA Deleted here edited there", "SUMMARY:QA Edited there") }
        repo.deleteWithUndo(item("QA Deleted here edited there")); repo.finishDeletion(repo.pendingDeletions.value.single().token)
        sync.send()
        assertTrue(dav.files.containsKey(path)) // the server refused the delete
        assertEquals(SentEvent.CHANGED, rows().single().problem)
        syncAgain()
        assertTrue(items().isEmpty()) // not brought back as a new event
        assertTrue(conflicts().single().conflict!!.contains("SUMMARY:QA Edited there"))
        sync.resolve(conflicts().single().id, CalendarSync.Resolution.NEXTCLOUD)
        assertEquals(listOf("QA Edited there"), items().map { it.title })
        assertTrue(conflicts().isEmpty())
    }

    // E2: made a bill in Planner while edited on Nextcloud: the refused delete keeps the row, as for a deleted event, so the
    // pull makes it a conflict instead of bringing the file in as a second event. Keeping Nextcloud's makes it an event of
    // its own; the bill stays.
    @Test fun aCopyRefusedOnTheWayOutBecomesAConflictNotADuplicate() = runBlocking {
        save("QA Turned bill")
        start()
        val path = plannerFile("QA Turned bill").key
        dav.edit(path) { it.replace("SUMMARY:QA Turned bill", "SUMMARY:QA Edited there") }
        repo.saveItem(item("QA Turned bill").copy(category = "Bills", billAmountMinor = 1000, startTime = null, durationMinutes = null))
        sync.send()
        assertTrue(dav.files.containsKey(path)) // the server refused the delete
        assertEquals(SentEvent.CHANGED, rows().single().problem)
        syncAgain()
        assertEquals(listOf("QA Turned bill"), items().map { it.title }) // not brought in as a new event
        assertTrue(conflicts().single().conflict!!.contains("SUMMARY:QA Edited there"))
        sync.resolve(conflicts().single().id, CalendarSync.Resolution.NEXTCLOUD)
        assertEquals(setOf("QA Turned bill", "QA Edited there"), items().map { it.title }.toSet())
        assertEquals("Bills", item("QA Turned bill").category)
        assertTrue(conflicts().isEmpty())
        syncAgain()
        assertEquals(listOf(path), plannerFiles()) // Nextcloud's file stays, now the new event's
        assertEquals(2, items().size)
        // Keeping Planner's instead: the file goes and the bill stays.
        val other = plannerFile("QA Edited there").key
        dav.edit(other) { it.replace("SUMMARY:QA Edited there", "SUMMARY:QA Edited there again") }
        repo.saveItem(item("QA Edited there").copy(category = "Bills", billAmountMinor = 500, startTime = null, durationMinutes = null))
        sync.send(); syncAgain()
        sync.resolve(conflicts().single().id, CalendarSync.Resolution.PLANNER)
        assertTrue(plannerFiles().isEmpty())
        assertEquals(2, items().count { it.category == "Bills" })
        assertTrue(rows().isEmpty())
        assertOtherCalendarUntouched()
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

    // D2: a backup from before backups kept what was sent ("calendarSend") must not leave the current record in place:
    // its event ids now mean other events, and the next send overwrote (or deleted) the wrong files on Nextcloud.
    @Test fun restoringABackupWithoutTheSendRecordRelinksInsteadOfOverwriting() = runBlocking {
        save("QA Alpha"); save("QA Gamma", 11)
        start()
        assertEquals(2, plannerFiles().size)
        val gammaFile = plannerFile("QA Gamma").key
        val backup = BackupManager(context, repo, AttachmentStore(context), SettingsRepository(context), sync)
        val zip = File(context.cacheDir, "twoway-old-backup.zip")
        backup.export(android.net.Uri.fromFile(zip), trackStatus = false)
        // As a backup from before calendar sync (no "calendars", no "calendarSend": the synced calendar stays chosen), and
        // the event Planner sent as QA Gamma is QA Beta there (same id).
        val json = org.json.JSONObject(java.util.zip.ZipFile(zip).use { z -> z.getInputStream(z.getEntry("data.json")).bufferedReader().readText() })
        json.remove("calendarSend"); json.remove("calendars")
        val events = json.getJSONArray("items")
        for (i in 0 until events.length()) events.getJSONObject(i).let { if (it.getString("title") == "QA Gamma") it.put("title", "QA Beta") }
        java.util.zip.ZipOutputStream(zip.outputStream()).use { it.putNextEntry(java.util.zip.ZipEntry("data.json")); it.write(json.toString().toByteArray()); it.closeEntry() }
        val before = writes().size
        backup.restore(backup.stage(android.net.Uri.fromFile(zip)))
        assertTrue(rows().isEmpty())
        assertNull(database.outsideDao().sources().single { it.sendHere }.fetchedFor)
        sync.send()
        assertEquals(before, writes().size) // nothing is written on the strength of the old record
        syncAgain()
        // Gamma's file is left as it was; Alpha is linked, not sent again. Beta sits where Planner's Gamma file is (same
        // day and time; see relink), so it is linked to that file as a choice for the user — nothing is overwritten.
        assertTrue(dav.files[gammaFile]!!.second.contains("SUMMARY:QA Gamma"))
        assertEquals(2, plannerFiles().size)
        assertEquals(1, plannerFiles().count { dav.files[it]!!.second.contains("SUMMARY:QA Alpha") })
        val beta = items().single { it.title == "QA Beta" }
        assertEquals(SentEvent.CONFLICT, rows().single { it.itemId == beta.id }.problem)
        assertEquals(setOf("QA Alpha", "QA Beta"), items().map { it.title }.toSet())
        assertTrue(writes().drop(before).none { it.first == "DELETE" || it.first == "PUT" })
        assertOtherCalendarUntouched()
    }

    // D2: the record goes in the same step as the events it names, so a restore step that fails later can't leave it.
    @Test fun replacingAllDataDropsTheSendRecordWithTheEvents() = runBlocking {
        save("QA Alpha")
        start()
        assertEquals(1, rows().size)
        repo.replaceAll(repo.snapshot())
        assertTrue(rows().isEmpty())
        assertNull(database.outsideDao().sources().single { it.sendHere }.fetchedFor)
        syncAgain()
        assertEquals(1, plannerFiles().size) // linked again by content, not sent twice
        assertEquals(1, rows().size)
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
        // The window starts on 1 July (three months back); this one is from March and already on Nextcloud, with years
        // of other past events.
        repo.saveItem(ItineraryItem(tripId = 0, date = LocalDate.of(2026, 3, 5), startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "QA Long ago"))
        val uid = "planner-00000001-aaaa-bbbb-cccc-dddddddddddd@planner"
        dav.put("${synced}$uid.ics", ics(uid, "QA Long ago", "20260305T090000Z"))
        for (n in 1..40) { val date = LocalDate.of(2026, 2, 1).minusWeeks(n.toLong())
            repo.saveItem(ItineraryItem(tripId = 0, date = date, startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "QA History $n"))
            dav.put("${synced}history-$n.ics", ics("history-$n", "QA History $n", "${date.toString().replace("-", "")}T090000Z")) }
        start()
        // The first pull reads the window only, however long the history (its files would be too large to read at once).
        assertTrue(dav.queries.isNotEmpty() && dav.queries.all { it >= "20260701" })
        assertEquals(41, rows().count { it.uid == null && it.problem == null })
        val before = writes().size
        repo.saveItem(item("QA Long ago").copy(location = "Room 3"))
        sync.send()
        // Edited, it is looked for around its own date: its own file is updated, not a second one written.
        assertEquals("20260304", dav.queries.last())
        assertEquals(before + 1, writes().size)
        assertEquals(1, dav.files.count { it.key.startsWith(synced) && it.value.second.contains("QA Long ago") })
        assertTrue(dav.files["${synced}$uid.ics"]!!.second.contains("LOCATION:Room 3"))
        assertEquals(uid, rows().single { it.itemId == item("QA Long ago").id }.uid)
        assertNull(sync.sendState.value.message)
        // An unedited past event is neither looked for nor sent.
        syncAgain()
        assertEquals(before + 1, writes().size)
        assertEquals(40, rows().count { it.uid == null && it.problem == null })
        assertOtherCalendarUntouched()
    }

    @Test fun anEditedPastDayOfASeriesUpdatesItsOwnFileNotANeighbours() = runBlocking {
        // A daily gym from 3 to 7 March (before the pull window), each day already on Nextcloud as Planner wrote it.
        val uids = (3..7).associateWith { "planner-0000000$it-aaaa-bbbb-cccc-dddddddddddd@planner" }
        for ((d, uid) in uids) {
            repo.saveItem(ItineraryItem(tripId = 0, date = LocalDate.of(2026, 3, d), startTime = LocalTime.of(7, 0), durationMinutes = 60, title = "QA Gym"))
            dav.put("${synced}$uid.ics", ics(uid, "QA Gym", "2026030${d}T070000Z"))
        }
        start()
        fun gym(d: Int) = items().single { it.title == "QA Gym" && it.date == LocalDate.of(2026, 3, d) }
        val neighbours = uids.filterKeys { it != 5 }.values.associateWith { dav.files["${synced}$it.ics"]!! }
        val before = writes().size
        // 5 March moved to 8:00. Its neighbours' files are read with it (a day either side) but are not its own.
        repo.saveItem(gym(5).copy(startTime = LocalTime.of(8, 0)))
        sync.send()
        assertTrue(conflicts().isEmpty())
        assertNull(sync.sendState.value.message)
        assertEquals(before + 1, writes().size)
        assertEquals(uids[5], rows().single { it.itemId == gym(5).id }.uid)
        assertTrue(dav.files["${synced}${uids[5]}.ics"]!!.second.contains("DTSTART:20260305T080000Z"))
        neighbours.forEach { (uid, file) -> assertEquals(file, dav.files["${synced}$uid.ics"]) }
        assertEquals(5, plannerFiles().size)
        // 6 March's own file deleted on Nextcloud, then moved in Planner: none found, so a new file (no neighbour's).
        dav.files.remove("${synced}${uids[6]}.ics")
        repo.saveItem(gym(6).copy(startTime = LocalTime.of(8, 0)))
        sync.send()
        assertTrue(conflicts().isEmpty())
        assertEquals(5, plannerFiles().size)
        assertTrue(plannerFiles().none { it == "${synced}${uids[6]}.ics" })
        uids.filterKeys { it != 5 && it != 6 }.values.forEach { assertEquals(neighbours[it], dav.files["${synced}$it.ics"]) }
        assertOtherCalendarUntouched()
    }

    @Test fun anEditedPastEventWhoseLookupFailsIsSentAsANewFile() = runBlocking {
        repo.saveItem(ItineraryItem(tripId = 0, date = LocalDate.of(2026, 3, 5), startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "QA Long ago"))
        start()
        // As if the reply were too large, or the server failed: the lookup is skipped, not the send.
        dav.refuseBefore = "20260701"
        repo.saveItem(item("QA Long ago").copy(location = "Room 3"))
        sync.send()
        assertTrue(dav.queries.any { it < "20260701" })
        assertTrue(plannerFile("QA Long ago").value.second.contains("LOCATION:Room 3"))
        assertNull(sync.sendState.value.message)
        assertTrue(rows().none { it.problem != null })
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

    // E6: a second save while the first one's write is still on its way to Nextcloud (Save keeps the editor open, so this
    // is common). The write in flight isn't cancelled: before, its new version went unrecorded, the next pass was refused
    // (412) and the pull made Planner's own edit a conflict. Now the second edit follows it, and nothing is to choose.
    @Test fun aSaveDuringASlowWriteIsSentAfterItWithoutAConflict() = runBlocking {
        save("QA Slow")
        start()
        val putting = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                if (request.method == "PUT") { putting.countDown(); Thread.sleep(3000) }
                return dav.dispatch(request)
            }
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        // As the app has it: sending a few seconds after each change (requestSend), with a scope to do it in.
        val app = CalendarSync(database, NextcloudAccountStore(context, alias), sync.client, now = { clock }, zone = { zone },
            planner = repo.asPlannerStore(), scope = scope)
        fun settled(title: String): Boolean {
            val file = dav.files.entries.singleOrNull { it.key.startsWith(synced) }?.value ?: return false
            val row = rows().single()
            return file.second.contains("SUMMARY:$title") && row.problem == null && row.etag == file.first &&
                row.fingerprint == CalendarSync.fingerprint(item(title))
        }
        try {
            repo.saveItem(item("QA Slow").copy(title = "QA Slow 1"))
            app.requestSend()
            assertTrue("The first write never started", putting.await(30, java.util.concurrent.TimeUnit.SECONDS))
            repo.saveItem(item("QA Slow 1").copy(title = "QA Slow 2")) // while that write is under way
            app.requestSend()
            val end = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < end && !settled("QA Slow 2")) Thread.sleep(200)
            assertTrue("Not settled: ${rows()}", settled("QA Slow 2"))
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            server.dispatcher = dav
        }
        syncAgain()
        assertEquals(emptyList<SentEvent>(), conflicts())
        assertEquals(listOf("QA Slow 2"), items().map { it.title })
        assertEquals(1, plannerFiles().size)
        // Both edits were written, one after the other; neither was refused.
        assertEquals(2, dav.requests.count { it.first == "PUT" && it.third != null })
        assertOtherCalendarUntouched()
    }

    // E7: the synced calendar can be listed and written but not read (calendar-query fails). Before, the send right after
    // hid the failure ("up to date"); now it stays on the calendar until a download works, and on a calendar just chosen
    // nothing new is sent meanwhile.
    @Test fun aFailingDownloadOfTheSyncedCalendarStaysVisibleUntilOneWorks() = runBlocking {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse =
                if (request.method == "REPORT" && request.body.clone().readUtf8().contains("calendar-query")) okhttp3.mockwebserver.MockResponse().setResponseCode(500)
                else dav.dispatch(request)
        }
        save("QA Waits")
        start() // chooses the calendar: its first download fails
        fun target() = runBlocking { database.outsideDao().sources() }.single { it.sendHere }
        assertNotNull(target().lastError)
        assertNull(target().fetchedFor)
        sync.send()
        assertNotNull("A send doesn't clear it", target().lastError)
        assertFalse(sync.sendState.value.error)
        assertTrue("Nothing new is sent before a download", plannerFiles().isEmpty())
        syncAgain()
        assertNotNull(target().lastError)
        server.dispatcher = dav
        syncAgain()
        assertNull(target().lastError)
        assertEquals(1, plannerFiles().size)
        // Failing again later, once the calendar changed there: shown again, and cleared by the next download.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse =
                if (request.method == "REPORT" && request.body.clone().readUtf8().contains("calendar-query")) okhttp3.mockwebserver.MockResponse().setResponseCode(500)
                else dav.dispatch(request)
        }
        dav.bump()
        syncAgain()
        assertNotNull(target().lastError)
        server.dispatcher = dav
        syncAgain()
        assertNull(target().lastError)
        assertOtherCalendarUntouched()
    }

    // E6 safety net: a write that reached Nextcloud but wasn't recorded (the app stopped on the way): the next pass is
    // refused (412), finds Planner's own event already there, and takes it as synced instead of a change or conflict.
    @Test fun anUnrecordedWriteOfPlannersOwnEditIsTakenAsSynced() = runBlocking {
        save("QA Unrecorded")
        start()
        val (path, file) = plannerFile("QA Unrecorded")
        val edited = item("QA Unrecorded").copy(title = "QA Unrecorded (moved)", startTime = LocalTime.of(14, 0))
        // What Planner's write would have left there, at a version Planner never heard of.
        dav.edit(path) { ServerEvents.patch(file.second, edited, zone, Instant.ofEpochMilli(clock)) }
        repo.saveItem(edited)
        sync.send()
        assertNull(rows().single().problem)
        assertEquals(dav.files[path]!!.first, rows().single().etag)
        syncAgain()
        assertEquals(emptyList<SentEvent>(), conflicts())
        assertEquals("QA Unrecorded (moved)", items().single().title)
        assertEquals(1, plannerFiles().size)
        assertOtherCalendarUntouched()
    }

    // Starts [action] on another thread from inside the pull's calendar query (the pull is under way), gives it time to
    // finish, and says whether it did.
    private fun duringThePull(action: suspend () -> Unit): Pair<() -> Boolean, () -> Deferred<Unit>> {
        var started: Deferred<Unit>? = null
        var doneDuringPull = false
        dav.onQuery = {
            dav.onQuery = null
            started = CoroutineScope(Dispatchers.IO).async { action() }
            Thread.sleep(1500)
            doneDuringPull = started!!.isCompleted
        }
        return { doneDuringPull } to { started!! }
    }

    // E1: restoring what was sent waits for a pull under way. Before, it went in at once and the pull then wrote back the
    // row it had read before it (same event id replaces), so the restored record was lost.
    @Test fun restoringTheSendRecordWaitsForAPullUnderWay() = runBlocking {
        save("QA Alpha")
        start()
        val before = rows().single()
        dav.edit(plannerFile("QA Alpha").key) { it.replace("SUMMARY:QA Alpha", "SUMMARY:QA Alpha web") }
        val (doneDuringPull, job) = duringThePull {
            sync.restoreSend(CalendarChoice(before.account, before.calendar, "Planner", null), listOf(before.copy(fingerprint = "from-backup")))
        }
        clock += CalendarSync.MIN_INTERVAL_MS
        sync.sync()
        job().await()
        assertFalse(doneDuringPull())
        assertEquals("from-backup", rows().single().fingerprint)
        assertNull(database.outsideDao().sources().single { it.sendHere }.fetchedFor) // the restore's: read again next time
    }

    // E1: a backup restore keeps the sync off from replacing the events until the record of what was sent is back.
    @Test fun aBackupRestoreWaitsForAPullUnderWay() = runBlocking {
        save("QA Alpha")
        start()
        val before = rows().single()
        val backup = BackupManager(context, repo, AttachmentStore(context), SettingsRepository(context), sync)
        val zip = File(context.cacheDir, "twoway-paused-backup.zip")
        backup.export(android.net.Uri.fromFile(zip), trackStatus = false)
        val staged = backup.stage(android.net.Uri.fromFile(zip))
        dav.edit(plannerFile("QA Alpha").key) { it.replace("SUMMARY:QA Alpha", "SUMMARY:QA Alpha web") }
        val (doneDuringPull, job) = duringThePull { backup.restore(staged) }
        clock += CalendarSync.MIN_INTERVAL_MS
        sync.sync()
        job().await()
        assertFalse(doneDuringPull())
        // The backup's events and record, as they were; the edit on Nextcloud comes in with the next pull.
        assertEquals(before.etag, rows().single().etag)
        assertEquals(listOf("QA Alpha"), items().map { it.title })
        syncAgain()
        assertEquals(listOf("QA Alpha web"), items().map { it.title })
        assertTrue(conflicts().isEmpty())
        assertEquals(1, plannerFiles().size)
    }
}
