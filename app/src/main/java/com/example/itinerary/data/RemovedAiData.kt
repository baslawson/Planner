package com.example.itinerary.data

import android.content.Context
import java.io.File
import java.security.KeyStore

/**
 * Planner no longer offers AI help in Quick entry. Earlier versions could keep a personal Gemini or OpenAI key on the
 * phone, so it is deleted rather than left behind unused. Safe to run on every start: it only removes what exists.
 */
object RemovedAiData {
    internal val FILES = listOf("gemini-key", "gemini-key-openai", "gemini-key-provider", "quick-ai-connection")
    internal val KEY_ALIASES = listOf("planner.gemini.v1", "planner.quick-ai.v1")
    internal const val SETTING = "ai_features_enabled"

    fun remove(context: Context) {
        // AtomicFile also leaves ".new" and ".bak" files beside the real one.
        for (name in FILES) for (suffix in listOf("", ".new", ".bak")) File(context.noBackupFilesDir, name + suffix).delete()
        runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for (alias in KEY_ALIASES) if (store.containsAlias(alias)) store.deleteEntry(alias)
        }
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.contains(SETTING)) prefs.edit().remove(SETTING).apply()
    }
}
