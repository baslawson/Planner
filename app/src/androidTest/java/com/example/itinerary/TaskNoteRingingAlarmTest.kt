package com.example.itinerary

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual AlarmManager deliveries and actual notification PendingIntents, never simulated ringing state. */
class TaskNoteRingingAlarmTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun alarm() = manager.activeNotifications.firstOrNull { it.id == 1_000_000_001 }?.notification
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 25_000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(100) }
        fail(message)
    }
    private data class Owned(val kind: String, val id: String, val at: Long)
    private fun start(kind: String): Owned {
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val at = System.currentTimeMillis() + 3_000
        val id = "qa-ring-$kind-${System.nanoTime()}"
        runBlocking {
            if (kind == "task") app.repository.saveTask(PlannerTask(id = id, title = "QA ringing task", reminderAt = at, ringUntilDismissed = true))
            else app.repository.saveNote(PlannerNote(id = id, title = "PRIVATE NOTE WORDS", content = "PRIVATE BODY", reminderAt = at, ringUntilDismissed = true), true)
        }
        await("The $kind alarm did not start") { alarm() != null }
        assertTrue(alarm()!!.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertEquals(listOf("Done", "Snooze", "Stop"), alarm()!!.actions.map { it.title.toString() })
        if (kind == "note") {
            assertEquals("Note reminder", alarm()!!.extras.getString(Notification.EXTRA_TITLE))
            assertFalse(alarm()!!.extras.toString().contains("PRIVATE"))
        }
        return Owned(kind, id, at)
    }
    private fun action(label: String) { alarm()!!.actions.single { it.title.toString() == label }.actionIntent.send() }
    @After fun stop() { context.stopService(Intent(context, AlarmService::class.java)) }
    private fun stopKeepsReminder(kind: String) {
        val owner = start(kind); action("Stop")
        await("Stop did not silence $kind") { alarm() == null }
        runBlocking {
            if (kind == "task") {
                val task = app.repository.snapshot().tasks.single { it.id == owner.id }
                assertFalse(task.done); assertEquals(owner.at, task.reminderAt); assertTrue(task.ringUntilDismissed)
            } else { val note = app.repository.note(owner.id)!!; assertEquals(owner.at, note.reminderAt); assertTrue(note.ringUntilDismissed) }
        }
    }
    @Test fun taskStopKeepsItsReminder() = stopKeepsReminder("task")
    @Test fun noteStopKeepsItsReminder() = stopKeepsReminder("note")
    private fun done(kind: String) {
        val owner = start(kind); action("Done")
        await("Done did not silence $kind") { alarm() == null }
        await("Done did not persist for $kind") { runBlocking {
            if (kind == "task") app.repository.snapshot().tasks.single { it.id == owner.id }.done
            else app.repository.note(owner.id)!!.reminderAt == null
        } }
    }
    @Test fun taskDoneCompletesAndSilences() = done("task")
    @Test fun noteDoneClearsAndSilences() = done("note")
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit); return result
    }
    private fun snooze(kind: String) {
        val owner = start(kind); action("Snooze")
        var choice: AccessibilityNodeInfo? = null
        await("Snooze chooser did not open") { choice = nodes().firstOrNull { it.text?.toString() == "10 minutes" && it.isVisibleToUser }; choice != null }
        while (choice != null && !choice!!.isClickable) choice = choice!!.parent
        assertTrue(choice!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("Snooze did not silence $kind") { alarm() == null }
        runBlocking {
            val base: Long?; val later: Long?; val ring: Boolean
            if (kind == "task") { val t = app.repository.snapshot().tasks.single { it.id == owner.id }; base = t.reminderAt; later = t.snoozedUntil; ring = t.ringUntilDismissed }
            else { val n = app.repository.note(owner.id)!!; base = n.reminderAt; later = n.snoozedUntil; ring = n.ringUntilDismissed }
            assertEquals(owner.at, base); assertTrue(ring)
            assertTrue("Snooze time was not ten minutes ahead", later != null && later - System.currentTimeMillis() in 570_000..610_000)
        }
    }
    @Test fun taskSnoozeKeepsRingingModeAndBaseTime() = snooze("task")
    @Test fun noteSnoozeKeepsRingingModeAndBaseTime() = snooze("note")
    private fun change(kind: String, operation: String) {
        val owner = start(kind)
        runBlocking {
            if (kind == "task") {
                val task = app.repository.snapshot().tasks.single { it.id == owner.id }
                when (operation) {
                    "remove" -> app.repository.saveTask(task.copy(reminderAt = null), false)
                    "off" -> app.repository.saveTask(task.copy(ringUntilDismissed = false), false)
                    else -> app.repository.deleteTask(owner.id)
                }
            } else {
                val note = app.repository.note(owner.id)!!
                when (operation) {
                    "remove" -> app.repository.saveNote(note.copy(reminderAt = null), false)
                    "off" -> app.repository.saveNote(note.copy(ringUntilDismissed = false), false)
                    else -> app.repository.deleteNote(owner.id)
                }
            }
        }
        await("$operation did not silence $kind") { alarm() == null }
        // D14-1: turned off, it stops ringing but stays a reminder; removed or deleted, it is gone.
        if (operation == "off") await("Turning ringing off lost the $kind reminder") { manager.activeNotifications.any { it.tag == "$kind:${owner.id}" } }
        else { Thread.sleep(500); assertFalse(manager.activeNotifications.any { it.tag == "$kind:${owner.id}" }) }
    }
    @Test fun removingTaskReminderSilences() = change("task", "remove")
    @Test fun removingNoteReminderSilences() = change("note", "remove")
    @Test fun disablingTaskRingSilences() = change("task", "off")
    @Test fun disablingNoteRingSilences() = change("note", "off")
    @Test fun deletingTaskSilences() = change("task", "delete")
    @Test fun deletingNoteSilences() = change("note", "delete")
    private fun posted(tag: String) = manager.activeNotifications.firstOrNull { it.tag == tag }
    private fun delivered(kind: String, ring: Boolean = false, ahead: Long = 3_000): String {
        ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val id = "qa-plain-$kind-${System.nanoTime()}"
        val at = System.currentTimeMillis() + ahead
        runBlocking {
            if (kind == "task") app.repository.saveTask(PlannerTask(id = id, title = "QA plain task", reminderAt = at, ringUntilDismissed = ring))
            else app.repository.saveNote(PlannerNote(id = id, title = "QA plain note", content = "plain body", reminderAt = at, ringUntilDismissed = ring), true)
        }
        return id
    }
    private fun setRing(kind: String, id: String, ring: Boolean) = runBlocking {
        if (kind == "task") app.repository.saveTask(app.repository.snapshot().tasks.single { it.id == id }.copy(ringUntilDismissed = ring), false)
        else app.repository.saveNote(app.repository.note(id)!!.copy(ringUntilDismissed = ring), false)
    }
    // D14-1: choosing to ring next time must not dismiss the reminder already showing.
    private fun ringOnlyEditKeepsDelivered(kind: String) {
        val id = delivered(kind)
        await("$kind reminder was not delivered") { posted("$kind:$id") != null }
        setRing(kind, id, true)
        Thread.sleep(1_500)
        assertNotNull("Ring-only save dismissed the $kind reminder", posted("$kind:$id"))
        assertNull("A delivered reminder must not ring afterwards", alarm())
    }
    @Test fun taskRingOnlyEditKeepsDeliveredReminder() = ringOnlyEditKeepsDelivered("task")
    @Test fun noteRingOnlyEditKeepsDeliveredReminder() = ringOnlyEditKeepsDelivered("note")
    // ...and a reminder still ahead is set again with the new choice, without being cancelled first.
    private fun ringTurnedOnForComingReminder(kind: String) {
        val id = delivered(kind, ahead = 5_000)
        setRing(kind, id, true)
        await("$kind reminder turned to ring didn't ring") { alarm() != null }
        action("Stop"); await("Stop failed") { alarm() == null }
    }
    @Test fun taskRingTurnedOnRingsWhenDue() = ringTurnedOnForComingReminder("task")
    @Test fun noteRingTurnedOnRingsWhenDue() = ringTurnedOnForComingReminder("note")
    // A14-3: a ringing note that gives way shows its own words (on an unlocked phone), as its ordinary reminder does.
    @Test fun displacedRingingNoteShowsItsWords() {
        val note = start("note")
        start("task")
        await("The displaced note didn't show its title") {
            posted("note:${note.id}")?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "PRIVATE NOTE WORDS"
        }
        assertEquals("PRIVATE BODY", posted("note:${note.id}")!!.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        action("Stop"); await("Stop failed") { alarm() == null }
    }
    @Test fun anotherOwnerCannotStopTheRingingNoteAndNewAlarmDisplacesItSafely() {
        val note = start("note")
        app.reminderScheduler.cancelTask(note.id)
        Thread.sleep(300); assertNotNull(alarm())
        val task = start("task")
        await("The new task alarm didn't replace the note") { alarm()?.extras?.getString(Notification.EXTRA_TITLE) == "QA ringing task" }
        await("The displaced note lost its notification") { manager.activeNotifications.any { it.tag == "note:${note.id}" } }
        app.reminderScheduler.cancelNote(note.id)
        Thread.sleep(300); assertNotNull(alarm())
        action("Stop"); await("The replacement did not stop") { alarm() == null }
    }
    @HarnessStage @Test fun seedBackgroundTaskAlarm() = runBlocking {
        val task = PlannerTask(id = "qa-background-ring-task", title = "QA background ring",
            reminderAt = System.currentTimeMillis() + 15_000, ringUntilDismissed = true)
        app.repository.saveTask(task, create = app.repository.snapshot().tasks.none { it.id == task.id })
    }
    // Inspect and tap Stop through the system UI after this step. Starting another instrumentation run
    // restarts the target process, so it cannot observe the original background player reliably.
}
