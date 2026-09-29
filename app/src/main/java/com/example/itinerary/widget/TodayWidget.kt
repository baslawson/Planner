package com.example.itinerary.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.example.itinerary.ItineraryApp
import com.example.itinerary.MainActivity
import com.example.itinerary.R
import com.example.itinerary.data.PlannerTask
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.dayCount
import com.example.itinerary.data.dayNumber
import com.example.itinerary.data.TimeFormat
import com.example.itinerary.data.eventsOnDay
import com.example.itinerary.ui.durationLabel
import com.example.itinerary.ui.label
import com.example.itinerary.ui.shortLabel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId

class TodayWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action !in actions) return
        val pending = goAsync()
        scope.launch {
            try { lock.withLock {
                if (intent.action == COMPLETE_TASK) {
                    intent.getStringExtra("task_id")?.let { (context.applicationContext as ItineraryApp).repository.setTaskDone(it, true) }
                }
                updateAll(context)
            } }
            catch (e: Exception) {
                Log.e("TodayWidget", "Widget refresh failed", e)
                withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, e.message ?: "Could not update Planner. Open the app and try again.", android.widget.Toast.LENGTH_LONG).show() }
            }
            finally { pending.finish() }
        }
    }

    companion object {
        const val COMPLETE_TASK = "com.example.itinerary.widget.COMPLETE_TASK"
        const val OPEN_TASK = "com.example.itinerary.widget.OPEN_TASK"
        const val REFRESH = "com.example.itinerary.widget.REFRESH"
        const val OPEN_TODAY = "com.example.itinerary.widget.OPEN_TODAY"
        const val OPEN_DATE = "com.example.itinerary.widget.OPEN_DATE"
        private val actions = setOf(COMPLETE_TASK, REFRESH, AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED, AppWidgetManager.ACTION_APPWIDGET_ENABLED,
            AppWidgetManager.ACTION_APPWIDGET_DISABLED, AppWidgetManager.ACTION_APPWIDGET_DELETED,
            AppWidgetManager.ACTION_APPWIDGET_RESTORED, Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lock = Mutex()

        fun requestUpdate(context: Context) {
            // No work or process wake-up when the user has not placed any widgets.
            val manager = AppWidgetManager.getInstance(context)
            if (manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java)).isNotEmpty()) {
                context.sendBroadcast(Intent(context, TodayWidget::class.java).setAction(REFRESH))
            }
        }

        fun pin(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return manager.isRequestPinAppWidgetSupported &&
                manager.requestPinAppWidget(ComponentName(context, TodayWidget::class.java), null, null)
        }

        private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 4801,
            Intent(context, TodayWidget::class.java).setAction(REFRESH), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        private fun openIntent(context: Context, date: LocalDate? = null): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(if (date == null) OPEN_TODAY else OPEN_DATE)
                .setData(Uri.parse("planner-widget://${date ?: "today"}"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            date?.let { intent.putExtra("widget_date", it.toString()) }
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun completeTaskIntent(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
            Intent(context, TodayWidget::class.java).setAction(COMPLETE_TASK)
                .setData(Uri.Builder().scheme("planner-widget").authority("done").appendPath(id).build()).putExtra("task_id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        private fun taskIntent(context: Context, id: String): PendingIntent = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).setAction(OPEN_TASK)
                .setData(Uri.Builder().scheme("planner-widget").authority("task").appendPath(id).build()).putExtra("task_id", id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // Bounded native RemoteViews stay small even with hundreds of events. The footer always opens the complete day.
        fun render(context: Context, events: List<ItineraryItem>, today: LocalDate, format: TimeFormat, heightDp: Int, tasks: List<PlannerTask> = emptyList()): RemoteViews {
            return renderDay(context, eventsOnDay(events.filterNot { it.skipped }, today), today, format, heightDp, tasks.filter { !it.done && it.dueDate != null && it.dueDate <= today }.sortedBy { it.dueDate })
        }

        private fun renderDay(context: Context, shown: List<ItineraryItem>, today: LocalDate, format: TimeFormat, heightDp: Int, tasks: List<PlannerTask> = emptyList()): RemoteViews {
            val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
            val capacity = ((heightDp - 108 * fontScale) / (64 * fontScale)).toInt().coerceIn(1, 6)
            return RemoteViews(context.packageName, R.layout.widget_today).apply {
                setTextViewText(R.id.widget_heading, "Today · ${today.shortLabel()}")
                setOnClickPendingIntent(R.id.widget_heading, openIntent(context))
                setOnClickPendingIntent(R.id.widget_open, openIntent(context))
                setOnClickPendingIntent(R.id.widget_empty, openIntent(context))
                setOnClickPendingIntent(R.id.widget_refresh, refreshIntent(context))
                setViewVisibility(R.id.widget_empty, if (shown.isEmpty() && tasks.isEmpty()) View.VISIBLE else View.GONE)
                setViewVisibility(R.id.widget_rows, if (shown.isEmpty() && tasks.isEmpty()) View.GONE else View.VISIBLE)
                setTextViewText(R.id.widget_open, if (shown.isEmpty()) "Open today’s calendar" else "View all ${shown.size} entries ›")
                removeAllViews(R.id.widget_rows)
                val taskSlots = if (shown.isEmpty()) capacity else if (tasks.isEmpty()) 0 else maxOf(1, capacity / 2)
                tasks.take(taskSlots).forEach { task ->
                    addView(R.id.widget_rows, RemoteViews(context.packageName, R.layout.widget_task).apply {
                        setTextViewText(R.id.widget_task_title, task.title)
                        setTextViewText(R.id.widget_task_due, if (task.dueDate!! < today) "Overdue · ${task.dueDate.shortLabel()}" else "Task · Due today")
                        setContentDescription(R.id.widget_task_done, "Complete ${task.title}")
                        setOnClickPendingIntent(R.id.widget_task_done, completeTaskIntent(context, task.id))
                        setOnClickPendingIntent(R.id.widget_task_open, taskIntent(context, task.id))
                    })
                }
                setTextViewText(R.id.widget_heading, "Today · ${today.shortLabel()}" + if (tasks.isNotEmpty()) " · ${tasks.size} tasks" else "")
                if (tasks.isNotEmpty()) setTextViewText(R.id.widget_open, "Calendar · ${shown.size} entries ›")
                setTextViewText(R.id.widget_empty, "No events or tasks due today")
                shown.take((capacity - minOf(tasks.size, taskSlots)).coerceAtLeast(0)).forEach { event ->
                    val time = if (event.category == "Bills") "Bill due${event.startTime?.let { " · ${it.label(format, context)}" }.orEmpty()}" else event.startTime?.label(format, context) ?: "All day"
                    val end = if (event.category != "Bills" && event.startTime != null && event.durationMinutes != null) {
                        val finish = event.date.atTime(event.startTime).plusMinutes(event.durationMinutes.toLong())
                        "–${finish.toLocalTime().label(format, context)}${if (finish.toLocalDate() > event.date) " (+1 day)" else ""} · ${durationLabel(event.durationMinutes)}"
                    } else ""
                    val carried = if (event.endDate != null) " · Day ${event.dayNumber(today)} of ${event.dayCount}"
                        else if (event.date < today) " · From yesterday" else ""
                    addView(R.id.widget_rows, RemoteViews(context.packageName, R.layout.widget_event).apply {
                        setTextViewText(R.id.widget_event_title, event.title)
                        setTextViewText(R.id.widget_event_time, "$time$end$carried")
                        setContentDescription(R.id.widget_event, "${event.title}, $time$end$carried")
                        setOnClickPendingIntent(R.id.widget_event, openIntent(context, today))
                    })
                }
            }
        }

        private suspend fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
            val alarm = context.getSystemService(AlarmManager::class.java)
            if (ids.isEmpty()) { alarm.cancel(refreshIntent(context)); return }
            val app = context.applicationContext as ItineraryApp
            val today = LocalDate.now()
            val events = app.repository.widgetEvents(today)
            val tasks = app.repository.widgetTasks(today)
            ids.forEach { id ->
                val options = manager.getAppWidgetOptions(id)
                val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 220)
                manager.updateAppWidget(id, renderDay(context, events, today, app.settings.timeFormat.value, height, tasks))
            }
            // Inexact, non-waking midnight refresh plus the platform's periodic update. No new alarm permission.
            alarm.set(AlarmManager.RTC, today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), refreshIntent(context))
        }
    }
}
