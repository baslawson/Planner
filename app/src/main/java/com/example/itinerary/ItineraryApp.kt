package com.example.itinerary

import android.app.Application
import kotlinx.coroutines.launch
import androidx.room.Room
import com.example.itinerary.data.AppDatabase
import com.example.itinerary.data.asNoteStore
import com.example.itinerary.data.AttachmentStore
import com.example.itinerary.data.BackupManager
import com.example.itinerary.data.CalendarSync
import com.example.itinerary.data.NextcloudAccountStore
import com.example.itinerary.data.asPlannerStore
import com.example.itinerary.data.asTaskStore
import com.example.itinerary.data.NextcloudBackups
import com.example.itinerary.data.Repository
import com.example.itinerary.data.SettingsRepository
import com.example.itinerary.reminders.ReminderScheduler
import com.example.itinerary.reminders.createReminderChannel

class ItineraryApp : Application() {
    // Simple manual DI. Swap for Hilt once the app grows.
    private val attachmentStoreDelegate = lazy { AttachmentStore(this) }
    val attachmentStore: AttachmentStore by attachmentStoreDelegate

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && attachmentStoreDelegate.isInitialized())
            attachmentStore.clearThumbnails()
    }

    val appLock: com.example.itinerary.ui.AppLock by lazy { com.example.itinerary.ui.AppLock(this) }

    val reminderScheduler: ReminderScheduler by lazy { ReminderScheduler(this) }

    // Internal so instrumented tests can put outside-calendar events in place without a server.
    internal val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "itinerary.db")
            .addMigrations(*com.example.itinerary.data.ALL_MIGRATIONS)
            .build()
    }

    val repository: Repository by lazy {
        Repository(database, attachmentStore, reminderScheduler,
            onChanged = { com.example.itinerary.widget.TodayWidget.requestUpdate(this); sendChanges() },
            onDeletionFinished = { sendChanges() }, shareScope = appScope)
    }

    // Planner's changes go to Nextcloud a few seconds later, while "Sync changes automatically" is on (see AutoSync).
    private fun sendChanges() {
        // Noted with the switch off too, so AutoSync's first check once it's on sends them, even while its checks back off
        // (CalendarSync.sendDue, unsentChange). Notes need no mark: a check compares them with what was last synced.
        calendarSync.markChanged(); taskSync.markChanged()
        if (!settings.autoSync.value) return
        calendarSync.requestSend(); taskSync.requestSend(); noteSync.request()
    }

    // New versions on GitHub (Settings → Updates); only the release app updates itself.
    val updates: com.example.itinerary.data.Updates by lazy {
        com.example.itinerary.data.Updates(com.example.itinerary.data.Updates.prefs(this),
            com.example.itinerary.data.ReleaseApi(http),
            installed = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
            folder = java.io.File(cacheDir, "updates"), supported = packageName == com.example.itinerary.data.Updates.RELEASE_ID &&
                com.example.itinerary.data.Updates.installable(android.os.Build.SUPPORTED_ABIS))
    }

    val autoSync: AutoSync by lazy { AutoSync(calendarSync, noteSync, appScope) }

    // DA-8: one HTTP client for the whole app, so calendar and notes sync to the same Nextcloud share connections (one
    // TLS handshake, one pool and dispatcher). Each user keeps its own settings: NextcloudClient and CalendarLinkClient
    // derive theirs from it (newBuilder: redirects off, their timeouts); notes and updates use it as it is (the defaults,
    // as their own OkHttpClient() had).
    val http: okhttp3.OkHttpClient by lazy { okhttp3.OkHttpClient() }

    // Work that must finish even when the screen that started it closes or rotates (calendar sync).
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    val phoneCalendars: com.example.itinerary.data.AndroidPhoneCalendars by lazy { com.example.itinerary.data.AndroidPhoneCalendars(this) }

    // Other calendars shown read-only beside Planner's own events (Nextcloud on the backup login, the phone's, links), and
    // two-way sync with one Nextcloud calendar.
    val calendarSync: CalendarSync by lazy {
        CalendarSync(database, NextcloudAccountStore(this), client = com.example.itinerary.data.NextcloudClient(http),
            linkClient = com.example.itinerary.data.CalendarLinkClient(http), onChanged = { com.example.itinerary.widget.TodayWidget.requestUpdate(this) },
            phone = phoneCalendars, scope = appScope, planner = repository.asPlannerStore(),
            pendingDeleted = { repository.pendingDeletions.value.flatMap { it.items }.mapTo(HashSet()) { it.id } })
            .also { it.tasks = taskSync }
    }

    // Two-way sync of Planner's tasks with one Nextcloud task list, read and sent alongside the calendars.
    val taskSync: com.example.itinerary.data.TaskSync by lazy {
        com.example.itinerary.data.TaskSync(database, NextcloudAccountStore(this), client = { calendarSync.client },
            store = repository.asTaskStore(), scope = appScope,
            pendingDeleted = { repository.pendingDeletions.value.flatMap { it.tasks }.mapTo(HashSet()) { it.id } },
            onChanged = { com.example.itinerary.widget.TodayWidget.requestUpdate(this) })
    }

    // Two-way sync of notes with the Nextcloud Notes app, on the same login; off until switched on in Notes.
    val noteSync: com.example.itinerary.data.NoteSync by lazy {
        com.example.itinerary.data.NoteSync(database, NextcloudAccountStore(this), com.example.itinerary.data.NotesApi(http),
            repository.asNoteStore(), com.example.itinerary.data.NoteSync.prefs(this),
            pendingDeleted = { repository.pendingDeletions.value.flatMap { it.notes }.mapTo(HashSet()) { it.id } }, scope = appScope)
    }

    val settings: SettingsRepository by lazy { SettingsRepository(this) { com.example.itinerary.widget.TodayWidget.requestUpdate(this) } }

    val backup: BackupManager by lazy { BackupManager(this, repository, attachmentStore, settings, calendarSync, taskSync, noteSync) }

    val nextcloudBackups: NextcloudBackups by lazy { NextcloudBackups(this, backup, client = com.example.itinerary.data.NextcloudClient(http)) }

    override fun onCreate() {
        super.onCreate()
        createReminderChannel(this)
        // RB-3: started by a reminder or the boot before the phone is first unlocked, the app's own storage (settings,
        // database) can't be read yet, so the rest waits for the unlock.
        if (com.example.itinerary.reminders.DirectBoot.isUnlocked(this)) startUnlocked()
        else {
            val unlocked = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    runCatching { unregisterReceiver(this) }
                    startUnlocked()
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(this, unlocked,
                android.content.IntentFilter(android.content.Intent.ACTION_USER_UNLOCKED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            // Unlocked in the meantime: the broadcast may have gone before the receiver was there.
            if (com.example.itinerary.reminders.DirectBoot.isUnlocked(this)) { runCatching { unregisterReceiver(unlocked) }; startUnlocked() }
        }
    }

    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun startUnlocked() {
        if (!started.compareAndSet(false, true)) return
        // A Gemini or OpenAI key saved by an earlier version is deleted, not left behind.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.example.itinerary.data.RemovedAiData.remove(this@ItineraryApp) } }
        // Calendar sync in the background follows the setting (and a restored backup's).
        // Logged, not thrown: started at the unlock, WorkManager may not be ready yet (it starts with the app's providers).
        // D6-5: then it is tried once more a little later, with the setting as it is by then.
        fun schedule(hours: Int) = runCatching { CalendarBackground.schedule(this@ItineraryApp, hours) }
            .onFailure { android.util.Log.w("ItineraryApp", "Couldn't schedule calendar sync", it) }
        appScope.launch { settings.calendarBackgroundHours.collect { hours ->
            if (schedule(hours).isFailure) appScope.launch {
                kotlinx.coroutines.delay(CALENDAR_SCHEDULE_RETRY_MS)
                schedule(settings.calendarBackgroundHours.value)
            }
        } }
    }

    private companion object {
        const val CALENDAR_SCHEDULE_RETRY_MS = 30_000L
    }
}
