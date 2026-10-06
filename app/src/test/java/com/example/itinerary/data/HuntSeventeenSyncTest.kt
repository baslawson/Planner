package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test

// Sync items from bug hunt 17 (H17-S1), as approved.
class HuntSeventeenSyncTest {
    private fun remote(title: String, content: String) = RemoteNote(1, "e", title, content, "", false)
    private fun note(title: String, content: String) = PlannerNote(title = title, content = content)

    @Test fun sameWordsAndTitlePair() {
        val mine = note("Shopping", "milk")
        assertSame(mine, NoteMapping.twin(listOf(note("Other", "milk"), mine), remote("Shopping", "milk")))
        // Nextcloud tidied the title.
        val colon = note("Plan: May", "x")
        assertSame(colon, NoteMapping.twin(listOf(colon), remote("Plan May", "x")))
    }

    @Test fun titleOnlyNotesDontPairByBlankText() {
        assertNull(NoteMapping.twin(listOf(note("Call Bob", "")), remote("Buy bread", "")))
        val same = note("Call Bob", "")
        assertSame(same, NoteMapping.twin(listOf(same), remote("Call Bob", "")))
    }

    @Test fun differentTitlesDontPairByText() {
        assertNull(NoteMapping.twin(listOf(note("Monday", "Same list")), remote("Tuesday", "Same list")))
        assertNull(NoteMapping.twin(listOf(note("🎄", "x")), remote("🎁", "x")))
    }

    @Test fun derivedTitlePairsByText() {
        // Planner's has no title: Nextcloud's named it after the first line, or kept a title of its own.
        val untitled = note("", "milk\neggs")
        assertSame(untitled, NoteMapping.twin(listOf(untitled), remote("milk", "milk\neggs")))
        assertSame(untitled, NoteMapping.twin(listOf(untitled), remote("Shopping", "milk\neggs")))
        // Nextcloud's title is only the first line, Planner's is its own.
        val titled = note("Shopping", "milk\neggs")
        assertSame(titled, NoteMapping.twin(listOf(titled), remote("milk", "milk\neggs")))
        // Line ends don't matter.
        assertSame(titled, NoteMapping.twin(listOf(titled), remote("milk", "milk\r\neggs\r\n")))
    }
}
