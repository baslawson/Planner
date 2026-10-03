package com.example.itinerary.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

// DU-9: the pieces CalendarSync and TaskSync now share, with the texts they had before.
class SyncSharedHelpersTest {
    @Test fun conflictSummaryTexts() {
        assertEquals(CalendarSync.State(message = "1 event was changed in both places. Choose which version to keep.", error = true),
            CalendarSync.conflictSummary("event", 1, 4))
        assertEquals(CalendarSync.State(message = "3 tasks were changed in both places. Choose which version to keep.", error = true),
            CalendarSync.conflictSummary("task", 3, 0))
        assertEquals(CalendarSync.State(message = "1 task was changed on Nextcloud; Planner will check at the next sync."),
            CalendarSync.conflictSummary("task", 0, 1))
        assertEquals(CalendarSync.State(message = "2 events were changed on Nextcloud; Planner will check at the next sync."),
            CalendarSync.conflictSummary("event", 0, 2))
        assertNull(CalendarSync.conflictSummary("event", 0, 0))
    }

    @Test fun refusedTexts() {
        assertEquals("Nextcloud refused 1 task (HTTP 400). The others were sent; Planner tries again at the next sync.", TaskSync.refusedMessage(1, 400))
        assertEquals("Nextcloud refused 2 events (HTTP 412). The others were sent; Planner tries again at the next sync.", CalendarSync.refusedMessage(2, 412))
    }

    @Test fun newUidsArePlannerUids() {
        val event = CalendarSync.newEventUid()
        val task = TaskSync.newTaskUid()
        assertTrue(event, Regex("planner-[0-9a-f-]{36}@planner").matches(event))
        assertTrue(task, Regex("planner-task-[0-9a-f-]{36}@planner").matches(task))
        assertTrue(CalendarSync.isPlannerUid(event)); assertTrue(TaskSync.isPlannerUid(task))
        assertTrue(CalendarSync.newEventUid() != event)
    }

    // A burst of requests is one send; a request while it sends is one more send after it.
    @Test fun launchedSendsCoalesce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val debounce = SendDebounce()
            val sends = AtomicInteger()
            val sending = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val secondDone = CompletableDeferred<Unit>()
            lateinit var request: () -> Unit
            request = {
                debounce.launch(scope, 50, send = {
                    when (sends.incrementAndGet()) {
                        1 -> { sending.complete(Unit); release.await() }
                        else -> secondDone.complete(Unit)
                    }
                }, sendAgain = { request() })
            }
            repeat(5) { request() }
            withTimeout(5_000) { sending.await() }
            assertEquals(1, sends.get())
            repeat(3) { request() } // while sending: not cancelled, one more pass afterwards
            release.complete(Unit)
            withTimeout(5_000) { secondDone.await() }
            delay(200)
            assertEquals(2, sends.get())
        } finally { scope.cancel() }
    }
}
