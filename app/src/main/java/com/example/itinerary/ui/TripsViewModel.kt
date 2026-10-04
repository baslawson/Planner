package com.example.itinerary.ui

import android.net.Uri
import kotlinx.coroutines.async
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.itinerary.data.AppFont
import com.example.itinerary.data.Attachment
import com.example.itinerary.data.BackupException
import com.example.itinerary.data.BackupManager
import com.example.itinerary.data.BackupTransfer
import com.example.itinerary.data.TransferCancelledException
import com.example.itinerary.data.CalendarSync
import com.example.itinerary.data.toPlanEvent
import com.example.itinerary.data.DateFormatChoice
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.NextcloudAccount
import com.example.itinerary.data.NextcloudBackup
import com.example.itinerary.data.NextcloudBackups
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
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
    // Nextcloud calendars shown beside Planner's events; null in tests that don't need them.
    private val calendars: CalendarSync? = null,
) : ViewModel() {
    suspend fun deleteEvents(ids: Set<Long>, taskIds: Set<String> = emptySet()) = viewModelScope.async {
        repo.deleteEventsWithUndo(ids, taskIds)
    }.await()


    // Null until read once, like agendaEvents (A6-5: a restored task selection waits for it).
    val tasks: StateFlow<List<com.example.itinerary.data.PlannerTask>?> = repo.tasks.stateInWhileVisible(viewModelScope, null)
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
        // The Nextcloud calendars use the same login, so their list and downloaded events go too (phone calendars stay).
        calendars?.clearNextcloud()
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

    fun uploadNextcloud() = BackupTransfer().let { transfer -> runBackup("Uploading backup...", cloudAction = true, uploading = true,
        transfer = transfer, cancelled = "Upload cancelled. Refresh the backup list to see what is on Nextcloud.") {
        val account = cloudAccount ?: throw BackupException("Connect to Nextcloud first.")
        val updated = nextcloud.upload(account, transfer)
        cloudAccount = updated
        showAccount(updated, "Backup uploaded to ${updated.folderPath}.")
        null
    } }

    fun listNextcloud() = runBackup("Loading backups...", cloudAction = true) {
        val account = cloudAccount ?: throw BackupException("Connect to Nextcloud first.")
        _cloud.value = _cloud.value.copy(backups = null)
        val files = nextcloud.list(account)
        _cloud.value = _cloud.value.copy(backups = files, error = false,
            status = if (files.isEmpty()) "No Planner backups found in ${account.folderPath}." else "Choose a backup to review before restoring.")
        null
    }

    fun importNextcloud(file: NextcloudBackup) = BackupTransfer().let { transfer -> runBackup("Downloading and checking backup...",
        cloudAction = true, transfer = transfer, cancelled = "Download cancelled. Nothing was restored.") {
        val account = cloudAccount ?: throw BackupException("Connect to Nextcloud first.")
        _stagedImport.value?.let(backup::discard)
        _stagedImport.value = null
        _stagedImport.value = nextcloud.stage(account, file, transfer)
        null
    } }

    private fun showAccount(account: NextcloudAccount, message: String) {
        _cloud.value = NextcloudUiState(account.server.toString().trimEnd('/'), account.username,
            connected = true, status = message, lastBackup = account.lastBackup, folderPath = account.folderPath)
    }

    // What the backup is doing right now ("Exporting..."), or null when idle. Blocks the screen while set.
    private val _backupBusy = MutableStateFlow<String?>(null)
    val backupBusy: StateFlow<String?> = _backupBusy.asStateFlow()

    // S6-1: the Nextcloud upload or download under way, which its busy pop-up can stop (Cancel), or null.
    private val _transfer = MutableStateFlow<BackupTransfer?>(null)
    val backupTransfer: StateFlow<BackupTransfer?> = _transfer.asStateFlow()
    fun cancelBackupTransfer() {
        val transfer = _transfer.value ?: return
        _backupBusy.value = "Cancelling..."
        _transfer.value = null
        transfer.cancel()
    }

    // A chosen backup file waiting for the user to confirm replacing everything.
    private val _stagedImport = MutableStateFlow<StagedBackup?>(null)
    val stagedImport: StateFlow<StagedBackup?> = _stagedImport.asStateFlow()

    // A finished backup action worth a line under Backup in Settings ("Backup restored."), until Settings closes.
    private val _backupNote = MutableStateFlow<String?>(null)
    val backupNote: StateFlow<String?> = _backupNote.asStateFlow()
    fun backupNoteShown() { _backupNote.value = null }

    // A backup error, shown once by the screen and then cleared.
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    fun backupMessageShown() {
        _backupMessage.value = null
    }

    // No message: the Backup section's "Last successful backup" line shows it.
    fun exportTo(uri: Uri) = runBackup("Saving backup...") {
        backup.export(uri)
        null
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
            // Said on the screen it came from: under Backup in Settings, and in the Nextcloud pop-up if that's open.
            _backupNote.value = "Backup restored."
            _cloud.value = _cloud.value.copy(status = "Backup restored.", error = false)
            null
        }
    }

    fun cancelImport() {
        _stagedImport.value?.let(backup::discard)
        _stagedImport.value = null
    }

    // [transfer]: an upload or download the busy pop-up can stop; [cancelled] is then said in its place.
    private fun runBackup(busy: String, cloudAction: Boolean = false, uploading: Boolean = false, transfer: BackupTransfer? = null,
                          cancelled: String? = null, block: suspend () -> String?) {
        // Set synchronously: two fast taps cannot start concurrent exports or replace a staged import.
        if (_backupBusy.value != null || _stagedImport.value != null) return
        _backupBusy.value = busy
        _transfer.value = transfer
        _backupNote.value = null
        viewModelScope.launch {
            try {
                if (cloudAction) _cloud.value = _cloud.value.copy(status = null, error = false)
                _backupMessage.value = block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: TransferCancelledException) {
                // Asked for: plainly said, not an error.
                if (cloudAction) _cloud.value = _cloud.value.copy(error = false, status = cancelled) else _backupMessage.value = cancelled
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
                _transfer.value = null
            }
        }
    }

    override fun onCleared() {
        // Nothing left to see it: an upload or download under way stops (its request goes on otherwise).
        _transfer.value?.cancel()
        _stagedImport.value?.let(backup::discard)
    }

    // Null until the database has answered, so the screen can wait instead of flashing "No plans yet".
    val trips: StateFlow<List<Trip>?> = repo.trips
        .stateInWhileVisible(viewModelScope, null)

    // Every event of every plan, for the agenda; null until the database has answered, like [trips].
    // Events of ticked Nextcloud calendars are added (with negative ids, see OutsideCalendars).
    val agendaEvents: StateFlow<List<PlanEvent>?> = combine(repo.planEvents, calendars?.shown ?: flowOf(emptyMap())) { own, outside ->
        if (outside.isEmpty()) own else own + outside.values.map { it.event.toPlanEvent(it.color) }
    }.stateInWhileVisible(viewModelScope, null)

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
    ): Long = repo.saveItemId(item, added, removed, addedReminders, removedReminders, options)

    val themeMode: StateFlow<ThemeMode> = settings.themeMode

    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)

    val timeFormat: StateFlow<TimeFormat> = settings.timeFormat

    fun setTimeFormat(format: TimeFormat) = settings.setTimeFormat(format)

    // How see-through the big + button is, in percent.
    val addButtonSeeThrough: StateFlow<Int> = settings.addButtonSeeThrough

    fun setAddButtonSeeThrough(percent: Int) = settings.setAddButtonSeeThrough(percent)

    // The colour of all headings in the app, as an ARGB value.
    val headingColor: StateFlow<Int> = settings.headingColor

    fun setHeadingColor(argb: Int) = settings.setHeadingColor(argb)

    val scrollBarColor: StateFlow<Int> = settings.scrollBarColor
    fun setScrollBarColor(argb: Int) = settings.setScrollBarColor(argb)
    val scrollBarSeeThrough: StateFlow<Int> = settings.scrollBarSeeThrough
    fun setScrollBarSeeThrough(percent: Int) = settings.setScrollBarSeeThrough(percent)

    // The typeface used for all text.
    val appFont: StateFlow<AppFont> = settings.appFont

    fun setAppFont(font: AppFont) = settings.setAppFont(font)

    // How a whole day is written in headings (see DateFormatChoice).
    val dateFormat: StateFlow<DateFormatChoice> = settings.dateFormat

    fun setDateFormat(choice: DateFormatChoice) = settings.setDateFormat(choice)

    // How big all text is, in percent.
    val textSizePercent: StateFlow<Int> = settings.textSizePercent

    fun setTextSizePercent(percent: Int) = settings.setTextSizePercent(percent)

    // What the event form (used by "Add quick event") needs.
    private val categories = CategoryState(repo, settings, viewModelScope)
    val categoryCounts: StateFlow<Map<String, Int>> = categories.counts
    val hiddenCategories: StateFlow<Set<String>> = categories.hidden

    fun removeCategories(names: Set<String>) = categories.remove(names)

    fun showCategory(name: String) = categories.show(name)
}
