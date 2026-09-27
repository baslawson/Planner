package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class EventConvenienceTest {
    @Test fun endTimeUsesSameDayOvernightAndFullDay() {
        assertEquals(90, durationUntilEnd(LocalTime.of(14,0), LocalTime.of(15,30)))
        assertEquals(120, durationUntilEnd(LocalTime.of(23,30), LocalTime.of(1,30)))
        assertEquals(1440, durationUntilEnd(LocalTime.of(9,0), LocalTime.of(9,0)))
        for (start in 0 until 1440 step 17) for (end in 0 until 1440 step 23) {
            val minutes = durationUntilEnd(LocalTime.of(start / 60,start % 60), LocalTime.of(end / 60,end % 60))
            assertTrue(minutes in 1..1440)
            assertEquals(end, (start + minutes) % 1440)
        }
    }
    @Test fun reminderDatesUseEventTimeOrNineForAllDay() {
        val date = LocalDate.of(2026,9,25)
        val zone = ZoneId.of("Australia/Perth")
        assertEquals(ZonedDateTime.of(2026,9,24,0,10,0,0,zone), reminderTrigger(date, LocalTime.of(0,10),1440,zone))
        assertEquals(ZonedDateTime.of(2026,9,24,9,0,0,0,zone), reminderTrigger(date,null,1440,zone))
        assertEquals(ZonedDateTime.of(2026,9,24,23,55,0,0,zone), reminderTrigger(date,LocalTime.of(0,10),15,zone))
    }
    @Test fun reminderPreviewResolvesDstExactlyLikeTheAlarm() {
        val zone = ZoneId.of("America/New_York")
        val spring = reminderTrigger(LocalDate.of(2026,3,8),LocalTime.of(3,30),60,zone)
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"),spring.toInstant())
        assertEquals(LocalTime.of(3,30),spring.toLocalTime()) // nonexistent 02:30 resolves forward
        val autumn = reminderTrigger(LocalDate.of(2026,11,1),LocalTime.of(2,30),60,zone)
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"),autumn.toInstant())
    }
}
