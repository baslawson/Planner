package com.example.itinerary.ui

import com.example.itinerary.data.SearchOutcome
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate

// Search results while the data changes: the last result stays until the new one is ready; a new search waits.
class SearchOutcomeTest {
    private val today = LocalDate.of(2026, 9, 30)
    private val found = SearchOutcome(emptyList(), listOf("dentist"), emptyList())

    @Test fun aDataChangeKeepsTheLastResultANewSearchWaits() {
        val request = SearchRequest("dentist", emptySet(), today)
        // Same query, categories and day (only the index changed): the last result, not "Searching…".
        assertSame(found, shownOutcome(SearchRequest("dentist", emptySet(), today), request to found))
        // Nothing finished yet, or the last result answers another query, category choice or day: "Searching…".
        assertSame(SearchOutcome.LOADING, shownOutcome(request, null))
        assertSame(SearchOutcome.LOADING, shownOutcome(SearchRequest("dentists", emptySet(), today), request to found))
        assertSame(SearchOutcome.LOADING, shownOutcome(SearchRequest("dentist", setOf("Health"), today), request to found))
        assertSame(SearchOutcome.LOADING, shownOutcome(SearchRequest("dentist", emptySet(), today.plusDays(1)), request to found))
    }
}
