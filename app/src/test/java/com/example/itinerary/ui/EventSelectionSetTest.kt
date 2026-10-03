package com.example.itinerary.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// UI-12: the set lookups follow the lists, however they are changed, and the lists keep their order.
class EventSelectionSetTest {
    @Test fun lookupsFollowTheLists() {
        val selection = EventSelection()
        selection.toggle(3); selection.toggle(1); selection.toggleTask("b"); selection.toggleTask("a")
        assertEquals(listOf(3L, 1L), selection.ids)
        assertEquals(listOf("b", "a"), selection.taskIds)
        assertTrue(selection.isSelected(1)); assertFalse(selection.isSelected(2)); assertTrue(selection.isTaskSelected("a"))
        selection.toggle(3); selection.toggleTask("b")
        assertEquals(listOf(1L), selection.ids); assertFalse(selection.isSelected(3)); assertFalse(selection.isTaskSelected("b"))
        // Set from outside (a restore, pruning, Delete) as well.
        selection.ids = listOf(7, 8); selection.taskIds = listOf("z")
        assertTrue(selection.isSelected(8)); assertFalse(selection.isSelected(1)); assertTrue(selection.isTaskSelected("z"))
        selection.selectAll(listOf(SelectableEvent(5, "e", java.time.LocalDate.of(2026, 10, 3))), emptyList())
        assertTrue(selection.isSelected(5)); assertFalse(selection.isSelected(7)); assertFalse(selection.isTaskSelected("z"))
        selection.clear()
        assertFalse(selection.isSelected(5)); assertFalse(selection.active)
    }
}
