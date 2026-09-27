package com.example.itinerary

import android.content.Intent
import android.os.Bundle
import android.os.FileObserver
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.Text
import com.example.itinerary.ui.rememberSearchOutcome
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.ui.ItemEditorSheet
import com.example.itinerary.ui.theme.ItineraryTheme
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/** Uses the external backup/restore harness for the real app's draft file. */
@Suppress("DEPRECATION")
class OptimizationUiTest {
    @Test fun rapidSearchChangesNeverPublishAnObsoleteResult() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val activity = ins.startActivitySync(Intent(ins.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val day = LocalDate.of(2000, 1, 1)
        val index = Search.prepare(
            listOf(Trip(id = 1, name = "Fixture", destination = "", startDate = day, endDate = day)),
            (1L..3000L).map { ItineraryItem(id = it, tripId = 1, date = day, startTime = null,
                title = if (it == 1L) "needle" else "electricity", notes = "billing document details ".repeat(20)) }, emptyList())
        val query = mutableStateOf("electricty")
        val finalResults = java.util.concurrent.CopyOnWriteArrayList<List<Long>>()
        try {
            ins.runOnMainSync { activity.setContent {
                ItineraryTheme {
                    val result = rememberSearchOutcome(query.value, emptySet(), index, day)
                    SideEffect {
                        if (query.value == "needle" && result !== SearchOutcome.LOADING)
                            finalResults.add(result.hits.map { it.item.id })
                    }
                    Text("${query.value}: ${result.hits.size}")
                }
            } }
            repeat(6) { n ->
                ins.runOnMainSync { query.value = if (n % 2 == 0) "document" else "electricty" }
                Thread.sleep(20)
            }
            ins.runOnMainSync { query.value = "needle" }
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (finalResults.isEmpty() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertFalse(finalResults.isEmpty())
            Thread.sleep(300)
            assertTrue(finalResults.all { it == listOf(1L) })
        } finally { ins.runOnMainSync { activity.finish() } }
    }

    /** Run only after the editor test and an external force-stop with a confirmed missing PID. */
    @Test fun recoverDurableDraftAfterProcessDeath() {
        val ins = InstrumentationRegistry.getInstrumentation()
        assertEquals("Durable changed title", EditorDraftStore(ins.targetContext).read()!!
            .getJSONObject("item").getString("title"))
        val activity = ins.startActivitySync(Intent(ins.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            fun contains(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.isEditable && node.text?.toString() == "Durable changed title") return true
                return (0 until node.childCount).any { contains(node.getChild(it)) }
            }
            val deadline = SystemClock.elapsedRealtime() + 10000
            while (SystemClock.elapsedRealtime() < deadline && !contains(ins.uiAutomation.rootInActiveWindow)) Thread.sleep(100)
            assertTrue(contains(ins.uiAutomation.rootInActiveWindow))
        } finally { ins.runOnMainSync { activity.finish() } }
    }

    @Test fun unrelatedEditorUpdatesDoNotRewriteDraftButTypingIsDurable() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val store = EditorDraftStore(ins.targetContext)
        store.clear()
        val writes = AtomicInteger()
        val observer = object : FileObserver(ins.targetContext.filesDir.path, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path?.startsWith("editor-draft.json") == true) writes.incrementAndGet()
            }
        }
        observer.startWatching()
        val activity = ins.startActivitySync(Intent(ins.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val tick = mutableIntStateOf(0)
        fun await(test: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 10000
            while (SystemClock.elapsedRealtime() < end) {
                if (test()) return
                Thread.sleep(50)
            }
            fail("Timed out waiting for durable draft")
        }
        try {
            ins.runOnMainSync { activity.setContent {
                ItineraryTheme { ItemEditorSheet(
                    ItineraryItem(tripId = 0, date = LocalDate.of(2000, 1, 1), startTime = null, title = "Draft benchmark"),
                    emptyList(), emptyList(), mapOf("Food" to tick.intValue), emptySet(), {}, {}, {},
                    onSave = { _, _, _, _, _, _ -> }, onDelete = { _, _ -> }) }
            } }
            await { store.read()?.getJSONObject("item")?.getString("title") == "Draft benchmark" }
            Thread.sleep(300)
            val before = writes.get()
            repeat(20) {
                ins.runOnMainSync { tick.intValue++ }
                ins.waitForIdleSync()
                Thread.sleep(30)
            }
            Thread.sleep(300)
            assertEquals(before, writes.get())
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isEditable && node.text?.toString()?.contains("Draft benchmark") == true) return node
                for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
                return null
            }
            await {
                if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
                find(ins.uiAutomation.rootInActiveWindow) != null
            }
            val field = find(ins.uiAutomation.rootInActiveWindow) ?: error("Title field missing")
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Durable changed title")
            }))
            await { store.read()?.getJSONObject("item")?.getString("title") == "Durable changed title" }
            await { writes.get() > before }
            Log.i("Optimization", "Draft: 20 unrelated updates, 0 file writes; actual title edit immediately persisted")
        } finally { observer.stopWatching(); ins.runOnMainSync { activity.finish() } }
    }
}
