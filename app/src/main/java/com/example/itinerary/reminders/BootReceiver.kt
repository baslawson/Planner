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
        if (intent.action !in setOf(Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED)) return
        // RB-3: directBootAware, so this also runs before the first unlock, when the database can't be read. Then the
        // nearest alarms are set from the locked snapshot; BOOT_COMPLETED, which Android sends once the phone is
        // unlocked, sets them all from the database (the same request codes: each replaces its snapshot one). A time
        // change while locked leaves them: they are set for fixed instants, and the unlock puts the times right.
        if (!DirectBoot.isUnlocked(context)) {
            if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) DirectBoot.armFromSnapshot(context)
            return
        }
        // Unlocked already (no screen lock, say): BOOT_COMPLETED follows and does it all.
        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val app = context.applicationContext as ItineraryApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Only a reboot leaves due alarms unseen for long: a time change fires past ones late, an update takes seconds.
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) showMissedReminders(context, afterBoot = true)
                app.repository.rescheduleAllReminders()
            } finally {
                pending.finish()
            }
        }
    }
}
