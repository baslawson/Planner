package com.example.itinerary

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The saved Nextcloud login is unlocked once and shared by every store on the same file, until it is saved again,
 *  cleared, or changed on disk (a damaged file is still reported, never served from memory). */
class NextcloudAccountCacheTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun unlockedOnceSharedAndDroppedWhenTheFileChanges() {
        val dir = File(base.cacheDir, "account-cache").apply { deleteRecursively(); mkdirs() }
        val isolated = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = File(dir, "nobackup").apply { mkdirs() } }
        val alias = "planner.nextcloud.cache-test"
        val one = NextcloudAccountStore(isolated, alias); val two = NextcloudAccountStore(isolated, alias)
        try {
            assertNull(one.load())
            val account = NextcloudAccount.create("https://cloud.example/", "qa", "qa-test-password")
            one.save(account)
            val first = one.load()!!
            assertEquals("qa-test-password", first.password)
            // Another store on the same file gets the same unlocked account (no second decryption).
            assertSame(first, two.load())
            // Saved again: the new one, everywhere.
            one.save(NextcloudAccount.create("https://cloud.example/", "qa", "changed-password"))
            assertEquals("changed-password", two.load()!!.password)
            // Damaged on disk: reported, not served from memory.
            Thread.sleep(1100) // a different modification time
            File(dir, "nobackup/nextcloud-account").writeBytes(ByteArray(64) { 7 })
            assertThrows(BackupException::class.java) { two.load() }
            // Cleared: gone for both.
            one.clear(); assertNull(two.load())
        } finally {
            one.clear(); dir.deleteRecursively()
            runCatching { java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
        }
    }
}
