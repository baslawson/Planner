package com.example.itinerary.reminders

/**
 * D6-1: the sounds a ringing alarm tries, in order, until one plays (AlarmService). The user's chosen alarm sound is a
 * `content://media` one, which can't be read before the first unlock after a reboot (credential-encrypted storage), so
 * then it is skipped: the settings' default alarm sound (`content://settings/system/alarm_alert`, served from a copy
 * Android keeps readable) comes first. Last is Planner's own tone, inside the app, which can always be read.
 */
object AlarmSound {
    fun <T : Any> choices(unlocked: Boolean, chosen: T?, defaultAlarm: T?, defaultRingtone: T?, bundled: T): List<T> =
        listOfNotNull(chosen.takeIf { unlocked }, defaultAlarm, defaultRingtone, bundled).distinct()
}
