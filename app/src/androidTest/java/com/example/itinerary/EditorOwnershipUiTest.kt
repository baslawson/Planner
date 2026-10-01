package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Only one editor at a time on the same draft.
 * U2: a bill editor that Agenda reopens while AppNav's recovery editor already has that bill's draft closes again.
 * Run only with an external backup/restore harness for the shared emulator (it adds events and tasks).
 */
@Suppress("DEPRECATION")
class EditorOwnershipUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private fun data() = runBlocking { app.repository.snapshot() }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && !it.isEditable && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-editor-ownership").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 30000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun click(text: String) {
        var tries = 0; var forward = true
        await {
            var node = find(text)
            while (node != null && !node.isClickable) node = node.parent
            if (node != null && node.isEnabled) node.performAction(AccessibilityNodeInfo.ACTION_CLICK) else {
                if (++tries % 4 == 0 && !scrollStep(nodes(), forward)) forward = !forward
                false
            }
        }; Thread.sleep(350)
    }
    private fun launch() {
        app.settings.lastViewCalendar = false
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA") != null }
    }

    @Test fun aBillEditorLeavesARecoveredBillToTheRecoveryEditor() = runBlocking {
        assertNull(EditorDraftStore(context).read())
        app.settings.setAgendaRange(AgendaRange.ALL)
        app.repository.saveItem(ItineraryItem(tripId = 0, date = LocalDate.now().plusDays(3), startTime = null, title = "QA owned bill", category = "Bills"))
        val id = data().items.single { it.title == "QA owned bill" }.id
        launch()
        // As while AppNav's recovery editor is up on this bill after process death.
        EditorDraftStore.recoveryOpened(id)
        try {
            click("QA owned bill")
            Thread.sleep(1500)
            assertNull(find("Edit bill task"))
            assertEquals(0, EditorDraftStore.openEditors.value)
            screenshot("left-to-recovery")
        } finally { EditorDraftStore.recoveryClosed(id) }
        // Once recovery is closed, the bill opens as usual.
        click("QA owned bill")
        await { find("Edit bill task") != null }
        click("Close")
        await { find("Edit bill task") == null }
    }
}
