package com.example.itinerary

import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.Trip
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate

/** Explicit opt-in only; caller must preserve/restore emulator data around the UI persistence check. */
class MenuPlanDeletionColdStartTest {
    @Test fun prepare() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("prepareDeleteEvidence") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ItineraryApp
        val names = listOf("QA-Menu-A", "QA-Menu-B")
        check(app.repository.snapshot().trips.none { it.name in names })
        val day = LocalDate.of(2000, 1, 2)
        for (name in names) app.repository.saveTrip(Trip(name = name, destination = "", startDate = day, endDate = day))
        check(app.repository.snapshot().trips.count { it.name in names } == 2)
    }
}
