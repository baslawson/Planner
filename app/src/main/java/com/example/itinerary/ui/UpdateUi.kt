package com.example.itinerary.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.Updates
import kotlinx.coroutines.launch
import java.io.File

// Hands the checked APK to Android's installer, which asks before updating. The first time, Android wants Planner
// allowed to install apps: its settings page opens, and Install now works once that's on.
// A phone without either screen (a work profile, some makers' builds) says so instead of closing Planner.
fun installUpdate(context: Context, file: File) {
    try {
        if (!context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, "Allow Planner to install updates, then tap Install now again.", Toast.LENGTH_LONG).show()
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: android.content.ActivityNotFoundException) {
        Toast.makeText(context, "This phone doesn't let apps install updates. Download Planner from its GitHub page instead.", Toast.LENGTH_LONG).show()
    }
}

// Install now, after checking the file is still the one downloaded and checked.
fun installReady(context: Context) {
    val app = context.applicationContext as ItineraryApp
    app.appScope.launch {
        if (!app.updates.stillReady()) return@launch
        val file = (app.updates.state.value as? Updates.State.Ready)?.file ?: return@launch
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { installUpdate(context, file) }
    }
}

// The pop-up for a new version: what's new (the release notes), then Update now / Later / Skip this version; the
// download's progress; Install now once it's checked. A background download only asks when it's ready.
@Composable
fun UpdateDialog() {
    val context = LocalContext.current
    val app = context.applicationContext as ItineraryApp
    val updates = app.updates
    val state by updates.state.collectAsStateWithLifecycle()
    val dismissed by updates.dismissed.collectAsStateWithLifecycle()
    val release = when (val s = state) {
        is Updates.State.Available -> s.release
        is Updates.State.Downloading -> s.release.takeIf { s.asked }
        is Updates.State.Ready -> s.release
        is Updates.State.Failed -> s.release
        else -> null
    }
    if (dismissed || release == null) return
    val s = state
    PlannerDialog(
        title = "Planner ${release.version} is available",
        onDismissRequest = updates::later,
        primary = when (s) {
            is Updates.State.Available -> DialogAction("Update now") { app.appScope.launch { updates.download(release) } }
            is Updates.State.Ready -> DialogAction("Install now") { installReady(context) }
            is Updates.State.Failed -> DialogAction("Try again") { app.appScope.launch { updates.download(release) } }
            else -> null
        },
        dismiss = DialogAction("Later", onClick = updates::later),
        extra = if (s is Updates.State.Available || s is Updates.State.Ready) listOf(DialogAction("Skip this version") { updates.skip(release) }) else emptyList(),
        note = when (s) {
            is Updates.State.Downloading -> ({
                androidx.compose.foundation.layout.Column {
                    Text(if (s.progress >= 0) "Downloading… ${(s.progress * 100).toInt()} %" else "Downloading…", style = MaterialTheme.typography.bodyMedium)
                    if (s.progress >= 0) LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth().padding(top = 6.dp))
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            })
            is Updates.State.Ready -> ({ Text("Downloaded and checked. Android asks before installing; your data stays as it is.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) })
            is Updates.State.Failed -> ({ Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) })
            else -> null
        },
    ) {
        Text("You have ${updates.installedVersion}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (release.notes.isNotBlank()) MarkdownView(release.notes, Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

// Settings → Updates: the version, the two switches and Check now.
@Composable
fun UpdatesSettingsSection() {
    val context = LocalContext.current
    val app = context.applicationContext as ItineraryApp
    val updates = app.updates
    val onStart by updates.checkOnStart.collectAsStateWithLifecycle()
    val auto by updates.autoDownload.collectAsStateWithLifecycle()
    val state by updates.state.collectAsStateWithLifecycle()
    SettingsHeading("Updates")
    Text("Planner ${updates.installedVersion}", style = MaterialTheme.typography.bodyMedium)
    if (!updates.supported) {
        Text("Updates aren't available in this build: only the Planner app from GitHub updates itself.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    @Composable
    fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = null)
        }
    }
    SwitchRow("Check for updates when Planner starts", onStart, updates::setCheckOnStart)
    SwitchRow("Download updates automatically", auto, updates::setAutoDownload)
    Text("Planner asks GitHub once a day at most, and always asks you before installing.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // A found or downloaded update can be acted on here too, after Later.
    when (val s = state) {
        is Updates.State.Ready -> StackedButton("Install Planner ${s.release.version}") { installReady(context) }
        is Updates.State.Available -> StackedButton("Update to Planner ${s.release.version}") { app.appScope.launch { updates.download(s.release) } }
        else -> {}
    }
    StackedButton(if (state is Updates.State.Checking) "Checking…" else "Check now") { app.appScope.launch { updates.check() } }
    val line = when (val s = state) {
        is Updates.State.Checking -> "Checking for updates…"
        is Updates.State.UpToDate -> "You have the latest version."
        is Updates.State.Available -> "Planner ${s.release.version} is available."
        is Updates.State.Downloading -> "Downloading Planner ${s.release.version}…"
        is Updates.State.Ready -> "Planner ${s.release.version} is ready to install."
        is Updates.State.Failed -> s.message
        else -> null
    }
    line?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
        color = if (state is Updates.State.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
    if (updates.lastChecked > 0) Text("Last checked " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(updates.lastChecked)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
