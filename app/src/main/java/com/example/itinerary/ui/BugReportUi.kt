package com.example.itinerary.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.BugReport
import com.example.itinerary.data.CrashLog

/**
 * Report a bug (wish list #6): what happened, and the last crash if the person ticks it, shown exactly as it will be
 * sent, then opened as a new GitHub issue in the browser for them to submit there.
 */
@Composable
fun ReportBugDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var description by rememberSaveable { mutableStateOf("") }
    var withCrash by rememberSaveable { mutableStateOf(false) }
    val crash = remember { CrashLog(context.filesDir).read() }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            ?.let { "${it.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it)})" } ?: "?"
    }
    val androidVersion = "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
    val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
    val crashText = crash?.text?.takeIf { withCrash }
    PlannerDialog("Report a bug", onDismissRequest = onDismiss,
        primary = DialogAction("Open GitHub", enabled = description.isNotBlank()) {
            val link = BugReport.url(description, version, androidVersion, device, crashText)
            try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))); onDismiss() }
            catch (_: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(context, "No app found to open this link", android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        dismiss = DialogAction("Cancel", onClick = onDismiss)) {
        Text("This opens a new issue on Planner's GitHub in your browser. You'll need a GitHub account, and the report is public.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(description, { description = it.take(3_000) }, label = { Text("What happened?") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
        if (crash != null) Row(Modifier.fillMaxWidth().toggleable(withCrash, role = Role.Checkbox) { withCrash = it },
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(withCrash, onCheckedChange = null)
            Text("Include the last crash (${momentLabel(crash.at, short = true)})", Modifier.padding(start = 8.dp))
        }
        Text("What will be sent:", style = MaterialTheme.typography.labelLarge)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
            Text(BugReport.body(description, version, androidVersion, device, crashText), Modifier.fillMaxWidth().padding(10.dp),
                style = MaterialTheme.typography.bodySmall)
        }
        Text("Nothing else is included: no events, tasks, notes or Nextcloud details.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
