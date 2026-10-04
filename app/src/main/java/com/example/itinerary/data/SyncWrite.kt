package com.example.itinerary.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// S6-5: marks Planner's writes that take in what Nextcloud has (sync's stores: asPlannerStore, asTaskStore, asNoteStore).
// They aren't changes made in Planner, so the Repository doesn't report them as such: nothing new to send, so they
// neither wake a send nor reset a send that is backing off (SY-2). The widget still shows them.
class SyncWrite private constructor() : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SyncWrite> {
        suspend fun <T> of(block: suspend () -> T): T = withContext(SyncWrite()) { block() }
        suspend fun active(): Boolean = currentCoroutineContext()[Key] != null
    }
}
