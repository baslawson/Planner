package com.example.itinerary

import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real service starts held behind a background edit, and real stale notification PendingIntents. */
class RingingOwnershipTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as ItineraryApp
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private fun alarm() = manager.activeNotifications.firstOrNull { it.id == 1_000_000_001 }
    private fun waitFor(message: String, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < end) { if (check()) return; Thread.sleep(100) }
        fail(message)
    }
    private fun foreground() {
        ins.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        ins.waitForIdleSync()
    }
    private var keepForHarness = false
    @After fun stop() { if (!keepForHarness) app.stopService(Intent(app, AlarmService::class.java)) }
    private fun queueThenChange(kind: String, operation: String) {
        foreground()
        val at = System.currentTimeMillis() - 1000
        val id = "qa-queued-$kind"
        runBlocking {
            if (kind == "task") app.repository.saveTask(PlannerTask(id=id, title="QA queued task", reminderAt=at, ringUntilDismissed=true))
            else app.repository.saveNote(PlannerNote(id=id, title="QA queued note", reminderAt=at, ringUntilDismissed=true), true)
        }
        ins.runOnMainSync {
            assertTrue(startOwnedAlarm(app, kind, id, "QA queued task", at, true))
            runBlocking(Dispatchers.IO) {
                if (kind == "task") {
                    val task = app.repository.task(id)!!
                    when (operation) {
                        "off" -> app.repository.saveTask(task.copy(ringUntilDismissed=false), false)
                        "remove" -> app.repository.saveTask(task.copy(reminderAt=null), false)
                        else -> app.repository.deleteTask(id)
                    }
                } else {
                    val note = app.repository.note(id)!!
                    when (operation) {
                        "off" -> app.repository.saveNote(note.copy(ringUntilDismissed=false), false)
                        "remove" -> app.repository.saveNote(note.copy(reminderAt=null), false)
                        else -> app.repository.deleteNote(id)
                    }
                }
            }
            assertNull("Control: service callback still queued", alarm())
        }
        // Observe the whole interval, so a brief orphan alarm cannot count as passing.
        val end = SystemClock.uptimeMillis() + 3000
        while (SystemClock.uptimeMillis() < end) {
            // Android may briefly expose the silent cancelled-start status while stopForeground removes it.
            // It must never expose a ringing alarm, and the native-player check below independently forbids playback.
            assertFalse("Cancelled queued $kind alarm started after $operation",
                alarm()?.notification?.category == android.app.Notification.CATEGORY_ALARM)
            Thread.sleep(50)
        }
        val media = android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("dumpsys media.player"))
            .bufferedReader().use { it.readText() }
        assertFalse("Cancelled alarm still has a native player", media.contains(app.packageName))
        waitFor("Cancelled-start status was not removed") { alarm() == null }
        // A later valid start must still work: rejecting one owner cannot poison the service.
        assertTrue(startOwnedAlarm(app, "task", "qa-control", "QA valid control", at, true))
        waitFor("Later valid control did not ring") { alarm()?.notification?.extras?.getString("android.title") == "QA valid control" }
        alarm()!!.notification.actions.single { it.title.toString() == "Stop" }.actionIntent.send()
        waitFor("Current control Stop failed") { alarm() == null }
    }
    @Test fun queuedTaskOffDoesNotRing() = queueThenChange("task", "off")
    @Test fun queuedNoteOffDoesNotRing() = queueThenChange("note", "off")
    @Test fun queuedTaskRemovalDoesNotRing() = queueThenChange("task", "remove")
    @Test fun queuedNoteRemovalDoesNotRing() = queueThenChange("note", "remove")
    @Test fun queuedTaskDeletionDoesNotRing() = queueThenChange("task", "delete")
    @Test fun queuedNoteDeletionDoesNotRing() = queueThenChange("note", "delete")

    private fun start(kind: String, id: String, title: String) {
        if (kind == "event") assertTrue(AlarmService.startTest(app))
        else assertTrue(startOwnedAlarm(app, kind, id, title, System.currentTimeMillis()-1000, true))
        val expected = if (kind == "event") "Test alarm" else if (kind == "note") "Note reminder" else title
        waitFor("$kind alarm absent") { alarm()?.notification?.extras?.getString("android.title") == expected }
    }
    private fun staleAction(first: String, second: String, delete: Boolean) {
        foreground()
        start(first, "qa-first", "QA first")
        val old = if (delete) alarm()!!.notification.deleteIntent else alarm()!!.notification.actions.single { it.title.toString() == "Stop" }.actionIntent
        start(second, "qa-second", "QA second")
        val replacement = alarm()!!.notification
        val current = if (delete) replacement.deleteIntent else replacement.actions.single { it.title.toString() == "Stop" }.actionIntent
        assertNotEquals("PendingIntent identity must differ per ring", old, current)
        old.send()
        Thread.sleep(600)
        assertNotNull("Stale action stopped replacement", alarm())
        assertEquals(replacement.extras.getString("android.title"), alarm()!!.notification.extras.getString("android.title"))
        current.send()
        waitFor("Current Stop/delete did not stop replacement") { alarm() == null }
    }
    @Test fun staleTaskStopCannotStopNote() = staleAction("task", "note", false)
    @Test fun staleEventStopCannotStopTask() = staleAction("event", "task", false)
    @Test fun staleNoteDeleteCannotStopTask() = staleAction("note", "task", true)
    @Test fun staleTaskDeleteCannotStopEvent() = staleAction("task", "event", true)

    @Test fun cancelledQueuedNoteCannotDisplaceActiveTask() {
        foreground()
        start("task", "qa-active", "QA active task")
        val note = PlannerNote(id="qa-cancelled", title="QA cancelled note", reminderAt=System.currentTimeMillis()-1000, ringUntilDismissed=true)
        runBlocking { app.repository.saveNote(note, true) }
        ins.runOnMainSync {
            assertTrue(startOwnedAlarm(app, "note", note.id, "Note reminder", note.reminderAt!!, true))
            runBlocking(Dispatchers.IO) { app.repository.saveNote(note.copy(ringUntilDismissed=false), false) }
        }
        val end = SystemClock.uptimeMillis() + 3000
        while (SystemClock.uptimeMillis() < end) {
            assertEquals("Cancelled note displaced valid task", "QA active task", alarm()?.notification?.extras?.getString("android.title"))
            Thread.sleep(50)
        }
        alarm()!!.notification.actions.single { it.title.toString() == "Stop" }.actionIntent.send()
        waitFor("Current Stop failed") { alarm() == null }
    }

    @Test fun staleSampleSnoozeCannotStopTask() {
        foreground()
        start("event", "qa-sample", "Test alarm")
        val old = alarm()!!.notification.actions.single { it.title.toString().startsWith("Snooze") }.actionIntent
        start("task", "qa-second", "QA second")
        old.send()
        Thread.sleep(600)
        assertEquals("QA second", alarm()?.notification?.extras?.getString("android.title"))
        alarm()!!.notification.actions.single { it.title.toString() == "Stop" }.actionIntent.send()
        waitFor("Current Stop failed") { alarm() == null }
    }

    @HarnessStage @Test fun seedRestartAlarm() {
        foreground()
        val task = PlannerTask(id="qa-restart-ring", title="QA restart ring", reminderAt=System.currentTimeMillis()+5000, ringUntilDismissed=true)
        runBlocking { app.repository.saveTask(task) }
        keepForHarness = true
    }
}
