package com.example.itinerary.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
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
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.ItineraryApp
import com.example.itinerary.LockActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// How long Planner may be out of sight before it asks to be unlocked again.
enum class LockAfter(val millis: Long, val label: String) {
    IMMEDIATELY(0, "Immediately"),
    ONE_MINUTE(60_000, "After 1 minute"),
    FIVE_MINUTES(5 * 60_000, "After 5 minutes"),
    FIFTEEN_MINUTES(15 * 60_000, "After 15 minutes");

    companion object {
        val DEFAULT = ONE_MINUTE
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

object AppLockRule {
    // A file picker, the camera scanner, a browser or the lock's own confirmation, opened from Planner itself, is part
    // of using Planner: it gets at least this long before the lock comes back, so "Immediately" doesn't ask again on
    // the way back from picking a file.
    const val OWN_TRIP_GRACE_MS = 5 * 60_000L

    // [leftAt]: when Planner was last left while unlocked (elapsed time, counting sleep), null if it never was.
    fun mustUnlock(enabled: Boolean, unlocked: Boolean, leftAt: Long?, ownTrip: Boolean, now: Long, lockAfterMs: Long): Boolean {
        if (!enabled) return false
        if (!unlocked) return true
        if (leftAt == null) return false
        val limit = if (ownTrip) maxOf(lockAfterMs, OWN_TRIP_GRACE_MS) else lockAfterMs
        return now - leftAt >= limit
    }

    // Fingerprint or face, or the phone's own PIN, pattern or password. The weak biometric class is the one that
    // combines with the device credential on every Android version Planner runs on (8 and later).
    const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun canAsk(context: Context) = BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
}

// App lock: the setting lives in its own preferences file, so Planner's backups neither carry nor restore it (a backup
// opened on another phone never locks anyone out). Whether Planner is unlocked is process memory only: a fresh start
// is always locked.
class AppLock(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _lockAfter = MutableStateFlow(LockAfter.fromName(prefs.getString(KEY_LOCK_AFTER, null)))
    val lockAfter: StateFlow<LockAfter> = _lockAfter.asStateFlow()
    private var unlocked = false
    // True while the lock screen should cover Planner.
    private val _locked = MutableStateFlow(_enabled.value)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private var leftAt: Long? = null
    private var leftOnOwnTrip = false
    // Set when Planner itself opens another screen (see MainActivity.startActivityForResult).
    @Volatile var ownTripStarting = false

    private fun now() = SystemClock.elapsedRealtime()

    // Called only after the user has confirmed it is them, so turning it on leaves Planner unlocked.
    fun setEnabled(on: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, on) }
        _enabled.value = on
        unlocked = true
        _locked.value = false
    }

    fun setLockAfter(value: LockAfter) {
        prefs.edit { putString(KEY_LOCK_AFTER, value.name) }
        _lockAfter.value = value
    }

    // Planner is coming into view: true means the lock screen must be shown first.
    fun checkOnStart(): Boolean {
        val must = AppLockRule.mustUnlock(_enabled.value, unlocked, leftAt, leftOnOwnTrip, now(), _lockAfter.value.millis)
        if (must) unlocked = false
        _locked.value = must
        leftAt = null
        return must
    }

    // Planner went out of sight.
    fun onLeft() {
        if (unlocked && leftAt == null) { leftAt = now(); leftOnOwnTrip = ownTripStarting }
        ownTripStarting = false
    }

    // Back in front without having left (a see-through permission prompt, say): nothing was opened after all.
    fun onResumed() { ownTripStarting = false }

    fun onUnlocked() {
        unlocked = true
        leftAt = null
        _locked.value = false
    }

    companion object {
        const val PREFS = "app_lock"
        const val KEY_ENABLED = "enabled"
        const val KEY_LOCK_AFTER = "lockAfter"
    }
}

@Composable
fun AppLockSettingsSection() {
    val context = LocalContext.current
    val appLock = (context.applicationContext as ItineraryApp).appLock
    val enabled by appLock.enabled.collectAsStateWithLifecycle()
    val lockAfter by appLock.lockAfter.collectAsStateWithLifecycle()
    // Turning it on or off asks first, so someone holding the unlocked phone can't simply switch it off.
    val confirm = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) appLock.setEnabled(!appLock.enabled.value)
    }
    SettingsHeading("App lock")
    Row(
        Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = {
            if (!enabled && !AppLockRule.canAsk(context))
                Toast.makeText(context, "Set a screen lock on your phone first", Toast.LENGTH_LONG).show()
            else confirm.launch(Intent(context, LockActivity::class.java).putExtra(LockActivity.EXTRA_CONFIRM, true))
        }).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Lock Planner", Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = null)
    }
    Text("Ask for your fingerprint, face, or the phone's PIN, pattern or password to open Planner. Reminders and the " +
        "home screen widget still show.", style = MaterialTheme.typography.bodySmall)
    if (enabled) {
        Spacer(Modifier.height(8.dp))
        SettingsDropdown(label = "Lock again", current = lockAfter.label, options = LockAfter.entries,
            onSelect = appLock::setLockAfter) { Text(it.label) }
    }
}
