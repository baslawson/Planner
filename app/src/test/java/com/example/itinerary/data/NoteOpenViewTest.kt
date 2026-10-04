package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test

// Wish list #5: Settings → Notes "Notes open in", and each note remembering Edit or Preview.
class NoteOpenViewTest {
    @Test fun newRecoveredAndEmptyNotesOpenReadyToType() {
        NoteOpenView.entries.forEach { view ->
            assertFalse(view.opensInPreview(creating = true, recovered = false, hasText = true, leftInPreview = true))
            assertFalse(view.opensInPreview(creating = false, recovered = true, hasText = true, leftInPreview = true))
            assertFalse(view.opensInPreview(creating = false, recovered = false, hasText = false, leftInPreview = true))
        }
    }

    @Test fun theWayItWasLeftByDefault() {
        assertTrue(NoteOpenView.LAST.opensInPreview(false, false, true, leftInPreview = true))
        assertFalse(NoteOpenView.LAST.opensInPreview(false, false, true, leftInPreview = false))
        // Never left either way: a note with words opens in Preview, as before the setting.
        assertTrue(NoteOpenView.LAST.opensInPreview(false, false, true, leftInPreview = null))
    }

    @Test fun aFixedViewWinsOverHowItWasLeft() {
        assertFalse(NoteOpenView.EDIT.opensInPreview(false, false, true, leftInPreview = true))
        assertTrue(NoteOpenView.PREVIEW.opensInPreview(false, false, true, leftInPreview = false))
    }
}
