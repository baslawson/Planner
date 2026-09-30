package com.example.itinerary.ui

import com.example.itinerary.data.EditorDraftStore
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
}
