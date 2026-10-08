package com.example.itinerary.data

// Opening Planner sets every reminder's alarm again (Repository.rescheduleAllReminders): a safety net for alarms Android
// dropped. That is needed once per start of the app's process (a force stop or an update cancels alarms and also ends the
// process) and when what decides an alarm changed since: the exact-alarm permission, or the time zone. Each return to the
// app between those is skipped (it was about 1,200 system calls a time with a few hundred reminders and tasks).
object RescheduleOnOpen {
    private var last: String? = null

    // [state]: what decides the alarms now. True when they should be set again; [done] records it once that has worked,
    // so a pass cut short (the screen closed while it waited) is tried again at the next open.
    @Synchronized fun due(state: String): Boolean = state != last

    @Synchronized fun done(state: String) { last = state }

    // Tests start each case afresh.
    @Synchronized internal fun forget() { last = null }

    // Hunt 25 D5: a reschedule handed to WorkManager (RescheduleRemindersWorker) is waiting: the next open does it too.
    @Synchronized fun again() { last = null }

    fun state(exactAllowed: Boolean, zone: java.time.ZoneId) = "$exactAllowed|${zone.id}"
}
