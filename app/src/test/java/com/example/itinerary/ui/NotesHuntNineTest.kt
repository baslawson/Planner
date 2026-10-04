package com.example.itinerary.ui

import com.example.itinerary.data.NoteDraftStore
import com.example.itinerary.data.NoteWindows
import com.example.itinerary.data.PlannerNote
import com.example.itinerary.data.recoverableNoteDraft
import com.example.itinerary.data.offeredNoteDraft
import org.junit.Assert.*
import org.junit.Test

// Bug hunt #9 (5 Oct 2026): which window a note draft belongs to as windows are destroyed and rebuilt (NO-1, CC-1, CC-2),
// and Undo kept after a Save that only cleaned the text (NO-2).
class NotesHuntNineTest {
    private fun draft(id: String, owner: String?) =
        NoteDraftStore.Draft(PlannerNote(id = id, title = id), creating = true, base = null, pendingPhoto = null, owner = owner)

    // NO-1: a window Android destroys to rebuild later still owns its draft; only one that finished leaves it.
    @Test fun aWindowDestroyedToBeRebuiltKeepsItsDraft() {
        val w = NoteWindows()
        assertFalse(w.started("A", fromSavedState = false)); w.opened("A")
        assertFalse(w.started("B", fromSavedState = false)); w.opened("B")
        assertTrue("on screen", w.another("B", "A", pageRestored = false))
        w.closed("B", gone = false)
        assertTrue("destroyed, kept in Recents", w.live("B"))
        assertTrue("NO-1: still B's", w.another("B", "A", pageRestored = false))
        // Rebuilt: the same id comes back from saved state, in the same process.
        assertFalse("CC-1: rebuilt in this process is not restored", w.started("B", fromSavedState = true)); w.opened("B")
        w.closed("B", gone = true)
        assertFalse("finished (swiped away, Back)", w.live("B"))
        assertFalse("NT-2: a finished window's draft is A's", w.another("B", "A", pageRestored = false))
        assertFalse("own", w.another("A", "A", pageRestored = false))
    }

    // CC-1: whether a window came back after Android closed Planner is fixed when the process first meets it.
    @Test fun aWindowIsRestoredOnlyWhenThisProcessFirstMeetsItFromSavedState() {
        val w = NoteWindows()
        assertFalse(w.started("A", fromSavedState = false)); w.opened("A")
        w.closed("A", gone = false)
        assertFalse("rebuilt by a configuration change", w.started("A", fromSavedState = true))
        // A draft left by a window of an earlier process (Planner killed while typing) is this fresh window's own.
        assertFalse(w.another("old", "A", pageRestored = w.started("A", fromSavedState = true)))
        val after = NoteWindows()
        assertTrue("came back after Android closed Planner", after.started("C", fromSavedState = true)); after.opened("C")
        assertTrue("and stays so when rebuilt again", after.started("C", fromSavedState = true))
        assertTrue("NW-5: a window that may still be restored keeps its draft", after.another("old", "C", pageRestored = true))
        assertFalse("a window not opened is not live", after.live("old"))
        assertFalse(after.live(null))
    }

    // CC-2: the registry and the page's choice together, as NotesScreen and the share's check use them.
    @Test fun aPageReopensOrOffersAsTheWindowsComeAndGo() {
        val w = NoteWindows()
        w.started("A", false); w.opened("A"); w.started("B", false); w.opened("B")
        val drafts = listOf(draft("b-note", "B"))
        val read = { id: String -> drafts.firstOrNull { it.note.id == id } }
        val another = { owner: String? -> w.another(owner, "A", pageRestored = false) }
        assertNull(recoverableNoteDraft(null, "A", { false }, read, { drafts }, another))
        assertEquals("b-note", offeredNoteDraft("A", { false }, drafts, another)?.note?.id)
        w.closed("B", gone = false)
        assertNull("NO-1: B may come back", recoverableNoteDraft(null, "A", { false }, read, { drafts }, another))
        w.closed("B", gone = true)
        assertEquals("b-note", recoverableNoteDraft(null, "A", { false }, read, { drafts }, another)?.note?.id)
        assertNull(offeredNoteDraft("A", { false }, drafts, another))
    }

    // NO-2: the fields as typed against the note as stored (cleaned by Notes.clean): only a change of words counts.
    @Test fun cleaningIsNotAChangeOfWords() {
        val stored = PlannerNote(id = "n", title = "Shopping", content = "milk\nbread", notebook = "Home")
        assertFalse(noteWordsDiffer(stored.copy(pinned = true), "Shopping ", "milk\nbread\n", "Home"))
        assertFalse(noteWordsDiffer(stored, " Shopping", "milk\r\nbread  ", "/Home/"))
        assertTrue(noteWordsDiffer(stored, "Shopping", "milk\nbread\neggs", "Home"))
        assertTrue(noteWordsDiffer(stored, "Shopping", "milk\nbread", "Work"))
        assertTrue("capitals are a change unless spelt as the existing notebook", noteWordsDiffer(stored, "Shopping", "milk\nbread", "home"))
    }
}
