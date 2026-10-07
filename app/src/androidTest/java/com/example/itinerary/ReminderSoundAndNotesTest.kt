package com.example.itinerary

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.Reminder
import com.example.itinerary.data.ReminderUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime

// Bugnotes 7 Oct: an event reminder in silent mode, with Planner in the background (as the user had it). It says what
// the event is for (its notes), and its chime is played from ChimeService, a foreground service, which newer Android
// doesn't mute ("background playback would be muted" when played straight from the alarm). The sound itself was
// checked by hand in the log; here, that the chime's service ran for it.
class ReminderSoundAndNotesTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private fun shell(command: String) = ins.uiAutomation.executeShellCommand(command).close()

    @Test fun anEventReminderSaysWhatItIsForAndChimesInSilentMode() = runBlocking {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        // Starts in two minutes, reminder one minute before: it fires about a minute from now (whole minutes).
        val start = LocalDateTime.now().withSecond(0).withNano(0).plusMinutes(2)
        app.repository.saveItem(ItineraryItem(tripId = 0, date = start.toLocalDate(), startTime = start.toLocalTime(),
            title = "QA sound check", notes = "Bring the passport\nand the tickets"),
            addedReminders = listOf(Reminder(itemId = 0, amount = 1, unit = ReminderUnit.MINUTES)))
        shell("cmd audio set-ringer-mode SILENT")
        shell("input keyevent KEYCODE_HOME")
        try {
            val end = SystemClock.uptimeMillis() + 150_000
            var shown: Notification? = null
            var chimed = false
            while (SystemClock.uptimeMillis() < end && (shown == null || !chimed)) {
                val active = manager.activeNotifications
                shown = shown ?: active.firstOrNull { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "QA sound check" }?.notification
                chimed = chimed || active.any { it.notification.channelId == "reminder_sound" }
                Thread.sleep(200)
            }
            assertNotNull("the reminder came", shown)
            val text = shown!!.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val big = shown.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
            assertTrue("its first line of notes shows: $text", text.endsWith("· Bring the passport"))
            assertTrue("all of them when opened: $big", big.contains("Bring the passport\nand the tickets"))
            assertTrue("in silent mode it went to the chime's channel", shown.channelId == "reminders_chime")
            if (!chimed) fail("the chime's foreground service didn't run")
        } finally {
            shell("cmd audio set-ringer-mode NORMAL")
        }
    }

}
