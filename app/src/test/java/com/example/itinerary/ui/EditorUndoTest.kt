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

    // ED-2: a field that is gone (a deleted checklist entry) is skipped, never put into another field.
    @Test fun aGoneFieldIsSkipped() {
        val undo = EditorUndo(); val a = Field(""); val b = Field("")
        undo.report("a", "", { a.text = it }, 0); undo.report("b", "", { b.text = it }, 0)
        b.text = "Eggs"; undo.report("b", "Eggs", { b.text = it }, 10)
        a.text = "Milk"; undo.report("a", "Milk", { a.text = it }, 5_000)
        undo.untrack("a")
        undo.undo()
        assertEquals("", b.text); assertEquals("Milk", a.text); assertFalse(undo.canUndo)
    }

    // ED-7: changes the app makes in several fields at once are one step; a sync reload is none and clears the history.
    @Test fun appChangesAreOneStepAndReloadsNone() {
        val undo = EditorUndo(); val a = Field("x"); val b = Field("y")
        undo.report("a", "x", { a.text = it }, 0); undo.report("b", "y", { b.text = it }, 0)
        a.text = "x1"; undo.report("a", "x1", { a.text = it }, 100)
        undo.together(10_000)
        a.text = "A"; undo.report("a", "A", { a.text = it }, 10_010)
        b.text = "B"; undo.report("b", "B", { b.text = it }, 10_020)
        undo.undo()
        assertEquals("x1", a.text); assertEquals("y", b.text)
        undo.redo(); assertEquals("A", a.text); assertEquals("B", b.text)
        undo.reload(20_000)
        a.text = "synced"; undo.report("a", "synced", { a.text = it }, 20_010)
        assertFalse(undo.canUndo); assertFalse(undo.canRedo)
        a.text = "synced!"; undo.report("a", "synced!", { a.text = it }, 30_000)
        undo.undo(); assertEquals("synced", a.text)
    }

    // ED-11: whole texts per step are capped by size as well as count.
    @Test fun bigTextsAreCapped() {
        val undo = EditorUndo(); val f = Field("")
        undo.report("t", "", { f.text = it }, 0)
        (1..30).forEach { i -> f.text = "x".repeat(100_000 + i); undo.report("t", f.text, { f.text = it }, i * 10_000L) }
        var steps = 0
        while (undo.canUndo) { undo.undo(); steps++ }
        assertTrue(steps in 2 until 30)
    }
}
