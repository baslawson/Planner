package com.example.itinerary

import android.app.Notification
import android.app.NotificationManager
import android.media.AudioManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.PlannerTask
import com.example.itinerary.data.Reminder
import com.example.itinerary.data.ReminderSound
import com.example.itinerary.data.ReminderUnit
import com.example.itinerary.reminders.ALARM_CHANNEL_ID
import com.example.itinerary.reminders.AlarmService
import com.example.itinerary.reminders.REMINDER_CHANNEL_ID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Reminder sound (bugnotes 7 Oct): a reminder left at Default, with Settings' "Reminder sound" at its new-install 10 s,
 * rings through AlarmService (alarm sound, through silent mode) in silent mode with Planner in the background (as the user
 * had it), for about 10 s, then stops by itself and leaves its normal notification (not a missed one). Both say what the
 * event is for (its notes, R5), and neither shows the notes on the lock screen (R2: a public version with the title and
 * time only). With "Notification sound only" a reminder is a plain notification on the Reminders category, nothing rings.
 *
 * Self-contained: the setting, the ringer mode and the notifications are put back afterwards, and any ring still going is
 * stopped. The sound itself isn't checked here (it was by hand, in the audio log); that AlarmService rang is.
 */
class ReminderSoundAndNotesTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun shell(command: String) = ins.uiAutomation.executeShellCommand(command).close()
    private fun titled(title: String, n: StatusBarNotification) = n.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title
    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
    private fun big(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

    // R2: the lock screen's copy has the title and time, and no word of the notes.
    private fun assertNotesOffTheLockScreen(n: Notification, title: String) {
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val public = n.publicVersion
        assertNotNull("no lock-screen version", public)
        assertEquals(title, public!!.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        val shown = text(public) + big(public)
        assertFalse("notes on the lock screen: $shown", shown.contains("passport"))
        assertTrue("no time on the lock screen: $shown", shown.isNotBlank())
    }

    // The ringer mode as it was, put back by name ("cmd audio set-ringer-mode" takes SILENT, VIBRATE or NORMAL).
    private fun ringerName(mode: Int) = when (mode) { AudioManager.RINGER_MODE_SILENT -> "SILENT"; AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"; else -> "NORMAL" }

    @Test fun aDefaultReminderRingsTenSecondsInSilentModeThenStaysAsItsNotification() = runBlocking {
        val audio = context.getSystemService(AudioManager::class.java)
        val ringerBefore = audio.ringerMode
        val soundBefore = app.settings.reminderSound.value
        app.settings.setReminderSound(ReminderSound.SECONDS_10) // the new-install default (CleanStart sets another)
        manager.cancelAll()
        // The 4-second chime's categories are gone (deleted when Planner starts, as after the update).
        assertNull(manager.getNotificationChannel("reminders_chime")); assertNull(manager.getNotificationChannel("reminder_sound"))
        // Starts in two minutes, reminder one minute before: it fires about a minute from now (whole minutes).
        val start = LocalDateTime.now().withSecond(0).withNano(0).plusMinutes(2)
        val title = "QA sound check"
        app.repository.saveItem(ItineraryItem(tripId = 0, date = start.toLocalDate(), startTime = start.toLocalTime(),
            title = title, notes = "Bring the passport\nand the tickets"),
            addedReminders = listOf(Reminder(itemId = 0, amount = 1, unit = ReminderUnit.MINUTES)))
        val reminder = app.repository.snapshot().reminders.single()
        assertEquals("left at Default", ReminderSound.DEFAULT, ReminderSound.of(reminder.ringUntilDismissed, reminder.ringSeconds))
        shell("cmd audio set-ringer-mode SILENT")
        shell("input keyevent KEYCODE_HOME")
        try {
            // It rings: AlarmService's notification on the ringing category, a foreground service's.
            var end = SystemClock.uptimeMillis() + 150_000
            var ringing: StatusBarNotification? = null
            while (SystemClock.uptimeMillis() < end && ringing == null) {
                ringing = manager.activeNotifications.firstOrNull { titled(title, it) && it.notification.channelId == ALARM_CHANNEL_ID }
                Thread.sleep(100)
            }
            assertNotNull("it didn't ring", ringing)
            val rangAt = SystemClock.uptimeMillis()
            assertEquals(AudioManager.RINGER_MODE_SILENT, audio.ringerMode)
            val ring = ringing!!.notification
            assertTrue("AlarmService rings it", ring.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            assertTrue("its first line of notes shows while it rings: ${text(ring)}", text(ring).endsWith("· Bring the passport"))
            assertTrue("all of them when opened: ${big(ring)}", big(ring).contains("Bring the passport\nand the tickets"))
            assertNotesOffTheLockScreen(ring, title)
            assertTrue("a Stop button", ring.actions.orEmpty().any { it.title.toString() == "Stop" })

            // About 10 s later it stops by itself and the reminder stays as its normal notification, not a missed one.
            end = SystemClock.uptimeMillis() + 45_000
            var after: StatusBarNotification? = null
            while (SystemClock.uptimeMillis() < end && after == null) {
                val active = manager.activeNotifications
                if (active.none { it.notification.channelId == ALARM_CHANNEL_ID && titled(title, it) })
                    after = active.firstOrNull { titled(title, it) && it.notification.channelId == REMINDER_CHANNEL_ID }
                Thread.sleep(100)
            }
            val rang = SystemClock.uptimeMillis() - rangAt
            assertNotNull("it didn't stop by itself, or left no notification", after)
            assertTrue("it rang for ${rang} ms, not about 10 s", rang in 7_000L..30_000L)
            assertEquals("not a missed alarm", reminder.id.toInt(), after!!.id)
            val shown = after.notification
            assertTrue("its first line of notes shows: ${text(shown)}", text(shown).endsWith("· Bring the passport"))
            assertTrue("all of them when opened: ${big(shown)}", big(shown).contains("Bring the passport\nand the tickets"))
            assertNotesOffTheLockScreen(shown, title)
            assertTrue("it keeps its Snooze", shown.actions.orEmpty().any { it.title.toString() == "Snooze" })
        } finally {
            AlarmService.stopIfRinging(context, reminder.id)
            shell("cmd audio set-ringer-mode ${ringerName(ringerBefore)}")
            app.settings.setReminderSound(soundBefore)
            manager.cancelAll()
        }
    }

    // "Notification sound only": a plain notification on the Reminders category (muted by silent mode, as Android does),
    // with the task's notes, kept off the lock screen; nothing rings.
    @Test fun notificationSoundOnlyIsAPlainNotification() = runBlocking {
        val soundBefore = app.settings.reminderSound.value
        app.settings.setReminderSound(ReminderSound.NOTIFICATION)
        manager.cancelAll()
        val title = "QA plain reminder"
        val task = PlannerTask(title = title, notes = "Bring the passport\nand the tickets", reminderAt = System.currentTimeMillis() + 5_000)
        app.repository.saveTask(task)
        shell("input keyevent KEYCODE_HOME")
        try {
            val end = SystemClock.uptimeMillis() + 60_000
            var shown: StatusBarNotification? = null
            while (SystemClock.uptimeMillis() < end && shown == null) {
                assertTrue("it rang", manager.activeNotifications.none { it.notification.channelId == ALARM_CHANNEL_ID })
                shown = manager.activeNotifications.firstOrNull { titled(title, it) }
                Thread.sleep(100)
            }
            assertNotNull("the reminder didn't come", shown)
            assertEquals(REMINDER_CHANNEL_ID, shown!!.notification.channelId)
            assertEquals("task:${task.id}", shown.tag)
            assertEquals("Bring the passport", text(shown.notification))
            assertTrue(big(shown.notification).contains("and the tickets"))
            assertNotesOffTheLockScreen(shown.notification, title)
            Thread.sleep(2_000)
            assertTrue("it rang", manager.activeNotifications.none { it.notification.channelId == ALARM_CHANNEL_ID })
        } finally {
            app.settings.setReminderSound(soundBefore)
            manager.cancelAll()
        }
    }
}
