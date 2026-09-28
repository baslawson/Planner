package com.example.itinerary

import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.NextcloudAccount
import com.example.itinerary.data.NextcloudAccountStore
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in setup for manually checking the normal app after process death. No network requests.
 * Requires preserved emulator data; the caller must restore it after the check.
 */
@HarnessStage
class NextcloudPathColdStartTest {
    @Test fun prepare() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("preparePathEvidence") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(!File(context.noBackupFilesDir, "nextcloud-account").exists()) {
            "Refusing to replace an existing connection"
        }
        NextcloudAccountStore(context).save(NextcloudAccount.create(
            "https://example.invalid", "qa-path", "local-test-only"))
    }
}
