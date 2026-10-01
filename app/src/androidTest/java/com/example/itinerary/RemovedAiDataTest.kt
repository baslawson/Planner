package com.example.itinerary

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.RemovedAiData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator

// A Gemini or OpenAI key saved by an earlier version is removed: its files, its keystore keys and the old AI switch.
class RemovedAiDataTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Test fun earlierAiKeysAreDeleted() {
        val files = RemovedAiData.FILES.flatMap { name -> listOf("", ".new", ".bak").map { File(context.noBackupFilesDir, name + it) } }
        files.forEach { it.writeText("QA saved key") }
        val unrelated = File(context.noBackupFilesDir, "qa-not-ai").apply { writeText("keep") }
        for (alias in RemovedAiData.KEY_ALIASES) KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        val prefs = context.getSharedPreferences("settings", 0)
        assertTrue(prefs.edit().putBoolean(RemovedAiData.SETTING, true).commit())
        try {
            assertTrue(files.all { it.exists() }); assertTrue(RemovedAiData.KEY_ALIASES.all { keyStore().containsAlias(it) })

            RemovedAiData.remove(context)

            assertTrue(files.none { it.exists() })
            assertTrue(RemovedAiData.KEY_ALIASES.none { keyStore().containsAlias(it) })
            assertFalse(prefs.contains(RemovedAiData.SETTING))
            assertTrue(unrelated.exists())
            RemovedAiData.remove(context) // Nothing left to remove: still fine.
        } finally { unrelated.delete() }
    }
}
