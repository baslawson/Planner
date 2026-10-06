package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test

// H17-D3: one Recently deleted bundle that can't be read made cleanup, purging, Delete forever and backups throw for good.
// Each is now read on its own: the unreadable one is reported and comes back null, the others as they are.
class ReadEachDeletedTest {
    private val empty = DeletedContents(emptyList(), emptyList(), emptyList(), emptyList())

    @Test fun anUnreadableBundleIsReportedAndTheRestAreRead() {
        val reported = mutableListOf<String>()
        val read = readEachDeleted(listOf("good", "bad", "good too"), { if (it == "bad") throw IllegalArgumentException("damaged") else empty }) { entry, e ->
            assertTrue(e is IllegalArgumentException)
            reported += entry
        }
        assertEquals(listOf(empty, null, empty), read)
        assertEquals(listOf("bad"), reported)
    }

    @Test fun allReadableReportsNothing() {
        val read = readEachDeleted(listOf(1, 2), { empty }) { _, _ -> fail("nothing to report") }
        assertTrue(read.none { it == null })
        assertTrue(readEachDeleted(emptyList<Int>(), { empty }) { _, _ -> fail() }.isEmpty())
    }
}
