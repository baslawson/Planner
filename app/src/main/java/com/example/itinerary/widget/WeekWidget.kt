package com.example.itinerary.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Log
import android.widget.RemoteViews
import com.example.itinerary.ItineraryApp
import com.example.itinerary.R
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.PlannerTask
import com.example.itinerary.data.widgetEventsOnDay
import com.example.itinerary.ui.label
import com.example.itinerary.ui.patternFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The week on the home screen (user, 15 Oct): the next seven days from today, a line for each, with what's on it (events
 * with their times, the tasks due, the bills due). A tap on a day opens it in Planner. As many entries per day as the
 * widget's height has room for, the rest counted. Refreshed with the Today widget (TodayWidget.requestUpdate), at midnight
 * and by Android's periodic update.
 */
class WeekWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action !in actions) return
        val pending = goAsync()
        scope.launch {
            try { lock.withLock { updateAll(context) } }
            catch (e: Exception) { Log.e("WeekWidget", "Widget refresh failed", e) }
            finally { pending.finish() }
        }
    }

    companion object {
        const val REFRESH = "com.example.itinerary.widget.WEEK_REFRESH"
        private const val DAYS = 7
        private val actions = setOf(REFRESH, AppWidgetManager.ACTION_APPWIDGET_UPDATE, AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED,
            AppWidgetManager.ACTION_APPWIDGET_ENABLED, AppWidgetManager.ACTION_APPWIDGET_RESTORED, Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lock = Mutex()

        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            if (manager.getAppWidgetIds(ComponentName(context, WeekWidget::class.java)).isNotEmpty())
                context.sendBroadcast(Intent(context, WeekWidget::class.java).setAction(REFRESH))
        }

        private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 4802,
            Intent(context, WeekWidget::class.java).setAction(REFRESH), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        /**
         * One day's line: "9:00 Dentist · All day Holiday · ☐ Tax return · Bill: Rent", at most [max] entries and then
         * "+2 more". Overdue tasks go on today's line. Empty when nothing is on.
         */
        internal fun dayText(events: List<ItineraryItem>, tasks: List<PlannerTask>, day: LocalDate, today: LocalDate, max: Int,
                             time: (LocalTime) -> String): String {
            val entries = events.map { event ->
                when {
                    event.category == "Bills" -> "Bill: ${event.title}" + if (event.paid) " (paid)" else ""
                    event.startTime != null && event.date == day -> "${time(event.startTime)} ${event.title}"
                    else -> event.title
                }
            } + tasks.filter { !it.done && it.dueDate != null && (it.dueDate == day || day == today && it.dueDate < today) }
                .map { "☐ ${it.title.ifBlank { "Untitled task" }}" }
            if (entries.isEmpty()) return ""
            return if (entries.size <= max) entries.joinToString(" · ") else entries.take(max).joinToString(" · ") + " · +${entries.size - max} more"
        }

        private suspend fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WeekWidget::class.java))
            val alarm = context.getSystemService(AlarmManager::class.java)
            if (ids.isEmpty()) { alarm.cancel(refreshIntent(context)); return }
            val app = context.applicationContext as ItineraryApp
            val today = LocalDate.now()
            val days = (0 until DAYS).map { today.plusDays(it.toLong()) }
            val events = days.associateWith { day -> widgetEventsOnDay(app.repository.widgetEvents(day) + app.calendarSync.widgetItems(day), day) }
            val tasks = app.repository.widgetTasks(days.last())
            val format = app.settings.timeFormat.value
            ids.forEach { id ->
                // Bug hunt 29 N6: the height it has now: the larger one in portrait, the smaller in landscape.
                val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                val height = manager.getAppWidgetOptions(id).getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300)
                val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
                // About 16 dp a line of entries: one entry per line, two lines at least.
                val perDay = (((height - 64) / DAYS) / (16 * fontScale)).toInt().coerceIn(1, 4)
                val views = RemoteViews(context.packageName, R.layout.widget_week).apply {
                    setTextViewText(R.id.widget_week_heading, "Week · ${today.format(patternFormatter("d MMM"))}–${days.last().format(patternFormatter("d MMM"))}")
                    setOnClickPendingIntent(R.id.widget_week_refresh, refreshIntent(context))
                    removeAllViews(R.id.widget_week_days)
                    days.forEach { day ->
                        val text = dayText(events.getValue(day), tasks, day, today, perDay * 2) { it.label(format, context) }
                        val name = if (day == today) "Today" else day.format(patternFormatter("EEE d"))
                        addView(R.id.widget_week_days, RemoteViews(context.packageName, R.layout.widget_week_day).apply {
                            setTextViewText(R.id.widget_day_name, if (day == today) SpannableString(name).apply {
                                setSpan(StyleSpan(Typeface.BOLD), 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) } else name)
                            setTextViewText(R.id.widget_day_entries, text.ifEmpty { "–" })
                            setInt(R.id.widget_day_entries, "setMaxLines", perDay)
                            setContentDescription(R.id.widget_day, "${day.format(patternFormatter("EEEE d MMMM"))}: ${text.ifEmpty { "nothing planned" }}")
                            setOnClickPendingIntent(R.id.widget_day, TodayWidget.openIntent(context, day))
                        })
                    }
                }
                manager.updateAppWidget(id, views)
            }
            alarm.setWindow(AlarmManager.RTC, today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                10 * 60_000L, refreshIntent(context))
        }
    }
}
