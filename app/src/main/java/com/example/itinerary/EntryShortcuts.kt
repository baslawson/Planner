package com.example.itinerary

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Only these explicit launcher actions can request a fresh editor. */
object EntryShortcuts {
    const val ADD_EVENT = "com.example.itinerary.ADD_EVENT"
    const val ADD_BILL = "com.example.itinerary.ADD_BILL"
    const val SCAN = "com.example.itinerary.SCAN_DOCUMENT"
    fun accepts(action: String?) = action == ADD_EVENT || action == ADD_BILL || action == SCAN

    // As a widget tap opens Planner: into its existing task, where an open MainActivity gets the shortcut in onNewIntent.
    const val FORWARD_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
}

/**
 * Where the launcher's shortcuts (res/xml/shortcuts.xml) land. Android starts a static shortcut with NEW_TASK |
 * CLEAR_TASK, which aimed at MainActivity destroyed a running Planner with the editor open in it, and the new Planner
 * then refused the shortcut because of that editor's draft (U-N3). This activity has a task of its own (an empty
 * taskAffinity in the manifest), so only that is cleared; it hands the shortcut to Planner and closes, unseen.
 */
class EntryShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.action?.takeIf(EntryShortcuts::accepts)?.let { action ->
            startActivity(Intent(this, MainActivity::class.java).setAction(action).addFlags(EntryShortcuts.FORWARD_FLAGS))
        }
        finish()
    }
}
