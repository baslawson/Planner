package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
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
import java.util.concurrent.ConcurrentHashMap

/** Calendar sync step 5 in the real app: choose "Send my Planner events to" in Settings → Calendars against a local
 *  HTTPS CalDAV server (never a real Nextcloud), and see an existing and a new event arrive there. */
@Suppress("DEPRECATION")
class CalendarSendUiTest {
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
        val dir = File(context.cacheDir, "qa-calendar-send-evidence").apply { mkdirs() }
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

    @Test fun chooseACalendarAndPlannerEventsArriveThere() {
        val home = "/remote.php/dav/calendars/qa/"
        val files = ConcurrentHashMap<String, String>()
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return when {
                    request.method == "PROPFIND" && path == home -> MockResponse().setResponseCode(207).setBody("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav"><d:response><d:href>${home}personal/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><d:displayname>QA Personal</d:displayname><d:current-user-privilege-set><d:privilege><d:write/></d:privilege></d:current-user-privilege-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""")
                    request.method == "PUT" && path.startsWith("${home}personal/planner-") && request.getHeader("If-None-Match") == "*" -> {
                        files[path] = request.body.readUtf8(); MockResponse().setResponseCode(201).setHeader("ETag", "\"e${files.size}\"")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val store = NextcloudAccountStore(context)
        val original = app.calendarSync.client
        try {
            app.calendarSync.client = NextcloudClient(OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build())
            store.save(NextcloudAccount.create(server.url("/").toString(), "qa", "qa-test-password"))
            val day = LocalDate.now().plusDays(1)
            runBlocking { app.repository.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(9, 0), durationMinutes = 30, title = "QA Sent existing")) }
            app.settings.lastViewCalendar = false
            ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await { find("AGENDA") != null }
            click("Settings")
            click("Calendars")
            await { find("Send my Planner events to") != null && find("QA Personal") != null }
            assertTrue(files.isEmpty()) // nothing is sent until a calendar is chosen
            // The name shows twice: under reading (a tick box) and under "Send my Planner events to" (the last one).
            nodes().last { it.isVisibleToUser && it.text?.toString() == "QA Personal" }.let { var n: AccessibilityNodeInfo? = it
                while (n != null && !n.isClickable) n = n.parent; assertTrue(n!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
            await { files.values.any { it.contains("SUMMARY:QA Sent existing") } && has("1 event is on QA Personal") }
            screenshot("sending")
            click("Close")
            click("Save")
            // A new event arrives a few seconds after it's saved.
            runBlocking { app.repository.saveItem(ItineraryItem(tripId = 0, date = day, startTime = LocalTime.of(14, 0), title = "QA Sent new")) }
            await(20000) { files.values.any { it.contains("SUMMARY:QA Sent new") } }
            assertEquals(2, files.size)
            assertTrue(files.keys.all { it.startsWith("${home}personal/planner-") && it.endsWith("@planner.ics") })
        } finally {
            app.calendarSync.client = original
            runBlocking { app.calendarSync.clearNextcloud() }
            store.clear()
            server.shutdown()
        }
    }
}
