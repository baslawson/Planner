package com.example.itinerary.data

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

// D1: no Recently deleted row, and no task row, may outgrow Android's 2 MB cursor window (one such row makes every read
// of its table fail).
class DeletedPayloadsTest {
    private val dir = Files.createTempDirectory("deleted-payloads").toFile()
    private val payloads = DeletedPayloads(File(dir, "recently-deleted"))
    @After fun cleanUp() { dir.deleteRecursively() }

    private fun bundle(chars: Int) = "{\"items\":\"" + "é".repeat(chars) + "\"}"

    @Test fun smallBundlesStayInTheRowAsBefore() {
        val small = bundle(1000)
        assertEquals(small, payloads.store(small))
        assertEquals(small, payloads.read(small))
        payloads.delete(small) // nothing to delete
        assertFalse(File(dir, "recently-deleted").exists())
    }

    @Test fun aBigBundleGoesToAFileAndItsRowStaysSmall() {
        val big = bundle(3_000_000) // about 6 MB as UTF-8
        val row = payloads.store(big)
        assertTrue(row.length < 100)
        assertEquals(big, payloads.read(row))
        val files = File(dir, "recently-deleted").listFiles()!!.toList()
        assertEquals(1, files.size)
        assertFalse(files.single().name.endsWith(".tmp"))
        payloads.delete(row)
        assertTrue(File(dir, "recently-deleted").listFiles()!!.isEmpty())
        // Each bundle has its own file, even with the same contents.
        assertNotEquals(payloads.store(big), payloads.store(big))
    }

    @Test fun theLimitKeepsEveryRowWellInsideTheWindow() {
        // Worst case for text from the BMP: 3 bytes per character in UTF-8.
        assertTrue(DeletedPayloads.INLINE_LIMIT * 3 < 2 * 1024 * 1024)
        val atLimit = "x".repeat(DeletedPayloads.INLINE_LIMIT)
        assertEquals(atLimit, payloads.store(atLimit))
        assertNotEquals(atLimit + "x", payloads.store(atLimit + "x"))
    }

    @Test fun aRowCannotNameAFileOutsideItsFolder() {
        assertThrows(IllegalArgumentException::class.java) { payloads.read("file:../editor-draft.json") }
        assertThrows(IllegalArgumentException::class.java) { payloads.delete("file:../attachments") }
    }

    private fun attachment(name: String, text: Int) = Attachment(itemId = 0, name = name, fileName = "$name.pdf",
        mimeType = "application/pdf", recognizedText = "a".repeat(text), textStatus = "READY")

    @Test fun aTaskKeepsAtMostOneDocumentsWorthOfRecognisedText() {
        // 100 attachments of 200,000 characters each would be a 20-million-character row.
        val task = PlannerTask(title = "Tax papers", attachments = List(3) { attachment("doc$it", 150_000) })
        assertThrows(IllegalArgumentException::class.java) { Tasks.validate(task) }
        val capped = Tasks.capText(task)
        Tasks.validate(capped)
        assertEquals(Tasks.MAX_TEXT, capped.attachments.sumOf { it.recognizedText.length })
        assertEquals(listOf(150_000, 50_000, 0), capped.attachments.map { it.recognizedText.length })
        assertEquals(listOf("READY", "PARTIAL", "PARTIAL"), capped.attachments.map { it.textStatus })
        // Every attachment stays; only text is cut.
        assertEquals(task.attachments.map { it.fileName }, capped.attachments.map { it.fileName })
        val small = PlannerTask(title = "Small", attachments = listOf(attachment("one", 1000)))
        assertSame(small, Tasks.capText(small))
    }
}
