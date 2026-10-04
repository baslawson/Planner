package com.example.itinerary.ui

import androidx.compose.ui.text.input.TextFieldValue
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

// Bug hunt #7 (5 Oct 2026): a note editor still on screen keeps its body in memory (NW-4).
class NotesHuntSevenUiTest {
    @After fun tidy() = NoteEditorMemory.forgetAll()

    private fun body(text: String) = NoteEditorMemory.Body(TextFieldValue(text), emptyList(), null)

    @Test fun anOpenEditorsBodyIsNeverPushedOut() {
        NoteEditorMemory.forgetAll()
        val open = body("typed in the open editor")
        NoteEditorMemory.keep("open", "n0", open); NoteEditorMemory.opened("open")
        // Windows that went with their editors open, and newer editors.
        (1..12).forEach { NoteEditorMemory.keep("gone$it", "n$it", body("$it")) }
        assertSame(open, NoteEditorMemory.restore("open", "n0"))
        assertNull("the oldest closed one goes", NoteEditorMemory.restore("gone1", "n1"))
        assertNotNull(NoteEditorMemory.restore("gone12", "n12"))
        // Closed, it goes past the few like any other.
        NoteEditorMemory.closed("open")
        (13..21).forEach { NoteEditorMemory.keep("gone$it", "n$it", body("$it")) }
        assertNull(NoteEditorMemory.restore("open", "n0"))
    }
}
