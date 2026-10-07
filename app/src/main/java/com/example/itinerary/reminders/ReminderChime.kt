package com.example.itinerary.reminders

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import com.example.itinerary.R

/**
 * A normal reminder's sound, played by Planner itself as an alarm sound, once (a few seconds), because silent and vibrate
 * mode mute every notification sound. With Settings › Reminders › "Play reminder sounds in silent and vibrate mode" on
 * (the default), the reminder's notification is posted silent and this plays instead, at alarm volume. Do Not Disturb
 * still silences it unless it lets alarms through. Ringing reminders don't use it: AlarmService rings them.
 */
object ReminderChime {
    const val PREF = "reminder_chime"
    private const val MAX_MS = 4_000L

    /** The setting; before the first unlock after a reboot settings can't be read, so then it's the default (on). */
    fun enabled(context: Context): Boolean =
        runCatching { context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(PREF, true) }.getOrDefault(true)

    /** Whether Do Not Disturb lets an alarm through: all off, alarms only, or priority with alarms allowed. */
    internal fun alarmsAllowed(filter: Int, priorityCategories: Int?): Boolean = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_NONE -> false
        NotificationManager.INTERRUPTION_FILTER_PRIORITY ->
            priorityCategories?.let { it and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0 } ?: true
        else -> true
    }

    /** Plays the chime (and a short vibration, unless the phone is on silent). Returns at once; it stops by itself. */
    fun play(context: Context) {
        val app = context.applicationContext
        val notifications = app.getSystemService(NotificationManager::class.java)
        val categories = if (Build.VERSION.SDK_INT >= 28) runCatching { notifications.notificationPolicy.priorityCategories }.getOrNull() else null
        if (!alarmsAllowed(notifications.currentInterruptionFilter, categories)) return
        val wake = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Planner:reminderChime")
            .apply { setReferenceCounted(false); acquire(MAX_MS + 1_000) }
        if (app.getSystemService(AudioManager::class.java).ringerMode != AudioManager.RINGER_MODE_SILENT) vibrate(app)
        // The phone's notification sound, else its default, else the alarm sound, else Planner's own tone (always readable,
        // also before the first unlock).
        val unlocked = DirectBoot.isUnlocked(app)
        val choices = AlarmSound.choices(unlocked,
            if (unlocked) runCatching { RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_NOTIFICATION) }.getOrNull() else null,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            Uri.parse("android.resource://${app.packageName}/${R.raw.alarm_fallback}"))
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val player = choices.firstNotNullOfOrNull { uri ->
            runCatching { MediaPlayer().apply { setAudioAttributes(attributes); setDataSource(app, uri); prepare() } }.getOrNull()
        }
        if (player == null) { wake.release(); return }
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        val stop = Runnable {
            if (!finished) { finished = true; runCatching { player.stop() }; player.release(); if (wake.isHeld) wake.release() }
        }
        player.setOnCompletionListener { handler.removeCallbacks(stop); stop.run() }
        player.setOnErrorListener { _, _, _ -> handler.removeCallbacks(stop); stop.run(); true }
        runCatching { player.start() }.onFailure { stop.run(); return }
        handler.postDelayed(stop, MAX_MS)
    }

    private fun vibrate(context: Context) {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 250, 150, 250), -1)
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator?.vibrate(effect)
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(effect)
    }
}
