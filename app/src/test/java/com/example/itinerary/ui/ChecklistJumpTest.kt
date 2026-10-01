package com.example.itinerary.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// When the floating "Checklist n/m" button shows in the task and event editors.
class ChecklistJumpTest {
    private val zone = 200f
    private fun shows(hasItems: Boolean = true, known: Boolean = true, inView: Boolean = false, imeVisible: Boolean = false,
                      scrollLeft: Int = 1000) = ChecklistJump.shows(hasItems, known, inView, imeVisible, scrollLeft, zone)

    @Test fun showsWhileTheChecklistIsOutOfSight() = assertTrue(shows())

    @Test fun hiddenWithoutItemsOrWhileOnScreenOrTyping() {
        assertFalse(shows(hasItems = false))
        assertFalse(shows(known = false))
        assertFalse(shows(inView = true))
        assertFalse(shows(imeVisible = true))
    }

    // At (or near) the end of the form it would sit on the last controls (reminder chips, Take photo).
    @Test fun hiddenAtTheEndOfTheForm() {
        assertFalse(shows(scrollLeft = 0))
        assertFalse(shows(scrollLeft = 199))
        assertTrue(shows(scrollLeft = 200))
    }
}
