package com.example.itinerary.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

// Turns a database flow into what a screen reads: it keeps running for 5 seconds after the screen stops looking (so a
// rotation doesn't reload everything) and then stops. [initial] is what the screen sees until the first answer.
fun <T> Flow<T>.stateInWhileVisible(scope: CoroutineScope, initial: T): StateFlow<T> =
    stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)
