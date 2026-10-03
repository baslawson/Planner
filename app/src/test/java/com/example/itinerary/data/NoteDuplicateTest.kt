package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test

// Duplicate: what a copy of a note keeps and what it leaves behind.
class NoteDuplicateTest {
    private val original = PlannerNote(id = "a", title = "Shopping", content = "- [ ] milk", notebook = "Home", color = 0xFF336699.toInt(),
        pinned = true, archived = true, created = 1_000, modified = 2_000, tags = listOf("food"),
        attachments = listOf(Attachment(itemId = 0, name = "list.jpg", fileName = "f1.jpg", mimeType = "image/jpeg")),
        reminderAt = 1_900_000_000_000, snoozedUntil = 1_900_000_100_000, position = -5, priority = TaskPriority.HIGH)

    @Test fun aCopyKeepsTheNoteButNotItsReminderOrPin() {
        val copy = Notes.copyOf(original, now = 5_000)
        assertNotEquals("a", copy.id)
        assertEquals("Shopping (copy)", copy.title)
        assertEquals(original.content, copy.content); assertEquals("Home", copy.notebook); assertEquals(original.color, copy.color)
        assertEquals(listOf("food"), copy.tags); assertEquals(original.attachments, copy.attachments); assertEquals(TaskPriority.HIGH, copy.priority)
        assertTrue("an archived note's copy stays archived", copy.archived)
        assertFalse(copy.pinned); assertNull(copy.reminderAt); assertNull(copy.snoozedUntil)
        assertEquals(5_000, copy.created); assertEquals(5_000, copy.modified)
    }

    @Test fun titles() {
        assertEquals("Groceries (copy)", Notes.copyTitle(PlannerNote(content = "Groceries\nbread")))
        assertEquals("Copy", Notes.copyTitle(PlannerNote(content = "   ", attachments = original.attachments)))
        val long = "x".repeat(Notes.MAX_TITLE)
        assertEquals(Notes.MAX_TITLE, Notes.copyTitle(PlannerNote(title = long)).length)
        assertTrue(Notes.copyTitle(PlannerNote(title = long)).endsWith(" (copy)"))
    }
}
