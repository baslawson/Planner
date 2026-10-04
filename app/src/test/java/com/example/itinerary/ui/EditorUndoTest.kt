package com.example.itinerary.ui

import org.junit.Assert.*
import org.junit.Test

// Wish list #2: one editor-wide history, a word or a pause per step, in the order things were typed.
class EditorUndoTest {
    private class Field(var text: String)

    @Test fun undoAndRedoWordByWordAcrossFields() {
        val undo = EditorUndo()
        val title = Field(""); val notes = Field("")
        fun type(f: Field, key: String, value: String, at: Long) { f.text = value; undo.report(key, value, { f.text = it }, at) }
        undo.report("title", "", { title.text = it }, 0); undo.report("notes", "", { notes.text = it }, 0)
        type(title, "title", "D", 10); type(title, "title", "Di", 20); type(title, "title", "Din", 30)
        type(title, "title", "Din ", 40); type(title, "title", "Din a", 50)
        type(notes, "notes", "x", 60)
        assertTrue(undo.canUndo); assertFalse(undo.canRedo)
        undo.undo(); undo.report("notes", notes.text, { notes.text = it }, 70)
        assertEquals("", notes.text); assertEquals("Din a", title.text)
        undo.undo(); undo.report("title", title.text, { title.text = it }, 80)
        assertEquals("Din", title.text)
        undo.undo(); undo.report("title", title.text, { title.text = it }, 90)
        assertEquals("", title.text); assertFalse(undo.canUndo)
        undo.redo(); undo.report("title", title.text, { title.text = it }, 100)
        assertEquals("Din", title.text)
        // Typing after an Undo drops what could be redone.
        type(title, "title", "Dinner", 2_000)
        assertFalse(undo.canRedo)
    }

    @Test fun aPauseStartsANewStep() {
        val undo = EditorUndo(); val f = Field("")
        undo.report("t", "", { f.text = it }, 0)
        f.text = "ab"; undo.report("t", "ab", { f.text = it }, 10)
        f.text = "abc"; undo.report("t", "abc", { f.text = it }, 5_000)
        undo.undo(); assertEquals("ab", f.text)
    }

    @Test fun theHistoryIsCapped() {
        val undo = EditorUndo(); val f = Field("")
        undo.report("t", "", { f.text = it }, 0)
        (1..150).forEach { i -> f.text = "w$i"; undo.report("t", f.text, { f.text = it }, i * 10_000L) }
        var steps = 0
        while (undo.canUndo) { undo.undo(); undo.report("t", f.text, { f.text = it }, 0); steps++ }
        assertEquals(EditorUndo.MAX_STEPS, steps)
    }

    @Test fun theCursorGoesWhereTheChangeWas() {
        assertEquals(5, undoCursor("Hello world", "Hello"))
        assertEquals(11, undoCursor("Hello", "Hello world"))
        assertEquals(3, undoCursor("abXYcd", "abZcd"))
    }
}
