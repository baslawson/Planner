package com.example.itinerary

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.ui.ItemEditorSheet
import com.example.itinerary.ui.theme.ItineraryTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/** Run with the external private-data backup/restore harness. */
class DuplicateBillUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as ItineraryApp
    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
        return null
    }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("Timed out waiting for duplicate-bill UI")
    }
    private fun visible(text: String) = find(ins.uiAutomation.rootInActiveWindow, text) != null
    private fun click(text: String) {
        await { visible(text) }
        var node = find(ins.uiAutomation.rootInActiveWindow, text)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun show(item: ItineraryItem, saves: AtomicInteger): MainActivity {
        EditorDraftStore(ins.targetContext).clear()
        val activity = ins.startActivitySync(Intent(ins.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        ins.runOnMainSync { activity.setContent {
            ItineraryTheme { ItemEditorSheet(item, emptyList(), emptyList(), emptyMap(), emptySet(), {}, {}, {},
                onSave = { event, added, removed, reminders, removedReminders, options ->
                    app.repository.saveItem(event, added, removed, reminders, removedReminders, options)
                    saves.incrementAndGet()
                }, onDelete = { _, _ -> }) }
        } }
        return activity
    }

    @Test fun warnsBeforeWritingGoBackKeepsDraftAndSaveAnywayWritesOnce() = runBlocking {
        val repo = app.repository
        val seed = ItineraryItem(tripId = 0, date = LocalDate.of(2000, 1, 1), startTime = null,
            title = "QA duplicate electricity", category = "Bills", billAmountMinor = 12345)
        repo.saveItem(seed)
        val before = repo.snapshot()
        val saved = before.items.single { it.title == seed.title }
        val saves = AtomicInteger()
        val activity = show(saved.copy(id = 0, title = "  QA DUPLICATE   electricity  "), saves)
        try {
            click("Save")
            await { visible("Possible duplicate bill") }
            assertEquals(before, repo.snapshot())
            assertEquals(0, saves.get())
            click("Open existing bill")
            await { visible("Existing bill") }
            await { visible(seed.title) && visible("AUD 123.45") }
            click("Back to draft")
            await { visible("Possible duplicate bill") }
            assertEquals(before, repo.snapshot())
            click("Go back")
            await { !visible("Possible duplicate bill") }
            assertTrue(EditorDraftStore(ins.targetContext).read() != null)
            click("Save")
            await { visible("Possible duplicate bill") }
            click("Save anyway")
            await { saves.get() == 1 }
            assertEquals(before.items.size + 1, repo.snapshot().items.size)
            assertNull(EditorDraftStore(ins.targetContext).read())
        } finally { ins.runOnMainSync { activity.finish() } }
    }

    @Test fun editingSameBillDoesNotWarnAboutItself() = runBlocking {
        val repo = app.repository
        repo.saveItem(ItineraryItem(tripId = 0, date = LocalDate.of(2000, 2, 2), startTime = null,
            title = "QA unique edit", category = "Bills", billAmountMinor = 45678))
        val before = repo.snapshot()
        val saves = AtomicInteger()
        val activity = show(before.items.single { it.title == "QA unique edit" }, saves)
        try {
            click("Save")
            await { saves.get() == 1 }
            assertFalse(visible("Possible duplicate bill"))
            assertEquals(before.items.size, repo.snapshot().items.size)
        } finally { ins.runOnMainSync { activity.finish() } }
    }
}
