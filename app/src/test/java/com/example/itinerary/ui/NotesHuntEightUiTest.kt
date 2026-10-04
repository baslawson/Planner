package com.example.itinerary.ui

import androidx.compose.ui.text.input.TextFieldValue
import com.example.itinerary.data.PlannerNote
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

// Bug hunt #8 (5 Oct 2026): the note editor's memory by editor, not by note (NT-1), and what Undo keeps when the stored
// note is loaded into the fields (EU-1, EU-2).
class NotesHuntEightUiTest {
    @After fun tidy() = NoteEditorMemory.forgetAll()

    private fun body(text: String) = NoteEditorMemory.Body(TextFieldValue(text), emptyList(), null)

    // NT-1: an editor another window left (its task swiped away) is no sign that this page's editor was rebuilt, so the
    // Notes page still reads the note's draft; only the page's own key finds its editor's body.
    @Test fun onlyThePagesOwnEditorIsFoundInMemory() {
        NoteEditorMemory.forgetAll()
        val left = body("typed in the window that went")
        NoteEditorMemory.keep("their-editor", "x", left)
        assertNull(NoteEditorMemory.restore("my-editor", "x"))
        assertSame(left, NoteEditorMemory.restore("their-editor", "x"))
        // This page's editor of the note then takes its place.
        val mine = body("mine")
        NoteEditorMemory.keep("my-editor", "x", mine)
        assertSame(mine, NoteEditorMemory.restore("my-editor", "x"))
        assertNull(NoteEditorMemory.restore("their-editor", "x"))
    }

    // EU-2: a snooze, a pin, an archive, a reorder or a sync's new time changes no words, so the history stays.
    @Test fun onlyAChangeOfWordsCountsAsReloadingTheText() {
        val note = PlannerNote(id = "n", title = "Shopping", content = "milk", notebook = "Home")
        assertFalse(noteWordsDiffer(note.copy(snoozedUntil = 5, pinned = true, archived = true, position = 3, modified = 9), "Shopping", "milk", "Home"))
        assertTrue(noteWordsDiffer(note, "Shopping", "milk\nbread", "Home"))
        assertTrue(noteWordsDiffer(note, "Shops", "milk", "Home"))
        assertTrue(noteWordsDiffer(note, "Shopping", "milk", "Work"))
    }

    // EU-1: a merged Save loads another device's line with the history reloaded first, so one Undo can't remove it.
    @Test fun aMergedLoadIsNotAStepToUndo() {
        val undo = EditorUndo()
        var body = "Shopping"
        undo.report("body", body, { body = it }, 0)
        body = "Shopping list"; undo.report("body", body, { body = it }, 10)
        // The merge, as saved() now loads it.
        undo.reload(2_000)
        body = "Remote line\nShopping list"; undo.report("body", body, { body = it }, 2_010)
        undo.undo()
        assertEquals("Remote line\nShopping list", body)
    }
}
