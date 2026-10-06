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
}
