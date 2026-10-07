package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test

class SharedFilesTest {
    private fun file(name: String) = Attachment(itemId = 0, name = name, fileName = "x.bin", mimeType = "application/octet-stream")

    @Test fun keyRoundTripsAndPlainTextIsNotAShareOfFiles() {
        val id = "0b1c2d3e-aaaa-4bbb-8ccc-123456789abc"
        assertEquals(id, SharedFiles.idOf(SharedFiles.key(id)))
        assertNull(SharedFiles.idOf("Lunch with Sam on Friday"))
        assertNull(SharedFiles.idOf(null))
        // Only a plain id: nothing that could reach outside the staging folder.
        assertNull(SharedFiles.idOf(SharedFiles.MARKER + "../attachments/x"))
        assertNull(SharedFiles.idOf(SharedFiles.MARKER))
    }

    @Test fun titleIsTheTextThenTheSubjectThenTheFirstFileName() {
        val files = listOf(file("Receipt March.pdf"), file("photo.jpg"))
        assertEquals("Fix the shelf", SharedFiles.draft(SharedFiles.Staged("Fix the shelf\nmeasure first", null, files)).title)
        assertEquals("Fix the shelf\nmeasure first", SharedFiles.draft(SharedFiles.Staged("Fix the shelf\nmeasure first", null, files)).notes)
        assertEquals("March bills", SharedFiles.draft(SharedFiles.Staged(null, "March bills", files)).title)
        assertEquals("Receipt March", SharedFiles.draft(SharedFiles.Staged("  ", null, files)).title)
        assertEquals("", SharedFiles.draft(SharedFiles.Staged(null, null, files)).notes)
        assertEquals(".bashrc", SharedFiles.withoutExtension(".bashrc"))
        assertEquals("archive.tar", SharedFiles.withoutExtension("archive.tar.gz"))
    }

    @Test fun summaryNamesTheFirstThree() {
        assertEquals("1 file: a.jpg", SharedFiles.summary(listOf(file("a.jpg"))))
        assertEquals("2 files: a.jpg, b.pdf", SharedFiles.summary(listOf(file("a.jpg"), file("b.pdf"))))
        assertEquals("5 files: a, b, c and 2 more", SharedFiles.summary(listOf("a", "b", "c", "d", "e").map(::file)))
    }

    // R18-D2: what a new process does with the shares an earlier one left: those no window took are offered again, oldest
    // first; one caught half way through copying is released and forgotten; taken ones and week-old ones are left to the
    // window that has them and to the sweep.
    @Test fun sharesLeftByAnEarlierProcessAreOfferedOrReleased() {
        val now = 10 * SharedFilesStore.SWEEP_AGE_MS
        fun waiting(id: String, age: Long, taken: Boolean = false, copying: Boolean = false, files: List<String> = listOf("$id.jpg")) =
            SharedFilesStore.Waiting(id, now - age, taken, copying, files)
        val r = SharedFilesStore.recovery(listOf(
            waiting("newer", 1_000), waiting("older", 60_000), waiting("taken", 2_000, taken = true),
            waiting("half", 3_000, copying = true, files = listOf("h1.pdf", "h2.pdf")), waiting("weekold", SharedFilesStore.SWEEP_AGE_MS + 1),
            waiting("empty", 4_000, copying = true, files = emptyList())), now)
        assertEquals(listOf("older", "newer"), r.offer)
        assertEquals(listOf("h1.pdf", "h2.pdf"), r.release)
        assertEquals(listOf("half", "empty"), r.forget)
        val none = SharedFilesStore.recovery(emptyList(), now)
        assertTrue(none.offer.isEmpty() && none.release.isEmpty() && none.forget.isEmpty())
    }
}
