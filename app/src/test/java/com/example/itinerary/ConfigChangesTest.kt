package com.example.itinerary

import org.junit.Assert.*
import org.junit.Test
import java.io.File

// U-N4: a configuration change missing from android:configChanges recreates the activity, and with it every screen's
// state. Planner is all Compose, which recomposes for these, so each is handled in place.
class ConfigChangesTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private fun changes(activity: String): Set<String> {
        val tag = Regex("<activity[^>]*android:name=\"\\.$activity\"[^>]*>").find(manifest)?.value
        assertNotNull("$activity is declared", tag)
        return Regex("android:configChanges=\"([^\"]*)\"").find(tag!!)!!.groupValues[1].split("|").toSet()
    }
    private val needed = setOf("orientation", "screenSize", "screenLayout", "smallestScreenSize", "keyboard", "keyboardHidden",
        "navigation", "fontScale", "fontWeightAdjustment", "uiMode", "colorMode", "density", "locale", "layoutDirection",
        "grammaticalGender")

    @Test fun plannerKeepsItsScreensThroughEveryChangeComposeHandles() =
        assertEquals(emptySet<String>(), needed - changes("MainActivity"))

    @Test fun theLockScreenToo() = assertEquals(emptySet<String>(), needed - changes("LockActivity"))
}
