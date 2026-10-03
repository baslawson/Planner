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
            onDeletionFinished = { sendChanges() })
    }

    // Planner's changes go to Nextcloud a few seconds later, while "Sync changes automatically" is on (see AutoSync).
    private fun sendChanges() {
        if (!settings.autoSync.value) return
        calendarSync.requestSend(); taskSync.requestSend(); noteSync.request()
    }

    // New versions on GitHub (Settings → Updates); only the release app updates itself.
    val updates: com.example.itinerary.data.Updates by lazy {
        com.example.itinerary.data.Updates(com.example.itinerary.data.Updates.prefs(this),
            com.example.itinerary.data.ReleaseApi(okhttp3.OkHttpClient()),
            installed = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
            folder = java.io.File(cacheDir, "updates"), supported = packageName == com.example.itinerary.data.Updates.RELEASE_ID)
    }

    val autoSync: AutoSync by lazy { AutoSync(calendarSync, noteSync, appScope) }

    // Work that must finish even when the screen that started it closes or rotates (calendar sync).
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    val phoneCalendars: com.example.itinerary.data.AndroidPhoneCalendars by lazy { com.example.itinerary.data.AndroidPhoneCalendars(this) }

    // Other calendars shown read-only beside Planner's own events (Nextcloud on the backup login, the phone's, links), and
    // two-way sync with one Nextcloud calendar.
    val calendarSync: CalendarSync by lazy {
        CalendarSync(database, NextcloudAccountStore(this), onChanged = { com.example.itinerary.widget.TodayWidget.requestUpdate(this) },
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
        com.example.itinerary.data.NoteSync(database, NextcloudAccountStore(this), com.example.itinerary.data.NotesApi(okhttp3.OkHttpClient()),
            repository.asNoteStore(), com.example.itinerary.data.NoteSync.prefs(this),
            pendingDeleted = { repository.pendingDeletions.value.flatMap { it.notes }.mapTo(HashSet()) { it.id } }, scope = appScope)
    }

    val settings: SettingsRepository by lazy { SettingsRepository(this) { com.example.itinerary.widget.TodayWidget.requestUpdate(this) } }

    val backup: BackupManager by lazy { BackupManager(this, repository, attachmentStore, settings, calendarSync, taskSync) }

    val nextcloudBackups: NextcloudBackups by lazy { NextcloudBackups(this, backup) }

    override fun onCreate() {
        super.onCreate()
        // A Gemini or OpenAI key saved by an earlier version is deleted, not left behind.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.example.itinerary.data.RemovedAiData.remove(this@ItineraryApp) } }
        createReminderChannel(this)
        // Calendar sync in the background follows the setting (and a restored backup's).
        appScope.launch { settings.calendarBackgroundHours.collect { CalendarBackground.schedule(this@ItineraryApp, it) } }
    }
}
