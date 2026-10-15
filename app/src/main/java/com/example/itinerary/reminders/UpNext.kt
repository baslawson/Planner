package com.example.itinerary.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.itinerary.ItineraryApp
import com.example.itinerary.R
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.widgetEventsOnDay
import com.example.itinerary.ui.label
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

const val UP_NEXT_CHANNEL_ID = "up_next"

/**
 * "Up next" (user, 15 Oct; Settings › Notifications, off until chosen): one quiet notification that stays, with the next
 * event that has a time in the coming 24 hours and Android's own countdown to it (so nothing has to update it every
 * minute). With a travel buffer before the event, it counts down to when to leave and says so. It moves on when that
 * moment comes (an inexact alarm), when Planner's data or the other calendars change, on start and after a reboot.
 */
object UpNext {
    private const val TAG = "up-next"
    private const val REQUEST_CODE = 1_000_000_121
    private val lock = Mutex()

    /** The next event and the moment the countdown is to: when to leave, if the event has a travel buffer and that's ahead. */
    data class Next(val event: ItineraryItem, val start: LocalDateTime, val leaveAt: LocalDateTime?) {
        val countdownTo: LocalDateTime get() = leaveAt ?: start
    }

    /**
     * Of the events with a time starting after [now] whose moment to act (leave, or start) is within a day of it (not
     * skipped, not a bill), the one to act on first: by when to leave where there's a travel buffer, so a later event
     * with a long journey comes before a nearer one without (bug hunt 30).
     */
    fun next(events: List<ItineraryItem>, now: LocalDateTime): Next? = events.asSequence()
        .filter { it.startTime != null && !it.skipped && it.category != "Bills" }
        .map { it to it.date.atTime(it.startTime!!) }
        .filter { (_, start) -> start > now }
        .map { (event, start) ->
            val leave = start.minusMinutes(event.bufferBeforeMinutes.toLong()).takeIf { event.bufferBeforeMinutes > 0 && it > now }
            Next(event, start, leave)
        }
        // Bug hunt 31: within a day by when to act, so a flight tomorrow with a long journey counts once leaving is near.
        .filter { it.countdownTo <= now.plusDays(1) }
        .minWithOrNull(compareBy<Next> { it.countdownTo }.thenBy { it.start }.thenBy { it.event.title })

    /** Within this long of leaving, the title says "Leave soon"; before that, "Leave at 6:00" (bug hunt 30). */
    const val SOON_MINUTES = 60L

    /** "", " tomorrow", or (bug hunt 32: with a long travel buffer the event can be two days on) its weekday. */
    fun dayWord(at: LocalDateTime, today: LocalDate): String = when (at.toLocalDate()) {
        today -> ""
        today.plusDays(1) -> " tomorrow"
        else -> " " + at.format(java.time.format.DateTimeFormatter.ofPattern("EEEE", java.util.Locale.getDefault()))
    }

    /** True when leaving time is close enough to say "Leave soon". */
    fun leaveSoon(next: Next, now: LocalDateTime): Boolean = next.leaveAt != null && !next.leaveAt.isAfter(now.plusMinutes(SOON_MINUTES))

    /** Works it out again soon, off the caller's thread. */
    fun request(context: Context) {
        val app = context.applicationContext as ItineraryApp
        app.appScope.launch { runCatching { refresh(app) }.onFailure { android.util.Log.w("UpNext", "Couldn't update Up next", it) } }
    }

    suspend fun refresh(context: Context) = lock.withLock {
        val app = context.applicationContext as ItineraryApp
        val manager = NotificationManagerCompat.from(context)
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (!app.settings.upNext.value) { manager.cancel(TAG, 0); alarms?.cancel(pendingIntent(context)); return@withLock }
        val today = LocalDate.now()
        val events = listOf(today, today.plusDays(1), today.plusDays(2)).flatMap { day ->
            widgetEventsOnDay(app.repository.widgetEvents(day) + app.calendarSync.widgetItems(day), day)
        }.distinctBy { it.id to it.date }
        val now = LocalDateTime.now()
        val next = next(events, now)
        if (next == null) {
            manager.cancel(TAG, 0)
            // Nothing in the next day: look again in a few hours (data changes ask sooner).
            schedule(context, now.plusHours(6))
            return@withLock
        }
        val format = reminderTimeFormat(context)
        val event = next.event
        val soon = leaveSoon(next, now)
        // Before "Leave soon", it's on again when that starts (bug hunt 30); otherwise when the countdown is over.
        // Bug hunt 31: also at midnight (so "tomorrow" doesn't stay after it), and at least every 6 hours (a later event
        // whose leaving time comes sooner, if nothing changed meanwhile).
        val again = listOf(next.leaveAt?.minusMinutes(SOON_MINUTES)?.takeIf { !soon } ?: next.countdownTo,
            today.plusDays(1).atStartOfDay(), now.plusHours(6)).min()
        // Swiped away (Android 14 lets ongoing ones go): stays away until the next one (bug hunt 30).
        // With the travel buffer (bug hunt 31): one added or changed brings it back.
        val key = "${event.id}|${event.title}|${next.start}|${event.bufferBeforeMinutes}"
        if (dismissed(context) == key) { manager.cancel(TAG, 0); schedule(context, again); return@withLock }
        fun dayWord(at: LocalDateTime) = dayWord(at, today)
        val text = buildList {
            add(next.start.toLocalTime().label(format, context) + dayWord(next.start))
            if (event.location.isNotBlank()) add(event.location)
            if (soon) next.leaveAt?.let { add("leave at ${it.toLocalTime().label(format, context)}") }
        }.joinToString(" · ")
        val title = when {
            next.leaveAt == null -> "Up next: "
            soon -> "Leave soon for "
            else -> "Leave at ${next.leaveAt.toLocalTime().label(format, context)}${dayWord(next.leaveAt)} for "
        } + event.title.ifBlank { "Untitled" }
        val zone = ZoneId.systemDefault()
        val open = PendingIntent.getActivity(context, REQUEST_CODE, openPlannerIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val delete = PendingIntent.getBroadcast(context, REQUEST_CODE + 1,
            Intent(context, UpNextReceiver::class.java).setAction(ACTION_DISMISSED).putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, UP_NEXT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            // No event words on the lock screen, as with the reminders (bug hunt 30).
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicReminder(context, UP_NEXT_CHANNEL_ID, "Up next", ""))
            .setDeleteIntent(delete)
            .setWhen(next.countdownTo.atZone(zone).toInstant().toEpochMilli())
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
        try { manager.notify(TAG, 0, notification) }
        catch (e: SecurityException) { android.util.Log.w("UpNext", "Notifications are off", e) }
        // On again when the countdown is over (to the event itself after leaving time, then the one after).
        schedule(context, again)
    }

    internal const val ACTION_DISMISSED = "io.github.baslawson.planner.UP_NEXT_DISMISSED"
    internal const val EXTRA_KEY = "key"
    private const val PREFS = "up_next"
    private const val KEY_DISMISSED = "dismissed"

    private fun dismissed(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DISMISSED, null)

    internal fun setDismissed(context: Context, key: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DISMISSED, key).apply()
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(context, REQUEST_CODE,
        Intent(context, UpNextReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    // Exact where Android allows it, and also while the phone dozes, as the reminders are (bug hunt 30: a 60-second
    // window could come 10 minutes late, with the countdown past zero meanwhile).
    private fun schedule(context: Context, at: LocalDateTime) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        setReminderAlarm(alarms, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pendingIntent(context))
    }
}

class UpNextReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == UpNext.ACTION_DISMISSED) { UpNext.setDismissed(context, intent.getStringExtra(UpNext.EXTRA_KEY)); return }
        val pending = goAsync()
        val app = context.applicationContext as ItineraryApp
        app.appScope.launch {
            try { UpNext.refresh(app) }
            catch (e: Exception) { android.util.Log.w("UpNext", "Couldn't update Up next", e) }
            finally { pending.finish() }
        }
    }
}
