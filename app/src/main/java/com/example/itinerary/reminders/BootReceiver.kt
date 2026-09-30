package com.example.itinerary.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.itinerary.ItineraryApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Alarms are cleared by a reboot and by app updates, so put every future reminder back, and after a reboot show the missed ones.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED)) return
        val app = context.applicationContext as ItineraryApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Only a reboot leaves due alarms unseen for long: a time change fires past ones late, an update takes seconds.
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) try {
                    val now = System.currentTimeMillis()
                    handleMissedReminders(app.repository, app.reminderScheduler.ledger, now) { postMissedReminders(context, it, now) }
                } catch (e: Exception) { android.util.Log.w("BootReceiver", "Couldn't show missed reminders", e) }
                app.repository.rescheduleAllReminders()
            } finally {
                pending.finish()
            }
        }
    }
}
