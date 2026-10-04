package com.example.itinerary.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// U3: a second Planner window doesn't recover the draft of an event editor still open in the first; sharing text to an
// event then says that an event is open rather than offering a draft to resume.
class DraftRecoveryRulesTest {
    @Test fun noRecoveryWhileAnEditorIsOpenInThisProcess() {
        var reads = 0
        assertNull(draftToRecover(1) { reads++; "draft" })
        assertEquals(0, reads) // not even read: the open editor is still writing it
        assertEquals("draft", draftToRecover(0) { reads++; "draft" })
        assertNull(draftToRecover<String>(0) { null })
    }

    // Q-2: after process death, a share restored as a new event reopens its own editor on the draft; AppNav's recovery
    // editor leaves that draft alone, and still recovers any other.
    @Test fun noRecoveryOfADraftAnotherEditorIsReopening() {
        val shared = "share's event"
        assertNull(draftToRecover(0, ownedElsewhere = { it == shared }) { shared })
        assertEquals("other draft", draftToRecover(0, ownedElsewhere = { it == shared }) { "other draft" })
        assertNull(draftToRecover(1, ownedElsewhere = { false }) { "draft" })
    }

    @Test fun sharingNamesAnOpenEventEditor() {
        assertEquals("An event is open in Planner. Close this share, then save or close that event before sharing again.",
            sharedDraftBlock("event", draftExists = true, eventEditorOpen = true))
        assertEquals("An event is open in Planner. Close this share, then save or close that event before sharing again.",
            sharedDraftBlock("event", draftExists = false, eventEditorOpen = true))
        assertEquals("You have an unfinished event. Close this share, then resume or discard that draft before sharing again.",
            sharedDraftBlock("event", draftExists = true, eventEditorOpen = false))
        assertNull(sharedDraftBlock("event", draftExists = false, eventEditorOpen = false))
        // A task's draft is its own; an open event editor doesn't block it.
        assertNull(sharedDraftBlock("task", draftExists = false, eventEditorOpen = true))
        assertEquals("You have an unfinished task. Close this share, then resume or discard that draft before sharing again.",
            sharedDraftBlock("task", draftExists = true, eventEditorOpen = true))
    }

    // A share made into a note opens on the Notes page: an open note, or an event or task editor the page would drop, comes first.
    @Test fun sharingANoteNamesWhatIsOpen() {
        assertEquals("A note is open in Planner. Close this share, then save or close that note before sharing again.",
            sharedDraftBlock("note", draftExists = true, eventEditorOpen = false, noteEditorOpen = true, otherEditorOpen = true))
        assertEquals("An event or task is open in Planner. Close this share, then save or close it before sharing again.",
            sharedDraftBlock("note", draftExists = false, eventEditorOpen = true, noteEditorOpen = false, otherEditorOpen = true))
        assertEquals("You have an unfinished note. Close this share, then resume or discard that draft before sharing again.",
            sharedDraftBlock("note", draftExists = true, eventEditorOpen = false))
        assertNull(sharedDraftBlock("note", draftExists = false, eventEditorOpen = false))
        // An open note doesn't block an event or task.
        assertNull(sharedDraftBlock("event", draftExists = false, eventEditorOpen = false, noteEditorOpen = true))
    }
}
