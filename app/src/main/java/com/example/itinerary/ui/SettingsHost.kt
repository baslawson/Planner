package com.example.itinerary.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate

// The Settings page with everything it opens: backup export and import, Nextcloud, and their progress and
// confirmation dialogs. Shared by Agenda and Calendar. Call it last inside a full-screen Box so the page
// is drawn over the screen. The backup pickers are registered here rather than in the settings page, so a result
// still arrives after a rotation. The Calendars pop-up is held by the caller: the sync icon opens it too.
@Composable
fun SettingsHost(vm: TripsViewModel, show: Boolean, onDismiss: () -> Unit, showCalendars: Boolean, onShowCalendars: (Boolean) -> Unit) {
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val timeFormat by vm.timeFormat.collectAsStateWithLifecycle()
    val addButtonSeeThrough by vm.addButtonSeeThrough.collectAsStateWithLifecycle()
    val headingColor by vm.headingColor.collectAsStateWithLifecycle()
    val scrollBarColor by vm.scrollBarColor.collectAsStateWithLifecycle()
    val scrollBarSeeThrough by vm.scrollBarSeeThrough.collectAsStateWithLifecycle()
    val dateFormat by vm.dateFormat.collectAsStateWithLifecycle()
    val appFont by vm.appFont.collectAsStateWithLifecycle()
    val textSizePercent by vm.textSizePercent.collectAsStateWithLifecycle()
    var showNextcloud by rememberSaveable { mutableStateOf(false) }
    var showDeleted by rememberSaveable { mutableStateOf(false) }
    val cloud by vm.cloud.collectAsStateWithLifecycle()
    LaunchedEffect(showNextcloud) { if (showNextcloud) vm.loadNextcloud() }

    val backupBusy by vm.backupBusy.collectAsStateWithLifecycle()
    val stagedImport by vm.stagedImport.collectAsStateWithLifecycle()
    val backupMessage by vm.backupMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(vm::exportTo) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::chooseImport) }
    LaunchedEffect(backupMessage) {
        backupMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.backupMessageShown()
        }
    }

    if (show) {
        // The system Back key or gesture closes Settings, like the back arrow.
        BackHandler(onBack = onDismiss)
        SettingsScreen(
            themeMode = themeMode,
            onThemeSelect = vm::setThemeMode,
            timeFormat = timeFormat,
            onTimeFormatSelect = vm::setTimeFormat,
            dateFormat = dateFormat,
            onDateFormat = vm::setDateFormat,
            appFont = appFont,
            onAppFont = vm::setAppFont,
            textSizePercent = textSizePercent,
            onTextSizePercent = vm::setTextSizePercent,
            headingColor = headingColor,
            onHeadingColor = vm::setHeadingColor,
            scrollBarColor = scrollBarColor,
            onScrollBarColor = vm::setScrollBarColor,
            scrollBarSeeThrough = scrollBarSeeThrough,
            onScrollBarSeeThrough = vm::setScrollBarSeeThrough,
            addButtonSeeThrough = addButtonSeeThrough,
            onAddButtonSeeThrough = vm::setAddButtonSeeThrough,
            onExport = { exportLauncher.launch("Planner-backup-${LocalDate.now()}.zip") },
            onImport = { importLauncher.launch(arrayOf("*/*")) },
            onNextcloud = { showNextcloud = true },
            onDismiss = onDismiss,
            onRecentlyDeleted = { showDeleted = true },
            onCalendars = { onShowCalendars(true) },
        )
    }
    // Before the Nextcloud pop-up, so "Connect Nextcloud" opens the login on top of it.
    if (showCalendars) CalendarsDialog(nextcloudOpen = showNextcloud, onConnect = { showNextcloud = true }, onDismiss = { onShowCalendars(false) })
    if (showNextcloud) {
        NextcloudDialog(
            state = cloud,
            busy = backupBusy != null,
            onConnect = vm::connectNextcloud,
            onSaveFolder = vm::saveNextcloudFolder,
            onDisconnect = vm::disconnectNextcloud,
            onUpload = vm::uploadNextcloud,
            onList = vm::listNextcloud,
            onRestore = vm::importNextcloud,
            onDismiss = { showNextcloud = false },
        )
    }
    if (showDeleted) RecentlyDeletedDialog((context.applicationContext as com.example.itinerary.ItineraryApp).repository) { showDeleted = false }
    backupBusy?.let { BusyDialog(it) }
    stagedImport?.let { staged ->
        ImportConfirmDialog(staged, onConfirm = vm::confirmImport, onCancel = vm::cancelImport)
    }
}
