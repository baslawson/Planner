package com.example.itinerary

import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.PlannerTask
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** By hand, for a real locked reboot (RB-3): set a task reminder [MINUTES] ahead and wait until it is in the
 *  before-unlock snapshot. Then (from outside) set a PIN, reboot without unlocking, see it ring locked, unlock. */
class LockedRebootStage {
    @HarnessStage @Test fun setATaskReminderAFewMinutesAhead() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ItineraryApp
        val at = (System.currentTimeMillis() / 60_000 + MINUTES) * 60_000
        val task = PlannerTask(id = "qa-locked-reboot", title = "QA locked reboot", reminderAt = at)
        app.repository.saveTask(task)
        val snapshot = File(File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "locked-alarms"), "snapshot")
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end && !(snapshot.isFile && snapshot.readText().contains("qa-locked-reboot"))) Thread.sleep(200)
        android.util.Log.i("QA-LOCKED", "reminder at $at in snapshot=${snapshot.isFile && snapshot.readText().contains("qa-locked-reboot")}")
        assertTrue("the reminder is in the before-unlock snapshot", snapshot.isFile && snapshot.readText().contains("qa-locked-reboot"))
    }

    // The same with an event whose reminder rings until stopped (AlarmService), to hear it ring before the unlock (D6-1).
    @HarnessStage @Test fun setARingingEventReminderAFewMinutesAhead() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ItineraryApp
        val start = java.time.LocalDateTime.now().plusMinutes(MINUTES.toLong() + 1).withSecond(0).withNano(0)
        val id = app.repository.saveItemId(com.example.itinerary.data.ItineraryItem(tripId = 0, date = start.toLocalDate(),
            startTime = start.toLocalTime(), title = "QA locked ring"),
            addedReminders = listOf(com.example.itinerary.data.Reminder(itemId = 0, amount = 0,
                unit = com.example.itinerary.data.ReminderUnit.MINUTES, ringUntilDismissed = true)))
        val snapshot = File(File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "locked-alarms"), "snapshot")
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end && !(snapshot.isFile && snapshot.readText().contains("QA locked ring"))) Thread.sleep(200)
        android.util.Log.i("QA-LOCKED", "event $id at $start in snapshot=${snapshot.isFile && snapshot.readText().contains("QA locked ring")}")
        assertTrue("the ringing reminder is in the before-unlock snapshot", snapshot.isFile && snapshot.readText().contains("QA locked ring"))
    }

    companion object { const val MINUTES = 4 }
}
