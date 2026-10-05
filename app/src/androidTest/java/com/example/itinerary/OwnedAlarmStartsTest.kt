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
    @Test fun cancellingAnotherKindCannotInvalidateNoteWithSameId() {
        val token = OwnedAlarmStarts.reserve(context, "note", id)
        OwnedAlarmStarts.cancel(context, "task", id)
        assertTrue(OwnedAlarmStarts.start(context, extras(token)) {})
    }
}
