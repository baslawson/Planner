package com.example.itinerary.reminders
import com.example.itinerary.ui.MatrixTextButton as TextButton

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.isSystemInDarkTheme
import com.example.itinerary.data.ThemeMode
import com.example.itinerary.ItineraryApp
import com.example.itinerary.ui.DialogAction
import com.example.itinerary.ui.PlannerDialog
import com.example.itinerary.ui.theme.ItineraryTheme
import kotlinx.coroutines.launch

/** An activity PendingIntent opens directly from the notification, including on Android 12+. */
class SnoozeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, 0)
        val taskId = intent.getStringExtra("taskId")
        val token = intent.getStringExtra(ReminderScheduler.EXTRA_SNOOZE_TOKEN)
        val trigger = intent.getLongExtra("trigger", 0)
        if (taskId == null && (id <= 0 || token == null)) { finish(); return }
        setContent {
            var busy by remember { mutableStateOf(false) }
            val settings = (application as ItineraryApp).settings
            val mode by settings.themeMode.collectAsStateWithLifecycle()
            val appTheme by settings.appTheme.collectAsStateWithLifecycle()
            val font by settings.appFont.collectAsStateWithLifecycle()
            val size by settings.textSizePercent.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            ItineraryTheme(appTheme = appTheme, darkTheme = dark, font = font, textSizePercent = size) {
                PlannerDialog("Snooze reminder", onDismissRequest = { if (!busy) finish() },
                    dismiss = DialogAction("Cancel", enabled = !busy) { finish() }) {
                        SnoozeChoice.entries.forEach { choice ->
                            TextButton(enabled = !busy, onClick = {
                                busy = true
                                lifecycleScope.launch {
                                    try {
                                        val repo = (application as ItineraryApp).repository
                                        val saved = if (taskId != null) repo.actOnTaskReminder(taskId, trigger, choice.until())
                                            else repo.snoozeReminder(id, choice.until(), token)
                                        Toast.makeText(this@SnoozeActivity,
                                            if (saved) "Snoozed: ${choice.label}" else "This reminder is no longer active", Toast.LENGTH_SHORT).show()
                                        finish()
                                    } catch (_: Exception) {
                                        busy = false
                                        Toast.makeText(this@SnoozeActivity, "Couldn't snooze. Please try again.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }) { Text(choice.label) }
                        }
                }
            }
        }
    }
    companion object {
        fun taskAction(context: Context, id: String, trigger: Long): PendingIntent = PendingIntent.getActivity(context, 0,
            Intent(context, SnoozeActivity::class.java)
                .setData(android.net.Uri.Builder().scheme("planner").authority("task-snooze").appendPath(id).appendQueryParameter("trigger", trigger.toString()).build())
                .putExtra("taskId", id).putExtra("trigger", trigger),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(context: Context, id: Long, token: String): PendingIntent = PendingIntent.getActivity(context, id.toInt(),
            Intent(context, SnoozeActivity::class.java)
                .setData(android.net.Uri.parse("planner://event-snooze/$id/$token"))
                .putExtra(ReminderScheduler.EXTRA_REMINDER_ID, id)
                .putExtra(ReminderScheduler.EXTRA_SNOOZE_TOKEN, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
