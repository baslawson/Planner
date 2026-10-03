package com.example.itinerary.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.PlannerTask
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// "Planner is free and open source": once after each update, not after a fresh install (the ask also sits in the
// "Planner X is available" pop-up and the ⋮ menu). The version it was last shown for lives in its own preferences file,
// so Planner's own backups neither carry nor restore it.
object SupportPrompt {
    const val PREFS = "support_prompt"
    const val KEY_SHOWN_FOR = "shownForVersion"
    const val TASK_TITLE = "Support Planner on Ko-fi"

    // Not in the test app, where it would stand in front of every UI test; a test that wants it sets this.
    @Volatile var showInTestApp = false

    // [freshInstall]: Planner was installed and never updated since; then the version is noted as seen without asking.
    fun due(shownFor: Long, installed: Long, freshInstall: Boolean = false) = !(freshInstall && shownFor == 0L) && shownFor < installed

    // The test app is installed afresh for every run; a test says which case it stands for.
    @Volatile var freshInstallInTests: Boolean? = null

    // Android gives a fresh install the same first-install and last-update times; any update moves the second.
    fun freshInstall(context: Context): Boolean = freshInstallInTests ?: runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).let { it.firstInstallTime == it.lastUpdateTime }
    }.getOrDefault(false)

    // "Remind me later" adds the task only when there isn't one still to do, so updates don't stack copies; once ticked
    // off or deleted, the next "Remind me later" adds a fresh one.
    fun needsTask(tasks: List<PlannerTask>) = tasks.none { it.title == TASK_TITLE && !it.done }

    fun installedVersion(context: Context): Long =
        runCatching { PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)) }.getOrDefault(0L)
}

// [blocked]: an editor or another start-up pop-up is open; the prompt waits for it.
@Composable
fun SupportPromptHost(blocked: Boolean) {
    val context = LocalContext.current
    val app = context.applicationContext as ItineraryApp
    val prefs = remember { context.getSharedPreferences(SupportPrompt.PREFS, Context.MODE_PRIVATE) }
    val installed = remember { SupportPrompt.installedVersion(context) }
    var showing by remember {
        val shownFor = prefs.getLong(SupportPrompt.KEY_SHOWN_FOR, 0L)
        val fresh = SupportPrompt.freshInstall(context)
        // A fresh install: this version counts as seen, so the first update is the first time it asks.
        if (fresh && shownFor == 0L && installed > 0) prefs.edit { putLong(SupportPrompt.KEY_SHOWN_FOR, installed) }
        mutableStateOf((SupportPrompt.showInTestApp || !context.packageName.endsWith(".uitest")) &&
            SupportPrompt.due(shownFor, installed, fresh))
    }
    if (!showing || blocked) return
    // Any answer (or Back) counts as seen for this version.
    fun seen() { prefs.edit { putLong(SupportPrompt.KEY_SHOWN_FOR, installed) }; showing = false }
    PlannerDialog("Planner is free and open source",
        onDismissRequest = ::seen,
        primary = DialogAction("Support on Ko-fi") {
            seen()
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KOFI_URL))) }
            catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(context, "No app found to open this link", Toast.LENGTH_SHORT).show()
            }
        },
        dismiss = DialogAction("No thanks", onClick = ::seen),
        extra = listOf(DialogAction("Remind me later") {
            seen()
            // A task in the normal list, ticked off or deleted like any other.
            app.appScope.launch { runCatching {
                if (SupportPrompt.needsTask(app.repository.tasks.first())) app.repository.saveTask(PlannerTask(title = SupportPrompt.TASK_TITLE, notes = KOFI_URL))
            } }
        }),
    ) {
        Text("No ads, no tracking, no account. Planner is made in spare time and shared for free. If it's useful to you, " +
            "you can support it on Ko-fi.")
    }
}
