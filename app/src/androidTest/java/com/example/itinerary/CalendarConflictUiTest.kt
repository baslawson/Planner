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
import java.time.LocalTime

/** Calendar sync step 6 in the real app: an event changed in Planner and on (a local fake) Nextcloud shows up as a
 *  conflict in Settings → Calendars, and "Keep both" there leaves two events in both places. */
@Suppress("DEPRECATION")
class CalendarConflictUiTest {
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
        val dir = File(context.cacheDir, "qa-calendar-conflict-evidence").apply { mkdirs() }
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
            else { if (++tries % 4 == 0) scrollStep(nodes(), true); false }
        }
        Thread.sleep(400)
    }

    @Test fun keepBothFromSettings() {
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
                app.repository.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "QA Clash"))
                app.calendarSync.sync()
                app.calendarSync.setSendTarget(app.database.outsideDao().sources().single { it.name == "Planner" }.id)
                // Changed on both sides since then.
                val path = dav.files.keys.single { it.startsWith(dav.synced) }
                dav.edit(path) { it.replace("SUMMARY:QA Clash", "SUMMARY:QA Clash web") }
                app.repository.saveItem(app.repository.snapshot().items.single { it.title == "QA Clash" }.copy(location = "Planner room"))
                app.calendarSync.sync()
            }
            assertEquals(1, runBlocking { app.database.sentDao().all() }.count { it.problem == SentEvent.CONFLICT })
            app.settings.lastViewCalendar = false
            ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await { find("AGENDA") != null }
            click("Settings")
            click("Calendars")
            click("Review 1 conflict")
            await { find("Changed in both places") != null && has("In Planner: ") && has("Planner room") && has("On Nextcloud: ") && has("QA Clash web") }
            screenshot("conflict")
            click("Keep both")
            await { find("Changed in both places") == null }
            runBlocking { app.calendarSync.send() }
            val titles = runBlocking { app.repository.snapshot().items }.map { it.title }.filter { it.startsWith("QA Clash") }.toSet()
            assertEquals(setOf("QA Clash", "QA Clash web"), titles)
            assertEquals(2, dav.files.count { it.key.startsWith(dav.synced) })
            assertTrue(runBlocking { app.database.sentDao().all() }.none { it.problem != null })
            screenshot("settled")
        } finally {
            app.calendarSync.client = original
            runBlocking { app.calendarSync.clearNextcloud() }
            store.clear()
            server.shutdown()
        }
    }
}
