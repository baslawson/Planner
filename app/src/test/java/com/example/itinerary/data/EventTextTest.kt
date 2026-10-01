package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

// E3: an event's text is capped as Nextcloud sync reads it back, so an event Planner keeps in sync never turns read-only
// there (DETACHED, shown twice) because of its own notes or title.
class EventTextTest {
    private val event = ItineraryItem(id = 1, tripId = 1, date = LocalDate.of(2026, 10, 5), startTime = LocalTime.of(9, 0),
        durationMinutes = 60, title = "Dentist")

    @Test fun whatAnEventCanHoldIsWhatSyncReadsBack() {
        val longest = event.copy(title = "t".repeat(EventText.MAX_TITLE), location = "l".repeat(EventText.MAX_LOCATION),
            notes = "n".repeat(EventText.MAX_NOTES))
        EventText.validate(longest, null)
        val file = CalendarExport.encode(longest, "planner-1@planner", ZoneOffset.UTC)
        assertNotNull(ServerEvents.parse(file, ZoneOffset.UTC).item)
        // One more character of notes and Nextcloud's copy would be read-only: refused on save instead, with a reason.
        val tooLong = longest.copy(notes = longest.notes + "n")
        assertNull(ServerEvents.parse(CalendarExport.encode(tooLong, "planner-1@planner", ZoneOffset.UTC), ZoneOffset.UTC).item)
        val error = assertThrows(IllegalArgumentException::class.java) { EventText.validate(tooLong, null) }
        assertTrue(error.message!!.contains("20,000"))
        assertThrows(IllegalArgumentException::class.java) { EventText.validate(event.copy(title = "t".repeat(501)), event) }
        assertThrows(IllegalArgumentException::class.java) { EventText.validate(event.copy(location = "l".repeat(2001)), event) }
    }

    @Test fun anEventFromBeforeTheLimitsCanStillBeSaved() {
        val old = event.copy(notes = "n".repeat(30_000))
        // Marked paid, moved, renamed: the long notes it already had stay.
        EventText.validate(old.copy(startTime = LocalTime.of(10, 0), title = "Dentist (moved)"), old)
        // Made longer still: refused.
        assertThrows(IllegalArgumentException::class.java) { EventText.validate(old.copy(notes = old.notes + "more"), old) }
    }

    @Test fun typingStopsAtTheLimitAndAPasteIsCutToFit() {
        val max = 10
        assertEquals("abc", EventText.typed("ab", "abc", max))
        assertEquals("0123456789", EventText.typed("0123456789", "0123456789x", max))
        assertEquals("abcdefghij", EventText.typed("ab", "abcdefghijklmnop", max))
        // A pair of UTF-16 halves (an emoji) is never cut in two.
        assertEquals("abcdefghi", EventText.typed("ab", "abcdefghi😀", max))
        // Text already over the limit can be shortened, not lengthened.
        val over = "x".repeat(15)
        assertEquals("x".repeat(14), EventText.typed(over, "x".repeat(14), max))
        assertEquals(over, EventText.typed(over, over + "y", max))
    }
}
