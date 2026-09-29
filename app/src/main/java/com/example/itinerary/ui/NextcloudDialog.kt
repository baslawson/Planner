package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton
import com.example.itinerary.ui.MatrixButton as Button

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.example.itinerary.data.NextcloudBackup
import com.example.itinerary.data.NextcloudAccount
import com.example.itinerary.data.TimeFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NextcloudDialog(
    state: NextcloudUiState,
    busy: Boolean,
    onConnect: (String, String, String) -> Unit,
    onDisconnect: () -> Unit,
    onUpload: () -> Unit,
    onList: () -> Unit,
    onRestore: (NextcloudBackup) -> Unit,
    onDismiss: () -> Unit,
    onSaveFolder: (String) -> Unit,
) {
    val context = LocalContext.current
    val timeFormat = LocalTimeFormat.current
    var server by rememberSaveable(state.server) { mutableStateOf(state.server) }
    var username by rememberSaveable(state.username) { mutableStateOf(state.username) }
    var folderPath by rememberSaveable(state.folderPath) { mutableStateOf(state.folderPath) }
    val folderChanged = folderPath != state.folderPath
    // Intentionally not rememberSaveable: passwords must not enter saved-state bundles or backups.
    var password by remember { mutableStateOf("") }
    LaunchedEffect(state.connected) { if (state.connected) password = "" }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(securePolicy = if (state.connected) SecureFlagPolicy.Inherit else SecureFlagPolicy.SecureOn),
        title = { HeadingText("Nextcloud backup") },
        text = {
            ScrollHints(rememberScrollState(), Modifier.fillMaxWidth().heightIn(max = 560.dp), fitContent = true) { Column(Modifier.fillMaxWidth()) {
                if (!state.connected) {
                    Text("Connect to save all events, tasks, reminders, attachments and settings to your Nextcloud.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(server, { server = it }, label = { Text("Server address") },
                        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(username, { username = it }, label = { Text("Username") },
                        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text("App password") },
                        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Spacer(Modifier.height(8.dp))
                    Text("The password is stored securely on this device and isn't included in backups.",
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onConnect(server, username, password) },
                        enabled = !busy && server.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()) { Text("Connect") }
                    if (state.error) {
                        OutlinedButton(onClick = { password = ""; onDisconnect() }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth()) { Text("Clear saved connection") }
                    }
                } else {
                    Text(state.server)
                    Text("Account: ${state.username}")
                    OutlinedTextField(folderPath, { folderPath = it }, label = { Text("Backup folder") },
                        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Text("Path inside your Nextcloud Files, e.g. Backups/Planner. Missing folders are created when you back up.",
                        style = MaterialTheme.typography.bodySmall)
                    if (folderChanged) {
                        Button(onClick = {
                            folderPath = runCatching { NextcloudAccount.normalizeFolder(folderPath) }.getOrDefault(folderPath)
                            onSaveFolder(folderPath)
                        }, enabled = !busy && folderPath.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()) { Text("Save folder") }
                        OutlinedButton(onClick = { folderPath = state.folderPath }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth()) { Text("Cancel folder change") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Backups include all app data and settings. Restoring replaces the data on this device.",
                        style = MaterialTheme.typography.bodySmall)
                    state.lastBackup?.let { value ->
                        val time = runCatching {
                            val local = Instant.parse(value).atZone(ZoneId.systemDefault())
                            "${local.toLocalDate().fullLabel()}, ${local.toLocalTime().label(timeFormat, context)}"
                        }.getOrDefault(value)
                        Text("Last backup from this device: $time", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = onUpload, enabled = !busy && !folderChanged, modifier = Modifier.fillMaxWidth()) { Text("Back up now") }
                    OutlinedButton(onClick = onList, enabled = !busy && !folderChanged, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.backups == null) "Restore from Nextcloud" else "Refresh backups")
                    }
                    OutlinedButton(onClick = onDisconnect, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
                }
                state.status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.backups?.forEach { file ->
                    OutlinedButton(onClick = { onRestore(file) }, enabled = !busy && !folderChanged, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text(nextcloudBackupLabel(file, timeFormat, context))
                            file.size?.let { Text(android.text.format.Formatter.formatShortFileSize(
                                context, it), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            } }
        },
        confirmButton = { OutlinedButton(onClick = onDismiss, enabled = !busy) { Text("Close") } },
    )
}

internal fun nextcloudBackupLabel(file: NextcloudBackup, timeFormat: TimeFormat, context: Context): String = runCatching {
    val stamp = file.name.removePrefix("Planner-backup-").take(19)
    val local = LocalDateTime.parse(stamp, DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        .atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault())
    "${local.toLocalDate().fullLabel()}, ${local.toLocalTime().label(timeFormat, context)}"
}.getOrElse { file.name }
