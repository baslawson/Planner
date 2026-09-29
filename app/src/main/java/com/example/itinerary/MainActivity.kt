package com.example.itinerary

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.graphics.Color as ComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.itinerary.data.ThemeMode
import com.example.itinerary.data.TimeFormat
import com.example.itinerary.reminders.AlarmService
import com.example.itinerary.ui.AppNav
import com.example.itinerary.ui.LocalDateFormat
import com.example.itinerary.ui.LocalHeadingColor
import com.example.itinerary.ui.LocalScrollBar
import com.example.itinerary.ui.ScrollBarStyle
import com.example.itinerary.ui.LocalTimeFormat
import com.example.itinerary.ui.is24Hour
import com.example.itinerary.ui.theme.ItineraryTheme

class MainActivity : ComponentActivity() {
    private var sharedText by mutableStateOf<String?>(null)
    private var sharedSubject by mutableStateOf<String?>(null)
    private var widgetTaskId by mutableStateOf<String?>(null)
    private var calendarUri by mutableStateOf<android.net.Uri?>(null)
    private var entryAction by mutableStateOf<String?>(null)
    private var widgetDate by mutableStateOf<java.time.LocalDate?>(null)
    private fun readWidgetIntent(intent: Intent?) {
        sharedText = if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain")
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: "" else null
        sharedSubject = if (sharedText != null) intent?.getStringExtra(Intent.EXTRA_SUBJECT) else null
        widgetTaskId = if (intent?.action == com.example.itinerary.widget.TodayWidget.OPEN_TASK) intent.getStringExtra("task_id") else null
        calendarUri = if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme in listOf("content", "file")) intent.data else null
        entryAction = intent?.action?.takeIf(EntryShortcuts::accepts)
        widgetDate = when (intent?.action) {
            com.example.itinerary.widget.TodayWidget.OPEN_TODAY -> java.time.LocalDate.now()
            com.example.itinerary.widget.TodayWidget.OPEN_DATE -> runCatching { java.time.LocalDate.parse(intent.getStringExtra("widget_date")) }.getOrNull()
            else -> null
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("sharedText", sharedText)
        outState.putString("sharedSubject", sharedSubject)
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readWidgetIntent(intent)
        stopAlarmIfRequested(intent)
    }

    // Tapping a ringing alarm's notification acknowledges it.
    private fun stopAlarmIfRequested(intent: Intent?) {
        if (intent?.getBooleanExtra(AlarmService.EXTRA_STOP_ALARM, false) == true) {
            stopService(Intent(this, AlarmService::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        com.example.itinerary.widget.TodayWidget.requestUpdate(this)
        // Cheap and idempotent; picks up exact-alarm permission the user just granted in system settings.
        lifecycleScope.launch(Dispatchers.IO) {
            (application as ItineraryApp).repository.rescheduleAllReminders()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only on a fresh launch: a rotation re-delivers the same intent and mustn't stop a newer alarm.
        if (savedInstanceState == null) { stopAlarmIfRequested(intent); readWidgetIntent(intent) }
        if (savedInstanceState != null) {
            sharedText = savedInstanceState.getString("sharedText")
            sharedSubject = savedInstanceState.getString("sharedSubject")
        }
        val settings = (application as ItineraryApp).settings

        setContent {
            val mode by settings.themeMode.collectAsStateWithLifecycle()
            val appTheme by settings.appTheme.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (mode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            // Status/navigation bar icons must follow the app's theme, not just the phone's,
            // otherwise choosing Dark on a light phone gives dark icons on a dark background.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        Color.argb(0xE6, 0xFF, 0xFF, 0xFF),
                        Color.argb(0x80, 0x1B, 0x1B, 0x1B),
                    ) { darkTheme },
                )
                onDispose {}
            }

            val timeFormat by settings.timeFormat.collectAsStateWithLifecycle()
            var use24Hour by remember(timeFormat) { mutableStateOf(timeFormat.is24Hour(this)) }
            // Returning from Android Settings must refresh labels even if no app data changed.
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                use24Hour = timeFormat.is24Hour(this)
            }
            val resolvedTimeFormat = if (use24Hour) TimeFormat.HOUR_24 else TimeFormat.HOUR_12

            val headingColor by settings.headingColor.collectAsStateWithLifecycle()
            val scrollBarColor by settings.scrollBarColor.collectAsStateWithLifecycle()
            val scrollBarBrightness by settings.scrollBarBrightness.collectAsStateWithLifecycle()
            val dateFormat by settings.dateFormat.collectAsStateWithLifecycle()
            val appFont by settings.appFont.collectAsStateWithLifecycle()
            val textSizePercent by settings.textSizePercent.collectAsStateWithLifecycle()

            CompositionLocalProvider(
                LocalTimeFormat provides resolvedTimeFormat,
                LocalDateFormat provides dateFormat,
                LocalHeadingColor provides ComposeColor(headingColor),
                LocalScrollBar provides ScrollBarStyle(ComposeColor(scrollBarColor), scrollBarBrightness),
            ) {
                ItineraryTheme(appTheme = appTheme, darkTheme = darkTheme, font = appFont, textSizePercent = textSizePercent) { AppNav(sharedText = sharedText, sharedSubject = sharedSubject, onSharedOpened = { sharedText = null; sharedSubject = null; intent?.action = Intent.ACTION_MAIN }, widgetTaskId = widgetTaskId, onWidgetTaskOpened = { widgetTaskId = null; intent?.action = Intent.ACTION_MAIN }, calendarUri = calendarUri, onCalendarOpened = { calendarUri = null; intent?.action = Intent.ACTION_MAIN }, widgetDate = widgetDate, onWidgetOpened = { widgetDate = null }, entryAction = entryAction, onEntryOpened = { entryAction = null; intent?.action = Intent.ACTION_MAIN }) }
            }
        }
    }
}
