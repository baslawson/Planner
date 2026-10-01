package com.example.itinerary.ui

import com.example.itinerary.data.EditorDraftStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// U2: after process death AppNav's recovery editor owns the draft of the bill it reopens; a bill editor restored by
// Agenda or Search for that bill closes again instead of adopting the same draft.
class RecoveryOwnerTest {
    @Test fun onlyTheRecoveredBillIsOwnedAndOnlyWhileRecoveryIsUp() {
        assertFalse(EditorDraftStore.recoveryOwns(5))
        EditorDraftStore.recoveryOpened(5)
        assertTrue(EditorDraftStore.recoveryOwns(5))
        assertFalse(EditorDraftStore.recoveryOwns(6))
        // Another id closing doesn't release it.
        EditorDraftStore.recoveryClosed(6)
        assertTrue(EditorDraftStore.recoveryOwns(5))
        EditorDraftStore.recoveryClosed(5)
        assertFalse(EditorDraftStore.recoveryOwns(5))
    }
}
