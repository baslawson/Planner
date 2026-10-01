package com.example.itinerary.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The support pop-up: once after installing, once after each update, never again for the same version.
class SupportPromptTest {
    @Test fun showsOncePerInstalledVersion() {
        assertTrue("fresh install", SupportPrompt.due(shownFor = 0, installed = 17))
        assertFalse("same version", SupportPrompt.due(shownFor = 17, installed = 17))
        assertTrue("update", SupportPrompt.due(shownFor = 17, installed = 18))
        assertFalse("downgrade", SupportPrompt.due(shownFor = 18, installed = 17))
    }
}
