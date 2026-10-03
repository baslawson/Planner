package com.example.itinerary.data

import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// The small private files Planner keeps beside the database (editor drafts, the bill scanner's session). Each store
// keeps its own lock; these only do the reading and the all-or-nothing write.

/** Writes [text] whole or not at all: a write that fails (anything thrown) leaves the previous file and rethrows. */
internal fun AtomicFile.writeText(text: String) {
    val stream = startWrite()
    try { stream.write(text.toByteArray()); finishWrite(stream) }
    catch (e: Throwable) { failWrite(stream); throw e }
}

/** The file's text, or null when there is no file. */
internal fun AtomicFile.readTextOrNull(): String? =
    if (!baseFile.exists()) null else openRead().bufferedReader().use { it.readText() }

/** How many editors of one kind are on screen now (event, task and note editors each keep one). */
class EditorCounter {
    private val count = MutableStateFlow(0)
    val open: StateFlow<Int> = count.asStateFlow()
    fun opened() = count.update { it + 1 }
    fun closed() = count.update { (it - 1).coerceAtLeast(0) }
}
