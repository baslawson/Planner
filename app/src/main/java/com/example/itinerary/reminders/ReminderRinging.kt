package com.example.itinerary.reminders

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import com.example.itinerary.data.ReminderSound

/**
 * Settings › Notifications › "Reminder sound" (ReminderSound), in the app's settings, and a copy in device-protected
 * storage: a reminder that rings before the first unlock after a reboot can't read the app's settings (RB-3), and would
 * otherwise ring for the new-install default instead of the user's choice.
 */
object ReminderSoundSetting {
    const val PREF = "reminder_sound_length"
    // The switch it replaces, "Play reminder sounds in silent and vibrate mode" (bugnotes item 6, 0.0.23 to 0.0.25).
    const val OLD_PREF = "reminder_chime"
    private const val LOCKED_PREFS = "reminder_sound"

    /** The choice in the app's settings: its own, else what the old switch said (off → notification only; on → 10 s). */
    fun read(prefs: SharedPreferences): ReminderSound = ReminderSound.fromSetting(prefs.getString(PREF, null),
        if (prefs.contains(OLD_PREF)) prefs.getBoolean(OLD_PREF, true) else null)

    fun write(context: Context, prefs: SharedPreferences, sound: ReminderSound) {
        prefs.edit().putString(PREF, sound.name).apply()
        mirror(context, sound)
    }

    private fun locked(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences(LOCKED_PREFS, Context.MODE_PRIVATE)

    /** Copies [sound] for a ring before the first unlock (also at start, so a restart before any reminder rings has it). */
    fun mirror(context: Context, sound: ReminderSound) {
        runCatching { locked(context).let { if (it.getString(PREF, null) != sound.name) it.edit().putString(PREF, sound.name).apply() } }
            .onFailure { android.util.Log.w("ReminderSound", "Couldn't keep the reminder sound for a locked restart", it) }
    }

    /**
     * The choice now, as a reminder ringing reads it: from the app's settings once unlocked (and copied for a locked restart,
     * so an upgrade from the old switch is copied the first time a reminder rings); before the first unlock, from that copy,
     * else the new-install default.
     */
    fun current(context: Context): ReminderSound {
        if (DirectBoot.isUnlocked(context)) {
            val sound = runCatching { read(context.getSharedPreferences("settings", Context.MODE_PRIVATE)) }.getOrDefault(ReminderSound.SETTING_DEFAULT)
            mirror(context, sound)
            return sound
        }
        return runCatching { ReminderSound.fromSetting(locked(context).getString(PREF, null), null) }.getOrDefault(ReminderSound.SETTING_DEFAULT)
    }
}

/**
 * How long a reminder due now rings through AlarmService, from its own choice ([ring], "Until I stop it", and [seconds],
 * ReminderSound): seconds, 0 until stopped, or null for the notification sound only (no ring). A timed ring is for a
 * normal reminder, so it gives way where the plain notification would be quiet anyway ([timedRingAllowed]); "Until I stop
 * it" rings as it always has.
 */
internal fun ringSecondsNow(context: Context, ring: Boolean, seconds: Int): Int? {
    val sound = ReminderSound.resolve(ring, seconds, ReminderSoundSetting.current(context))
    val ringFor = sound.alarmSeconds ?: return null
    if (ringFor == 0) return 0
    val manager = context.getSystemService(NotificationManager::class.java)
    val reminders = runCatching { manager.getNotificationChannel(REMINDER_CHANNEL_ID) }.getOrNull()
    val audioMode = runCatching { context.getSystemService(AudioManager::class.java).mode }.getOrDefault(AudioManager.MODE_NORMAL)
    val filter = runCatching { manager.currentInterruptionFilter }.getOrDefault(NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
    return ringFor.takeIf { timedRingAllowed(reminders?.importance, reminders == null || reminders.sound != null, filter, audioMode) }
}

/**
 * Whether a timed ring (10 s to 1 min) may ring, or the reminder is a plain notification instead. Not when the user made
 * Planner's Reminders category silent in Android's settings (their choice for reminders wins, hunt 19 P3); not in any Do
 * Not Disturb mode (the default one lets alarms through, so a normal reminder would ring through it, hunt 20 R1); not
 * during a call or while one rings (hunt 20 R2). The kept checks of the 4-second chime this replaces.
 */
internal fun timedRingAllowed(remindersImportance: Int?, remindersHaveSound: Boolean,
                              interruptionFilter: Int = NotificationManager.INTERRUPTION_FILTER_ALL,
                              audioMode: Int = AudioManager.MODE_NORMAL): Boolean =
    (remindersImportance == null || remindersImportance >= NotificationManager.IMPORTANCE_DEFAULT) && remindersHaveSound &&
        interruptionFilter in setOf(NotificationManager.INTERRUPTION_FILTER_ALL, NotificationManager.INTERRUPTION_FILTER_UNKNOWN) &&
        audioMode == AudioManager.MODE_NORMAL
