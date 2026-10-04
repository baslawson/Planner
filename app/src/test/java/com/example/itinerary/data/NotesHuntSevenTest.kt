package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test

// Bug hunt #7 (5 Oct 2026): note drafts and Planner windows (NW-5).
class NotesHuntSevenTest {
    private fun draft(id: String, owner: String?) =
        NoteDraftStore.Draft(PlannerNote(id = id, title = id), creating = true, base = null, pendingPhoto = null, owner = owner)

    // NW-5: a Notes page with no note open reopens its own window's draft (or one from before drafts named a window); another
    // window's is only offered, so that window finds it when it comes back.
    @Test fun aPageReopensItsOwnDraftAndOffersAnotherWindows() {
        val drafts = listOf(draft("theirs", "A"), draft("mine", "B"), draft("old", null))
        val all = { drafts }
        val read = { id: String -> drafts.firstOrNull { it.note.id == id } }
        assertEquals("mine", recoverableNoteDraft(null, "B", { false }, read, all)?.note?.id)
        assertEquals("old", recoverableNoteDraft(null, "B", { it == "mine" }, read, all)?.note?.id)
        assertEquals("theirs", recoverableNoteDraft(null, "A", { false }, read, all)?.note?.id)
        assertNull("only theirs not open", recoverableNoteDraft(null, "B", { it != "theirs" }, read, all))
        assertEquals("theirs", offeredNoteDraft("B", { false }, drafts)?.note?.id)
        assertNull("open in its own window", offeredNoteDraft("B", { it == "theirs" }, drafts))
        assertEquals("mine", offeredNoteDraft("A", { false }, drafts)?.note?.id)
        // The share's check (no page) and a page rebuilt with a note open see any.
        assertEquals("theirs", recoverableNoteDraft(null, null, { false }, read, all)?.note?.id)
        assertEquals("theirs", recoverableNoteDraft("theirs", "B", { false }, read, all)?.note?.id)
    }
}
