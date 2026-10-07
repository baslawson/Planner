package com.example.itinerary.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.itinerary.R

/**
 * Plays a normal reminder's chime (ReminderChime) as a foreground service, for the few seconds it lasts. Newer Android
 * mutes sound started by an app in the background, alarm sound included, unless it comes from a foreground service
 * (bugnotes 7 Oct: no sound in silent or vibrate mode on Android 16). Android asks for a notification while it runs:
 * a quiet one on its own minimal channel, gone when the chime ends. A chime while one plays waits its turn.
 */
class ChimeService : Service() {
    private var playing = false
    private var pending = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Reminder sound playing", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown for the few seconds a reminder's sound plays in silent or vibrate mode"
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
        val notice = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Reminder sound").setPriority(NotificationCompat.PRIORITY_LOW).setCategory(NotificationCompat.CATEGORY_REMINDER).build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Exception) {
            // Not allowed as a foreground service now: played as before (it may be muted), and the service ends.
            android.util.Log.w("ChimeService", "Couldn't start in the foreground", e)
            ReminderChime.playNow(applicationContext) {}
            stopSelf(startId)
            return START_NOT_STICKY
        }
        pending++
        if (!playing) next()
        return START_NOT_STICKY
    }

    private fun next() {
        if (pending == 0) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        pending--
        playing = true
        ReminderChime.playNow(this) { playing = false; next() }
    }

    companion object {
        private const val CHANNEL_ID = "reminder_sound"
        private const val NOTIFICATION_ID = 0x0C41
    }
}
