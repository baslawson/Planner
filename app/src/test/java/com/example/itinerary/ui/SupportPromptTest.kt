package com.example.itinerary.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The support pop-up: not after a fresh install, once after each update, never again for the same version.
class SupportPromptTest {
    @Test fun showsOncePerInstalledVersion() {
        assertFalse("fresh install", SupportPrompt.due(shownFor = 0, installed = 17, freshInstall = true))
        assertTrue("updated from a version that never showed it", SupportPrompt.due(shownFor = 0, installed = 17, freshInstall = false))
        assertTrue("an update after a fresh install", SupportPrompt.due(shownFor = 17, installed = 18, freshInstall = false))
        assertFalse("same version", SupportPrompt.due(shownFor = 17, installed = 17))
        assertTrue("update", SupportPrompt.due(shownFor = 17, installed = 18))
        assertFalse("downgrade", SupportPrompt.due(shownFor = 18, installed = 17))
    }

    // One support task at a time: none added while one is still to do; a fresh one once it was ticked off or deleted.
    @Test fun remindMeLaterNeverStacksTasks() {
        val open = com.example.itinerary.data.PlannerTask(title = SupportPrompt.TASK_TITLE)
        assertTrue("none yet", SupportPrompt.needsTask(emptyList()))
        assertFalse("one still to do", SupportPrompt.needsTask(listOf(open)))
        assertTrue("ticked off", SupportPrompt.needsTask(listOf(open.copy(done = true))))
        assertTrue("renamed by the user", SupportPrompt.needsTask(listOf(open.copy(title = "Pay for apps"))))
    }
}
