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
const val EVENING_CHANNEL_ID = "evening_summary"

/**
 * The morning summary (user, 14 Oct): once a day, at the time chosen in Settings, one notification with the day ahead:
 * today's events (Planner's and the other calendars' shown in it), the tasks due today or overdue, and the bills due today
 * still unpaid. Nothing planned, nothing shown. Its alarm is inexact (a 10-minute window, no "Alarms & reminders"
 * needed) and is set again after each summary, on start, after a reboot, an update or a time change (BootReceiver), and
 * when the setting changes.
 *
 * The evening summary (user, 15 Oct) is the same for tomorrow, at its own time (8 pm unless chosen), with tomorrow's
 * events, the tasks due tomorrow and the bills due tomorrow. Both have buttons (user, 15 Oct): Open, and Mark done when
 * exactly one task is listed, Mark paid when exactly one bill is; the notification then shows what is left.
 */
object MorningSummary {
    enum class Kind(val requestCode: Int, val tag: String, val channel: String) {
        MORNING(1_000_000_101, "morning-summary", MORNING_CHANNEL_ID),
        EVENING(1_000_000_102, "evening-summary", EVENING_CHANNEL_ID),
    }

    private const val WINDOW_MS = 10 * 60_000L
    // An inbox-style notification shows about this many lines; the rest are counted on the last one.
    internal const val MAX_LINES = 6
    const val ACTION_SHOW = "com.example.itinerary.SUMMARY_SHOW"
    const val ACTION_DONE = "com.example.itinerary.SUMMARY_DONE"
    const val ACTION_PAID = "com.example.itinerary.SUMMARY_PAID"
    const val EXTRA_KIND = "summary_kind"
    const val EXTRA_TASK = "summary_task"
    const val EXTRA_BILL = "summary_bill"
    const val EXTRA_DAY = "summary_day"

    /** What a summary says: [taskIds] and [billIds] are the tasks and bills it lists, for its buttons. */
    class Content(val title: String, val lines: List<String>, val taskIds: List<String> = emptyList(), val billIds: List<Long> = emptyList())

    /**
     * What the summary says for [day], or null when there is nothing to tell. [time] writes a clock time; [tomorrow] makes
     * it the evening's ("Tomorrow: …"), whose tasks are only those due that day (today's overdue ones were the morning's).
     */
    fun content(events: List<ItineraryItem>, tasks: List<PlannerTask>, day: LocalDate, tomorrow: Boolean = false,
                today: LocalDate = if (tomorrow) day.minusDays(1) else day, time: (LocalTime) -> String): Content? {
        val bills = events.filter { it.category == "Bills" && !it.paid }
        val others = events.filter { it.category != "Bills" }
        val due = if (tomorrow) tasks.filter { it.dueDate == day } else tasks
        if (others.isEmpty() && due.isEmpty() && bills.isEmpty()) return null
        fun count(n: Int, one: String, many: String) = if (n == 0) null else "$n ${if (n == 1) one else many}"
        // Said as the day is now: an evening summary that comes just after midnight is about today (bug hunt 29).
        val title = (if (day == today.plusDays(1)) "Tomorrow: " else "Today: ") + listOfNotNull(count(others.size, "event", "events"), count(due.size, "task", "tasks"),
            count(bills.size, "bill due", "bills due")).joinToString(", ")
        val lines = others.map { event ->
            val start = event.startTime
            when {
                event.date < day -> "Continues: ${event.title}"
                start == null -> "All day: ${event.title}"
                else -> "${time(start)}  ${event.title}"
            }
        } + due.map { "☐ ${it.title.ifBlank { "Untitled task" }}${if (it.dueDate != null && it.dueDate < day) " (overdue)" else ""}" } +
            bills.map { "Bill due: ${it.title}" }
        val shown = if (lines.size <= MAX_LINES) lines else lines.take(MAX_LINES - 1) + "and ${lines.size - MAX_LINES + 1} more"
        return Content(title, shown, due.map { it.id }, bills.map { it.id })
    }

    /** When the next summary is due: today at [at] if that's still ahead and today's hasn't been shown, else tomorrow. */
    fun nextAt(now: LocalDateTime, at: LocalTime, shownToday: Boolean): LocalDateTime =
        if (!shownToday && now.toLocalTime() < at) now.toLocalDate().atTime(at) else now.toLocalDate().plusDays(1).atTime(at)

    private class Setting(val on: Boolean, val at: LocalTime, val shownOn: String?)
    private fun setting(context: Context, kind: Kind): Setting {
        val settings = (context.applicationContext as ItineraryApp).settings
        return when (kind) {
            Kind.MORNING -> Setting(settings.morningSummary.value, settings.morningSummaryAt.value, settings.morningSummaryShownOn)
            Kind.EVENING -> Setting(settings.eveningSummary.value, settings.eveningSummaryAt.value, settings.eveningSummaryShownOn)
        }
    }
    private fun markShown(context: Context, kind: Kind, day: LocalDate) {
        val settings = (context.applicationContext as ItineraryApp).settings
        when (kind) {
            Kind.MORNING -> settings.morningSummaryShownOn = day.toString()
            Kind.EVENING -> settings.eveningSummaryShownOn = day.toString()
        }
    }

    /** Sets both summaries' next alarms, or cancels one that is off. */
    fun schedule(context: Context) = Kind.entries.forEach { schedule(context, it) }

    // Bug hunt 29 N3: the alarm carries the day it was set for ([EXTRA_DAY]), so one that comes after midnight (a late
    // evening time, the alarm's window, Doze) still shows that day's summary.
    private fun pendingIntent(context: Context, kind: Kind, day: LocalDate? = null) = PendingIntent.getBroadcast(context, kind.requestCode,
        Intent(context, MorningSummaryReceiver::class.java).setAction(ACTION_SHOW).putExtra(EXTRA_KIND, kind.name)
            .apply { day?.let { putExtra(EXTRA_DAY, it.toString()) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun schedule(context: Context, kind: Kind) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val setting = setting(context, kind)
        if (!setting.on) { alarms.cancel(pendingIntent(context, kind)); return }
        val zone = ZoneId.systemDefault()
        val next = nextAt(LocalDateTime.now(zone), setting.at, setting.shownOn == LocalDate.now(zone).toString())
        runCatching { alarms.setWindow(AlarmManager.RTC_WAKEUP, next.atZone(zone).toInstant().toEpochMilli(), WINDOW_MS, pendingIntent(context, kind, next.toLocalDate())) }
            .onFailure { android.util.Log.w("MorningSummary", "Couldn't set the ${kind.name.lowercase()} summary's alarm", it) }
    }

    /**
     * The day an alarm set for [alarmDay] stands for: the evening's, that day even when it comes just after midnight (not
     * when more than a day gone: the phone was off); the morning's, always today (yesterday's morning is no use now).
     */
    internal fun alarmDay(kind: Kind, alarmDay: LocalDate?, today: LocalDate): LocalDate = if (kind == Kind.MORNING) today else
        alarmDay?.takeIf { it <= today && it >= today.minusDays(1) } ?: today

    /**
     * Shows [kind]'s summary for the day its alarm was set for ([alarmDay]; today when not known), once a day, if it is on
     * and there is something to say. Bug hunt 29 N4: marked shown only once it has been worked out, so a failed read
     * doesn't lose the day.
     */
    suspend fun show(context: Context, kind: Kind, alarmDay: LocalDate? = null) {
        val day = alarmDay(kind, alarmDay, LocalDate.now())
        val setting = setting(context, kind)
        if (!setting.on || setting.shownOn == day.toString()) return
        post(context, kind, day, update = false)
        markShown(context, kind, day)
    }

    // The day [kind]'s summary set off on [alarmDay] is about.
    private fun summaryDay(kind: Kind, alarmDay: LocalDate) = if (kind == Kind.EVENING) alarmDay.plusDays(1) else alarmDay

    // Builds [kind]'s summary as things are now and shows it; [update]: after a button, quietly, or taken away when
    // nothing is left.
    private suspend fun post(context: Context, kind: Kind, alarmDay: LocalDate, update: Boolean) {
        val app = context.applicationContext as ItineraryApp
        val day = summaryDay(kind, alarmDay)
        val events = widgetEventsOnDay(app.repository.widgetEvents(day) + app.calendarSync.widgetItems(day), day)
        val format = reminderTimeFormat(context)
        val tasks = app.repository.widgetTasks(day)
        val content = content(events, tasks, day, tomorrow = kind == Kind.EVENING, today = LocalDate.now()) { it.label(format, context) }
        val manager = NotificationManagerCompat.from(context)
        if (content == null) { if (update) manager.cancel(kind.tag, 0); return }
        // Bug hunt 29 N2: no Mark done for a task still waiting on another (it can't be done yet).
        val doable = content.taskIds.singleOrNull()?.takeIf { it !in app.repository.widgetBlockedTasks(tasks) }
        fun action(action: String, extras: Intent.() -> Unit, code: Int) = PendingIntent.getBroadcast(context, kind.requestCode + code,
            Intent(context, MorningSummaryReceiver::class.java).setAction(action).putExtra(EXTRA_KIND, kind.name)
                .putExtra(EXTRA_DAY, alarmDay.toString()).apply(extras),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(context, kind.requestCode, openPlannerIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, kind.channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.lines.take(2).joinToString(" · "))
            .setStyle(NotificationCompat.InboxStyle().also { style -> content.lines.forEach(style::addLine) })
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, "Open", open)
            .apply {
                doable?.let { id -> addAction(0, "Mark done", action(ACTION_DONE, { putExtra(EXTRA_TASK, id) }, 10)) }
                content.billIds.singleOrNull()?.let { id -> addAction(0, "Mark paid", action(ACTION_PAID, { putExtra(EXTRA_BILL, id) }, 20)) }
            }
            .build()
        try { manager.notify(kind.tag, 0, notification) }
        catch (e: SecurityException) { android.util.Log.w("MorningSummary", "Notifications are off", e) }
    }

    /**
     * A summary's button: marks the task done or the bill paid, then shows what is left. Bug hunt 29 N2: says so when it
     * can't (deleted meanwhile, paid in part, ...), and a payment gets the Undo a bill reminder's Mark paid has.
     */
    suspend fun act(context: Context, kind: Kind, intent: Intent) {
        val repository = (context.applicationContext as ItineraryApp).repository
        val alarmDay = alarmDay(kind, intent.getStringExtra(EXTRA_DAY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, LocalDate.now())
        val said = try {
            when (intent.action) {
                ACTION_DONE -> intent.getStringExtra(EXTRA_TASK)?.let { repository.setTaskDone(it, true); "Task done" }
                ACTION_PAID -> intent.getLongExtra(EXTRA_BILL, 0L).takeIf { it > 0 }?.let { id ->
                    repository.setPaid(id, true)
                    repository.pendingPayments.value.lastOrNull { it.before.id == id && it.paid }?.let { change ->
                        BillPaymentReceiver.showUndo(context, id, change.token, change.before.title)
                    }
                    "Bill marked paid"
                }
                else -> null
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            android.util.Log.w("MorningSummary", "Couldn't act on the summary", e)
            (e as? com.example.itinerary.data.PaymentUpdateException)?.message ?: e.message?.takeIf { it.isNotBlank() }
                ?: "Couldn't do that. Please try in Planner."
        }
        said?.let { text -> kotlinx.coroutines.withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show() } }
        post(context, kind, alarmDay, update = true)
    }
}

class MorningSummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = runCatching { MorningSummary.Kind.valueOf(intent.getStringExtra(MorningSummary.EXTRA_KIND) ?: "MORNING") }.getOrNull() ?: return
        val day = intent.getStringExtra(MorningSummary.EXTRA_DAY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (intent.action == MorningSummary.ACTION_DONE || intent.action == MorningSummary.ACTION_PAID) MorningSummary.act(context, kind, intent)
                else MorningSummary.show(context, kind, day)
            }
            catch (e: Exception) { android.util.Log.w("MorningSummary", "Couldn't show the ${kind.name.lowercase()} summary", e) }
            finally {
                MorningSummary.schedule(context)
                pending.finish()
            }
        }
    }
}
