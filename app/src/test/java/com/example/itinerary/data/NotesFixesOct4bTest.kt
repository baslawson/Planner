package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test

/** Bug hunt 4 Oct (b), notes: drafts per note (N6-1) and where Duplicate puts copies (N6-3, N6-4). */
class NotesFixesOct4bTest {
    private fun draft(id: String) = NoteDraftStore.Draft(PlannerNote(id = id, title = id), creating = true, base = null, pendingPhoto = null)

    // N6-1: a fresh Notes page reopens the newest draft, but never one whose note an editor (here or in another window)
    // still has open; rebuilt with a note open, it takes only that note's draft.
    @Test fun aDraftWhoseNoteIsOpenIsNotRecovered() {
        val drafts = listOf(draft("shared"), draft("older"))
        val all = { drafts }
        val read = { id: String -> drafts.firstOrNull { it.note.id == id } }
        assertEquals("shared", recoverableNoteDraft(null, null, { false }, read, all)?.note?.id)
        assertEquals("older", recoverableNoteDraft(null, null, { it == "shared" }, read, all)?.note?.id)
        assertNull(recoverableNoteDraft(null, null, { true }, read, all))
        // The page's own editor after Android closed Planner: its draft, not the newest.
        assertEquals("older", recoverableNoteDraft("older", null, { false }, read, all)?.note?.id)
        assertNull("its note is open in another window by now", recoverableNoteDraft("older", null, { it == "older" }, read, all))
        assertNull(recoverableNoteDraft("none", null, { false }, read, all))
    }

    // N6-3: copies of pinned notes each go to the top, so they are made last first and show in the order chosen.
    @Test fun pinnedCopiesAreMadeLastFirst() {
        val pinned = setOf("p1", "p2")
        assertEquals(listOf("a", "b", "p2", "p1"), Notes.duplicateOrder(listOf("p1", "a", "p2", "b"), pinned::contains))
        assertEquals(listOf("a"), Notes.duplicateOrder(listOf("a"), pinned::contains))
    }

    // N6-3 on the page: P1, P2, A with P1 and P2 duplicated gives P1, P2, P1', P2', A (as Repository.duplicateNotes).
    @Test fun pinnedCopiesShowInOrder() {
        var notes = listOf(PlannerNote(id = "p1", pinned = true, position = -2), PlannerNote(id = "p2", pinned = true, position = -1),
            PlannerNote(id = "a", position = 1))
        Notes.duplicateOrder(listOf("p1", "p2")) { id -> notes.first { it.id == id }.pinned }.forEach { id ->
            val original = notes.first { it.id == id }
            notes = notes + PlannerNote(id = "$id'", modified = 0, position = Notes.copyPosition(original, notes))
        }
        assertEquals(listOf("p1", "p2", "p1'", "p2'", "a"), notes.sortedWith(Notes.order).map { it.id })
    }

    // N6-4: notes sharing the original's place that show after it are the ones moved after its copy, in their order.
    @Test fun tiedNotesAfterTheOriginal() {
        val a = PlannerNote(id = "a", position = -3, modified = 2_000)
        val c = PlannerNote(id = "c", position = -3, modified = 1_000)
        val e = PlannerNote(id = "e", position = -3, modified = 500)
        val newer = PlannerNote(id = "n", position = -3, modified = 3_000)
        val d = PlannerNote(id = "d", position = -2)
        assertEquals(listOf("c", "e"), Notes.tiedAfter(a, listOf(e, d, newer, a, c)).map { it.id })
        assertTrue(Notes.tiedAfter(d, listOf(a, c, d)).isEmpty())
    }
}
