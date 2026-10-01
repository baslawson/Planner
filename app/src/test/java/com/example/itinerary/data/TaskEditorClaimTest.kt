package com.example.itinerary.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// U4: one task editor per task. The first claims it; a second (the widget's, another window's) doesn't get it, and
// only the owner's leaving frees it.
class TaskEditorClaimTest {
    @Test fun theFirstEditorOwnsATaskUntilItLeaves() {
        val agenda = Any(); val widget = Any(); val other = Any()
        assertTrue(TaskDraftStore.claim("u4-a", agenda))
        assertTrue(TaskDraftStore.claim("u4-a", agenda)) // asking again changes nothing
        assertFalse(TaskDraftStore.claim("u4-a", widget))
        assertTrue(TaskDraftStore.claim("u4-b", other)) // another task is free
        TaskDraftStore.release("u4-a", widget) // not the owner: no effect
        assertFalse(TaskDraftStore.claim("u4-a", widget))
        TaskDraftStore.release("u4-a", agenda)
        assertTrue(TaskDraftStore.claim("u4-a", widget))
        TaskDraftStore.release("u4-a", widget); TaskDraftStore.release("u4-b", other)
    }
}
