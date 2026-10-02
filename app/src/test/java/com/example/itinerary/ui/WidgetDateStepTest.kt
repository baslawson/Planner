package com.example.itinerary.ui

import com.example.itinerary.data.EditorDraftStore
import com.example.itinerary.data.TaskDraftStore
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

// Fourth bug hunt D10: a widget tap while an event editor is open waits for it to be saved or discarded.
class WidgetDateStepTest {
    private val day = LocalDate.of(2026, 10, 3)

    @Test fun waitsWhileAnEditorIsOpenThenOpensTheDay() {
        assertEquals(WidgetDateStep.NOTHING, widgetDateStep(null, 0))
        assertEquals(WidgetDateStep.NOTHING, widgetDateStep(null, 1))
        assertEquals(WidgetDateStep.WAIT, widgetDateStep(day, 1))
        assertEquals(WidgetDateStep.WAIT, widgetDateStep(day, 2))
        assertEquals(WidgetDateStep.OPEN, widgetDateStep(day, 0))
    }

    @Test fun openEditorsCountsOpenAndClosedEditors() {
        val before = EditorDraftStore.openEditors.value
        EditorDraftStore.editorOpened(); EditorDraftStore.editorOpened()
        assertEquals(WidgetDateStep.WAIT, widgetDateStep(day, EditorDraftStore.openEditors.value))
        EditorDraftStore.editorClosed()
        assertEquals(before + 1, EditorDraftStore.openEditors.value)
        EditorDraftStore.editorClosed()
        assertEquals(before, EditorDraftStore.openEditors.value)
        assertEquals(WidgetDateStep.OPEN, widgetDateStep(day, EditorDraftStore.openEditors.value))
    }

    // U5: an open task editor makes the widget's day wait too, and the message names what is open.
    @Test fun openTaskEditorsWaitToo() {
        val before = TaskDraftStore.openEditors.value
        TaskDraftStore.editorOpened()
        assertEquals(WidgetDateStep.WAIT, widgetDateStep(day, EditorDraftStore.openEditors.value + TaskDraftStore.openEditors.value))
        TaskDraftStore.editorClosed()
        assertEquals(before, TaskDraftStore.openEditors.value)
        assertEquals("Close this event first. Then the widget's day opens.", widgetWaitMessage(1, 0))
        assertEquals("Close this task first. Then the widget's day opens.", widgetWaitMessage(0, 1))
        assertEquals("Close the open event and task first. Then the widget's day opens.", widgetWaitMessage(1, 2))
        assertEquals("Close this note first. Then the widget's day opens.", widgetWaitMessage(0, 0, 1))
        assertEquals("Close the open editors first. Then the widget's day opens.", widgetWaitMessage(1, 0, 1))
    }

    // U-N5: an editor open in another Planner window (a share opened in the browser's task) doesn't hold up this
    // window's widget day; a shortcut, which waits for the one event draft wherever it is, says where that editor is.
    @Test fun eachWindowWaitsOnlyForItsOwnEditors() {
        val main = WindowEditors(); val share = WindowEditors()
        share.events++; share.tasks++
        assertEquals(WidgetDateStep.OPEN, widgetDateStep(day, main.events + main.tasks))
        assertEquals(WidgetDateStep.WAIT, widgetDateStep(day, share.events + share.tasks))
        main.tasks++
        assertEquals(WidgetDateStep.WAIT, widgetDateStep(day, main.events + main.tasks))
        assertEquals("Finish or discard the event open in another Planner window before using a shortcut.", shortcutBlockedMessage(0, 1))
        assertEquals("Finish or discard your current draft before using a shortcut.", shortcutBlockedMessage(1, 2))
        assertEquals("Finish or discard your current draft before using a shortcut.", shortcutBlockedMessage(0, 0))
    }
}
