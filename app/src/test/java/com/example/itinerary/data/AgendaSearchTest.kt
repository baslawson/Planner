package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AgendaSearchTest {
    private val day = LocalDate.of(2026, 9, 25)
    private val plans = listOf(
        Trip(id = 1, name = "Zanzibar", destination = "Expedition", startDate = day, endDate = day),
        Trip(id = 2, name = "Hidden", destination = "", startDate = day, endDate = day),
    )
    private val events = listOf(
        ItineraryItem(id = 1, tripId = 1, date = day, startTime = null, title = "Café breakfast", location = "Riverside", notes = "Bring umbrella"),
        ItineraryItem(id = 2, tripId = 2, date = day, startTime = null, title = "Café dinner"),
    )
    private fun hits(query: String) = Search.run(query, emptySet(), plans, events, emptyList(), day).hits.map { it.item.id }.toSet()

    @Test fun legacyPlanNamesAndDescriptionsAreNotSearchable() {
        assertEquals(emptySet<Long>(), hits("Zanzibar"))
        assertEquals(emptySet<Long>(), hits("Expedition"))
    }

    @Test fun eventFieldsStillMatchAcrossLegacyOwners() {
        assertEquals(setOf(1L, 2L), hits("cafe"))
        assertEquals(setOf(1L), hits("riverside"))
        assertEquals(setOf(1L), hits("umbrella"))
    }
}
