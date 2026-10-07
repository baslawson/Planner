package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Test

// The editor fills an empty Title with the note's first line of text, without its Markdown.
class NoteAutoTitleTest {
    @Test fun theFirstLineWithoutMarkdown() {
        assertEquals("Buy milk", Notes.firstLine("Buy milk\nPick up parcel"))
        assertEquals("Buy milk", Notes.firstLine("\n\n  Buy milk  \nmore"))
        assertEquals("Shopping", Notes.firstLine("# Shopping\n- eggs"))
        assertEquals("bold start", Notes.firstLine("**bold** start"))
        assertEquals("", Notes.firstLine(""))
        assertEquals(Notes.MAX_TITLE, Notes.firstLine("x".repeat(Notes.MAX_TITLE + 50)).length)
    }
}
