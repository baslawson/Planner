package com.example.itinerary.ui

import org.junit.Assert.assertEquals
import org.junit.Test

// 1 Oct 2026 review: an accent stored as a separate combining mark ("e" + U+0300) still highlights its whole word.
class SearchHighlightTest {
    @Test fun decomposedAccentsHighlightTheWholeWord() {
        val decomposed = "Crème brûlée"
        assertEquals(listOf(0..5), matchedWords(decomposed, listOf("creme")))
        assertEquals(listOf(7..14), matchedWords(decomposed, listOf("brulee")))
        assertEquals(listOf(0..4), matchedWords("Crème brûlée", listOf("creme")))
    }
}
