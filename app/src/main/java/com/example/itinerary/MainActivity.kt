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
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.graphics.Color as ComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
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

/** The files or photos a share carries (one with ACTION_SEND, several with ACTION_SEND_MULTIPLE): content: URIs only, as [opensCalendarFile]. */
internal fun sharedStreams(intent: Intent?): List<android.net.Uri> {
    val uris = when (intent?.action) {
        Intent.ACTION_SEND -> listOfNotNull(androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java).orEmpty()
        else -> emptyList()
    }
    return uris.filter { opensCalendarFile(it.scheme) }.distinct()
}

/**
 * Shares of files copied and ready, in the order they came, each as the text and subject a share opens with (SharedFiles.key,
 * or just the text when none of the files could be read). A window on screen takes the first once it has no share open
 * or waiting, so one never replaces another (whose files would then be left behind).
 */
internal object SharedFileArrivals {
    val queue = kotlinx.coroutines.flow.MutableStateFlow<List<Pair<String, String?>>>(emptyList())
    // R18-D2: the staging ids queued in this process, so a share of files is never offered twice.
    private val queued: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Queues [share]; false if it's a share of files queued before. */
    fun add(share: Pair<String, String?>): Boolean {
        com.example.itinerary.data.SharedFiles.idOf(share.first)?.let { if (!queued.add(it)) return false }
        queue.update { it + share }
        return true
    }
    // R18-D2: shares of files an earlier process left (SharedFilesStore.recover), looked for once per process; a new share
    // is copied only after that.
    private var recovery: kotlinx.coroutines.Job? = null
    @Synchronized fun recoverOnce(start: () -> kotlinx.coroutines.Job): kotlinx.coroutines.Job = recovery ?: start().also { recovery = it }
    suspend fun recovered() { synchronized(this) { recovery }?.join() }
    /** Takes [share] off the queue; false if another window took it first. */
    fun take(share: Pair<String, String?>): Boolean {
        while (true) {
            val now = queue.value
            if (now.firstOrNull() != share) return false
            if (queue.compareAndSet(now, now.drop(1))) return true
        }
    }
}

/**
 * Whether Planner opens on the chosen start screen (Settings → Open Planner on): only when it is opened itself, from the
 * launcher or Recents. Anything else opens what it is for (SR-1): a notification tap ([fromNotification]) carries
 * nothing else to say so, and a widget, shortcut, share or calendar file has its own action.
 */
internal fun opensOnStartScreen(action: String?, fromNotification: Boolean): Boolean =
    (action == null || action == Intent.ACTION_MAIN) && !fromNotification

/**
 * What an intent asks Planner to open: shared text (with its subject), a widget task, a calendar file, an entry
 * shortcut, a widget day, a note (its reminder's notification, U-13). Null: not asked. [U] is android.net.Uri (generic so the rules run in a plain JVM test).
 */
internal data class LaunchFields<U>(
    val sharedText: String? = null,
    val sharedSubject: String? = null,
    val widgetTaskId: String? = null,
    val calendarUri: U? = null,
    val entryAction: String? = null,
    val widgetDate: java.time.LocalDate? = null,
    val noteId: String? = null,
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
        noteId = incoming.noteId ?: noteId,
    )
}

class MainActivity : ComponentActivity() {
    private var sharedText by mutableStateOf<String?>(null)
    private var sharedSubject by mutableStateOf<String?>(null)
    private var widgetTaskId by mutableStateOf<String?>(null)
    private var calendarUri by mutableStateOf<android.net.Uri?>(null)
    private var entryAction by mutableStateOf<String?>(null)
    private var widgetDate by mutableStateOf<java.time.LocalDate?>(null)
    private var noteId by mutableStateOf<String?>(null)
    private fun launchFields() = LaunchFields(sharedText, sharedSubject, widgetTaskId, calendarUri, entryAction, widgetDate, noteId)
    private fun launchFieldsOf(intent: Intent?): LaunchFields<android.net.Uri> {
        // Q-5: only as much as Planner can use is kept, since it goes into the saved state.
        // A share of files (with or without text) is read by importSharedFiles instead. Text that looks like a share of
        // files (SharedFiles.MARKER) is refused, so another app can't make Planner open one.
        // H17-A3: text that comes under another type (an app sharing a link as "*/*") is read as text too.
        val sharedText = if (intent?.action == Intent.ACTION_SEND && sharedStreams(intent).isEmpty() &&
            (intent.type?.startsWith("text/") == true || !intent.getCharSequenceExtra(Intent.EXTRA_TEXT).isNullOrBlank()))
            com.example.itinerary.data.SharedText.kept(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: "")
                .takeUnless { it.startsWith(com.example.itinerary.data.SharedFiles.MARKER) } else null
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
            noteId = if (intent?.action == com.example.itinerary.reminders.NoteReminderReceiver.OPEN_NOTE)
                intent.getStringExtra(com.example.itinerary.reminders.NoteReminderReceiver.EXTRA_NOTE_ID) else null,
        )
    }
    private fun setLaunchFields(fields: LaunchFields<android.net.Uri>) {
        sharedText = fields.sharedText
        sharedSubject = fields.sharedSubject
        widgetTaskId = fields.widgetTaskId
        calendarUri = fields.calendarUri
        entryAction = fields.entryAction
        widgetDate = fields.widgetDate
        noteId = fields.noteId
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
        outState.putString("noteId", noteId)
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
        importSharedFiles(intent)
    }

    // Files or photos shared to Planner: copied into the attachment store now, while Android lets Planner read them, then
    // offered like a shared text (SharedFiles). In the app's scope, so turning the phone meanwhile doesn't stop it.
    private fun importSharedFiles(intent: Intent?) {
        val uris = sharedStreams(intent)
        val app = application as ItineraryApp
        // H17-A3: a share with nothing Planner can take (files it can't open, as a file: path, and no text) says so,
        // rather than Planner just opening.
        if (intent != null && uris.isEmpty() && (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) &&
            intent.type?.startsWith("text/") != true && intent.getCharSequenceExtra(Intent.EXTRA_TEXT).isNullOrBlank())
            android.widget.Toast.makeText(app, "Planner can't add what was shared.", android.widget.Toast.LENGTH_LONG).show()
        if (intent == null || uris.isEmpty()) return
        val caption = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?.let(com.example.itinerary.data.SharedText::kept)?.takeIf { it.isNotBlank() }
        val subject = com.example.itinerary.data.SharedText.keptSubject(intent.getStringExtra(Intent.EXTRA_SUBJECT))
        android.widget.Toast.makeText(app, if (uris.size == 1) "Adding the file to Planner…" else "Adding ${uris.size} files to Planner…",
            android.widget.Toast.LENGTH_SHORT).show()
        app.appScope.launch {
            SharedFileArrivals.recovered()
            val store = com.example.itinerary.data.SharedFilesStore(app)
            val id = java.util.UUID.randomUUID().toString()
            // R18-D2: noted as being copied from the start, and again after each file, so the copies of a share Android ends
            // half way are released at the next start (all but the one being copied then: AttachmentStore names it).
            fun note(files: List<com.example.itinerary.data.Attachment>) =
                runCatching { store.write(id, com.example.itinerary.data.SharedFiles.Staged(caption, subject, files), copying = true) }
            val files = mutableListOf<com.example.itinerary.data.Attachment>()
            note(files)
            for (uri in uris.take(com.example.itinerary.data.SharedFiles.MAX_FILES)) app.attachmentStore.import(uri)?.let { files += it; note(files) }
            val missed = uris.size - files.size
            val staged = if (files.isEmpty()) null else runCatching {
                id.also { store.write(it, com.example.itinerary.data.SharedFiles.Staged(caption, subject, files)) }
            }.getOrNull()
            if (staged == null) { runCatching { app.repository.releaseTaskFiles(files.map { it.fileName }) }; runCatching { store.delete(id) } }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                when {
                    staged == null -> android.widget.Toast.makeText(app, "Couldn't read the shared ${if (uris.size == 1) "file" else "files"}" +
                        if (caption != null) ". Its text opens on its own." else ".", android.widget.Toast.LENGTH_LONG).show()
                    missed > 0 -> android.widget.Toast.makeText(app, "Couldn't add $missed of the shared files.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            // With none of the files read, text that came with them is still offered, as a text share would be.
            if (staged != null) SharedFileArrivals.add(com.example.itinerary.data.SharedFiles.key(staged) to null)
            else if (caption != null && !caption.startsWith(com.example.itinerary.data.SharedFiles.MARKER)) SharedFileArrivals.add(caption to subject)
        }
    }

    // Tapping a ringing alarm's notification acknowledges it (with that ring's own token, A6-7).
    private fun stopAlarmIfRequested(intent: Intent?) {
        AlarmService.stopFromTap(this, intent?.getStringExtra(AlarmService.EXTRA_STOP_ALARM))
    }

    // While Planner is on screen, changes to the phone's calendars are read in (see CalendarSync.refreshPhone).
    private var stopWatchingCalendars: (() -> Unit)? = null

    private val appLock get() = (application as ItineraryApp).appLock

    // App lock: anything Planner opens itself (file picker, camera scanner, browser, Settings' lock confirmation) goes
    // through one of these two, so leaving for it is not treated like going to the home screen (see AppLockRule).
    // A plain startActivity on this activity (or on a screen's LocalContext, which wraps it) ends up here as well; one on
    // the application context does not, so external screens are opened from the screen's context (Q-7).
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
        autoSync?.cancel(); autoSync = null
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (appLock.checkOnStart())
            startActivity(Intent(this, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        com.example.itinerary.widget.TodayWidget.requestUpdate(this)
        AlarmService.stopIfUnseen(this)
        // Once per process, and again when the exact-alarm permission (just granted in system settings, say) or the
        // time zone changed: see RescheduleOnOpen.
        lifecycleScope.launch(Dispatchers.IO) {
            // First the reminders whose alarms Android dropped (a force stop), before they are set again.
            com.example.itinerary.reminders.showMissedReminders(applicationContext, afterBoot = false)
            val app = application as ItineraryApp
            val state = com.example.itinerary.data.RescheduleOnOpen.state(app.reminderScheduler.canScheduleExact(), java.time.ZoneId.systemDefault())
            // In the app's scope: leaving the screen doesn't cut it short; it counts as done only once it finished.
            if (com.example.itinerary.data.RescheduleOnOpen.due(state)) app.appScope.launch(Dispatchers.IO) {
                app.repository.rescheduleAllReminders()
                com.example.itinerary.data.RescheduleOnOpen.done(state)
            }
        }
        // The phone's ticked calendars and links; Nextcloud is checked by AutoSync while Planner is on screen, if it's on.
        (application as ItineraryApp).let { app ->
            app.appScope.launch { app.calendarSync.syncIfDue(nextcloud = false) }
            // A new version on GitHub, at most once a day (Settings → Updates); it only asks.
            app.appScope.launch { app.updates.checkIfDue() }
            autoSync = lifecycleScope.launch { app.settings.autoSync.collectLatest { on -> if (on) app.autoSync.watch(applicationContext) } }
        }
    }

    private var autoSync: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // R18-D2: once per process, before any share is copied: shares of files an earlier process left are offered again
        // (or, caught half way through copying, released). Then those left a week ago or more: their files go, unless used.
        (application as ItineraryApp).let { app -> SharedFileArrivals.recoverOnce { app.appScope.launch(Dispatchers.IO) {
            val store = com.example.itinerary.data.SharedFilesStore(app)
            runCatching {
                val found = store.recover()
                found.offer.forEach { SharedFileArrivals.add(com.example.itinerary.data.SharedFiles.key(it) to null) }
                if (found.release.isNotEmpty()) app.repository.releaseTaskFiles(found.release)
            }
            runCatching { store.sweep().takeIf { it.isNotEmpty() }?.let { app.repository.releaseTaskFiles(it) } }
        } } }
        if (actsOnLaunchIntent(intent?.flags ?: 0, savedInstanceState == null)) { stopAlarmIfRequested(intent); readWidgetIntent(intent); importSharedFiles(intent) }
        // Shared files, once copied, open like a shared text, in the window on screen: only a started one takes them, so a
        // window closing as a new share opens Planner (CLEAR_TASK) can't take them away with it. One at a time, once this
        // window has no share open or waiting.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(SharedFileArrivals.queue, androidx.compose.runtime.snapshotFlow { sharedText }) { queue, open ->
                    queue.firstOrNull().takeIf { open == null }
                }.collect { share ->
                    // Not a window that is closing (a new share opened Planner with CLEAR_TASK): it is still started a moment.
                    if (share != null && !isFinishing && SharedFileArrivals.take(share)) {
                        setLaunchFields(launchFields().mergedWith(LaunchFields(sharedText = share.first, sharedSubject = share.second)))
                        // R18-D2: taken, so it isn't offered again after Planner restarts.
                        com.example.itinerary.data.SharedFiles.idOf(share.first)?.let { id -> (application as ItineraryApp).appScope.launch(Dispatchers.IO) {
                            runCatching { com.example.itinerary.data.SharedFilesStore(application).markTaken(id) }
                        } }
                    }
                }
            }
        }
        if (savedInstanceState != null) {
            sharedText = savedInstanceState.getString("sharedText")
            sharedSubject = savedInstanceState.getString("sharedSubject")
            widgetTaskId = savedInstanceState.getString("widgetTaskId")
            calendarUri = androidx.core.os.BundleCompat.getParcelable(savedInstanceState, "calendarUri", android.net.Uri::class.java)
            entryAction = savedInstanceState.getString("entryAction")?.takeIf(EntryShortcuts::accepts)
            widgetDate = savedInstanceState.getString("widgetDate")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            noteId = savedInstanceState.getString("noteId")
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
                ItineraryTheme(appTheme = appTheme, darkTheme = darkTheme, font = appFont, textSizePercent = textSizePercent) { Box { AppNav(sharedText = sharedText, sharedSubject = sharedSubject, onSharedOpened = { sharedText = null; sharedSubject = null; intent?.action = Intent.ACTION_MAIN }, widgetTaskId = widgetTaskId, onWidgetTaskOpened = { widgetTaskId = null; intent?.action = Intent.ACTION_MAIN }, calendarUri = calendarUri, onCalendarOpened = { calendarUri = null; intent?.action = Intent.ACTION_MAIN }, widgetDate = widgetDate, onWidgetOpened = { widgetDate = null }, entryAction = entryAction, onEntryOpened = { entryAction = null; intent?.action = Intent.ACTION_MAIN }, noteId = noteId, onNoteOpened = { noteId = null; intent?.action = Intent.ACTION_MAIN })
                    // Under the lock screen (LockActivity), so Planner's content never shows in the moment before it.
                    val locked by appLock.locked.collectAsStateWithLifecycle()
                    if (locked) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
                    else com.example.itinerary.ui.UpdateDialog()
                } }
            }
        }
    }
}
