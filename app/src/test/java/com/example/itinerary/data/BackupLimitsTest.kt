package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// E5: a crafted backup (a small zip that unpacks to gigabytes) is read only up to a limit, never in full: before, data.json
// was read whole into memory (OutOfMemoryError, a crash) and attachments were copied until the phone was full.
class BackupLimitsTest {
    // Endless zeros: what a zip bomb's entry unpacks to. Counts what was read.
    private class Endless : InputStream() {
        var read = 0L
        override fun read(): Int { read++; return 0 }
        override fun read(b: ByteArray, off: Int, len: Int): Int { b.fill(0, off, off + len); read += len; return len }
    }

    @Test fun dataIsReadOnlyUpToTheLimit() {
        val endless = Endless()
        assertNull(BackupManager.readLimited(endless, 1_000_000))
        assertTrue(endless.read < 1_100_000) // stopped just past the limit
        assertArrayEquals("{}".toByteArray(), BackupManager.readLimited("{}".byteInputStream(), 2))
        assertNull(BackupManager.readLimited("{} ".byteInputStream(), 2))
        assertEquals(64L * 1024 * 1024, BackupManager.MAX_DATA_BYTES)
    }

    @Test fun anAttachmentIsCopiedOnlyUpToTheLimit() {
        val endless = Endless()
        val out = ByteArrayOutputStream()
        val tooLarge = assertThrows(BackupManager.TooLargeException::class.java) { BackupManager.copyLimited(endless, out, 1_000_000) }
        assertTrue(tooLarge.space) // a limit below the largest attachment is the space left
        assertTrue(out.size() <= 1_000_000 && endless.read < 1_100_000)
        val copy = ByteArrayOutputStream()
        assertEquals(5L, BackupManager.copyLimited("hello".byteInputStream(), copy, 5))
        assertEquals("hello", copy.toString())
        assertFalse(assertThrows(BackupManager.TooLargeException::class.java) {
            BackupManager.copyLimited(Endless(), java.io.OutputStream.nullOutputStream(), BackupManager.MAX_ATTACHMENT_BYTES)
        }.space)
    }

    @Test fun aZipBombsEntryIsCutOffNotUnpacked() {
        // 200 MB of zeros in a zip of a few hundred KB, as data.json.
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z ->
                z.putNextEntry(ZipEntry("data.json"))
                val chunk = ByteArray(1024 * 1024)
                repeat(200) { z.write(chunk) }
                z.closeEntry()
            }
        }.toByteArray()
        assertTrue(zip.size < 1_000_000)
        ZipInputStream(zip.inputStream()).use { z ->
            z.nextEntry
            assertNull(BackupManager.readLimited(z, BackupManager.MAX_DATA_BYTES))
        }
    }
}
