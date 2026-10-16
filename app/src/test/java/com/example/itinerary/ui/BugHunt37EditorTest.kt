package com.example.itinerary.ui

import org.junit.Assert.*
import org.junit.Test

// Bug hunt 37: an editor's title pick and an applied bill suggestion are each one Undo step back to before them.
class BugHunt37EditorTest {
    private class Field(var text: String)

    // E3: a pick that changes only the title and place (a saved event: nothing kept changes) is one step, as pickTitle
    // now makes it with around, and doesn't merge the title into the typing just before it.
    @Test fun aTitlePickIsOneStepBackToBeforeIt() {
        val undo = EditorUndo(); val title = Field(""); val location = Field("")
        undo.report("title", "", { title.text = it }, 0); undo.report("location", "", { location.text = it }, 0)
        title.text = "De"; undo.report("title", "De", { title.text = it }, 100)
        undo.around(undo = {}, redo = {}, now = 300)
        title.text = "Den"; undo.report("title", "Den", { title.text = it }, 310)
        location.text = "Home"; undo.report("location", "Home", { location.text = it }, 320)
        undo.undo()
        assertEquals("De", title.text); assertEquals("", location.text)
        undo.redo()
        assertEquals("Den", title.text); assertEquals("Home", location.text)
    }

    // E2: Apply's date, amount, category and added reminder come back with its title in one Undo, and again with Redo.
    @Test fun anAppliedBillSuggestionIsOneStep() {
        val undo = EditorUndo(); val title = Field("Power")
        var date = "2026-10-01"; var amount = ""; var category = "Home"
        val reminders = mutableListOf<String>()
        undo.report("title", "Power", { title.text = it }, 0)
        val kept = listOf(UndoKept({ date }) { date = it }, UndoKept({ amount }) { amount = it }, UndoKept({ category }) { category = it })
        val wasAdded = reminders.toList()
        title.text = "Power bill"; date = "2026-10-20"; amount = "120.00"; category = "Bills"; reminders += "3 days"
        kept.forEach { it.applied() }
        val nowAdded = reminders.toList()
        undo.around(undo = { kept.forEach { it.back() }; undoShift(reminders, nowAdded, wasAdded) },
            redo = { kept.forEach { it.again() }; undoShift(reminders, wasAdded, nowAdded) }, now = 1_000)
        undo.report("title", "Power bill", { title.text = it }, 1_010)
        undo.undo()
        assertEquals("Power", title.text); assertEquals("2026-10-01", date); assertEquals("", amount)
        assertEquals("Home", category); assertTrue(reminders.isEmpty()); assertFalse(undo.canUndo)
        undo.redo()
        assertEquals("Power bill", title.text); assertEquals("2026-10-20", date); assertEquals("120.00", amount)
        assertEquals("Bills", category); assertEquals(listOf("3 days"), reminders)
    }
}
