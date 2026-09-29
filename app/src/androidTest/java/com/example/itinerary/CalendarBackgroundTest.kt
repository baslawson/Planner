package com.example.itinerary

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderScheduler
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Calendar sync in the background: the job runs the same sync as opening the app (against a local fake Nextcloud), the
 *  interval chosen in Settings → Calendars is what Android schedules (Off cancels it), and backups keep the interval. */
@Suppress("DEPRECATION")
class CalendarBackgroundTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun await(timeout: Long = 30000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        fail("Timed out: " + nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun click(text: String) {
        if (text == "Settings" && find(text) == null && find("More options") != null) click("More options")
        var tries = 0
        await {
            val target = nodes().filter { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
                .firstNotNullOfOrNull { var n: AccessibilityNodeInfo? = it; while (n != null && !n.isClickable) n = n.parent; n?.takeIf { c -> c.isEnabled } }
            if (target != null) target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            else { if (++tries % 4 == 0) scrollStep(nodes(), true); false }
        }
        Thread.sleep(400)
    }
    private fun work(): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(CalendarBackground.WORK).get()

    @After fun off() { app.settings.setCalendarBackgroundHours(BackgroundSync.OFF) }

    @Test fun theBackgroundJobSyncsBothWays() {
        val home = "/remote.php/dav/calendars/qa/"
        val dav = FakeCalDav(home, "qa", "qa-test-password")
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.dispatcher = dav
        server.start()
        val store = NextcloudAccountStore(context)
        val original = app.calendarSync.client
        try {
            app.calendarSync.client = NextcloudClient(OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build())
            store.save(NextcloudAccount.create(server.url("/").toString(), "qa", "qa-test-password"))
            val day = LocalDate.now().plusDays(2)
            runBlocking {
                app.repository.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "QA Background"))
                app.calendarSync.sync()
                app.calendarSync.setSendTarget(app.database.outsideDao().sources().single { it.name == "Planner" }.id)
            }
            val path = dav.files.keys.single { it.startsWith(dav.synced) }
            // Changed on Nextcloud while Planner is closed…
            dav.edit(path) { it.replace("SUMMARY:QA Background", "SUMMARY:QA Background (web)") }
            // …and a Planner change that couldn't be sent yet (as if the phone had been offline).
            dav.code = 503
            runBlocking { app.repository.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(15, 0), title = "QA Made offline")) }
            runBlocking { app.calendarSync.send() }
            dav.code = null
            val result = runBlocking { TestListenableWorkerBuilder<CalendarSyncWorker>(context).build().doWork() }
            assertEquals(ListenableWorker.Result.success(), result)
            val titles = runBlocking { app.repository.snapshot().items }.map { it.title }
            assertTrue("Brought in from Nextcloud: $titles", titles.contains("QA Background (web)"))
            assertEquals(1, dav.files.count { it.key.startsWith(dav.synced) && it.value.second.contains("SUMMARY:QA Made offline") })
            // With nothing to sync, a run does nothing and still succeeds.
            runBlocking { app.calendarSync.clearNextcloud() }
            store.clear()
            val before = dav.requests.size
            assertEquals(ListenableWorker.Result.success(), runBlocking { TestListenableWorkerBuilder<CalendarSyncWorker>(context).build().doWork() })
            assertEquals(before, dav.requests.size)
        } finally {
            app.calendarSync.client = original
            runBlocking { app.calendarSync.clearNextcloud() }
            store.clear()
            server.shutdown()
        }
    }

    @Test fun theChosenIntervalIsWhatAndroidSchedules() {
        // The test app has calendar permission, so Settings → Calendars shows the choice.
        app.settings.lastViewCalendar = false
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA") != null }
        click("Settings")
        click("Calendars")
        await { true }
        click("Every 3 hours")
        await { app.settings.calendarBackgroundHours.value == 3 }
        await { work().any { it.state == WorkInfo.State.ENQUEUED && it.periodicityInfo?.repeatIntervalMillis == TimeUnit.HOURS.toMillis(3) } }
        click("Once a day")
        await { work().any { it.state == WorkInfo.State.ENQUEUED && it.periodicityInfo?.repeatIntervalMillis == TimeUnit.HOURS.toMillis(24) } }
        assertEquals(1, work().count { it.state == WorkInfo.State.ENQUEUED }) // replaced, not added
        click("Off")
        await { work().none { it.state == WorkInfo.State.ENQUEUED } }
        assertEquals(BackgroundSync.OFF, app.settings.calendarBackgroundHours.value)
    }

    @Test fun backupsKeepTheInterval() = runBlocking {
        val base = context
        val sandbox = File(base.cacheDir, "background-backup").apply { mkdirs() }
        val sandboxed = object : ContextWrapper(base) {
            override fun getFilesDir() = File(sandbox, "files").apply { mkdirs() }
            override fun getCacheDir() = File(sandbox, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(sandbox, "no_backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences("background_test_$name", mode)
        }
        val database = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            val settings = SettingsRepository(sandboxed)
            val attachments = AttachmentStore(sandboxed)
            val backup = BackupManager(sandboxed, Repository(database, attachments, ReminderScheduler(sandboxed)), attachments, settings)
            settings.setCalendarBackgroundHours(6)
            val file = File(sandboxed.cacheDir, "background.zip")
            backup.export(Uri.fromFile(file), trackStatus = false)
            settings.setCalendarBackgroundHours(BackgroundSync.OFF)
            backup.restore(backup.stage(Uri.fromFile(file)))
            assertEquals(6, settings.calendarBackgroundHours.value)
            settings.setCalendarBackgroundHours(7) // not a choice: off
            assertEquals(BackgroundSync.OFF, settings.calendarBackgroundHours.value)
        } finally {
            database.close()
            listOf("settings", "backup_status").forEach { base.deleteSharedPreferences("background_test_$it") }
            sandbox.deleteRecursively()
        }
    }
}
