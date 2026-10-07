package com.example.itinerary.data

/**
 * How a reminder sounds (bugnotes 7 Oct, after bug hunt 21): the plain notification sound, which silent and vibrate mode
 * mute, or a ring through AlarmService (the alarm sound, through silent and vibrate mode, shown over the lock screen) for
 * 10 s, 30 s, 1 min, or until it is stopped. Settings › Notifications › "Reminder sound" says what [DEFAULT] means; each
 * reminder can choose its own.
 *
 * Stored per reminder (event reminders, tasks, notes) as two fields, so the data and backups made before keep their
 * meaning with no rewrite: `ringUntilDismissed` stays the "Until I stop it" flag it always was, and `ringSeconds` holds the
 * rest (0 = the default, -1 = notification sound only, 10/30/60 = rings that long; always 0 with the flag on). A reminder
 * from before is then either "Until I stop it" or "Default", and a backup or build that knows only the flag reads the
 * new ones as "Default" rather than failing.
 */
enum class ReminderSound(val ring: Boolean, val seconds: Int, val label: String, val short: String) {
    DEFAULT(false, 0, "Default", "Default"),
    NOTIFICATION(false, -1, "Notification sound only", "notification sound only"),
    SECONDS_10(false, 10, "10 seconds", "10 s"),
    SECONDS_30(false, 30, "30 seconds", "30 s"),
    MINUTE(false, 60, "1 minute", "1 min"),
    UNTIL_STOPPED(true, 0, "Until I stop it", "until I stop it");

    /**
     * How long it rings through AlarmService, in seconds (0: until stopped, giving up after AlarmService.MAX_RING_MINUTES);
     * null: no ring, the notification sound only. [DEFAULT] has none of its own: resolve it first ([resolve]).
     */
    val alarmSeconds: Int? get() = when (this) {
        DEFAULT, NOTIFICATION -> null
        UNTIL_STOPPED -> 0
        else -> seconds
    }

    /** What a reminder's own list shows for this choice, [default] being the Settings choice: "Default (10 s)". */
    fun choiceLabel(default: ReminderSound): String =
        if (this == DEFAULT) "Default (${default.takeUnless { it == DEFAULT }?.short ?: SETTING_DEFAULT.short})" else label

    companion object {
        /** The Settings choice on a new install, and for the old switch left on (its default). */
        val SETTING_DEFAULT = SECONDS_10

        /** What Settings can choose: everything but Default. */
        val settingChoices: List<ReminderSound> = entries.filter { it != DEFAULT }

        /** A reminder's choice from its two stored fields. A length no build offers (a later version's, say) reads as Default. */
        fun of(ring: Boolean, seconds: Int): ReminderSound =
            if (ring) UNTIL_STOPPED else entries.firstOrNull { !it.ring && it.seconds == seconds } ?: DEFAULT

        /** `ringSeconds` as it is stored: only a length on offer, 0 with "Until I stop it", and 0 without a reminder. */
        fun cleanSeconds(ring: Boolean, seconds: Int, hasReminder: Boolean = true): Int =
            if (!hasReminder || ring) 0 else of(false, seconds).seconds

        /** What a reminder with these fields does now, with [default] the Settings choice: never [DEFAULT]. */
        fun resolve(ring: Boolean, seconds: Int, default: ReminderSound): ReminderSound =
            of(ring, seconds).takeUnless { it == DEFAULT } ?: default.takeUnless { it == DEFAULT } ?: SETTING_DEFAULT

        /**
         * The Settings choice from what is stored: its own [name], else (an upgrade) the old switch "Play reminder sounds in
         * silent and vibrate mode" ([oldSwitch], null when never set): off → the notification sound only; on, its default,
         * → [SETTING_DEFAULT], which also sounds through silent and vibrate mode as the switch did.
         */
        fun fromSetting(name: String?, oldSwitch: Boolean?): ReminderSound =
            entries.firstOrNull { it.name == name && it != DEFAULT } ?: if (oldSwitch == false) NOTIFICATION else SETTING_DEFAULT
    }
}

/** This reminder's sound choice. */
val Reminder.sound: ReminderSound get() = ReminderSound.of(ringUntilDismissed, ringSeconds)
val PlannerTask.sound: ReminderSound get() = ReminderSound.of(ringUntilDismissed, ringSeconds)
val PlannerNote.sound: ReminderSound get() = ReminderSound.of(ringUntilDismissed, ringSeconds)

fun Reminder.withSound(sound: ReminderSound): Reminder = copy(ringUntilDismissed = sound.ring, ringSeconds = sound.seconds)
