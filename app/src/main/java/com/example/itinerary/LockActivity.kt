package com.example.itinerary

import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.itinerary.data.ThemeMode
import com.example.itinerary.ui.AppLockRule
import com.example.itinerary.ui.theme.ItineraryTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// The lock screen, over Planner's own screen (which keeps its place underneath, open editors and dialogs included).
// With EXTRA_CONFIRM it only confirms it's the user (Settings, turning App lock on or off) and returns RESULT_OK.
class LockActivity : FragmentActivity() {
    private val confirmOnly get() = intent.getBooleanExtra(EXTRA_CONFIRM, false)
    private val appLock get() = (application as ItineraryApp).appLock
    private var message by mutableStateOf<String?>(null)
    private var asked = false
    // On older Android the PIN screen is a separate screen that hides this one; coming back from it is not a return.
    private var asking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back leaves Planner locked rather than going behind the lock screen. In the task of an app that shared something
        // to Planner, it closes this and the Planner screen under it (finishAffinity stops at that app's own screens)
        // instead of sending that app away.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (confirmOnly) finish() else if (inPlannersTask()) moveTaskToBack(true) else finishAffinity() }
        })
        // Unlocked on another lock screen (a second Planner screen, opened from a notification or a share while locked,
        // has its own): this one goes without asking.
        if (!confirmOnly) lifecycleScope.launch { appLock.locked.first { !it }; close() }
        val settings = (application as ItineraryApp).settings
        setContent {
            val mode by settings.themeMode.collectAsStateWithLifecycle()
            val appTheme by settings.appTheme.collectAsStateWithLifecycle()
            val appFont by settings.appFont.collectAsStateWithLifecycle()
            val textSizePercent by settings.textSizePercent.collectAsStateWithLifecycle()
            val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
            ItineraryTheme(appTheme = appTheme, darkTheme = dark, font = appFont, textSizePercent = textSizePercent) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (confirmOnly) "Confirm it's you" else "Planner is locked", style = MaterialTheme.typography.headlineSmall)
                        message?.let {
                            Spacer(Modifier.height(12.dp))
                            Text(it, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = ::ask) { Text(if (confirmOnly) "Confirm" else "Unlock") }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!confirmOnly && !appLock.locked.value) { close(); return }
        // Ask straight away on opening and on every return to it; after a cancel the button asks again.
        if (!asked) { asked = true; ask() }
    }

    override fun onRestart() {
        super.onRestart()
        if (!asking) asked = false
    }

    private fun done() {
        if (confirmOnly) setResult(Activity.RESULT_OK) else appLock.onUnlocked()
        close()
    }

    private fun close() {
        if (isFinishing) return
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    // Whether this is Planner's own task, not another app's that Planner was shared into (Android lists only the tasks
    // Planner started as its own).
    private fun inPlannersTask() = getSystemService(ActivityManager::class.java).appTasks.any { task ->
        val info = runCatching { task.taskInfo }.getOrNull() ?: return@any false
        (if (Build.VERSION.SDK_INT >= 29) info.taskId else @Suppress("DEPRECATION") info.id) == taskId
    }

    private fun ask() {
        message = null
        if (!AppLockRule.canAsk(this)) {
            // The phone's screen lock was removed: there is nothing to ask for, and locking the user out of their own
            // data would be worse. App lock stays on and works again once a screen lock is set.
            if (!confirmOnly) Toast.makeText(this, "Your phone has no screen lock, so Planner can't ask for it", Toast.LENGTH_LONG).show()
            done()
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { asking = false; done() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                asking = false
                when {
                    confirmOnly -> finish()
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON -> Unit
                    else -> message = errString.toString()
                }
            }
        })
        asking = true
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (confirmOnly) "Confirm it's you" else "Unlock Planner")
            .setAllowedAuthenticators(AppLockRule.AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build())
    }

    companion object {
        const val EXTRA_CONFIRM = "confirm"
    }
}
