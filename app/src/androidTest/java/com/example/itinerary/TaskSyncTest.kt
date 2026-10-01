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
import java.time.ZoneOffset

/** Task sync with a Nextcloud task list over real TLS against the CalDAV fixture (a tasks-only list "Tasks", plus the
 *  calendars "Planner" and "Work"): what goes each way, Planner-only details, deletions, conflicts, relinking, lost
 *  replies, backups, and that no calendar is touched. */
class TaskSyncTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: MockWebServer
    private lateinit var database: AppDatabase
    private lateinit var repo: Repository
    private lateinit var sync: CalendarSync
    private lateinit var tasks: TaskSync
    private lateinit var sandbox: File
    private lateinit var context: Context
    private val home = "/remote.php/dav/calendars/bas/"
    private val dav = FakeCalDav(home, "bas", "test-password-only", taskList = true)
    private var clock = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
    private val alias = "planner.nextcloud.tasksync-instrumentation"
    private val list = "${home}tasks/"
    private val day = LocalDate.of(2026, 10, 5)

    @Before fun setUp() {
        val base = instrumentation.targetContext
        sandbox = File(base.cacheDir, "task-sync-instrumentation").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(sandbox, "files").apply { mkdirs() }
            override fun getCacheDir() = File(sandbox, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(sandbox, "no_backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences("tasksync_test_$name", mode)
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
            now = { clock }, zone = { ZoneOffset.UTC }, planner = repo.asPlannerStore())
        tasks = TaskSync(database, accounts, { sync.client }, repo.asTaskStore(),
            pendingDeleted = { repo.pendingDeletions.value.flatMap { it.tasks }.mapTo(HashSet()) { it.id } },
            now = { clock }, zone = { ZoneOffset.UTC })
        sync.tasks = tasks
        dav.put("${dav.other}untouchable.ics", "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:w1\r\nDTSTART:20261006T090000Z\r\n" +
            "SUMMARY:Work thing\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
    }

    @After fun tearDown() {
        database.close()
        server.shutdown()
        listOf("settings", "backup_status").forEach { instrumentation.targetContext.deleteSharedPreferences("tasksync_test_$it") }
        sandbox.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }

    private fun todo(uid: String, title: String, vararg extra: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Nextcloud Tasks//EN\r\n" +
        "BEGIN:VTODO\r\nUID:$uid\r\nSUMMARY:$title\r\n${extra.joinToString("") { "$it\r\n" }}END:VTODO\r\nEND:VCALENDAR\r\n"
    private fun all() = runBlocking { database.taskDao().all() }
    private fun task(title: String) = all().single { it.title == title }
    private fun rows() = runBlocking { database.sentTaskDao().all() }
    private fun listFiles() = dav.files.filterKeys { it.startsWith(list) }
    private fun fileOf(title: String) = listFiles().entries.single { it.value.second.contains("SUMMARY:$title") }
    private fun writes() = dav.requests.filter { it.first == "PUT" || it.first == "DELETE" }
    private suspend fun start(name: String = "Tasks") {
        sync.sync()
        tasks.setTarget(database.outsideDao().sources().single { it.name == name }.id)
        sync.sync(); tasks.send()
    }
    private suspend fun syncAgain() { clock += CalendarSync.MIN_INTERVAL_MS; sync.sync(); tasks.send() }
    private suspend fun add(title: String, done: Boolean = false, repeat: String = "NONE", due: LocalDate? = day) =
        repo.saveTask(PlannerTask(title = title, dueDate = due, done = done, repeat = repeat, reminderAt = 1_900_000_000_000,
            checklist = listOf(ChecklistEntry("c", "Only in Planner", false))))
    private fun assertCalendarsUntouched() {
        assertTrue(writes().all { it.second.startsWith(list) })
        assertEquals("\"w-orig\"", dav.files["${dav.other}untouchable.ics"]!!.first)
    }

    @Test fun theTaskListIsOfferedAndCalendarsStayEventsOnly() = runBlocking {
        sync.sync()
        val sources = database.outsideDao().sources().associateBy { it.name }
        assertTrue(sources.getValue("Tasks").tasks); assertFalse(sources.getValue("Tasks").events)
        assertTrue(sources.getValue("Planner").events && sources.getValue("Planner").tasks) // no component list: both
        // A tasks-only list can't become the events' calendar.
        sync.setSendTarget(sources.getValue("Tasks").id)
        assertTrue(database.outsideDao().sources().none { it.sendHere })
    }

    @Test fun eachSideGetsTheOthersOpenTasks() = runBlocking {
        add("QA Buy milk"); add("QA Already done", done = true)
        dav.put("${list}web.ics", todo("web-1", "Made in Nextcloud", "DUE;VALUE=DATE:20261007", "PRIORITY:1", "CATEGORIES:Errands", "X-OTHER:kept"))
        dav.put("${list}finished.ics", todo("web-2", "Finished long ago", "STATUS:COMPLETED", "COMPLETED:20250101T000000Z"))
        dav.put("${list}weekly.ics", todo("web-3", "Weekly on Nextcloud", "RRULE:FREQ=WEEKLY"))
        dav.put("${list}cancelled.ics", todo("web-4", "Cancelled one", "STATUS:CANCELLED"))
        start()
        // Nextcloud → Planner: the open one only.
        assertEquals(setOf("QA Buy milk", "QA Already done", "Made in Nextcloud"), all().map { it.title }.toSet())
        val web = task("Made in Nextcloud")
        assertEquals(LocalDate.of(2026, 10, 7), web.dueDate); assertEquals(TaskPriority.HIGH, web.priority)
        assertEquals(1, tasks.hidden.value)
        // Planner → Nextcloud: the open one only; nothing was written back to the web task.
        val milk = fileOf("QA Buy milk").value.second
        assertTrue(milk.contains("BEGIN:VTODO") && milk.contains("DUE;VALUE=DATE:20261005") && milk.contains("STATUS:NEEDS-ACTION"))
        assertFalse(milk.contains("Only in Planner")) // the checklist stays in Planner
        assertTrue(listFiles().values.none { it.second.contains("QA Already done") })
        assertEquals(listOf("PUT"), writes().map { it.first })
        assertEquals(5, listFiles().size)
        // A second pass changes nothing.
        syncAgain()
        assertEquals(1, writes().size); assertEquals(3, all().size)
        assertCalendarsUntouched()
    }

    @Test fun editsGoBothWaysAndKeepWhatTheOtherSideHolds() = runBlocking {
        dav.put("${list}web.ics", todo("web-1", "Pay rent", "PRIORITY:3", "X-OTHER:kept", "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT15M", "END:VALARM"))
        add("QA Dentist")
        start()
        // Planner → Nextcloud: only the managed properties change.
        repo.saveTask(task("Pay rent").copy(title = "Pay rent today", notes = "Before noon"), create = false)
        tasks.send()
        val rent = dav.files["${list}web.ics"]!!.second
        assertTrue(rent.contains("SUMMARY:Pay rent today") && rent.contains("DESCRIPTION:Before noon"))
        assertTrue(rent.contains("PRIORITY:3") && rent.contains("X-OTHER:kept") && rent.contains("TRIGGER:-PT15M") && rent.contains("UID:web-1"))
        assertNotNull(writes().last().third) // If-Match
        // Nextcloud → Planner: Planner-only details stay.
        val (path, _) = fileOf("QA Dentist")
        dav.edit(path) { it.replace("SUMMARY:QA Dentist", "SUMMARY:QA Dentist (moved)").replace("DUE;VALUE=DATE:20261005", "DUE;VALUE=DATE:20261009") }
        val writesBefore = writes().size
        syncAgain()
        val moved = task("QA Dentist (moved)")
        assertEquals(LocalDate.of(2026, 10, 9), moved.dueDate)
        assertEquals("Only in Planner", moved.checklist.single().text)
        assertEquals(1_900_000_000_000, moved.reminderAt)
        assertEquals(writesBefore, writes().size) // taking a change in writes nothing back
        assertCalendarsUntouched()
    }

    @Test fun tickingWorksBothWaysAndRepeatsMakeTheirNextOne() = runBlocking {
        add("QA Water plants", repeat = "WEEKLY"); add("QA Post letter")
        start()
        // Ticked on Nextcloud: done in Planner, and the weekly one's next occurrence is a new task there too.
        dav.edit(fileOf("QA Water plants").key) { it.replace("STATUS:NEEDS-ACTION", "STATUS:COMPLETED\r\nCOMPLETED:20261001T080000Z") }
        syncAgain()
        val plants = all().filter { it.title == "QA Water plants" }
        assertEquals(listOf(false, true), plants.map { it.done }.sorted())
        assertTrue(plants.single { !it.done }.dueDate!! > day)
        assertEquals(2, listFiles().values.count { it.second.contains("SUMMARY:QA Water plants") })
        // Ticked in Planner: completed on Nextcloud.
        repo.setTaskDone(task("QA Post letter").id, true)
        tasks.send()
        val letter = fileOf("QA Post letter").value.second
        assertTrue(letter.contains("STATUS:COMPLETED") && letter.contains("PERCENT-COMPLETE:100") && letter.contains("COMPLETED:"))
        // Unticked there: open again in Planner.
        dav.edit(fileOf("QA Post letter").key) { it.replace("STATUS:COMPLETED", "STATUS:NEEDS-ACTION") }
        syncAgain()
        assertFalse(task("QA Post letter").done)
    }

    @Test fun deletionsGoBothWays() = runBlocking {
        add("QA Delete here"); add("QA Delete there")
        start()
        // Deleted in Planner: the file waits for Undo, then goes.
        val here = task("QA Delete here")
        repo.deleteTask(here.id)
        tasks.send()
        assertEquals(1, listFiles().values.count { it.second.contains("QA Delete here") })
        repo.pendingDeletions.value.forEach { repo.finishDeletion(it.token) }
        tasks.send()
        assertTrue(listFiles().values.none { it.second.contains("QA Delete here") })
        assertEquals("DELETE", writes().last().first)
        // Deleted on Nextcloud: to Recently deleted in Planner.
        dav.files.remove(fileOf("QA Delete there").key); dav.bump()
        syncAgain()
        assertTrue(all().none { it.title == "QA Delete there" })
        assertTrue(repo.recentlyDeleted.first().any { it.label == "QA Delete there" })
        assertTrue(rows().isEmpty())
    }

    @Test fun changedInBothPlacesIsTheUsersChoice() = runBlocking {
        add("QA Keep mine"); add("QA Keep theirs"); add("QA Keep both")
        start()
        listOf("QA Keep mine", "QA Keep theirs", "QA Keep both").forEach { title ->
            dav.edit(fileOf(title).key) { it.replace("SUMMARY:$title", "SUMMARY:$title (web)") }
            repo.saveTask(task(title).copy(notes = "Planner note"), create = false)
        }
        syncAgain()
        val conflicts = tasks.conflicts.first()
        assertEquals(3, conflicts.size)
        fun row(title: String) = conflicts.single { c -> all().single { it.id == c.taskId }.title == title }
        tasks.resolve(row("QA Keep mine").id, TaskSync.Resolution.PLANNER)
        tasks.resolve(row("QA Keep theirs").id, TaskSync.Resolution.NEXTCLOUD)
        tasks.resolve(row("QA Keep both").id, TaskSync.Resolution.BOTH)
        assertTrue(tasks.conflicts.first().isEmpty())
        val mine = listFiles().values.single { it.second.contains("UID:") && it.second.contains("DESCRIPTION:Planner note") && it.second.contains("QA Keep mine") }.second
        assertTrue(mine.contains("SUMMARY:QA Keep mine\r\n"))
        assertEquals("", task("QA Keep theirs (web)").notes)
        assertEquals(setOf("QA Keep both", "QA Keep both (web)"), all().map { it.title }.filter { it.startsWith("QA Keep both") }.toSet())
        assertEquals(2, listFiles().values.count { it.second.contains("SUMMARY:QA Keep both") })
        assertCalendarsUntouched()
    }

    @Test fun deletedHereChangedThereIsAConflictNotADeletion() = runBlocking {
        add("QA Gone or not")
        start()
        val path = fileOf("QA Gone or not").key
        dav.edit(path) { it.replace("SUMMARY:QA Gone or not", "SUMMARY:QA Gone or not (web)") }
        repo.deleteTask(task("QA Gone or not").id)
        repo.pendingDeletions.value.forEach { repo.finishDeletion(it.token) }
        tasks.send()
        assertNotNull(dav.files[path]) // the delete was refused (If-Match)
        syncAgain()
        val conflict = tasks.conflicts.first().single()
        tasks.resolve(conflict.id, TaskSync.Resolution.NEXTCLOUD)
        assertNotNull(all().singleOrNull { it.title == "QA Gone or not (web)" })
    }

    @Test fun choosingTheListAgainLinksInsteadOfCopying() = runBlocking {
        add("QA Linked once")
        start()
        assertEquals(1, listFiles().size)
        tasks.setTarget(null)
        tasks.forget()
        start()
        assertEquals(1, listFiles().size)
        assertEquals(1, all().size)
        assertEquals(1, rows().count { it.problem == null })
    }

    @Test fun aLostReplyNeverMakesASecondFile() = runBlocking {
        add("QA Lost reply")
        sync.sync()
        tasks.setTarget(database.outsideDao().sources().single { it.name == "Tasks" }.id)
        sync.sync()
        dav.loseReplies = 1
        tasks.send()
        assertEquals(SentEvent.PENDING, rows().single().problem)
        tasks.send()
        assertEquals(1, listFiles().size)
        assertNull(rows().single().problem)
    }

    @Test fun aListHoldingEventsTooKeepsThemApart() = runBlocking {
        // "Planner" holds both: its events sync as events, its tasks as tasks.
        sync.sync()
        sync.setSendTarget(database.outsideDao().sources().single { it.name == "Planner" }.id)
        repo.saveItem(ItineraryItem(tripId = 0, date = day, startTime = null, title = "QA An event"))
        sync.send()
        dav.put("${dav.synced}task.ics", todo("t-1", "QA A web task"))
        tasks.setTarget(database.outsideDao().sources().single { it.name == "Planner" }.id)
        add("QA A planner task")
        syncAgain()
        assertEquals(setOf("QA A planner task", "QA A web task"), all().map { it.title }.toSet())
        assertEquals(listOf("QA An event"), repo.snapshot().items.map { it.title })
        assertEquals(1, dav.files.filterKeys { it.startsWith(dav.synced) }.values.count { it.second.contains("BEGIN:VEVENT") })
        assertEquals(2, dav.files.filterKeys { it.startsWith(dav.synced) }.values.count { it.second.contains("BEGIN:VTODO") })
        syncAgain()
        assertEquals(2, all().size); assertEquals(1, repo.snapshot().items.size)
    }

    // T1: a file Nextcloud refuses (415, as sabre/vobject answers an invalid one) holds up only its own task.
    @Test fun aRefusedTaskDoesntHoldUpTheOthers() = runBlocking {
        add("QA Refused new"); add("QA Fine new")
        dav.put("${list}web.ics", todo("web-1", "QA Refused edit"))
        dav.refuse = { body -> if (body.contains("QA Refused")) 415 else null }
        start()
        assertNotNull(fileOf("QA Fine new"))
        assertTrue(listFiles().values.none { it.second.contains("QA Refused new") })
        assertTrue(tasks.state.value.error)
        assertTrue(tasks.state.value.message.orEmpty(), tasks.state.value.message.orEmpty().contains("HTTP 415"))
        // An edit refused, one to another task still goes.
        repo.saveTask(task("QA Refused edit").copy(notes = "Refused note"), create = false)
        repo.saveTask(task("QA Fine new").copy(notes = "Fine note"), create = false)
        tasks.send()
        assertTrue(fileOf("QA Fine new").value.second.contains("DESCRIPTION:Fine note"))
        assertFalse(dav.files["${list}web.ics"]!!.second.contains("Refused note"))
        // Accepted again: both are sent at the next pass.
        dav.refuse = null
        syncAgain()
        assertNotNull(fileOf("QA Refused new"))
        assertTrue(dav.files["${list}web.ics"]!!.second.contains("DESCRIPTION:Refused note"))
        assertFalse(tasks.state.value.error)
        assertTrue(rows().all { it.problem == null })
    }

    // T4: a file the task query returns without its content is still there: not "deleted on Nextcloud".
    @Test fun aFileMissingFromTheQueryIsntTakenForDeleted() = runBlocking {
        add("QA Still there")
        start()
        dav.withoutData = setOf(fileOf("QA Still there").key); dav.bump()
        syncAgain()
        assertEquals(1, all().count { it.title == "QA Still there" })
        assertTrue(repo.recentlyDeleted.first().none { it.label == "QA Still there" })
        assertNull(rows().single().problem)
        // Really gone: then it is.
        dav.withoutData = emptySet(); dav.files.remove(fileOf("QA Still there").key); dav.bump()
        syncAgain()
        assertTrue(all().none { it.title == "QA Still there" })
    }

    // T5: the data is replaced first and the task record only after; a send in between deletes nothing on Nextcloud.
    @Test fun aSendBetweenRestoreStepsDeletesNothing() = runBlocking {
        add("QA Not in the backup")
        start()
        repo.replaceAll(repo.snapshot().copy(tasks = emptyList()))
        assertTrue(rows().isEmpty())
        tasks.send()
        assertNotNull(fileOf("QA Not in the backup"))
        assertTrue(writes().none { it.first == "DELETE" })
    }

    // Keep both with a done Planner task: that one is sent as its own file too.
    @Test fun keepBothSendsADonePlannerTask() = runBlocking {
        add("QA Both done")
        start()
        dav.edit(fileOf("QA Both done").key) { it.replace("SUMMARY:QA Both done", "SUMMARY:QA Both done (web)") }
        repo.setTaskDone(task("QA Both done").id, true)
        syncAgain()
        tasks.resolve(tasks.conflicts.first().single().id, TaskSync.Resolution.BOTH)
        val done = listFiles().values.single { it.second.contains("SUMMARY:QA Both done\r\n") }.second
        assertTrue(done, done.contains("STATUS:COMPLETED"))
        assertNotNull(fileOf("QA Both done (web)"))
        assertTrue(rows().all { it.problem == null })
    }

    // A conflict whose file is then deleted on Nextcloud shows it as deleted there, not the version it had.
    @Test fun aConflictsFileDeletedLaterShowsAsDeleted() = runBlocking {
        add("QA Conflict then gone")
        start()
        val path = fileOf("QA Conflict then gone").key
        dav.edit(path) { it.replace("SUMMARY:QA Conflict then gone", "SUMMARY:QA Conflict then gone (web)") }
        repo.saveTask(task("QA Conflict then gone").copy(notes = "Planner note"), create = false)
        syncAgain()
        assertTrue(tasks.conflicts.first().single().conflict!!.contains("(web)"))
        dav.files.remove(path); dav.bump()
        syncAgain()
        assertEquals("", tasks.conflicts.first().single().conflict)
    }

    // Sync now says when only the task list couldn't be downloaded.
    @Test fun aFailedTaskListIsntUpToDate() = runBlocking {
        start()
        dav.failTaskQueries = true; dav.bump()
        clock += CalendarSync.MIN_INTERVAL_MS
        sync.sync()
        assertEquals("Synced, but the task list couldn't be downloaded.", sync.state.value.message)
    }

    @Test fun aBackupKeepsTheListAndWhatWasSynced() = runBlocking {
        add("QA Backed up")
        start()
        val backup = BackupManager(context, repo, AttachmentStore(context), SettingsRepository(context), sync, tasks)
        val zip = File(context.cacheDir, "tasksync-backup.zip")
        backup.export(android.net.Uri.fromFile(zip), trackStatus = false)
        tasks.restore(null, emptyList())
        backup.restore(backup.stage(android.net.Uri.fromFile(zip)))
        assertTrue(database.outsideDao().sources().single { it.tasksHere }.name == "Tasks")
        repo.saveTask(task("QA Backed up").copy(notes = "After restore"), create = false)
        syncAgain()
        assertEquals(1, listFiles().size)
        assertTrue(fileOf("QA Backed up").value.second.contains("DESCRIPTION:After restore"))
    }
}
