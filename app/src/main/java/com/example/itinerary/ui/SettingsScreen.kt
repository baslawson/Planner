package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixIconButton as IconButton
import com.example.itinerary.ui.MatrixButton as Button

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.AppFont
import com.example.itinerary.data.DateFormatChoice
import com.example.itinerary.data.ThemeMode
import com.example.itinerary.data.TimeFormat
import com.example.itinerary.reminders.AlarmService
import com.example.itinerary.reminders.openAppSettings
import com.example.itinerary.reminders.sendTestNotification
import androidx.core.app.NotificationManagerCompat

// The Settings page, full screen: a title bar with a back arrow, the settings in a scrolling column with a scroll bar
// (ScrollHints), and a big Save button pinned at the bottom. It is drawn over the current screen
// (not in a dialog window), so the pickers and confirmations still open on top of it; the system Back key closes it.
// Changes apply immediately.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeSelect: (ThemeMode) -> Unit,
    timeFormat: TimeFormat,
    onTimeFormatSelect: (TimeFormat) -> Unit,
    dateFormat: DateFormatChoice,
    onDateFormat: (DateFormatChoice) -> Unit,
    appFont: AppFont,
    onAppFont: (AppFont) -> Unit,
    textSizePercent: Int,
    onTextSizePercent: (Int) -> Unit,
    headingColor: Int,
    onHeadingColor: (Int) -> Unit,
    scrollBarColor: Int,
    onScrollBarColor: (Int) -> Unit,
    scrollBarSeeThrough: Int,
    onScrollBarSeeThrough: (Int) -> Unit,
    addButtonSeeThrough: Int,
    onAddButtonSeeThrough: (Int) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onNextcloud: () -> Unit,
    onDismiss: () -> Unit,
    onRecentlyDeleted: () -> Unit = {},
    onCalendars: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings = (context.applicationContext as ItineraryApp).settings
    val showBillsSummary by settings.showBillsSummary.collectAsStateWithLifecycle()
    val scheduler = remember { (context.applicationContext as ItineraryApp).reminderScheduler }
    // Re-checked on resume because the user grants this in system settings, outside the app.
    var exactAllowed by remember { mutableStateOf(scheduler.canScheduleExact()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exactAllowed = scheduler.canScheduleExact() }
    val notifications = rememberNotificationState()

    val scrollState = rememberScrollState()
    // Fills the screen and takes every touch, so nothing behind it can be tapped.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        HeadingText(
                            "Settings",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding(),
                    ) { Text("Save") }
                }
            },
        ) { inner ->
            ScrollHints(scrollState, Modifier.padding(inner).fillMaxSize()) {
                Column(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 24.dp)) {
                    SettingsHeading("Quick entry")
                    QuickAiSettingsButton()
                    SettingsHeading("Agenda")
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = showBillsSummary, role = Role.Switch,
                            onValueChange = settings::setShowBillsSummary).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Show unpaid bills summary", Modifier.weight(1f))
                        Switch(checked = showBillsSummary, onCheckedChange = null)
                    }
                    Text("Hide the summary card without hiding bill events.", style = MaterialTheme.typography.bodySmall)
                    SettingsSection(
                        title = "Appearance",
                        options = ThemeMode.entries,
                        selected = themeMode,
                        label = { it.label },
                        onSelect = onThemeSelect,
                        withDivider = true,
                    )
                    SettingsSection(
                        title = "Time format",
                        options = TimeFormat.entries,
                        selected = timeFormat,
                        label = { it.label },
                        onSelect = onTimeFormatSelect,
                    )
                    DateFormatSettingsSection(dateFormat, onDateFormat)
                    FontSettingsSection(appFont, onAppFont, textSizePercent, onTextSizePercent)
                    HeadingColorSettingsSection(headingColor, onHeadingColor)
                    ScrollBarSettingsSection(scrollBarColor, scrollBarSeeThrough, onScrollBarColor, onScrollBarSeeThrough)
                    AddButtonSettingsSection(addButtonSeeThrough, onAddButtonSeeThrough)
                    SettingsHeading("Home screen")
                    Text("See today’s events and due tasks, and complete tasks from your home screen.", style = MaterialTheme.typography.bodySmall)
                    StackedButton("Add Today widget") {
                        if (!com.example.itinerary.widget.TodayWidget.pin(context)) {
                            Toast.makeText(context, "Long-press your home screen, choose Widgets, then Planner.", Toast.LENGTH_LONG).show()
                        }
                    }
                    SettingsHeading("Calendars")
                    Text("Show other calendars beside your own. Only Nextcloud can sync both ways; phone calendars and links are read-only.", style = MaterialTheme.typography.bodySmall)
                    StackedButton("Calendars", onCalendars)
                    SettingsHeading("Backup")
                    StackedButton("Recently deleted", onRecentlyDeleted)
                    BackupStatusPanel()
                    Text(
                        "Save all your events, tasks, templates, reminders, attachments and settings to one file, or restore them from one. " +
                            "Importing replaces everything currently in the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StackedButton("Export all data", onExport)
                    StackedButton("Import data", onImport)
                    StackedButton("Nextcloud backup", onNextcloud)
                    SettingsHeading("Notifications")
                    Text(
                        if (notifications.enabled) "Notifications are on." else "Notifications are off, so reminders won't show.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (notifications.enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                    if (!notifications.enabled) {
                        StackedButton("Turn on notifications", notifications.enable)
                    }
                    if (!exactAllowed) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Exact alarms are off, so reminders can arrive a few minutes late.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        StackedButton("Allow exact alarms") {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        }
                    }
                    StackedButton("Send test notification") {
                        if (!sendTestNotification(context)) {
                            Toast.makeText(context, "Notifications are off", Toast.LENGTH_SHORT).show()
                        }
                    }
                    StackedButton("Test alarm (rings until stopped)") {
                        if (!AlarmService.startTest(context)) {
                            val reason = if (NotificationManagerCompat.from(context).areNotificationsEnabled()) "Ringing alarms are off" else "Notifications are off"
                            Toast.makeText(context, reason, Toast.LENGTH_SHORT).show()
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Not arriving? Some phones stop background alarms to save battery. " +
                            "Set this app's battery use to Unrestricted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StackedButton("Open app settings") { openAppSettings(context) }
                }
            }
        }
    }
}

@Composable
private fun <T> SettingsSection(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    withDivider: Boolean = true,
) {
    SettingsHeading(title, withDivider)
    Column(Modifier.selectableGroup()) {
        options.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = null)
                Spacer(Modifier.width(16.dp))
                Text(label(option), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
