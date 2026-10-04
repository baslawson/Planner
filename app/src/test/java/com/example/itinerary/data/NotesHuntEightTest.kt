package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test

// Bug hunt #8 (5 Oct 2026): whose note draft a Notes page reopens once windows come and go (NT-2).
class NotesHuntEightTest {
    private fun draft(id: String, owner: String?) =
        NoteDraftStore.Draft(PlannerNote(id = id, title = id), creating = true, base = null, pendingPhoto = null, owner = owner)

    // NT-2: another window's draft is only offered while that window is on screen, or (after Android closed Planner with
    // both open) while it may still be restored; a window that went, or one from before a fresh start, leaves it to this one.
    @Test fun aDraftIsAnotherWindowsOnlyWhileThatWindowMayComeBack() {
        assertFalse("own", anotherWindowsDraft("B", "B", live = true, seen = true, pageRestored = false))
        assertFalse("from before drafts named a window", anotherWindowsDraft(null, "B", live = false, seen = false, pageRestored = true))
        assertTrue("on screen", anotherWindowsDraft("A", "B", live = true, seen = true, pageRestored = false))
        assertFalse("went while Planner ran (task swiped away)", anotherWindowsDraft("A", "B", live = false, seen = true, pageRestored = true))
        assertFalse("from before a fresh start (swiped away, reboot)", anotherWindowsDraft("A", "B", live = false, seen = false, pageRestored = false))
        assertTrue("NW-5: both windows came back from saved state", anotherWindowsDraft("A", "B", live = false, seen = false, pageRestored = true))
    }

    @Test fun aWindowThatWentLeavesItsDraftToBeReopened() {
        val drafts = listOf(draft("gone", "A"), draft("live", "C"), draft("mine", "B"))
        val all = { drafts }
        val read = { id: String -> drafts.firstOrNull { it.note.id == id } }
        val others = { owner: String? -> owner == "C" } // A went, C is on screen
        assertEquals("gone", recoverableNoteDraft(null, "B", { false }, read, all, others)?.note?.id)
        assertEquals("mine", recoverableNoteDraft(null, "B", { it == "gone" }, read, all, others)?.note?.id)
        assertNull(recoverableNoteDraft(null, "B", { it != "live" }, read, all, others))
        assertEquals("live", offeredNoteDraft("B", { false }, drafts, others)?.note?.id)
        assertNull("a gone window's draft isn't offered: it reopens", offeredNoteDraft("B", { it == "live" }, drafts, others))
    }
}
