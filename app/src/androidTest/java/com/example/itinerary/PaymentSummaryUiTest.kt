package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** The bill editor's payments: a summary (paid of amount, remaining, progress, latest payment) instead of every payment;
 *  "Payment history (n)" opens the list newest first, where Reverse still works and the summary follows. */
@Suppress("DEPRECATION")
class PaymentSummaryUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun visible() = nodes().filter { it.isVisibleToUser }
    private fun find(text: String) = visible().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun has(part: String) = visible().any { it.text?.toString()?.contains(part) == true }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-payment-summary").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 20000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + visible().mapNotNull { it.text }.joinToString(" | "))
    }
    // A real touch swipe: [back] scrolls towards the top of the form.
    private fun swipe(back: Boolean = false) {
        val m = context.resources.displayMetrics
        val (from, to) = if (back) m.heightPixels * 4 / 10 to m.heightPixels * 7 / 10 else m.heightPixels * 7 / 10 to m.heightPixels * 4 / 10
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(
            "input swipe ${m.widthPixels / 3} $from ${m.widthPixels / 3} $to 300")).use { it.readBytes() }
        Thread.sleep(700)
    }
    private fun reveal(back: Boolean = false, test: () -> Boolean) { var tries = 0; await { test() || run { if (++tries % 4 == 0) swipe(back); false } } }
    private fun click(text: String) {
        reveal { find(text) != null }
        await { var n = find(text); while (n != null && !n.isClickable) n = n.parent; n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
        Thread.sleep(500)
    }
    // What a screen reader hears as the state of the clickable control holding [text] (Android 11 and later).
    private fun state(text: String): String? { var n = find(text); while (n != null && !n.isClickable) n = n.parent; return n?.stateDescription?.toString() }
    private fun top(text: String): Int { val r = Rect(); visible().first { it.text?.toString()?.startsWith(text) == true }.getBoundsInScreen(r); return r.top }

    @Test fun summaryThenHistory() = runBlocking {
        val d = LocalDate.of(2026, 9, 1)
        app.repository.saveItem(ItineraryItem(tripId = 0, date = LocalDate.now().plusDays(3), startTime = null, title = "QA Rent", category = "Bills",
            billAmountMinor = 70000, billCurrency = "AUD", payments = listOf(
                BillPayment(id = "a", amount = 10000, date = d.minusDays(29)), BillPayment(id = "b", amount = 20000, date = d, note = "Bank transfer"),
                BillPayment(id = "c", amount = 5000, date = d.plusDays(3), reversed = true), BillPayment(id = "d", amount = 15000, date = d.minusDays(12)))))
        val item = app.repository.snapshot().items.single { it.title == "QA Rent" }
        val activity = ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        ins.runOnMainSync { activity.setContent {
            com.example.itinerary.ui.theme.ItineraryTheme {
                com.example.itinerary.ui.ItemEditorSheet(item, emptyList(), emptyList(), emptyMap(), emptySet(), {}, {}, {},
                    onSave = { event, added, removed, reminders, removedReminders, options -> app.repository.saveItemId(event, added, removed, reminders, removedReminders, options) },
                    onDelete = { _, _ -> })
            }
        } }
        await { find("Edit bill task") != null }
        // The summary, not the payments.
        reveal { has("AUD 450.00 of AUD 700.00 paid · AUD 250.00 remaining") && find("Payment history (4)") != null }
        assertTrue(has("Last payment: AUD 200.00 on "))
        assertFalse(has("Bank transfer")); assertFalse(has("Reversed")); assertNull(find("Reverse"))
        if (android.os.Build.VERSION.SDK_INT >= 30) assertEquals("Collapsed", state("Payment history (4)"))
        screenshot("summary")
        // Opened: newest first (the reversed one on the 4th, then the 1st, 20 Aug, 3 Aug), Reverse on those that count.
        click("Payment history (4)")
        reveal { has("AUD 100.00 · ") }
        assertTrue(has("Bank transfer") && has("Reversed"))
        assertTrue(top("AUD 50.00 · ") < top("AUD 200.00 · ") && top("AUD 200.00 · ") < top("AUD 150.00 · ") && top("AUD 150.00 · ") < top("AUD 100.00 · "))
        assertEquals(3, visible().count { it.text?.toString() == "Reverse" })
        screenshot("history")
        if (android.os.Build.VERSION.SDK_INT >= 30) { reveal(back = true) { find("Payment history (4)") != null }; assertEquals("Expanded", state("Payment history (4)")) }
        // Reversing one updates the summary.
        click("Reverse")
        click("Reverse payment")
        // Back to the top of the form, then down to the summary.
        repeat(6) { swipe(back = true) }
        // The first Reverse is the newest payment that counts (AUD 200.00 on 1 Sep): AUD 250.00 paid, the latest now 20 Aug.
        reveal { has("AUD 250.00 of AUD 700.00 paid · AUD 450.00 remaining") }
        assertTrue(has("Last payment: AUD 150.00 on "))
        screenshot("reversed")
    }
}
