package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

// Wish list #6: what a bug report sends, and a link short enough for a browser.
class BugReportTest {
    private fun query(url: String, key: String) = URLDecoder.decode(url.substringAfter("$key=").substringBefore('&'), "UTF-8")

    @Test fun theReportHoldsOnlyWhatIsShown() {
        val body = BugReport.body("The widget shows yesterday.\nAfter midnight.", "0.0.16 (23)", "15 (API 35)", "Google Pixel 7 Pro", null)
        assertEquals("The widget shows yesterday.\nAfter midnight.\n\n---\nPlanner 0.0.16 (23) · Android 15 (API 35) · Google Pixel 7 Pro", body)
        val url = BugReport.url("The widget shows yesterday.\nAfter midnight.", "0.0.16 (23)", "15 (API 35)", "Google Pixel 7 Pro", null)
        assertTrue(url.startsWith("https://github.com/baslawson/Planner/issues/new?title="))
        assertEquals("The widget shows yesterday.", query(url, "title"))
        assertEquals(body, query(url, "body"))
        assertEquals("Bug report", BugReport.title("   \n "))
    }

    @Test fun aLongCrashIsCutToFitTheLink() {
        val crash = (1..2_000).joinToString("\n") { "    at com.example.Frame$it(Frame.kt:$it)" }
        val url = BugReport.url("Crashed on save", "0.0.16", "15", "Pixel", crash)
        assertTrue(url.length <= BugReport.MAX_URL)
        val body = query(url, "body")
        assertTrue(body.contains("Last crash:")); assertTrue(body.contains("Frame1("))
        assertTrue(body.contains("…"))
    }
}
