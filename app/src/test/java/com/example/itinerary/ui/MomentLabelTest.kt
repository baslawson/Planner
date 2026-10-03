package com.example.itinerary.ui

import com.example.itinerary.data.DateFormatChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

// DU-3: the reminder and "Synced …" labels as they read before they were shared.
class MomentLabelTest {
    private lateinit var locale: Locale
    @Before fun englishUk() { locale = Locale.getDefault(); Locale.setDefault(Locale.UK) }
    @After fun restore() { Locale.setDefault(locale) }

    private val zone = ZoneId.of("Australia/Sydney")
    private val afternoon = ZonedDateTime.of(2026, 10, 3, 14, 5, 0, 0, zone).toInstant().toEpochMilli()
    private val morning = ZonedDateTime.of(2026, 1, 9, 7, 30, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun reminderLabelIsTheChosenDayFormatThenTheTime() {
        assertEquals("Saturday 3 October 2026, 14:05", momentLabel(afternoon, DateFormatChoice.DAY_MONTH_YEAR, true, zone))
        assertEquals("Saturday, October 3, 2026, 2:05 pm", momentLabel(afternoon, DateFormatChoice.MONTH_DAY_YEAR, false, zone))
        assertEquals("Fri 09/01/2026, 07:30", momentLabel(morning, DateFormatChoice.NUMERIC_DMY, true, zone))
        assertEquals("Fri 2026-01-09, 7:30 am", momentLabel(morning, DateFormatChoice.ISO, false, zone))
    }

    @Test fun syncedLabelUsesTheShortDay() {
        assertEquals("3 Oct, 14:05", momentLabel(afternoon, null, true, zone))
        assertEquals("9 Jan, 7:30 am", momentLabel(morning, null, false, zone))
    }

    @Test fun timeLabelFollowsTheClockChoice() {
        assertEquals("09:00", LocalTime.of(9, 0).label(true))
        assertEquals("9:00 am", LocalTime.of(9, 0).label(false))
    }
}
