package com.example.itinerary.reminders

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// D6-3 (R-2): a note reminder posted while the phone is locked, or its screen is off (the lock screen is what will show
// it), leaves the note's words out of the notification itself, whatever the lock-screen setting.
class NoteWordsTest {
    @Test fun wordsOnlyWhileTheScreenIsOnAndUnlocked() {
        assertFalse(NoteWords.hidden(keyguardLocked = false, interactive = true))
        assertTrue(NoteWords.hidden(keyguardLocked = true, interactive = true))
        assertTrue(NoteWords.hidden(keyguardLocked = false, interactive = false))
        assertTrue(NoteWords.hidden(keyguardLocked = true, interactive = false))
    }
}
