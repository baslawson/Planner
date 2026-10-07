package com.example.itinerary.reminders

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.example.itinerary.R

/**
 * A normal reminder's sound, played by Planner itself as an alarm sound, once (a few seconds), because silent and vibrate
 * mode mute every notification sound. With Settings › Reminders › "Play reminder sounds in silent and vibrate mode" on
 * (the default), the reminder's notification goes to [CHANNEL_ID], which has no sound of its own, and this plays instead,
 * at alarm volume. Do Not Disturb still silences it unless it lets alarms through. Ringing reminders don't use it:
 * AlarmService rings them.
 *
 * Not setSilent: NotificationCompat files a silent notification in a group that never pops up or wakes the lock screen
 * (bug hunt 19, P1). The channel keeps importance high, so the reminder still shows on screen, and vibrates as Android's
 * own setting for the mode says.
 */
object ReminderChime {
    const val PREF = "reminder_chime"
    const val CHANNEL_ID = "reminders_chime"
    private const val MAX_MS = 4_000L

    /** The setting; before the first unlock after a reboot settings can't be read, so then it's the default (on). */
    fun enabled(context: Context): Boolean =
        runCatching { context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(PREF, true) }.getOrDefault(true)

    fun createChannel(manager: NotificationManager) {
        manager.createNotificationChannel(
            android.app.NotificationChannel(CHANNEL_ID, "Reminders with Planner's sound", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders while \"Play reminder sounds in silent and vibrate mode\" is on: Planner plays the sound"
                setSound(null, null)
                enableVibration(true)
            },
        )
    }

    /**
     * Whether a reminder [wanted] to sound gets the chime (and goes to [CHANNEL_ID]); otherwise it goes to the Reminders
     * category as any notification does. Not when: the setting is off; the user made the Reminders category silent, or
     * this one (P3, hunt 20 R4); Do Not Disturb is on (R1: it would hide the notification, yet alarms are let through by
     * default, so a sound would play with nothing on screen); a call is on (R2); or the phone's notification sound is
     * None (R3: there is nothing to play, not the alarm tone instead).
     */
    fun use(context: Context, wanted: Boolean): Boolean {
        if (!wanted || !enabled(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        val reminders = manager.getNotificationChannel(REMINDER_CHANNEL_ID)
        val audioMode = runCatching { context.getSystemService(android.media.AudioManager::class.java).mode }.getOrDefault(android.media.AudioManager.MODE_NORMAL)
        val soundNone = reminders?.sound == android.provider.Settings.System.DEFAULT_NOTIFICATION_URI && DirectBoot.isUnlocked(context) &&
            runCatching { RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION) == null }.getOrDefault(false)
        return chimes(reminders?.importance, reminders == null || reminders.sound != null, manager.getNotificationChannel(CHANNEL_ID)?.importance,
            manager.currentInterruptionFilter, audioMode, soundNone)
    }

    internal fun chimes(reminderImportance: Int?, reminderHasSound: Boolean, chimeImportance: Int?,
                        interruptionFilter: Int = NotificationManager.INTERRUPTION_FILTER_ALL,
                        audioMode: Int = android.media.AudioManager.MODE_NORMAL, notificationSoundNone: Boolean = false): Boolean =
        (reminderImportance == null || reminderImportance >= NotificationManager.IMPORTANCE_DEFAULT) && reminderHasSound &&
            (chimeImportance == null || chimeImportance >= NotificationManager.IMPORTANCE_DEFAULT) &&
            interruptionFilter in setOf(NotificationManager.INTERRUPTION_FILTER_ALL, NotificationManager.INTERRUPTION_FILTER_UNKNOWN) &&
            audioMode == android.media.AudioManager.MODE_NORMAL && !notificationSoundNone

    /** Plays the chime (the channel vibrates, as the phone's mode allows). Returns at once; it stops by itself. */
    fun play(context: Context) {
        val app = context.applicationContext
        val notifications = app.getSystemService(NotificationManager::class.java)
        val wake = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Planner:reminderChime")
            .apply { setReferenceCounted(false); acquire(MAX_MS + 1_000) }
        // The sound chosen for the Reminders category (P3: a custom one too), else the phone's notification sound, else its
        // default, else the alarm sound, else Planner's own tone (always readable, also before the first unlock).
        val unlocked = DirectBoot.isUnlocked(app)
        val chosen = notifications.getNotificationChannel(REMINDER_CHANNEL_ID)?.sound?.takeIf { it != android.provider.Settings.System.DEFAULT_NOTIFICATION_URI }
            ?: if (unlocked) runCatching { RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_NOTIFICATION) }.getOrNull() else null
        val choices = AlarmSound.choices(unlocked, chosen,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            Uri.parse("android.resource://${app.packageName}/${R.raw.alarm_fallback}"))
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val player = choices.firstNotNullOfOrNull { uri ->
            runCatching { MediaPlayer().apply { setAudioAttributes(attributes); setDataSource(app, uri); prepare() } }.getOrNull()
        }
        if (player == null) { wake.release(); return }
        // R2: music and other sound dip under the chime, and come back after (audio focus, given back when it stops).
        val audio = app.getSystemService(android.media.AudioManager::class.java)
        val focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()
        runCatching { audio.requestAudioFocus(focus) }
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        val stop = Runnable {
            if (!finished) { finished = true; runCatching { player.stop() }; player.release(); runCatching { audio.abandonAudioFocusRequest(focus) }; if (wake.isHeld) wake.release() }
        }
        player.setOnCompletionListener { handler.removeCallbacks(stop); stop.run() }
        player.setOnErrorListener { _, _, _ -> handler.removeCallbacks(stop); stop.run(); true }
        runCatching { player.start() }.onFailure { stop.run(); return }
        handler.postDelayed(stop, MAX_MS)
    }
}
