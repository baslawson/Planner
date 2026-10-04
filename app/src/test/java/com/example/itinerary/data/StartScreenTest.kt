package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Test

// Wish list #10: Settings → Open Planner on.
class StartScreenTest {
    @Test fun whereItOpens() {
        assertEquals(StartScreen.CALENDAR, StartScreen.LAST.page(lastCalendar = true))
        assertEquals(StartScreen.AGENDA, StartScreen.LAST.page(lastCalendar = false))
        assertEquals(StartScreen.NOTES, StartScreen.NOTES.page(lastCalendar = true))
        assertEquals(StartScreen.AGENDA, StartScreen.AGENDA.page(lastCalendar = true))
    }
}
