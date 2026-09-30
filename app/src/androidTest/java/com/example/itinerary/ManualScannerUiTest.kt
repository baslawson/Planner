package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Normal application flow. Run only after backing up user data; the external harness restores it. */
@Suppress("DEPRECATION")
class ManualScannerUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val repo get() = (instrumentation.targetContext.applicationContext as ItineraryApp).repository
    private fun snapshot() = runBlocking { repo.snapshot() }

    @Test fun manualShutterCropPdfAndImageAttachInNormalEditor() = runBlocking {
        val info = instrumentation.uiAutomation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        instrumentation.uiAutomation.serviceInfo = info
        val app = instrumentation.targetContext.applicationContext as ItineraryApp
        EditorDraftStore(instrumentation.targetContext).clear()
        app.settings.lastViewCalendar = false
        app.settings.setAgendaRange(AgendaRange.ALL)
        app.settings.setAgendaTypes(AgendaType.entries.toSet())
        repo.saveItem(ItineraryItem(tripId = 0, date = LocalDate.now(), startTime = null, title = "QA manual scan"))
        val item = snapshot().items.single { it.title == "QA manual scan" }
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        click("QA manual scan"); click("QA manual scan")
        click("Scan document")
        capturePage()
        await { nodes().any { it.text?.toString()?.startsWith("Page 1 of 1 ·") == true } }
        val crop = nodes().first { it.contentDescription?.toString() == "Document crop. Adjust the four page corners." }
        val action = crop.actionList.first { it.label?.toString() == "Move top left corner right" }
        assertTrue(crop.performAction(action.id))
        click("Page tools"); click("Rotate")
        Thread.sleep(1000)
        screenshot("manual-page-review")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys window windows")).use { input ->
            File(instrumentation.targetContext.cacheDir,"qa-manual-scanner-evidence/window.txt").writeBytes(input.readBytes())
        }
        val attach = requireNotNull(find("Preview scan"))
        val bounds = android.graphics.Rect().also(attach::getBoundsInScreen)
        val display = instrumentation.uiAutomation.takeScreenshot()!!
        try { assertTrue("Attach scan must be on screen: $bounds, height=${display.height}",bounds.bottom <= display.height && bounds.top >= 0) }
        finally { display.recycle() }
        click("Page tools"); click("Take page"); capturePage()
        await { nodes().any { it.text?.toString()?.startsWith("Page 2 of 2 ·") == true } }
        click("Page tools"); click("Move earlier")
        await { nodes().any { it.text?.toString()?.startsWith("Page 1 of 2 ·") == true } }
        click("Preview scan");click("Attach scan")
        await { find("Save") != null }
        saveAndClose()
        await { snapshot().attachments.any { it.itemId == item.id } }
        val pdf = snapshot().attachments.single { it.itemId == item.id }
        assertEquals("application/pdf",pdf.mimeType)
        android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(app.attachmentStore.fileFor(pdf.fileName),android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use {
            assertEquals(2,it.pageCount)
        }
        click("QA manual scan");click("Take photo")
        capturePage(scanning = false)
        await { find("Save") != null }
        saveAndClose()
        await { snapshot().attachments.count { it.itemId == item.id } == 2 }
        val jpeg = snapshot().attachments.single { it.itemId == item.id && it.mimeType == "image/jpeg" }
        val bitmap = android.graphics.BitmapFactory.decodeFile(app.attachmentStore.fileFor(jpeg.fileName).path)
        assertNotNull(bitmap);assertTrue(bitmap.width > 100);bitmap.recycle()
        // Capture another page, discard only the scan, then close the unchanged editor: no extra attachment.
        click("QA manual scan");click("Scan document");capturePage()
        click("Cancel");click("Discard")
        await { find("Save") != null }
        click("Close") // the scan was discarded, so nothing is unsaved and it closes at once
        await { find("Save") == null && find("QA manual scan") != null }
        assertEquals(2,snapshot().attachments.count { it.itemId == item.id })
        await {
            File(instrumentation.targetContext.cacheDir,"manual-scans").listFiles()?.none { it.isDirectory } != false
        }
        screenshot("saved-scans")
    }

    private fun capturePage(scanning: Boolean = true) {
        // AOSP camera: prove it waits for a shutter tap instead of capturing automatically.
        fun shutter() = nodes().firstOrNull { it.isVisibleToUser &&
            (it.viewIdResourceName?.endsWith(":id/shutter_button") == true || it.contentDescription?.toString() == "Shutter") }
        await { shutter() != null }
        Thread.sleep(1500)
        assertNotNull("Camera must still be waiting for a manual shutter tap",shutter())
        screenshot("manual-camera")
        assertTrue(shutter()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await {
            if (if (scanning) nodes().any { it.contentDescription?.toString() == "Document crop. Adjust the four page corners." } else find("Save") != null) true
            else {
                // The camera exposes Done before JPEG processing finishes; a successful accessibility
                // action alone does not mean it accepted the photo. Wait for the actual return.
                nodes().firstOrNull { it.isVisibleToUser && it.isEnabled &&
                    (it.viewIdResourceName?.endsWith(":id/done_button") == true || it.contentDescription?.toString() == "Done") }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(350)
                false
            }
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        instrumentation.uiAutomation.freshRoot?.let(::visit)
        return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser &&
        (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(150)
        }
        screenshot("failure")
        throw AssertionError("Timed out. Visible text: " + nodes().mapNotNull { it.text?.toString() }.joinToString(" | "))
    }
    // Save keeps the editor open on the saved event; Close then leaves (nothing is unsaved).
    private fun saveAndClose() { click("Save"); click("Close") }
    private fun click(text: String) {
        var attempts = 0
        var forward = true
        await {
            var target = find(text)
            while (target != null && !target.isClickable) target = target.parent
            if (target != null) target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            else {
                // Give navigation/recomposition time before moving the new screen. Search both
                // directions so a transient missing node cannot strand the test below the target.
                if (++attempts > 5) {
                    val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    val scrolled = nodes().lastOrNull { it.isScrollable && !it.isEditable }?.performAction(action) == true
                    if (!scrolled) forward = !forward
                    Thread.sleep(600)
                    screenshot("scroll-$attempts")
                }
                false
            }
        }
        Thread.sleep(350)
    }
    private fun setText(node: AccessibilityNodeInfo, value: String) {
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        Thread.sleep(250)
    }
    private fun screenshot(name: String) {
        val folder = File(instrumentation.targetContext.cacheDir, "qa-manual-scanner-evidence").apply { mkdirs() }
        File(folder, "$name.txt").writeText(nodes().joinToString("\n") { "${android.graphics.Rect().also(it::getBoundsInScreen)} ${it.className} text=${it.text} desc=${it.contentDescription} id=${it.viewIdResourceName} editable=${it.isEditable} focus=${it.isFocused} actions=${it.actionList}" })
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
