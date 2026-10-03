package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Test

// DU-2: the open-editor count the event, task and note draft stores share.
class EditorCounterTest {
    @Test fun countsOpenEditorsAndNeverGoesBelowZero() {
        val counter = EditorCounter()
        assertEquals(0, counter.open.value)
        counter.opened(); counter.opened()
        assertEquals(2, counter.open.value)
        counter.closed()
        assertEquals(1, counter.open.value)
        counter.closed(); counter.closed()
        assertEquals(0, counter.open.value)
        counter.opened()
        assertEquals(1, counter.open.value)
    }

    @Test fun eachStoreKeepsItsOwnCount() {
        val events = EditorDraftStore.openEditors.value
        val tasks = TaskDraftStore.openEditors.value
        val notes = NoteDraftStore.openEditors.value
        NoteDraftStore.editorOpened()
        assertEquals(notes + 1, NoteDraftStore.openEditors.value)
        assertEquals(events, EditorDraftStore.openEditors.value)
        assertEquals(tasks, TaskDraftStore.openEditors.value)
        NoteDraftStore.editorClosed()
        assertEquals(notes, NoteDraftStore.openEditors.value)
    }
}
