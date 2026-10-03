package com.example.itinerary

import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Updates from GitHub, against a fake GitHub: offered only when newer, at most once a day at start-up, Skip, the
 *  checksum, the background download, and nothing at all in a build that can't update itself. */
class UpdatesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun offersNewerReleasesAndChecksTheDownload() = runBlocking {
        val fake = FakeGitHub()
        val folder = File(context.cacheDir, "updates-test").apply { deleteRecursively() }
        val prefs = context.getSharedPreferences("updates_test", 0).apply { edit().clear().commit() }
        var clock = 1_800_000_000_000L
        try {
            val updates = Updates(prefs, ReleaseApi(fake.http, fake.base), "0.0.14", folder, supported = true, now = { clock })
            assertTrue(updates.checkOnStart.value); assertFalse(updates.autoDownload.value)

            // A newer release: offered, nothing downloaded yet.
            updates.checkIfDue()
            val available = updates.state.value as Updates.State.Available
            assertEquals("0.0.15", available.release.version)
            assertEquals("## New\n- **Faster** sync\n- A fix", available.release.notes)
            assertEquals(listOf("/repos/baslawson/Planner/releases/latest"), fake.requests)

            // Once a day: a start an hour later doesn't ask GitHub; a day later it does.
            clock += 60 * 60 * 1000L; updates.checkIfDue(); assertEquals(1, fake.requests.size)
            clock += Updates.DAY_MS; updates.checkIfDue(); assertEquals(2, fake.requests.size)

            // Skip this version: start-up checks stay quiet about it; Check now offers it again.
            updates.skip(available.release)
            clock += Updates.DAY_MS; updates.checkIfDue()
            assertTrue(updates.state.value is Updates.State.UpToDate)
            updates.check(); assertTrue(updates.state.value is Updates.State.Available)

            // A wrong checksum: nothing kept, nothing offered to install.
            fake.badSha = true
            updates.download(available.release)
            assertTrue((updates.state.value as Updates.State.Failed).message.contains("checksum"))
            assertTrue(folder.listFiles().orEmpty().isEmpty())
            // The right one: downloaded and checked.
            fake.badSha = false
            updates.download(available.release)
            val ready = updates.state.value as Updates.State.Ready
            assertArrayEquals(fake.apk, ready.file.readBytes())
            // Planner restarted (as Android does after "install unknown apps" is allowed): still ready, not downloaded again.
            val downloads = fake.requests.count { it == "/dl/Planner.apk" }
            val restarted = Updates(prefs, ReleaseApi(fake.http, fake.base), "0.0.14", folder, supported = true, now = { clock })
            assertEquals("0.0.15", (restarted.state.value as Updates.State.Ready).release.version)
            assertEquals(downloads, fake.requests.count { it == "/dl/Planner.apk" })
            // After the update itself (0.0.15 installed), the old download goes.
            Updates(prefs, ReleaseApi(fake.http, fake.base), "0.0.15", folder, supported = true, now = { clock }).let {
                assertEquals(Updates.State.Idle, it.state.value); assertTrue(folder.listFiles().orEmpty().isEmpty())
            }
            updates.download(available.release) // back for the checks below

            // The same or an older version, or none: up to date, and the old download goes.
            fake.version = "v0.0.14"; updates.check(); assertTrue(updates.state.value is Updates.State.UpToDate)
            assertTrue(folder.listFiles().orEmpty().isEmpty())
            fake.version = "v0.0.9"; updates.check(); assertTrue(updates.state.value is Updates.State.UpToDate)
            fake.version = null; updates.check(); assertTrue(updates.state.value is Updates.State.UpToDate)
            // Drafts and unreadable tags are never offered.
            fake.version = "v0.0.16"; fake.draft = true; updates.check(); assertTrue(updates.state.value is Updates.State.UpToDate)
            fake.draft = false; fake.version = "latest"; updates.check(); assertTrue(updates.state.value is Updates.State.UpToDate)

            // Download automatically: found, downloaded in the background (not "asked"), then ready to install.
            fake.version = "v0.0.16"; updates.setAutoDownload(true)
            updates.check()
            assertEquals("0.0.16", (updates.state.value as Updates.State.Ready).release.version)

            // Switched off: no start-up check.
            updates.setCheckOnStart(false); val before = fake.requests.size
            clock += 2 * Updates.DAY_MS; updates.checkIfDue(); assertEquals(before, fake.requests.size)

            // Offline from Settings: said; at start-up: quiet.
            updates.setCheckOnStart(true)
            fake.server.shutdown()
            updates.check(); assertTrue(updates.state.value is Updates.State.Failed)
            clock += 2 * Updates.DAY_MS; updates.checkIfDue(); assertEquals(Updates.State.Idle, updates.state.value)
        } finally {
            runCatching { fake.server.shutdown() }
            folder.deleteRecursively(); context.deleteSharedPreferences("updates_test")
        }
    }

    @Test fun aBuildThatCantUpdateNeverAsks() = runBlocking {
        val fake = FakeGitHub()
        val prefs = context.getSharedPreferences("updates_test2", 0).apply { edit().clear().commit() }
        try {
            val updates = Updates(prefs, ReleaseApi(fake.http, fake.base), "0.0.14", File(context.cacheDir, "updates-test2"), supported = false)
            updates.checkIfDue(); updates.check()
            assertTrue(fake.requests.isEmpty()); assertEquals(Updates.State.Idle, updates.state.value)
            // This test app is not the release app, so the real one is switched off here too.
            assertFalse((context.applicationContext as ItineraryApp).updates.supported)
        } finally { fake.server.shutdown(); context.deleteSharedPreferences("updates_test2") }
    }
}
