package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** The sync icon on Agenda and Calendar: hidden without two-way sync, then up to date, spinning while a sync runs, a
 *  warning when it fails, a conflict count, and a tap opens Settings → Calendars. Against a local fake Nextcloud. */
@Suppress("DEPRECATION")
class SyncIndicatorUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun syncLabels() = nodes().filter { it.isVisibleToUser }.mapNotNull { it.contentDescription?.toString() }.filter { it.startsWith("Sync: ") }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-sync-indicator-evidence").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 30000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "))
    }
    private fun click(text: String) {
        await {
            val target = nodes().filter { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
                .firstNotNullOfOrNull { var n: AccessibilityNodeInfo? = it; while (n != null && !n.isClickable) n = n.parent; n?.takeIf { c -> c.isEnabled } }
            target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        Thread.sleep(400)
    }
    // A toast isn't in the window tree; Android announces it to accessibility as a notification event.
    private fun awaitToast(text: String, action: () -> Unit) {
        ins.uiAutomation.executeAndWaitForEvent({ action() }, { e ->
            e.eventType == android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && e.text.any { it.toString() == text }
        }, 10000)
    }
    private fun open() {
        app.settings.lastViewCalendar = false
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA") != null }
    }
    private fun back() {
        ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(400)
    }

    @Test fun iconFollowsTwoWaySyncOnAgendaAndCalendar() {
        val home = "/remote.php/dav/calendars/qa/"
        val dav = FakeCalDav(home, "qa", "qa-test-password")
        val slow = java.util.concurrent.atomic.AtomicBoolean(false)
        val broken = java.util.concurrent.atomic.AtomicBoolean(false)
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (slow.get()) Thread.sleep(2500)
                return if (broken.get()) MockResponse().setResponseCode(500) else dav.dispatch(request)
            }
        }
        server.start()
        val store = NextcloudAccountStore(context)
        val original = app.calendarSync.client
        try {
            // No two-way sync: no icon.
            open()
            Thread.sleep(1000)
            assertEquals(emptyList<String>(), syncLabels())

            app.calendarSync.client = NextcloudClient(OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build())
            store.save(NextcloudAccount.create(server.url("/").toString(), "qa", "qa-test-password"))
            runBlocking {
                app.repository.saveItem(ItineraryItem(tripId = 0, date = LocalDate.now().plusDays(2), startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "QA Indicator"))
                app.calendarSync.sync()
                app.calendarSync.setSendTarget(app.database.outsideDao().sources().single { it.name == "Planner" }.id)
                app.calendarSync.sync()
            }
            await { syncLabels() == listOf("Sync: up to date") }
            screenshot("agenda-synced")

            // A tap on "up to date" syncs at once, without opening Calendars: a change made on Nextcloud arrives.
            runBlocking {
                val path = dav.files.keys.single { it.startsWith(dav.synced) }
                dav.edit(path) { it.replace("SUMMARY:QA Indicator", "SUMMARY:QA Indicator tapped") }
            }
            click("Sync: up to date")
            await { runBlocking { app.repository.snapshot() }.items.any { it.title == "QA Indicator tapped" } }
            assertNull("tap doesn't open Calendars", find("Keep in sync with"))
            await { syncLabels() == listOf("Sync: up to date") }
            runBlocking {
                val path = dav.files.keys.single { it.startsWith(dav.synced) }
                dav.edit(path) { it.replace("SUMMARY:QA Indicator tapped", "SUMMARY:QA Indicator") }
                app.calendarSync.sync()
            }
            await { syncLabels() == listOf("Sync: up to date") }
            // A long press opens Calendars.
            await { nodes().firstOrNull { it.contentDescription?.toString() == "Sync: up to date" }?.let { n ->
                var c: AccessibilityNodeInfo? = n; while (c != null && !c.isLongClickable) c = c.parent
                c?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) } == true }
            await { find("Keep in sync with") != null }
            screenshot("agenda-long-press-calendars")
            back()
            await { find("Keep in sync with") == null && find("AGENDA") != null }

            // A slow sync: the icon says it is syncing, then up to date again.
            slow.set(true)
            app.appScope.launch { app.calendarSync.sync() }
            await { syncLabels() == listOf("Sync: syncing") }
            screenshot("agenda-syncing")
            // A4: a tap while it syncs says so (it used to do nothing), and doesn't open Calendars.
            awaitToast(com.example.itinerary.ui.SYNCING_MESSAGE) { click("Sync: syncing") }
            assertNull("tap while syncing doesn't open Calendars", find("Keep in sync with"))
            slow.set(false)
            await { syncLabels() == listOf("Sync: up to date") }

            // A failed sync: a warning, until a sync works again.
            broken.set(true)
            runBlocking { app.calendarSync.sync() }
            await { syncLabels() == listOf("Sync: problem, open Calendars") }
            screenshot("agenda-problem")
            broken.set(false)
            runBlocking { app.calendarSync.sync() }
            await { syncLabels() == listOf("Sync: up to date") }

            // Changed in both places: a conflict count, on Agenda and on Calendar, and a tap opens Calendars.
            runBlocking {
                val path = dav.files.keys.single { it.startsWith(dav.synced) }
                dav.edit(path) { it.replace("SUMMARY:QA Indicator", "SUMMARY:QA Indicator web") }
                app.repository.saveItem(app.repository.snapshot().items.single { it.title == "QA Indicator" }.copy(location = "Planner room"))
                app.calendarSync.sync()
            }
            await { syncLabels() == listOf("Sync: 1 conflict, open Calendars") }
            screenshot("agenda-conflict")
            click("Sync: 1 conflict, open Calendars")
            await { find("Keep in sync with") != null && find("Sync now") != null }
            screenshot("agenda-calendars-opened")
            back()
            await { find("Keep in sync with") == null && find("AGENDA") != null }
            click("Switch to Calendar view")
            await { find("CALENDAR") != null && syncLabels() == listOf("Sync: 1 conflict, open Calendars") }
            screenshot("calendar-conflict")
            click("Sync: 1 conflict, open Calendars")
            await { find("Keep in sync with") != null && find("Sync now") != null }
            screenshot("calendar-calendars-opened")
            back()
            await { find("Keep in sync with") == null && find("CALENDAR") != null }

            // Sync switched off: the icon goes.
            runBlocking { app.calendarSync.clearNextcloud() }
            await { syncLabels().isEmpty() }
            screenshot("calendar-sync-off")
        } finally {
            app.calendarSync.client = original
            runBlocking { app.calendarSync.clearNextcloud() }
            store.clear()
            server.shutdown()
        }
    }

    // E7: the synced calendar can't be downloaded (calendar-query fails) while writing to it works. Before, the send right
    // after the failed download said "up to date"; now the icon warns until a download works, and Sync now says so.
    @Test fun aFailingDownloadShowsAProblemEvenWhenSendingWorks() {
        val home = "/remote.php/dav/calendars/qa/"
        val dav = FakeCalDav(home, "qa", "qa-test-password")
        val noQuery = java.util.concurrent.atomic.AtomicBoolean(false)
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (noQuery.get() && request.method == "REPORT" && request.body.clone().readUtf8().contains("calendar-query")) MockResponse().setResponseCode(500)
                else dav.dispatch(request)
        }
        server.start()
        val store = NextcloudAccountStore(context)
        val original = app.calendarSync.client
        try {
            app.calendarSync.client = NextcloudClient(OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build())
            store.save(NextcloudAccount.create(server.url("/").toString(), "qa", "qa-test-password"))
            runBlocking {
                app.repository.saveItem(ItineraryItem(tripId = 0, date = LocalDate.now().plusDays(2), startTime = LocalTime.of(9, 0), durationMinutes = 60, title = "QA Pull fails"))
                app.calendarSync.sync()
                app.calendarSync.setSendTarget(app.database.outsideDao().sources().single { it.name == "Planner" }.id)
                app.calendarSync.sync()
            }
            open()
            await { syncLabels() == listOf("Sync: up to date") }

            // The download fails, the send after it works: still a problem.
            noQuery.set(true)
            dav.bump()
            runBlocking {
                app.repository.saveItem(app.repository.snapshot().items.single { it.title == "QA Pull fails" }.copy(location = "Sent anyway"))
                app.calendarSync.sync(); app.calendarSync.send()
            }
            assertTrue(dav.files.values.any { it.second.contains("LOCATION:Sent anyway") })
            assertFalse(app.calendarSync.sendState.value.error)
            await { syncLabels() == listOf("Sync: problem, open Calendars") }
            screenshot("pull-fails-problem")
            // Sync now says which calendar couldn't be downloaded.
            click("Sync: problem, open Calendars")
            click("Sync now")
            await { nodes().any { it.isVisibleToUser && it.text?.toString()?.contains("Planner, the calendar kept in sync, couldn't be downloaded") == true } }
            screenshot("pull-fails-sync-now")
            back()
            await { find("Keep in sync with") == null && find("AGENDA") != null }

            // A download works again: up to date.
            noQuery.set(false)
            runBlocking { app.calendarSync.sync() }
            await { syncLabels() == listOf("Sync: up to date") }
        } finally {
            app.calendarSync.client = original
            runBlocking { app.calendarSync.clearNextcloud() }
            store.clear()
            server.shutdown()
        }
    }
}
