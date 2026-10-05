package com.example.itinerary

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.ui.TaskEditor
import org.junit.Assert.*
import org.junit.Test

/** New task ownership starts while blank, before its shared recovery draft exists. */
class NewTaskDraftOwnershipRegressionTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { out += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return out
    }
    private fun await(block: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < until) { if (block()) return; Thread.sleep(100) }
        fail("Timed out: " + nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "))
    }
    private fun x(n: AccessibilityNodeInfo) = Rect().also(n::getBoundsInScreen).centerX()
    private fun fields() = nodes().filter { n -> n.isVisibleToUser && n.isEditable &&
        (0 until n.childCount).any { n.getChild(it)?.text?.toString() == "Task title" } }.sortedBy(::x)
    private fun type(n: AccessibilityNodeInfo, value: String) {
        assertTrue(n.isEnabled)
        assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }
    private fun click(text: String, rightmost: Boolean = false) {
        await {
            val found = nodes().filter { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
            var n = if (rightmost) found.maxByOrNull(::x) else found.firstOrNull()
            while (n != null && !n.isClickable) n = n.parent
            n?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        Thread.sleep(200)
    }
    @Test fun secondNewEditorCannotEraseFirstEditorsDraft() {
        val store = TaskDraftStore(context)
        store.clear("new")
        val activity = ins.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val a = PlannerTask(id = "qa-new-editor-a")
        val b = PlannerTask(id = "qa-new-editor-b")
        val showA = mutableStateOf(true)
        val showB = mutableStateOf(false)
        try {
            ins.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        Row(Modifier.fillMaxSize()) {
                            if (showA.value) Box(Modifier.weight(1f).fillMaxHeight()) {
                                TaskEditor(a, true, onDismiss = { showA.value = false })
                            }
                            if (showB.value) Box(Modifier.weight(1f).fillMaxHeight()) {
                                TaskEditor(b, true, onDismiss = { showB.value = false })
                            }
                        }
                    }
                }
            }
            await { TaskDraftStore.openEditors.value == 1 && fields().size == 1 }
            assertNull("Blank new task has no draft yet", store.read("new"))
            ins.runOnMainSync { showB.value = true }
            await { nodes().any { it.text?.toString() == "Task already open" } }
            assertEquals("Blocked editor never owns a draft", 1, TaskDraftStore.openEditors.value)
            assertNull(store.read("new"))
            click("Close")
            await { fields().size == 1 && !showB.value }
            type(fields().single(), "unsavedA")
            await { store.read("new")?.optString("id") == a.id && store.read("new")?.optString("title") == "unsavedA" }
            ins.runOnMainSync { showB.value = true }
            await { nodes().any { it.text?.toString() == "Task already open" } }
            click("Close")
            await { fields().size == 1 && !showB.value }
            store.flush()
            assertEquals("Other editor's text remains visible", "unsavedA", fields().single().text?.toString())
            assertEquals("Closing blocked editor preserves owner's draft", "unsavedA", store.read("new")?.optString("title"))
            assertEquals(a.id, store.read("new")?.optString("id"))
        } finally {
            ins.runOnMainSync { activity.finish() }
            store.clear("new")
        }
    }
}
