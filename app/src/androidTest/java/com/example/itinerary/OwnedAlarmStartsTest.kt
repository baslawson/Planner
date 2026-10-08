package com.example.itinerary

import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.reminders.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

/** Device-protected durable ownership, independent of the service's in-memory current owner. */
class OwnedAlarmStartsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = "qa-start-${System.nanoTime()}"
    private fun extras(token: String) = ownedAlarmExtras("note", id, "PRIVATE NOTE WORDS", 1000).apply { putString(EXTRA_OWNER_START, token) }
    @After fun clear() { OwnedAlarmStarts.cancel(context, "note", id); OwnedAlarmStarts.cancel(context, "task", id) }
    @Test fun reservationIsWrittenToDeviceProtectedNoBackupFileWithoutNoteWords() {
        val token = OwnedAlarmStarts.reserve(context, "note", id)
        val path = File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "ring-starts")
        val disk = path.readText()
        assertEquals(token, JSONObject(disk).getString("note:$id"))
        assertFalse(disk.contains("PRIVATE NOTE WORDS"))
        assertTrue(OwnedAlarmStarts.start(context, extras(token)) {})
    }
    @Test fun cancelledAndUnknownStartsCannotExecute() {
        val token = OwnedAlarmStarts.reserve(context, "note", id)
        OwnedAlarmStarts.cancel(context, "note", id)
        assertFalse(OwnedAlarmStarts.start(context, extras(token)) { fail("Cancelled start ran") })
        assertFalse(OwnedAlarmStarts.start(context, extras("unknown")) { fail("Unknown start ran") })
    }
    @Test fun finishingOldSameOwnerLeavesReplacementValid() {
        val old = OwnedAlarmStarts.reserve(context, "note", id)
        val current = OwnedAlarmStarts.reserve(context, "note", id)
        OwnedAlarmStarts.finish(context, extras(old))
        assertFalse(OwnedAlarmStarts.start(context, extras(old)) { fail("Old start ran") })
        assertTrue(OwnedAlarmStarts.start(context, extras(current)) {})
    }
    // A14-2: an unreadable store is refused once, then written afresh; cancelling through it doesn't throw.
    @Test fun unreadableStoreHealsOnNextReservation() {
        val old = OwnedAlarmStarts.reserve(context, "note", id)
        File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "ring-starts").writeText("{not json")
        assertFalse(OwnedAlarmStarts.start(context, extras(old)) { fail("Start from an unreadable store ran") })
        OwnedAlarmStarts.cancel(context, "task", id)
        val fresh = OwnedAlarmStarts.reserve(context, "note", id)
        assertTrue(OwnedAlarmStarts.start(context, extras(fresh)) {})
    }
    // D14-1: turned quiet, the start is refused but reported once as quiet; it still stands for displacement (A14-5).
    @Test fun quietStartIsRefusedButKeepsItsReminder() {
        val token = OwnedAlarmStarts.reserve(context, "note", id)
        OwnedAlarmStarts.quiet(context, "note", id)
        assertTrue(OwnedAlarmStarts.isCurrent(context, extras(token)))
        assertFalse(OwnedAlarmStarts.start(context, extras(token)) { fail("Quiet start rang") })
        assertTrue(OwnedAlarmStarts.takeQuiet(context, extras(token)))
        assertFalse(OwnedAlarmStarts.takeQuiet(context, extras(token)))
        assertFalse(OwnedAlarmStarts.isCurrent(context, extras(token)))
    }
    @Test fun cancelledStartIsNotCurrentAndNotQuiet() {
        val token = OwnedAlarmStarts.reserve(context, "note", id)
        OwnedAlarmStarts.cancel(context, "note", id)
        assertFalse(OwnedAlarmStarts.isCurrent(context, extras(token)))
        assertFalse(OwnedAlarmStarts.takeQuiet(context, extras(token)))
        assertTrue(OwnedAlarmStarts.isCurrent(context, android.os.Bundle()))
    }
    @Test fun cancellingAnotherKindCannotInvalidateNoteWithSameId() {
        val token = OwnedAlarmStarts.reserve(context, "note", id)
        OwnedAlarmStarts.cancel(context, "task", id)
        assertTrue(OwnedAlarmStarts.start(context, extras(token)) {})
    }
    // Hunt 24 D1: an event's ringing start is reserved too; cancelling the reminder (ReminderScheduler.cancel, through
    // stopIfRinging) refuses one delivered again, while an event alarm without a reservation (the Settings test) still rings.
    @Test fun eventStartIsRefusedOnceItsReminderIsCancelled() {
        val reminderId = 9_000_000_000L + System.nanoTime() % 1_000_000
        fun event(token: String?) = android.os.Bundle().apply {
            putLong(ReminderScheduler.EXTRA_REMINDER_ID, reminderId); token?.let { putString(EXTRA_EVENT_START, it) } }
        try {
            val token = OwnedAlarmStarts.reserve(context, OwnedAlarmStarts.EVENT, reminderId.toString())
            assertTrue(OwnedAlarmStarts.isCurrent(context, event(token)))
            assertTrue(OwnedAlarmStarts.start(context, event(token)) {})
            AlarmService.stopIfRinging(context, reminderId)
            assertFalse(OwnedAlarmStarts.isCurrent(context, event(token)))
            assertFalse(OwnedAlarmStarts.start(context, event(token)) { fail("Cancelled event start rang") })
            // A snooze's ring reserves afresh: it rings; the earlier one stays refused.
            val snoozed = OwnedAlarmStarts.reserve(context, OwnedAlarmStarts.EVENT, reminderId.toString())
            assertTrue(OwnedAlarmStarts.start(context, event(snoozed)) {})
            assertFalse(OwnedAlarmStarts.start(context, event(token)) { fail("Old event start rang") })
            OwnedAlarmStarts.finish(context, event(snoozed))
            assertFalse(OwnedAlarmStarts.start(context, event(snoozed)) { fail("Finished event start rang") })
            assertTrue(OwnedAlarmStarts.isCurrent(context, event(null)))
            assertTrue(OwnedAlarmStarts.start(context, event(null)) {})
        } finally { OwnedAlarmStarts.cancel(context, OwnedAlarmStarts.EVENT, reminderId.toString()) }
    }
}
