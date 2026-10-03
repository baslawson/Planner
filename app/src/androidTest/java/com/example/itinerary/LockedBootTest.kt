package com.example.itinerary

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.PlannerTask
import com.example.itinerary.reminders.BootReceiver
import com.example.itinerary.reminders.DirectBoot
import com.example.itinerary.reminders.LockedAlarm
import com.example.itinerary.reminders.LockedAlarmManager
import com.example.itinerary.reminders.LockedAlarmSetter
import com.example.itinerary.reminders.LockedAlarmStore
import com.example.itinerary.reminders.LockedSnapshot
import com.example.itinerary.reminders.NoteReminderReceiver
import com.example.itinerary.reminders.ReminderReceiver
import com.example.itinerary.reminders.ReminderScheduler
import com.example.itinerary.reminders.ReminderScheduler.Companion.EXTRA_TRIGGER
import com.example.itinerary.reminders.TaskReminderReceiver
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * RB-3: after a reboot, before the first unlock (faked through DirectBoot's seams, with the snapshot in a folder of its
 * own, not the app's): BootReceiver sets the snapshot's alarms; the receivers show them from their intents and note
 * them as rung; ReminderScheduler keeps the snapshot; and its own alarm replaces the locked one rather than adding a second.
 */
class LockedBootTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private lateinit var dir: File
    private lateinit var store: LockedAlarmStore
    private var unlocked = false
    private val savedUnlocked = DirectBoot.unlocked
    private val savedStore = DirectBoot.store
    private val savedAlarms = DirectBoot.alarms

    // What BootReceiver asked to be set, in order.
    private class Recorder : LockedAlarmSetter {
        val set = mutableListOf<LockedAlarm>()
        override fun set(alarm: LockedAlarm) { set += alarm }
    }
    private val recorder = Recorder()

    @Before fun fakeLockedPhone() {
        dir = File(context.cacheDir, "qa-locked-boot").apply { deleteRecursively(); mkdirs() }
        store = LockedAlarmStore(dir)
        DirectBoot.unlocked = { unlocked }
        DirectBoot.store = { store }
        DirectBoot.alarms = { recorder }
    }

    @After fun restore() {
        DirectBoot.unlocked = savedUnlocked
        DirectBoot.store = savedStore
        DirectBoot.alarms = savedAlarms
        dir.deleteRecursively()
    }

    private val now = System.currentTimeMillis()
    private val event = LockedAlarm.Event(990_001, now + 3_600_000, "QA locked event", "Hall", "2026-10-05", "09:30",
        "1 hour before", bill = false, ring = false, snoozeToken = "token", billToken = null)
    private val task = LockedAlarm.Task("qa-locked-task", now + 60_000, "QA locked task")
    private val note = LockedAlarm.Note("qa-locked-note", now + 7_200_000)

    @Test fun lockedBootSetsTheSnapshotsAlarmsStillAhead() {
        store.write(LockedSnapshot("HOUR_24", listOf(note, LockedAlarm.Note("qa-past", now - 60_000), event, task)))
        BootReceiver().onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertEquals(listOf(task, event, note), recorder.set)
        // A time change while locked, and BOOT_COMPLETED arriving while locked (it doesn't), set nothing and read no database.
        recorder.set.clear()
        BootReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        assertEquals(emptyList<LockedAlarm>(), recorder.set)
        // Unlocked already: BOOT_COMPLETED does it all from the database, so the locked one sets nothing.
        unlocked = true
        BootReceiver().onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertEquals(emptyList<LockedAlarm>(), recorder.set)
    }

    @Test fun noSnapshotSetsNothing() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertEquals(emptyList<LockedAlarm>(), recorder.set)
    }

    @Test fun lockedReceiversShowFromTheirIntentAndNoteTheAlarmAsRung() {
        try {
            TaskReminderReceiver().onReceive(context, TaskReminderReceiver.intent(context, task.id).putExtra("trigger", task.trigger)
                .putExtra(TaskReminderReceiver.EXTRA_LOCKED_TITLE, task.title))
            NoteReminderReceiver().onReceive(context, NoteReminderReceiver.intent(context, note.id).putExtra("trigger", note.trigger))
            ReminderReceiver().onReceive(context, com.example.itinerary.reminders.eventIntent(context, event).putExtra(EXTRA_TRIGGER, event.trigger))
            Thread.sleep(500)
            fun shown(tag: String?, id: Int) = manager.activeNotifications.firstOrNull { it.tag == tag && it.id == id }?.notification
            assertEquals("QA locked task", shown("task:${task.id}", 0)?.extras?.getString(android.app.Notification.EXTRA_TITLE))
            // No word of the note: it has none to show.
            val noteShown = shown("note:${note.id}", 0)
            assertEquals("Note reminder", noteShown?.extras?.getString(android.app.Notification.EXTRA_TITLE))
            assertEquals("Note reminder", noteShown?.publicVersion?.extras?.getString(android.app.Notification.EXTRA_TITLE))
            assertEquals("QA locked event", shown(null, event.reminderId.toInt())?.extras?.getString(android.app.Notification.EXTRA_TITLE))
            assertEquals(listOf("t:${task.id}" to task.trigger, "n:${note.id}" to note.trigger, "e:${event.reminderId}" to event.trigger),
                store.fired().map { it.key to it.trigger })
            // Done needs the database: on Android 12+ it asks for the unlock first, below that it isn't offered while locked.
            val done = shown("task:${task.id}", 0)!!.actions.map { it.title.toString() }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                assertEquals(listOf("Done", "Snooze"), done)
                assertTrue(shown("task:${task.id}", 0)!!.actions.first().isAuthenticationRequired)
            } else assertEquals(listOf("Snooze"), done)
        } finally {
            manager.cancel("task:${task.id}", 0); manager.cancel("note:${note.id}", 0); manager.cancel(event.reminderId.toInt())
        }
    }

    @Test fun schedulerKeepsTheSnapshotAndReplacesTheLockedAlarm() {
        unlocked = true
        val scheduler = ReminderScheduler(context)
        val planned = PlannerTask(id = "qa-locked-scheduled", title = "QA locked scheduled", reminderAt = now + 600_000)
        try {
            // The alarm set before the unlock...
            LockedAlarmManager(context).set(LockedAlarm.Task(planned.id, now + 600_000, planned.title))
            fun pending() = PendingIntent.getBroadcast(context, 0, TaskReminderReceiver.intent(context, planned.id),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            assertNotNull(pending())
            // ...is the one ReminderScheduler sets and cancels: one alarm, not two.
            scheduler.scheduleTask(planned)
            scheduler.saveLockedAlarms()
            assertTrue(store.read()!!.alarms.contains(LockedAlarm.Task(planned.id, now + 600_000, planned.title)))
            scheduler.cancelTask(planned.id)
            assertNull(pending())
            scheduler.saveLockedAlarms()
            assertFalse(store.read()!!.alarms.any { it.key == "t:${planned.id}" })
        } finally { scheduler.cancelTask(planned.id) }
    }

    // D6-4: a reminder ringing (unlocked) rewrites a snapshot half a day old from every alarm, so it never runs out.
    @Test fun aRingRefreshesAnOldSnapshot() = kotlinx.coroutines.runBlocking {
        unlocked = true
        val past = LockedAlarm.Task("qa-locked-past", now - 60_000, "QA rung long ago")
        store.write(LockedSnapshot("HOUR_24", listOf(past)))
        File(dir, "snapshot").setLastModified(now - com.example.itinerary.reminders.LockedAlarmSelection.REFRESH_MS - 60_000)
        DirectBoot.afterRing(context.applicationContext as ItineraryApp)
        val writtenAt = store.writtenAt()!!
        assertTrue("written again: $writtenAt", writtenAt >= now - 5_000)
        assertFalse(store.read()!!.alarms.any { it.key == past.key })
        // Fresh: the next ring only refills, it doesn't write again.
        DirectBoot.afterRing(context.applicationContext as ItineraryApp)
        assertEquals(writtenAt, store.writtenAt())
    }
}
