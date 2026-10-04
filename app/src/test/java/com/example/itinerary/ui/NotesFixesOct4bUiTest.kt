package com.example.itinerary.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Bug hunt 4 Oct (b): the Notes sync message (N6-2) and a camera photo left in a recovered draft (SH-9). */
class NotesFixesOct4bUiTest {
    // N6-2: "left as they are" was dropped whenever conflict copies were made.
    @Test fun syncMessageKeepsBothCounts() {
        assertEquals("", syncCounts(0, 0))
        assertEquals(" · 1 conflict copy made (changed in both places)", syncCounts(1, 0))
        assertEquals(" · 2 notes left as they are (too long for Planner, or Nextcloud wouldn't take the change)", syncCounts(0, 2))
        assertEquals(" · 3 conflict copies made (changed in both places) · 1 note left as they are (too long for Planner, " +
            "or Nextcloud wouldn't take the change)", syncCounts(3, 1))
    }

    // SH-9: a photo taken is attached; an empty file (nothing taken) goes; one attached already stays as it is.
    @Test fun aLeftoverCameraPhoto() {
        val dir = createTempDirectory()
        try {
            val taken = File(dir, "taken.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val photo = leftoverPhoto(taken, "Task photo.jpg", emptyList())
            assertEquals("taken.jpg", photo?.fileName); assertEquals("Task photo.jpg", photo?.name); assertEquals("image/jpeg", photo?.mimeType)
            assertNull(leftoverPhoto(taken, "Task photo.jpg", listOf(photo!!)))
            assertTrue(taken.exists())
            val empty = File(dir, "empty.jpg").apply { createNewFile() }
            assertNull(leftoverPhoto(empty, "Note photo.jpg", emptyList()))
            assertFalse(empty.exists())
            assertNull(leftoverPhoto(File(dir, "missing.jpg"), "Note photo.jpg", emptyList()))
        } finally { dir.deleteRecursively() }
    }

    private fun createTempDirectory(): File = kotlin.io.path.createTempDirectory("sh9").toFile()
}
