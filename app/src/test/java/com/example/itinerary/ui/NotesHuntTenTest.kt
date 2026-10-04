package com.example.itinerary.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

// Bug hunt #10 (5 Oct 2026): a Notes page looking for drafts again when another window lets a note go (NX-1, NX-2).
class NotesHuntTenTest {
    private suspend fun settle() = repeat(10) { yield() }

    // NX-1: a window that goes while the page's first look is still reading is looked at once that look is done.
    @Test fun aChangeDuringTheFirstLookIsLookedAtAfterIt() = runBlocking {
        val changes = MutableStateFlow(0)
        val looks = DraftLooks(changes.value)
        val firstDone = CompletableDeferred<Unit>()
        var count = 0
        // The first look starts (and notes the value it reads at), then another window goes while it reads.
        looks.looked(changes.value)
        val job = launch { lookOnChanges(changes, looks, { firstDone.await() }, { true }) { looks.looked(changes.value); count++ } }
        settle()
        changes.value++
        settle()
        assertEquals("waits for the first look", 0, count)
        firstDone.complete(Unit)
        settle()
        assertEquals(1, count)
        job.cancel()
    }

    // NX-1: one between the page's composition and the moment it listens is not taken for the starting value.
    @Test fun aChangeBeforeThePageListensIsNotDropped() = runBlocking {
        val changes = MutableStateFlow(4)
        val looks = DraftLooks(changes.value)
        var count = 0
        changes.value++
        val job = launch { lookOnChanges(changes, looks, {}, { true }) { looks.looked(changes.value); count++ } }
        settle()
        assertEquals(1, count)
        job.cancel()
    }

    // A rebuilt window's page starts with the old page's change in it: nothing to look at again. One the first look
    // already saw, neither.
    @Test fun aChangeAlreadySeenMakesNoLook() = runBlocking {
        val changes = MutableStateFlow(7)
        val looks = DraftLooks(changes.value)
        var count = 0
        val job = launch { lookOnChanges(changes, looks, {}, { true }) { looks.looked(changes.value); count++ } }
        settle()
        assertEquals(0, count)
        changes.value++
        looks.looked(changes.value) // the first look started after it
        val again = launch { lookOnChanges(changes, looks, {}, { true }) { count++ } }
        settle()
        assertEquals(0, count)
        job.cancel(); again.cancel()
    }

    // NX-1: a change while a look reads makes one more look after it.
    @Test fun aChangeDuringALookMakesAnother() = runBlocking {
        val changes = MutableStateFlow(0)
        val looks = DraftLooks(changes.value)
        val gate = CompletableDeferred<Unit>()
        var count = 0
        val job = launch { lookOnChanges(changes, looks, {}, { true }) { looks.looked(changes.value); count++; if (count == 1) gate.await() } }
        changes.value++
        settle()
        assertEquals(1, count)
        changes.value++
        settle()
        gate.complete(Unit)
        settle()
        assertEquals(2, count)
        job.cancel()
    }

    // NX-2: a stopped page doesn't look; a change made meanwhile is looked at as it starts again. None with a note open.
    @Test fun aStoppedPageLooksOnlyOnceItStartsAgain() = runBlocking {
        val changes = MutableStateFlow(0)
        val looks = DraftLooks(changes.value)
        var count = 0
        var noteOpen = false
        val look: suspend () -> Unit = { looks.looked(changes.value); count++ }
        val started = launch { lookOnChanges(changes, looks, {}, { !noteOpen }, look) }
        settle()
        started.cancel() // ON_STOP: repeatOnLifecycle(STARTED) cancels the collecting
        changes.value++; changes.value++
        settle()
        assertEquals(0, count)
        val again = launch { lookOnChanges(changes, looks, {}, { !noteOpen }, look) }
        settle()
        assertEquals(1, count)
        noteOpen = true
        changes.value++
        settle()
        assertEquals("a note open: its closing looks again", 1, count)
        again.cancel()
    }
}
