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
import com.example.itinerary.data.PlannerTask
import com.example.itinerary.data.widgetEventsOnDay
import com.example.itinerary.ui.label
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

const val MORNING_CHANNEL_ID = "morning_summary"

/**
 * The morning summary (user, 14 Oct): once a day, at the time chosen in Settings, one notification with the day ahead:
 * today's events (Planner's and the other calendars' shown in it), the tasks due today or overdue, and the bills due today
 * still unpaid. Nothing planned, nothing shown. Its alarm is inexact (a 10-minute window, no "Alarms & reminders"
 * needed) and is set again after each summary, on start, after a reboot, an update or a time change (BootReceiver), and
 * when the setting changes.
 */
object MorningSummary {
    private const val REQUEST_CODE = 1_000_000_101
    private const val WINDOW_MS = 10 * 60_000L
    private const val TAG = "morning-summary"
    // An inbox-style notification shows about this many lines; the rest are counted on the last one.
    internal const val MAX_LINES = 6

    class Content(val title: String, val lines: List<String>)

    /** What the summary says for [today], or null when there is nothing to tell. [time] writes a clock time. */
    fun content(events: List<ItineraryItem>, tasks: List<PlannerTask>, today: LocalDate, time: (LocalTime) -> String): Content? {
        val bills = events.filter { it.category == "Bills" && !it.paid }
        val others = events.filter { it.category != "Bills" }
        if (others.isEmpty() && tasks.isEmpty() && bills.isEmpty()) return null
        fun count(n: Int, one: String, many: String) = if (n == 0) null else "$n ${if (n == 1) one else many}"
        val title = "Today: " + listOfNotNull(count(others.size, "event", "events"), count(tasks.size, "task", "tasks"),
            count(bills.size, "bill due", "bills due")).joinToString(", ")
        val lines = others.map { event ->
            val start = event.startTime
            when {
                event.date < today -> "Continues: ${event.title}"
                start == null -> "All day: ${event.title}"
                else -> "${time(start)}  ${event.title}"
            }
        } + tasks.map { "☐ ${it.title.ifBlank { "Untitled task" }}${if (it.dueDate != null && it.dueDate < today) " (overdue)" else ""}" } +
            bills.map { "Bill due: ${it.title}" }
        val shown = if (lines.size <= MAX_LINES) lines else lines.take(MAX_LINES - 1) + "and ${lines.size - MAX_LINES + 1} more"
        return Content(title, shown)
    }

    /** When the next summary is due: today at [at] if that's still ahead and today's hasn't been shown, else tomorrow. */
    fun nextAt(now: LocalDateTime, at: LocalTime, shownToday: Boolean): LocalDateTime =
        if (!shownToday && now.toLocalTime() < at) now.toLocalDate().atTime(at) else now.toLocalDate().plusDays(1).atTime(at)

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(context, REQUEST_CODE,
        Intent(context, MorningSummaryReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Sets the next summary's alarm, or cancels it when the summary is off. */
    fun schedule(context: Context) {
        val settings = (context.applicationContext as ItineraryApp).settings
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        if (!settings.morningSummary.value) { alarms.cancel(pendingIntent(context)); return }
        val zone = ZoneId.systemDefault()
        val next = nextAt(LocalDateTime.now(zone), settings.morningSummaryAt.value, settings.morningSummaryShownOn == LocalDate.now(zone).toString())
        runCatching { alarms.setWindow(AlarmManager.RTC_WAKEUP, next.atZone(zone).toInstant().toEpochMilli(), WINDOW_MS, pendingIntent(context)) }
            .onFailure { android.util.Log.w("MorningSummary", "Couldn't set the morning summary's alarm", it) }
    }

    /** Shows today's summary, once a day, if it is on and there is something to say. */
    suspend fun show(context: Context) {
        val app = context.applicationContext as ItineraryApp
        val settings = app.settings
        val today = LocalDate.now()
        if (!settings.morningSummary.value || settings.morningSummaryShownOn == today.toString()) return
        val events = widgetEventsOnDay(app.repository.widgetEvents(today) + app.calendarSync.widgetItems(today), today)
        val format = reminderTimeFormat(context)
        val content = content(events, app.repository.widgetTasks(today), today) { it.label(format, context) }
        settings.morningSummaryShownOn = today.toString()
        content ?: return
        val open = PendingIntent.getActivity(context, REQUEST_CODE, openPlannerIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, MORNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.lines.take(2).joinToString(" · "))
            .setStyle(NotificationCompat.InboxStyle().also { style -> content.lines.forEach(style::addLine) })
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try { NotificationManagerCompat.from(context).notify(TAG, 0, notification) }
        catch (e: SecurityException) { android.util.Log.w("MorningSummary", "Notifications are off", e) }
    }
}

class MorningSummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { MorningSummary.show(context) }
            catch (e: Exception) { android.util.Log.w("MorningSummary", "Couldn't show the morning summary", e) }
            finally {
                MorningSummary.schedule(context)
                pending.finish()
            }
        }
    }
}
