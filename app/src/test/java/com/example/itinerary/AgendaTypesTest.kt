package com.example.itinerary

import com.example.itinerary.data.AgendaType
import com.example.itinerary.data.AgendaTypes
import org.junit.Assert.*
import org.junit.Test

class AgendaTypesTest {
    @Test fun oldSingleSelectionAndEverythingLoadWithoutLosingPreference() {
        for (type in AgendaType.entries) assertEquals(setOf(type), AgendaTypes.decode(null, type.name))
        for (legacy in listOf(null, "EVERYTHING", "invalid"))
            assertEquals(AgendaType.entries.toSet(), AgendaTypes.decode(null, legacy))
    }

    @Test fun allEightCombinationsRoundTripAndToggleIndependently() {
        for (mask in 0..7) {
            val selected = AgendaType.entries.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            assertEquals(selected, AgendaTypes.decode(selected.map { it.name }.toSet(), "TASKS"))
            for (type in AgendaType.entries) {
                val result = AgendaTypes.toggle(selected, type)
                assertEquals(selected - type, result - type)
                assertEquals(type !in selected, type in result)
            }
        }
    }

    @Test fun emptySelectionIsPreservedAndInvalidValuesRecover() {
        assertEquals(emptySet<AgendaType>(), AgendaTypes.decode(emptySet(), "TASKS"))
        assertEquals(AgendaType.entries.toSet(), AgendaTypes.decode(setOf("unknown"), null))
        assertEquals(setOf(AgendaType.BILLS), AgendaTypes.decode(setOf("BILLS", "unknown"), null))
    }
}
