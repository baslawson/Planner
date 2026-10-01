package com.example.itinerary

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// U-N3: Android starts a static launcher shortcut with NEW_TASK | CLEAR_TASK. Aimed at MainActivity that destroyed a
// running Planner and the editor open in it. The shortcuts go to EntryShortcutActivity instead, in a task of its own,
// which hands them to Planner's existing task without clearing it.
class ShortcutRoutingTest {
    private fun targets(variant: String): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/$variant/res/xml/shortcuts.xml"))
        val intents = doc.getElementsByTagName("intent")
        return (0 until intents.length).map { (intents.item(it) as org.w3c.dom.Element).getAttribute("android:targetClass") }
    }

    @Test fun everyShortcutInEveryBuildGoesThroughTheTrampoline() {
        for (variant in listOf("main", "debug", "uitest")) {
            val classes = targets(variant)
            assertEquals(variant, 3, classes.size)
            assertTrue("$variant: $classes", classes.all { it == "com.example.itinerary.EntryShortcutActivity" })
        }
    }

    @Test fun theTrampolineHasItsOwnTaskAndNoTrace() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val tag = Regex("<activity[^>]*android:name=\"\\.EntryShortcutActivity\"[^>]*>").find(manifest)?.value
        assertNotNull("EntryShortcutActivity is declared", tag)
        // Its own task: the launcher's CLEAR_TASK then clears only that, not Planner's.
        assertTrue(tag!!.contains("android:taskAffinity=\"\""))
        assertTrue(tag.contains("android:excludeFromRecents=\"true\""))
        assertTrue(tag.contains("android:noHistory=\"true\""))
    }

    @Test fun theShortcutReachesTheRunningPlannerInsteadOfReplacingIt() {
        val flags = EntryShortcuts.FORWARD_FLAGS
        assertEquals(0, flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertNotEquals(0, flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertNotEquals(0, flags and Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
