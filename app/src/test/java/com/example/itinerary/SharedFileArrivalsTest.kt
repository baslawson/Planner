package com.example.itinerary

import com.example.itinerary.data.SharedFiles
import org.junit.Assert.*
import org.junit.Test

// R18-D2: shares of files waiting for a window, queued in memory and, at a new process's start, from their staging files.
class SharedFileArrivalsTest {
    // A share of files is queued once per process: offered again from its staging file at start, it isn't queued twice
    // (nor again once a window took it). Plain text shares have no id and are queued as they come.
    @Test fun aShareOfFilesIsQueuedOnce() {
        val id = java.util.UUID.randomUUID().toString()
        val share = SharedFiles.key(id) to null
        SharedFileArrivals.queue.value = emptyList()
        try {
            assertTrue(SharedFileArrivals.add(share))
            assertFalse(SharedFileArrivals.add(share))
            assertEquals(listOf(share), SharedFileArrivals.queue.value)
            assertTrue(SharedFileArrivals.take(share))
            assertFalse(SharedFileArrivals.add(share))
            assertTrue(SharedFileArrivals.queue.value.isEmpty())
            assertTrue(SharedFileArrivals.add("Lunch on Friday" to null))
            assertTrue(SharedFileArrivals.add("Lunch on Friday" to null))
            assertEquals(2, SharedFileArrivals.queue.value.size)
        } finally { SharedFileArrivals.queue.value = emptyList() }
    }
}
