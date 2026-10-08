package com.example.itinerary.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.itinerary.ItineraryApp
import kotlinx.coroutines.*

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, 0L)
        // Sample alarms use id zero and have no database record.
        if (id == 0L) { show(context, intent); return }
        // RB-3: before the first unlock after a reboot the database can't be read. This alarm was set from the locked
        // snapshot (BootReceiver), and its intent carries what the notification shows; once unlocked it's noted as rung.
        if (!DirectBoot.isUnlocked(context)) {
            DirectBoot.fired(context, MissedReminders.eventKey(id), intent.getLongExtra(ReminderScheduler.EXTRA_TRIGGER, 0L))
            show(context, intent)
            return
        }
        (context.applicationContext as ItineraryApp).reminderScheduler.ledger.fired(MissedReminders.eventKey(id), System.currentTimeMillis())
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val trigger = intent.getLongExtra(ReminderScheduler.EXTRA_TRIGGER, 0L)
                (context.applicationContext as ItineraryApp).repository.deliverReminder(id, trigger) { item, reminder ->
                    show(context, reminderIntent(context, item, reminder, trigger))
                }
            } catch (e: Exception) { android.util.Log.w("ReminderReceiver", "Couldn't deliver reminder", e) }
            finally {
                pending.finish()
                // One alarm fewer: a reminder waiting for one gets it (AlarmWindow); and the locked-reboot snapshot is kept
                // fresh. H17-R1: after finish(), so the next alarm's receiver isn't held up by it.
                DirectBoot.afterRingLater(context.applicationContext as ItineraryApp)
            }
        }
    }

    private fun show(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, 0L)
        val content = reminderContent(context, intent.extras) ?: return
        var couldNotRing = false
        // Its sound (ReminderSound): rings for a few seconds or until stopped, through AlarmService; or the notification only.
        val ringFor = ringSecondsNow(context, intent.getBooleanExtra(ReminderScheduler.EXTRA_RING, false),
            intent.getIntExtra(ReminderScheduler.EXTRA_RING_SECONDS, 0))
        if (ringFor != null && ringingAlarmsEnabled(context)) {
            // Hunt 24 D1: reserved, as a task's ringing start is, so a start Android delivers again after ending the process is
            // refused once the reminder was deleted, paid, skipped, snoozed or moved (ReminderScheduler.cancel). Kept on
            // device-protected storage, so this works before the first unlock too. One that can't be reserved rings as before.
            val token = if (id == 0L) null else runCatching { OwnedAlarmStarts.reserve(context, OwnedAlarmStarts.EVENT, id.toString()) }
                .onFailure { android.util.Log.w("ReminderReceiver", "Couldn't reserve the ringing start", it) }.getOrNull()
            try {
                ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java).putExtras(intent)
                    .putExtra(AlarmService.EXTRA_RING_FOR, ringFor)
                    // Not an earlier ring's (a snooze set from the ringing alarm carries its extras).
                    .apply { if (token != null) putExtra(EXTRA_EVENT_START, token) else removeExtra(EXTRA_EVENT_START) })
                return
            } catch (e: Exception) {
                token?.let { runCatching { OwnedAlarmStarts.cancel(context, OwnedAlarmStarts.EVENT, id.toString(), it) } }
                // Android 12+ lets a background app start the ringing service from an exact alarm only. Without "Alarms &
                // reminders" (off by default from Android 14) this alarm came inexact, and nothing else allowed then can
                // ring: setAlarmClock needs the same permission, and full-screen intents are for calling and clock apps.
                // So the notification itself keeps sounding until it is seen, and says why it didn't ring. H17-R2: with exact
                // alarms allowed (always from Android 13) the cause is elsewhere, battery use say (CouldNotRing).
                android.util.Log.w("ReminderReceiver", "Couldn't start the ringing alarm", e)
                couldNotRing = true
            }
        }
        postReminderNotification(context, id.toInt(), content.title, content.text, content.subText, id, intent.getStringExtra(ReminderScheduler.EXTRA_BILL_TOKEN), intent.getStringExtra(ReminderScheduler.EXTRA_SNOOZE_TOKEN), couldNotRing, details = content.details, publicText = content.whenText)
    }
}
