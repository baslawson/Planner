package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Task sync in the real app against a local fake Nextcloud: choosing the task list in Settings → Calendars brings its
 *  open task in and sends Planner's; a task changed in both places shows as a conflict there and "Keep Planner's" settles it. */
@Suppress("DEPRECATION")
class TaskSyncUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun has(part: String) = nodes().any { it.isVisibleToUser && it.text?.toString()?.contains(part) == true }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-task-sync-evidence").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 30000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun click(text: String) {
        if (text == "Settings" && find(text) == null && find("More options") != null) click("More options")
        var tries = 0
        await {
            val target = nodes().filter { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
                .firstNotNullOfOrNull { var n: AccessibilityNodeInfo? = it; while (n != null && !n.isClickable) n = n.parent; n?.takeIf { c -> c.isEnabled } }
            if (target != null) target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            else { if (++tries % 4 == 0) swipeUp(); false }
        }
        Thread.sleep(400)
    }
    // A real touch swipe in the middle of the screen, as a finger would (the Calendars pop-up doesn't take an
    // accessibility scroll).
    private fun swipeUp() {
        val metrics = context.resources.displayMetrics
        val x = metrics.widthPixels / 2; val from = metrics.heightPixels * 7 / 10; val to = metrics.heightPixels * 4 / 10
        // Read the command's output to the end, so it has run before going on.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("input swipe $x $from $x $to 400")).use { it.readBytes() }
        Thread.sleep(700)
    }
    private fun reveal(part: String) { var tries = 0; await { has(part) || run { if (++tries % 4 == 0) swipeUp(); false } } }

    @Test fun chooseTheListAndSettleAConflict() {
        val home = "/remote.php/dav/calendars/qa/"
        val dav = FakeCalDav(home, "qa", "qa-test-password", taskList = true)
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
            dav.put("${dav.tasks}web.ics", "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:web-1\r\nSUMMARY:QA From Nextcloud\r\n" +
                "DUE;VALUE=DATE:${LocalDate.now().plusDays(1).toString().replace("-", "")}\r\nEND:VTODO\r\nEND:VCALENDAR\r\n")
            runBlocking { app.repository.saveTask(PlannerTask(title = "QA From Planner", dueDate = LocalDate.now().plusDays(2))) }
            app.settings.lastViewCalendar = false
            ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await { find("AGENDA") != null }
            click("Settings")
            click("Calendars")
            // While the pop-up opens, all of its contents briefly count as visible: let it settle before looking.
            await { find("Calendars") != null }; Thread.sleep(1500)
            reveal("Keep tasks in sync with")
            reveal("Off (don't sync tasks)")
            screenshot("choice")
            click("Tasks")
            await(45000) { runBlocking { app.database.sentTaskDao().all() }.count { it.problem == null && it.uid != null } == 2 }
            reveal("tasks are kept in sync with Tasks")
            screenshot("synced")
            val titles = runBlocking { app.database.taskDao().all() }.map { it.title }.toSet()
            assertEquals(setOf("QA From Nextcloud", "QA From Planner"), titles)
            assertTrue(dav.files.filterKeys { it.startsWith(dav.tasks) }.values.any { it.second.contains("SUMMARY:QA From Planner") })
            // Changed in both places.
            val path = dav.files.keys.single { it.startsWith(dav.tasks) && dav.files[it]!!.second.contains("QA From Planner") }
            dav.edit(path) { it.replace("SUMMARY:QA From Planner", "SUMMARY:QA From Planner web") }
            runBlocking {
                val task = app.database.taskDao().all().single { it.title == "QA From Planner" }
                app.repository.saveTask(task.copy(notes = "Planner note"), create = false)
                app.calendarSync.sync()
            }
            click("Review 1 task conflict")
            await { find("Tasks changed in both places") != null && has("In Planner: ") && has("On Nextcloud: ") && has("QA From Planner web") }
            screenshot("conflict")
            click("Keep Planner's")
            await { find("Tasks changed in both places") == null }
            assertTrue(dav.files[path]!!.second.contains("SUMMARY:QA From Planner\r\n") && dav.files[path]!!.second.contains("DESCRIPTION:Planner note"))
            assertTrue(runBlocking { app.database.sentTaskDao().all() }.none { it.problem != null })
            screenshot("settled")
        } finally {
            app.calendarSync.client = original
            runBlocking { app.calendarSync.clearNextcloud() }
            store.clear()
            server.shutdown()
        }
    }
}
