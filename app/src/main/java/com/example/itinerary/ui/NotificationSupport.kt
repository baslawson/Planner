package com.example.itinerary.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.itinerary.reminders.notificationsEnabled
import com.example.itinerary.reminders.openNotificationSettings

class NotificationState(val enabled: Boolean, val enable: () -> Unit)

// Whether notifications can currently show, and a way to fix it when they can't.
// [enable] asks for permission when Android will still show the prompt, and otherwise
// opens the system notification settings, where the user can switch them on themselves.
@Composable
fun rememberNotificationState(): NotificationState {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(notificationsEnabled(context)) }
    // Re-checked on resume because the change happens in a system dialog or settings screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled = notificationsEnabled(context) }

    // Hunt 26 E2: whether the prompt was asked for before this request. A first prompt backed out of comes back denied with no
    // rationale too, like one Android no longer shows, so only after an earlier ask does a refusal go to settings.
    val asked = remember { context.getSharedPreferences("notification_prompt", Context.MODE_PRIVATE) }
    var askedBefore by remember { mutableStateOf(false) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        enabled = notificationsEnabled(context)
        if (!granted && askedBefore) {
            // Once Android stops offering the prompt, settings is the only way left.
            val activity = context.findActivity()
            val canAskAgain = activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
            if (!canAskAgain) openNotificationSettings(context)
        }
    }

    return NotificationState(enabled) {
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            askedBefore = asked.getBoolean("asked", false)
            asked.edit().putBoolean("asked", true).apply()
            requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else openNotificationSettings(context)
    }
}

// Shared with ItemEditorSheet (same package), which needs an Activity to start the document scanner.
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
