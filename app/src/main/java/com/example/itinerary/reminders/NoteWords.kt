package com.example.itinerary.reminders

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * D6-3 (R-2): no word of a note on the lock screen. VISIBILITY_PRIVATE and a public version hide them only when the
 * phone's lock-screen setting hides sensitive content; with "show all content" (the usual default) Android shows the
 * notification itself. So a note reminder posted while the phone is locked, or its screen is off, says only
 * "Note reminder" in the notification itself, and is shown again quietly with the note's words once the phone is
 * unlocked ([later]). That needs Planner's process to still be running at the unlock; if Android has ended it, the
 * notification stays word-free (tapping it still opens the note).
 *
 * Not covered: a notification shown with its words while unlocked keeps them if the phone is locked afterwards.
 */
object NoteWords {
    /** Whether a note reminder posted now must leave out the note's words. */
    fun hidden(keyguardLocked: Boolean, interactive: Boolean): Boolean = keyguardLocked || !interactive

    /** A seam for tests (set back afterwards). */
    @Volatile internal var locked: (Context) -> Boolean = { context ->
        hidden(context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true,
            context.getSystemService(PowerManager::class.java)?.isInteractive ?: false)
    }

    fun hide(context: Context): Boolean = runCatching { locked(context) }.getOrDefault(true)

    // Notification tag → what shows it again with its words.
    private val waiting = linkedMapOf<String, () -> Unit>()
    private var receiver: BroadcastReceiver? = null
    private var registeredOn: Context? = null

    /** The notification [tag] was posted without its words: [repost] shows them once the phone is unlocked. */
    fun later(context: Context, tag: String, repost: () -> Unit) {
        val app = context.applicationContext ?: context
        synchronized(this) {
            waiting[tag] = repost
            if (receiver != null) return
            val unlocked = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) = reveal(app)
            }
            // USER_PRESENT once the lock screen is passed; SCREEN_ON for a phone with no lock screen.
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT).apply { addAction(Intent.ACTION_SCREEN_ON) }
            runCatching { ContextCompat.registerReceiver(app, unlocked, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
                .onSuccess { receiver = unlocked; registeredOn = app }
                .onFailure { android.util.Log.w("NoteWords", "Can't wait for the unlock", it) }
        }
    }

    /** The notification [tag] was posted with its words: nothing to show again. */
    @Synchronized fun shown(tag: String) { waiting.remove(tag) }

    /** Unlocked: the notifications still there, posted without their words, are shown again with them. */
    internal fun reveal(context: Context) {
        if (hide(context) || !DirectBoot.isUnlocked(context)) return
        val due = synchronized(this) {
            val list = waiting.toList()
            waiting.clear()
            receiver?.let { r -> runCatching { registeredOn?.unregisterReceiver(r) } }
            receiver = null; registeredOn = null
            list
        }
        due.forEach { (tag, repost) ->
            if (notificationShown(context, tag)) runCatching(repost).onFailure { android.util.Log.w("NoteWords", "Couldn't show $tag again", it) }
        }
    }
}
