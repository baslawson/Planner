package com.example.itinerary.ui

import android.net.Uri
import kotlinx.coroutines.async
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.itinerary.data.AppFont
import com.example.itinerary.data.Attachment
import com.example.itinerary.data.BackupException
import com.example.itinerary.data.BackupManager
import com.example.itinerary.data.DateFormatChoice
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.NextcloudAccount
import com.example.itinerary.data.NextcloudBackup
import com.example.itinerary.data.NextcloudBackups
import com.example.itinerary.data.PlanColors
import com.example.itinerary.data.PlanEvent
import com.example.itinerary.data.Reminder
import com.example.itinerary.data.Repository
import com.example.itinerary.data.SettingsRepository
import com.example.itinerary.data.StagedBackup
import com.example.itinerary.data.ThemeMode
import com.example.itinerary.data.TimeFormat
import com.example.itinerary.data.Trip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

data class NextcloudUiState(
    val server: String = NextcloudAccount.DEFAULT_SERVER,
    val username: String = NextcloudAccount.DEFAULT_USERNAME,
    val connected: Boolean = false,
    val status: String? = null,
    val error: Boolean = false,
    val lastBackup: String? = null,
    val backups: List<NextcloudBackup>? = null,
    val folderPath: String = NextcloudAccount.FOLDER,
)

class TripsViewModel(
    private val repo: Repository,
    private val settings: SettingsRepository,
    private val backup: BackupManager,
    private val nextcloud: NextcloudBackups,
) : ViewModel() {
    suspend fun deleteEvents(ids: Set<Long>) = viewModelScope.async {
        repo.deleteEventsWithUndo(ids)
    }.await()


    val tasks = repo.tasks.stateInWhileVisible(viewModelScope, emptyList())
    val backupStatus = backup.status.state
    init {
        viewModelScope.launch {
            try { nextcloud.savedAccount()?.lastBackup?.let(backup.status::seedLegacy) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Existing credential storage may be temporarily unavailable. */ }
        }
    }
    private var cloudAccount: NextcloudAccount? = null
    private var cloudLoaded = false
    private val _cloud = MutableStateFlow(NextcloudUiState())
    val cloud: StateFlow<NextcloudUiState> = _cloud.asStateFlow()

    fun loadNextcloud() {
        if (cloudLoaded) return
        runBackup("Loading connection...", cloudAction = true) {
            val account = nextcloud.savedAccount()
            cloudAccount = account
            if (account != null) showAccount(account, "Connection saved on this device.")
            cloudLoaded = true
            null
        }
    }

    fun connectNextcloud(server: String, username: String, password: String) =
        runBackup("Checking Nextcloud...", cloudAction = true) {
            val account = nextcloud.connect(server, username, password)
            cloudAccount = account
            cloudLoaded = true
            showAccount(account, "Connected. Backups will be saved in ${account.folderPath}.")
            null
        }

    fun disconnectNextcloud() = runBackup("Disconnecting...", cloudAction = true) {
        nextcloud.disconnect()
        cloudAccount = null
        cloudLoaded = true
        _cloud.value = _cloud.value.copy(connected = false, backups = null, lastBackup = null,
            status = "Disconnected. Your files on Nextcloud are unchanged.", error = false)
        null
    }

    fun saveNextcloudFolder(path: String) = runBackup("Saving backup folder...", cloudAction = true) {
        val account = cloudAccount ?: throw BackupException("Connect to Nextcloud first.")
        val updated = nextcloud.saveFolder(account, path)
        cloudAccount = updated
        showAccount(updated, "Backup folder saved. Existing backups stay in their original folder.")
        null
    }

    fun uploadNextcloud() = runBackup("Uploading backup...", cloudAction = true, uploading = true) {
        val account = cloudAccount ?: throw BackupException("Connect to Nextcloud first.")
        val updated = nextcloud.upload(account)
        cloudAccount = updated
        showAccount(updated, "Backup uploaded to ${updated.folderPath}.")
        null
    }

    fun listNextcloud() = runBackup("Loading backups...", cloudAction = true) {
        val account = cloudAccount ?: throw BackupException("Connect to Nextcloud first.")
        _cloud.value = _cloud.value.copy(backups = null)
        val files = nextcloud.list(account)
        _cloud.value = _cloud.value.copy(backups = files, error = false,
            status = if (files.isEmpty()) "No Planner backups found in ${account.folderPath}." else "Choose a backup to review before restoring.")
        null
    }

    fun importNextcloud(file: NextcloudBackup) = runBackup("Downloading and checking backup...", cloudAction = true) {
        val account = cloudAccount ?: throw BackupException("Connect to Nextcloud first.")
        _stagedImport.value?.let(backup::discard)
        _stagedImport.value = null
        _stagedImport.value = nextcloud.stage(account, file)
        null
    }

    private fun showAccount(account: NextcloudAccount, message: String) {
        _cloud.value = NextcloudUiState(account.server.toString().trimEnd('/'), account.username,
            connected = true, status = message, lastBackup = account.lastBackup, folderPath = account.folderPath)
    }

    // What the backup is doing right now ("Exporting..."), or null when idle. Blocks the screen while set.
    private val _backupBusy = MutableStateFlow<String?>(null)
    val backupBusy: StateFlow<String?> = _backupBusy.asStateFlow()

    // A chosen backup file waiting for the user to confirm replacing everything.
    private val _stagedImport = MutableStateFlow<StagedBackup?>(null)
    val stagedImport: StateFlow<StagedBackup?> = _stagedImport.asStateFlow()

    // Result of the last backup action, shown once by the screen and then cleared.
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    fun backupMessageShown() {
        _backupMessage.value = null
    }

    fun exportTo(uri: Uri) = runBackup("Saving backup...") {
        backup.export(uri)
        "Backup saved"
    }

    // Reads and checks the file; the user still has to confirm before anything is replaced.
    fun chooseImport(uri: Uri) = runBackup("Reading backup...") {
        _stagedImport.value?.let(backup::discard)
        _stagedImport.value = backup.stage(uri)
        null
    }

    fun confirmImport() {
        val staged = _stagedImport.value ?: return
        _stagedImport.value = null
        runBackup("Restoring...") {
            backup.restore(staged)
            "Backup restored"
        }
    }

    fun cancelImport() {
        _stagedImport.value?.let(backup::discard)
        _stagedImport.value = null
    }

    private fun runBackup(busy: String, cloudAction: Boolean = false, uploading: Boolean = false, block: suspend () -> String?) {
        // Set synchronously: two fast taps cannot start concurrent exports or replace a staged import.
        if (_backupBusy.value != null || _stagedImport.value != null) return
        _backupBusy.value = busy
        viewModelScope.launch {
            try {
                if (cloudAction) _cloud.value = _cloud.value.copy(status = null, error = false)
                _backupMessage.value = block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                if (cloudAction) {
                    _cloud.value = _cloud.value.copy(error = true, status = e.message +
                        if (uploading) " Upload wasn't confirmed; refresh the backup list before retrying." else "")
                } else _backupMessage.value = e.message
            } catch (e: Exception) {
                if (cloudAction) _cloud.value = _cloud.value.copy(error = true,
                    status = "Couldn't complete the Nextcloud operation. Check your connection and available storage, then try again.")
                else _backupMessage.value = "Couldn't complete the backup operation. Please check your data before trying again."
            } finally {
                _backupBusy.value = null
            }
        }
    }

    override fun onCleared() {
        _stagedImport.value?.let(backup::discard)
    }

    // Null until the database has answered, so the screen can wait instead of flashing "No plans yet".
    val trips: StateFlow<List<Trip>?> = repo.trips
        .stateInWhileVisible(viewModelScope, null)

    // The events each plan card is made of, keyed by plan id and still in date then time order. Grouped here
    // rather than on the screen so it happens once per database change instead of once per recomposition.
    val eventsByPlan: StateFlow<Map<Long, List<PlanEvent>>> = repo.planEvents
        .map { events -> events.groupBy { it.tripId } }
        .stateInWhileVisible(viewModelScope, emptyMap())

    // Every event of every plan, for the agenda; null until the database has answered, like [trips].
    val agendaEvents: StateFlow<List<PlanEvent>?> = repo.planEvents
        .stateInWhileVisible(viewModelScope, null)

    val showBillsSummary = settings.showBillsSummary
    val agendaRange = settings.agendaRange
    fun setAgendaRange(range: com.example.itinerary.data.AgendaRange) = settings.setAgendaRange(range)
    suspend fun moveToTomorrow(id: Long) = repo.moveToTomorrow(id)

    // New events get their internal storage owner in the repository.
    suspend fun saveEvent(
        item: ItineraryItem,
        added: List<Attachment>,
        removed: List<Attachment>,
        addedReminders: List<Reminder>,
        removedReminders: List<Reminder>,
        options: com.example.itinerary.data.EventSaveOptions = com.example.itinerary.data.EventSaveOptions(),
    ) {
        repo.saveItem(item, added, removed, addedReminders, removedReminders, options)
    }

    val themeMode: StateFlow<ThemeMode> = settings.themeMode

    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)

    val timeFormat: StateFlow<TimeFormat> = settings.timeFormat

    fun setTimeFormat(format: TimeFormat) = settings.setTimeFormat(format)

    // Plans that start soon can be shown at the top of the list (see UpcomingPlans); this is display only.
    val upcomingOnTop: StateFlow<Boolean> = settings.upcomingOnTop

    fun setUpcomingOnTop(enabled: Boolean) = settings.setUpcomingOnTop(enabled)

    // How see-through the big + button is, in percent.
    val addButtonSeeThrough: StateFlow<Int> = settings.addButtonSeeThrough

    fun setAddButtonSeeThrough(percent: Int) = settings.setAddButtonSeeThrough(percent)

    // The colour of all headings in the app, as an ARGB value.
    val headingColor: StateFlow<Int> = settings.headingColor

    fun setHeadingColor(argb: Int) = settings.setHeadingColor(argb)

    // The typeface used for all text.
    val appFont: StateFlow<AppFont> = settings.appFont

    fun setAppFont(font: AppFont) = settings.setAppFont(font)

    // How a whole day is written in headings (see DateFormatChoice).
    val dateFormat: StateFlow<DateFormatChoice> = settings.dateFormat

    fun setDateFormat(choice: DateFormatChoice) = settings.setDateFormat(choice)

    // How big all text is, in percent.
    val textSizePercent: StateFlow<Int> = settings.textSizePercent

    fun setTextSizePercent(percent: Int) = settings.setTextSizePercent(percent)

    val upcomingDays: StateFlow<Int> = settings.upcomingDays

    fun setUpcomingDays(days: Int) = settings.setUpcomingDays(days)

    fun reorder(orderedIds: List<Long>) {
        viewModelScope.launch { repo.reorderTrips(orderedIds) }
    }

    // What the event form (used by "Add quick event") needs.
    private val categories = CategoryState(repo, settings, viewModelScope)
    val categoryCounts: StateFlow<Map<String, Int>> = categories.counts
    val hiddenCategories: StateFlow<Set<String>> = categories.hidden

    fun removeCategories(names: Set<String>) = categories.remove(names)

    fun showCategory(name: String) = categories.show(name)

    // "Add quick event": the event's name becomes a new one-day plan on the event's day, which holds the event.
    // The plan takes the plan colour the fewest plans use, like the New plan dialog; it can be edited afterwards.
    fun createQuickEvent(
        item: ItineraryItem,
        added: List<Attachment>,
        addedReminders: List<Reminder>,
        onCreated: (String) -> Unit,
    ) {
        val plan = Trip(
            name = item.title,
            destination = "",
            startDate = item.date,
            endDate = item.date,
            colorIndex = PlanColors.next(trips.value.orEmpty().usedPaletteColors()),
        )
        viewModelScope.launch {
            repo.createPlanWithEvent(plan, item, added, addedReminders)
            onCreated(plan.name)
        }
    }

    fun moveToTop(id: Long) = reorder(listOf(id) + trips.value.orEmpty().map { it.id }.filter { it != id })

    fun moveToBottom(id: Long) = reorder(trips.value.orEmpty().map { it.id }.filter { it != id } + id)

    fun save(trip: Trip) {
        viewModelScope.launch { repo.saveTrip(trip) }
    }

    fun delete(trip: Trip) {
        viewModelScope.launch { repo.deleteTrip(trip) }
    }

    private val _deletingPlans = MutableStateFlow(false)
    val deletingPlans = _deletingPlans.asStateFlow()
    private val _planDeletionError = MutableStateFlow<String?>(null)
    val planDeletionError = _planDeletionError.asStateFlow()

    fun clearPlanDeletionError() { _planDeletionError.value = null }

    fun deletePlans(plans: List<Trip>, onDeleted: () -> Unit) {
        if (_deletingPlans.value || plans.isEmpty()) return
        _deletingPlans.value = true
        _planDeletionError.value = null
        viewModelScope.launch {
            try {
                repo.deleteTrips(plans)
                onDeleted()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _planDeletionError.value = "Couldn't finish deleting the selected plans. Check Manage plans before trying again."
            } finally {
                _deletingPlans.value = false
            }
        }
    }
}
