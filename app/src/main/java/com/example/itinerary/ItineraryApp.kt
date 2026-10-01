package com.example.itinerary

import android.app.Application
import kotlinx.coroutines.launch
import androidx.room.Room
import com.example.itinerary.data.AppDatabase
import com.example.itinerary.data.AttachmentStore
import com.example.itinerary.data.BackupManager
import com.example.itinerary.data.CalendarSync
import com.example.itinerary.data.NextcloudAccountStore
import com.example.itinerary.data.asPlannerStore
import com.example.itinerary.data.NextcloudBackups
import com.example.itinerary.data.Repository
import com.example.itinerary.data.SettingsRepository
import com.example.itinerary.reminders.ReminderScheduler
import com.example.itinerary.reminders.createReminderChannel

class ItineraryApp : Application() {
    lateinit var quickAiClient: com.example.itinerary.data.QuickAiClient
        internal set
    lateinit var quickAiConnectionStore: com.example.itinerary.data.QuickAiConnectionStore
        internal set
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
            onChanged = { com.example.itinerary.widget.TodayWidget.requestUpdate(this); calendarSync.requestSend() },
            onDeletionFinished = { calendarSync.requestSend() })
    }

    // Work that must finish even when the screen that started it closes or rotates (calendar sync).
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    val phoneCalendars: com.example.itinerary.data.AndroidPhoneCalendars by lazy { com.example.itinerary.data.AndroidPhoneCalendars(this) }

    // Other calendars shown read-only beside Planner's own events (Nextcloud on the backup login, the phone's, links), and
    // two-way sync with one Nextcloud calendar.
    val calendarSync: CalendarSync by lazy {
        CalendarSync(database, NextcloudAccountStore(this), onChanged = { com.example.itinerary.widget.TodayWidget.requestUpdate(this) },
            phone = phoneCalendars, scope = appScope, planner = repository.asPlannerStore(),
            pendingDeleted = { repository.pendingDeletions.value.flatMap { it.items }.mapTo(HashSet()) { it.id } })
    }

    val settings: SettingsRepository by lazy { SettingsRepository(this) { com.example.itinerary.widget.TodayWidget.requestUpdate(this) } }

    val backup: BackupManager by lazy { BackupManager(this, repository, attachmentStore, settings, calendarSync) }

    val nextcloudBackups: NextcloudBackups by lazy { NextcloudBackups(this, backup) }

    override fun onCreate() {
        super.onCreate()
        quickAiClient = com.example.itinerary.data.QuickAiClient(featuresEnabled = settings.aiFeaturesEnabled)
        quickAiConnectionStore = com.example.itinerary.data.QuickAiConnectionStore(this)
        val aiStore = quickAiConnectionStore
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { aiStore.retireObsoleteAccess() }
        }
        createReminderChannel(this)
        // Calendar sync in the background follows the setting (and a restored backup's).
        appScope.launch { settings.calendarBackgroundHours.collect { CalendarBackground.schedule(this@ItineraryApp, it) } }
    }
}
