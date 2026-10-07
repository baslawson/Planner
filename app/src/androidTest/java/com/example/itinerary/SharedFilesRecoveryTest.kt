package com.example.itinerary

import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.Attachment
import com.example.itinerary.data.SharedFiles
import com.example.itinerary.data.SharedFilesStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File

// Bug hunt 18, R18-D2: shares of files are kept on disk until a window takes them, so one isn't lost when Android ends
// Planner first; one caught half way through copying is released at the next start.
class SharedFilesRecoveryTest {
    private fun file(name: String) = Attachment(itemId = 0, name = name, fileName = "$name.bin", mimeType = "application/octet-stream")

    @Test fun stagingFilesAreOfferedAgainUntilTakenAndHalfCopiedOnesAreReleased() {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "shared-files-recovery").apply { deleteRecursively() }
        try {
            val store = SharedFilesStore(dir)
            store.write("waiting", SharedFiles.Staged("Receipt", null, listOf(file("a"))))
            File(dir, "waiting.json").setLastModified(System.currentTimeMillis() - 60_000)
            store.write("second", SharedFiles.Staged(null, "Photos", listOf(file("b"))))
            store.write("taken", SharedFiles.Staged(null, null, listOf(file("c")))); store.markTaken("taken")
            store.write("half", SharedFiles.Staged(null, null, listOf(file("d"))), copying = true)
            val found = store.recover()
            assertEquals(listOf("waiting", "second"), found.offer)
            assertEquals(listOf("d.bin"), found.release)
            assertNull("forgotten", store.read("half"))
            assertEquals(listOf("a.bin"), store.read("waiting")!!.files.map { it.fileName })
            // Closed: its staging file and taken mark go.
            store.delete("taken")
            assertFalse(File(dir, "taken.json").exists() || File(dir, "taken.taken").exists())
            // A week on, the sweep takes the taken marks too.
            store.markTaken("second")
            val later = System.currentTimeMillis() + SharedFilesStore.SWEEP_AGE_MS + 60_000
            assertEquals(setOf("a.bin", "b.bin"), store.sweep(now = later).toSet())
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
