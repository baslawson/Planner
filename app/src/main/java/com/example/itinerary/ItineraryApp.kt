package com.example.itinerary

import android.app.Application
import kotlinx.coroutines.launch
import androidx.room.Room
import com.example.itinerary.data.AppDatabase
import com.example.itinerary.data.AttachmentStore
import com.example.itinerary.data.BackupManager
import com.example.itinerary.data.NextcloudBackups
import com.example.itinerary.data.MIGRATION_1_2
import com.example.itinerary.data.MIGRATION_2_3
import com.example.itinerary.data.MIGRATION_3_4
import com.example.itinerary.data.MIGRATION_4_5
import com.example.itinerary.data.MIGRATION_5_6
import com.example.itinerary.data.MIGRATION_6_7
import com.example.itinerary.data.MIGRATION_7_8
import com.example.itinerary.data.MIGRATION_8_9
import com.example.itinerary.data.MIGRATION_10_11
import com.example.itinerary.data.MIGRATION_16_17
import com.example.itinerary.data.MIGRATION_15_16
import com.example.itinerary.data.MIGRATION_14_15
import com.example.itinerary.data.MIGRATION_13_14
import com.example.itinerary.data.MIGRATION_12_13
import com.example.itinerary.data.MIGRATION_11_12
import com.example.itinerary.data.MIGRATION_9_10
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

    val reminderScheduler: ReminderScheduler by lazy { ReminderScheduler(this) }

    val repository: Repository by lazy {
        val db = Room.databaseBuilder(this, AppDatabase::class.java, "itinerary.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, com.example.itinerary.data.MIGRATION_17_18, com.example.itinerary.data.MIGRATION_18_19, com.example.itinerary.data.MIGRATION_19_20, com.example.itinerary.data.MIGRATION_20_21, com.example.itinerary.data.MIGRATION_21_22, com.example.itinerary.data.MIGRATION_22_23)
            .build()
        Repository(db, attachmentStore, reminderScheduler) { com.example.itinerary.widget.TodayWidget.requestUpdate(this) }
    }

    val settings: SettingsRepository by lazy { SettingsRepository(this) { com.example.itinerary.widget.TodayWidget.requestUpdate(this) } }

    val backup: BackupManager by lazy { BackupManager(this, repository, attachmentStore, settings) }

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
    }
}
