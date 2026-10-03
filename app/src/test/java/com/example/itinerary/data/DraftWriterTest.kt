package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// B1: the editors' background draft writer. The "disk" here is a map; what matters is what ends up in it, and when.
class DraftWriterTest {
    private val disk = Collections.synchronizedMap(HashMap<String, String>())
    private val writes = Collections.synchronizedList(ArrayList<String>())
    private fun DraftWriter.type(key: String, value: String) = schedule(key, value) { disk[key] = value; writes += value }

    @Test fun onlyTheNewestDraftIsWrittenAfterThePause() {
        val writer = DraftWriter(delayMs = 200)
        "Dentist".indices.forEach { writer.type("event", "Dentist".take(it + 1)) }
        assertEquals("Dentist", writer.pending("event")) // a reader sees it before the disk does
        assertTrue(writes.isEmpty())
        Thread.sleep(800)
        assertEquals(listOf("Dentist"), writes)
        assertEquals("Dentist", disk["event"])
        assertNull(writer.pending("event"))
    }

    @Test fun flushWritesWhatIsWaitingAtOnce() {
        val writer = DraftWriter(delayMs = 60_000)
        writer.type("event", "a"); writer.type("new", "task")
        writer.flush()
        assertEquals(mapOf("event" to "a", "new" to "task"), HashMap(disk))
        assertTrue(writer.pendingValues().isEmpty())
        writer.flush() // nothing left: nothing written twice
        assertEquals(2, writes.size)
    }

    @Test fun clearDropsAWaitingDraftSoItNeverLandsAfterIt() {
        val writer = DraftWriter(delayMs = 100)
        writer.type("event", "typed")
        writer.now("event") { disk.remove("event") } // Discard
        assertNull(writer.pending("event"))
        Thread.sleep(500) // past the pause: the old draft must not come back
        writer.flush()
        assertNull(disk["event"])
        assertTrue(writes.isEmpty())
    }

    @Test fun clearWaitsForAWriteUnderWayAndStillWins() {
        val writer = DraftWriter(delayMs = 0)
        val writing = CountDownLatch(1); val release = CountDownLatch(1)
        writer.schedule("event", "typed") { writing.countDown(); release.await(5, TimeUnit.SECONDS); disk["event"] = "typed" }
        assertTrue(writing.await(5, TimeUnit.SECONDS)) // the background write has started
        val cleared = Thread { writer.now("event") { disk.remove("event") } }.apply { start() }
        Thread.sleep(100)
        assertTrue(cleared.isAlive) // waiting for the write in progress, not racing it
        release.countDown(); cleared.join(5000)
        assertNull(disk["event"])
    }

    @Test fun aNewerDraftDuringAWriteIsWrittenNext() {
        val writer = DraftWriter(delayMs = 0)
        val writing = CountDownLatch(1); val release = CountDownLatch(1)
        writer.schedule("event", "old") { writing.countDown(); release.await(5, TimeUnit.SECONDS); disk["event"] = "old"; writes += "old" }
        assertTrue(writing.await(5, TimeUnit.SECONDS))
        assertEquals("old", writer.pending("event")) // still the current one while it is written
        writer.type("event", "new")
        assertEquals("new", writer.pending("event"))
        release.countDown()
        writer.flush()
        assertEquals("new", disk["event"])
        assertEquals(listOf("old", "new"), writes)
    }

    @Test fun aDirectWriteReplacesTheWaitingOne() {
        val writer = DraftWriter(delayMs = 60_000)
        writer.type("t1", "typed")
        writer.now("t1") { disk["t1"] = "with attachment" }
        writer.flush()
        assertEquals("with attachment", disk["t1"])
    }

    @Test fun aFailedWriteIsReportedAndDropped() {
        val writer = DraftWriter(delayMs = 60_000)
        val failures = ArrayList<Exception>()
        writer.schedule("event", "x", { failures += it }) { throw java.io.IOException("disk full") }
        writer.flush()
        assertEquals("disk full", failures.single().message)
        assertNull(writer.pending("event"))
    }
}
