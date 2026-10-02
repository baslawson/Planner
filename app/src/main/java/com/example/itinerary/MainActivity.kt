package com.example.itinerary

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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
import com.example.itinerary.ui.AppLockRule
import com.example.itinerary.ui.AppNav
import com.example.itinerary.ui.LocalDateFormat
import com.example.itinerary.ui.LocalHeadingColor
import com.example.itinerary.ui.LocalScrollBar
import com.example.itinerary.ui.ScrollBarStyle
import com.example.itinerary.ui.LocalTimeFormat
import com.example.itinerary.ui.is24Hour
import com.example.itinerary.ui.theme.ItineraryTheme

/**
 * Whether the intent that opened Planner is acted on (stop the ringing alarm; open the shared text, widget task or date,
 * calendar file or shortcut). Only on a fresh launch: a rotation ([savedStateNull] false) and a reopen from Recents
 * (FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY: Android re-delivers the task's old root intent) must not stop a newer alarm or
 * replay an old share.
 */
internal fun actsOnLaunchIntent(flags: Int, savedStateNull: Boolean): Boolean =
    savedStateNull && (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0

/**
 * Whether a calendar file opened from another app (ACTION_VIEW) is read: content: URIs only (Q-6). Planner has no
 * storage permission, so a file: path it can read is in practice one of its own private files (the event draft, say),
 * which another app must not be able to make it open.
 */
internal fun opensCalendarFile(scheme: String?): Boolean = scheme == "content"

/**
 * What an intent asks Planner to open: shared text (with its subject), a widget task, a calendar file, an entry
 * shortcut, a widget day. Null: not asked. [U] is android.net.Uri (generic so the rules run in a plain JVM test).
 */
internal data class LaunchFields<U>(
    val sharedText: String? = null,
    val sharedSubject: String? = null,
    val widgetTaskId: String? = null,
    val calendarUri: U? = null,
    val entryAction: String? = null,
    val widgetDate: java.time.LocalDate? = null,
) {
    /**
     * These fields after a later intent ([incoming]) arrives while Planner is open: what it carries replaces the old
     * value, the rest stay (E5: a reminder notification used to close an open share review for good). A share's subject
     * goes with its text.
     */
    fun mergedWith(incoming: LaunchFields<U>): LaunchFields<U> = LaunchFields(
        sharedText = incoming.sharedText ?: sharedText,
        sharedSubject = if (incoming.sharedText != null) incoming.sharedSubject else sharedSubject,
        widgetTaskId = incoming.widgetTaskId ?: widgetTaskId,
        calendarUri = incoming.calendarUri ?: calendarUri,
        entryAction = incoming.entryAction ?: entryAction,
        widgetDate = incoming.widgetDate ?: widgetDate,
    )
}

class MainActivity : ComponentActivity() {
    private var sharedText by mutableStateOf<String?>(null)
    private var sharedSubject by mutableStateOf<String?>(null)
    private var widgetTaskId by mutableStateOf<String?>(null)
    private var calendarUri by mutableStateOf<android.net.Uri?>(null)
    private var entryAction by mutableStateOf<String?>(null)
    private var widgetDate by mutableStateOf<java.time.LocalDate?>(null)
    private fun launchFields() = LaunchFields(sharedText, sharedSubject, widgetTaskId, calendarUri, entryAction, widgetDate)
    private fun launchFieldsOf(intent: Intent?): LaunchFields<android.net.Uri> {
        // Q-5: only as much as Planner can use is kept, since it goes into the saved state.
        val sharedText = if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain")
            com.example.itinerary.data.SharedText.kept(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: "") else null
        return LaunchFields(
            sharedText = sharedText,
            sharedSubject = if (sharedText != null) com.example.itinerary.data.SharedText.keptSubject(intent?.getStringExtra(Intent.EXTRA_SUBJECT)) else null,
            widgetTaskId = if (intent?.action == com.example.itinerary.widget.TodayWidget.OPEN_TASK) intent.getStringExtra("task_id") else null,
            calendarUri = if (intent?.action == Intent.ACTION_VIEW && opensCalendarFile(intent.data?.scheme)) intent.data else null,
            entryAction = intent?.action?.takeIf(EntryShortcuts::accepts),
            widgetDate = when (intent?.action) {
                com.example.itinerary.widget.TodayWidget.OPEN_TODAY -> java.time.LocalDate.now()
                com.example.itinerary.widget.TodayWidget.OPEN_DATE -> runCatching { java.time.LocalDate.parse(intent.getStringExtra("widget_date")) }.getOrNull()
                else -> null
            },
        )
    }
    private fun setLaunchFields(fields: LaunchFields<android.net.Uri>) {
        sharedText = fields.sharedText
        sharedSubject = fields.sharedSubject
        widgetTaskId = fields.widgetTaskId
        calendarUri = fields.calendarUri
        entryAction = fields.entryAction
        widgetDate = fields.widgetDate
    }
    // A fresh launch takes everything from its intent; a later intent (see onNewIntent) only what it carries.
    private fun readWidgetIntent(intent: Intent?) = setLaunchFields(launchFieldsOf(intent))
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("sharedText", sharedText)
        outState.putString("sharedSubject", sharedSubject)
        // U-N4: a recreation (or process death) keeps an open calendar-file review, widget task or unhandled tap too.
        outState.putString("widgetTaskId", widgetTaskId)
        outState.putParcelable("calendarUri", calendarUri)
        outState.putString("entryAction", entryAction)
        outState.putString("widgetDate", widgetDate?.toString())
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!actsOnLaunchIntent(intent.flags, savedStateNull = true)) return
        // E5: tapping a reminder or the widget must not close an open share review, calendar-file import or widget
        // task editor: only what this intent carries is replaced.
        setLaunchFields(launchFields().mergedWith(launchFieldsOf(intent)))
        stopAlarmIfRequested(intent)
    }

    // Tapping a ringing alarm's notification acknowledges it.
    private fun stopAlarmIfRequested(intent: Intent?) {
        if (intent?.getBooleanExtra(AlarmService.EXTRA_STOP_ALARM, false) == true) {
            stopService(Intent(this, AlarmService::class.java))
        }
    }

    // While Planner is on screen, changes to the phone's calendars are read in (see CalendarSync.refreshPhone).
    private var stopWatchingCalendars: (() -> Unit)? = null

    private val appLock get() = (application as ItineraryApp).appLock

    // App lock: anything Planner opens itself (file picker, camera scanner, browser, Settings' lock confirmation) goes
    // through one of these two, so leaving for it is not treated like going to the home screen (see AppLockRule).
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) =
        AppLockRule.ownTrip({ appLock.ownTripStarting = it }) { super.startActivityForResult(intent, requestCode, options) }

    override fun startIntentSenderForResult(intent: android.content.IntentSender, requestCode: Int, fillInIntent: Intent?,
                                            flagsMask: Int, flagsValues: Int, extraFlags: Int, options: Bundle?) =
        AppLockRule.ownTrip({ appLock.ownTripStarting = it }) {
            super.startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask, flagsValues, extraFlags, options)
        }

    override fun onResume() {
        super.onResume()
        appLock.onResumed()
        // Also after the calendar permission was just granted (the permission prompt only pauses the screen).
        if (stopWatchingCalendars == null) (application as ItineraryApp).let { app ->
            stopWatchingCalendars = app.phoneCalendars.watch { app.appScope.launch { app.calendarSync.refreshPhone() } }
        }
    }

    override fun onStop() {
        appLock.onStopped(isChangingConfigurations)
        stopWatchingCalendars?.invoke()
        stopWatchingCalendars = null
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (appLock.checkOnStart())
            startActivity(Intent(this, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        com.example.itinerary.widget.TodayWidget.requestUpdate(this)
        // Cheap and idempotent; picks up exact-alarm permission the user just granted in system settings.
        lifecycleScope.launch(Dispatchers.IO) {
            // First the reminders whose alarms Android dropped (a force stop), before they are set again.
            com.example.itinerary.reminders.showMissedReminders(applicationContext, afterBoot = false)
            (application as ItineraryApp).repository.rescheduleAllReminders()
        }
        // The phone's ticked calendars, and ticked Nextcloud calendars at most every 15 minutes (nothing without a login).
        (application as ItineraryApp).let { app -> app.appScope.launch { app.calendarSync.syncIfDue() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (actsOnLaunchIntent(intent?.flags ?: 0, savedInstanceState == null)) { stopAlarmIfRequested(intent); readWidgetIntent(intent) }
        if (savedInstanceState != null) {
            sharedText = savedInstanceState.getString("sharedText")
            sharedSubject = savedInstanceState.getString("sharedSubject")
            widgetTaskId = savedInstanceState.getString("widgetTaskId")
            calendarUri = androidx.core.os.BundleCompat.getParcelable(savedInstanceState, "calendarUri", android.net.Uri::class.java)
            entryAction = savedInstanceState.getString("entryAction")?.takeIf(EntryShortcuts::accepts)
            widgetDate = savedInstanceState.getString("widgetDate")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        }
        val settings = (application as ItineraryApp).settings
        // With App lock on, the recent-apps list shows a blank card instead of the last screen (Android 13 and later).
        if (android.os.Build.VERSION.SDK_INT >= 33) lifecycleScope.launch {
            appLock.enabled.collect { setRecentsScreenshotEnabled(!it) }
        }

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
            val scrollBarSeeThrough by settings.scrollBarSeeThrough.collectAsStateWithLifecycle()
            val dateFormat by settings.dateFormat.collectAsStateWithLifecycle()
            val appFont by settings.appFont.collectAsStateWithLifecycle()
            val textSizePercent by settings.textSizePercent.collectAsStateWithLifecycle()

            CompositionLocalProvider(
                LocalTimeFormat provides resolvedTimeFormat,
                LocalDateFormat provides dateFormat,
                LocalHeadingColor provides ComposeColor(headingColor),
                LocalScrollBar provides ScrollBarStyle(ComposeColor(scrollBarColor), scrollBarSeeThrough),
            ) {
                ItineraryTheme(appTheme = appTheme, darkTheme = darkTheme, font = appFont, textSizePercent = textSizePercent) { Box { AppNav(sharedText = sharedText, sharedSubject = sharedSubject, onSharedOpened = { sharedText = null; sharedSubject = null; intent?.action = Intent.ACTION_MAIN }, widgetTaskId = widgetTaskId, onWidgetTaskOpened = { widgetTaskId = null; intent?.action = Intent.ACTION_MAIN }, calendarUri = calendarUri, onCalendarOpened = { calendarUri = null; intent?.action = Intent.ACTION_MAIN }, widgetDate = widgetDate, onWidgetOpened = { widgetDate = null }, entryAction = entryAction, onEntryOpened = { entryAction = null; intent?.action = Intent.ACTION_MAIN })
                    // Under the lock screen (LockActivity), so Planner's content never shows in the moment before it.
                    val locked by appLock.locked.collectAsStateWithLifecycle()
                    if (locked) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
                } }
            }
        }
    }
}
