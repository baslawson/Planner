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

/** Recently deleted: entries are picked (a tap on the row, or Select all), then restored or deleted forever together. */
@Suppress("DEPRECATION")
class RecentlyDeletedUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private fun data() = runBlocking { app.repository.snapshot() }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun button(text: String): AccessibilityNodeInfo? { var n = find(text); while (n != null && !n.isClickable) n = n.parent; return n }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir, "qa-recently-deleted").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }
    private fun await(timeout: Long = 20000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(150) }
        screenshot("failure"); fail("Timed out: " + nodes().filter { it.isVisibleToUser }.mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "))
    }
    private fun click(text: String) {
        if (text == "Settings" && find(text) == null && find("More options") != null) click("More options")
        // Further down a page (Settings' Recently deleted): scroll the page until it shows.
        await {
            button(text)?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true ||
                run { if (find(text) == null) nodes().firstOrNull { it.isScrollable && it.isVisibleToUser }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); Thread.sleep(300); false }
        }
        Thread.sleep(400)
    }
    private fun trashed() = data().deleted.map { it.label }.toSet()
    private fun tasks() = data().tasks.map { it.title }.toSet()

    @Test fun pickedEntriesAreRestoredOrDeletedForeverTogether() = runBlocking {
        val names = listOf("QA trash one", "QA trash two", "QA trash three", "QA trash four")
        names.forEach { name ->
            val task = PlannerTask(title = name); app.repository.saveTask(task); app.repository.deleteTask(task.id)
            app.repository.finishDeletion(app.repository.pendingDeletions.value.single { it.tasks.any { t -> t.id == task.id } }.token)
        }
        assertEquals(names.toSet(), trashed())
        app.settings.lastViewCalendar = false
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA") != null }
        click("Settings"); click("Recently deleted")
        await { names.all { find(it) != null } && find("4 entries") != null }
        // Nothing picked yet: both actions wait for a selection.
        assertFalse(button("Restore")!!.isEnabled); assertFalse(button("Delete forever")!!.isEnabled)
        screenshot("nothing-picked")

        // Two picked, restored together; the other two stay.
        click("QA trash one"); click("QA trash three")
        await { find("Restore (2)") != null && find("Delete forever (2)") != null }
        screenshot("two-picked")
        click("Restore (2)")
        await { tasks().containsAll(listOf("QA trash one", "QA trash three")) && trashed() == setOf("QA trash two", "QA trash four") }
        await { find("QA trash one") == null && find("Restore") != null && find("2 entries") != null }

        // Select all, then Delete forever: asks first, and Cancel keeps them.
        click("Select all")
        await { find("Delete forever (2)") != null }
        click("Delete forever (2)")
        await { find("Delete 2 entries forever?") != null }
        screenshot("confirm")
        click("Cancel")
        assertEquals(setOf("QA trash two", "QA trash four"), trashed())
        // Select all again clears the selection; once more picks both.
        click("Select all"); await { find("Restore") != null }
        click("Select all"); await { find("Delete forever (2)") != null }
        click("Delete forever (2)"); await { find("Delete 2 entries forever?") != null }
        click("Delete forever")
        await { trashed().isEmpty() && find("No recently deleted events or tasks.") != null }
        assertTrue(tasks().none { it == "QA trash two" || it == "QA trash four" })
        screenshot("empty")
        click("Close")
    }
}
