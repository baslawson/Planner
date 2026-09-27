package com.example.itinerary

import com.example.itinerary.data.*
import com.example.itinerary.reminders.SnoozeChoice
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.math.BigDecimal

class PlannerConveniencesTest {
    private val today = LocalDate.of(2026, 9, 26)
    private fun event() = ItineraryItem(id=7, tripId=1, date=today, startTime=null, title="Lunch, tea; cake\\coffee\nSecond line")
    @Test fun allDayHasExclusiveEndAndEscapedText() {
        val text=CalendarExport.encode(event().copy(location="A\r\nB", notes="Hello, friend"), "event-7@planner")
        assertTrue(text.contains("DTSTART;VALUE=DATE:20260926\r\nDTEND;VALUE=DATE:20260927\r\n"))
        assertTrue(text.contains("SUMMARY:Lunch\\, tea\\; cake\\\\coffee\\nSecond line\r\n"))
        assertTrue(text.contains("LOCATION:A\\nB"));assertTrue(text.contains("DESCRIPTION:Hello\\, friend"))
        assertTrue(text.endsWith("END:VCALENDAR\r\n")); assertFalse(text.contains("RRULE"))
    }
    @Test fun timedExportUsesUtcAndCrossesMidnight() {
        val text=CalendarExport.encode(event().copy(startTime=LocalTime.of(23,30), durationMinutes=120),"event-7@planner",ZoneId.of("Australia/Perth"))
        assertTrue(text.contains("DTSTART:20260926T153000Z"));assertTrue(text.contains("DTEND:20260926T173000Z"))
        val zero=CalendarExport.encode(event().copy(startTime=LocalTime.NOON),"event-7@planner",ZoneOffset.UTC)
        assertFalse(zero.contains("DTEND"))
    }
    @Test fun unicodeLinesFoldByUtf8BytesWithoutSplittingCharacters() {
        val title="😀 café, 日本語; ".repeat(30)
        val text=CalendarExport.encode(event().copy(title=title),"event-7@planner")
        text.split("\r\n").forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size<=75) }
        val unfolded=text.replace("\r\n ", "")
        assertTrue(unfolded.contains("SUMMARY:"+title.replace(",","\\,").replace(";","\\;")))
        assertFalse(text.contains('\uFFFD'))
    }
    @Test fun snoozesHandleDaylightSavingAndTomorrowIsLocalNine() {
        val now=ZonedDateTime.of(2026,3,28,23,55,0,0,ZoneId.of("Europe/London"))
        assertEquals(now.toInstant().plusSeconds(600).toEpochMilli(),SnoozeChoice.TEN_MINUTES.until(now))
        assertEquals(now.toInstant().plusSeconds(3600).toEpochMilli(),SnoozeChoice.ONE_HOUR.until(now))
        assertEquals(Instant.parse("2026-03-29T08:00:00Z").toEpochMilli(),SnoozeChoice.TOMORROW.until(now))
    }
    @Test fun backupPromptUsesSevenLocalDaysAndHonorsDeferAndDisable() {
        val zone=ZoneId.of("Australia/Perth")
        assertTrue(BackupReminder().due(null,today,zone))
        assertFalse(BackupReminder().due("2026-09-20T00:00:00Z",today,zone))
        assertTrue(BackupReminder().due("2026-09-19T00:00:00Z",today,zone))
        assertFalse(BackupReminder(deferredUntil=today.plusDays(1)).due(null,today,zone))
        assertTrue(BackupReminder(deferredUntil=today).due(null,today,zone))
        assertFalse(BackupReminder(enabled=false).due(null,today,zone))
        assertFalse(BackupReminder().due("2026-09-18T20:00:00Z",today.minusDays(1),zone))
        assertTrue(BackupReminder().due("broken",today,zone))
        assertFalse(BackupReminder().due("2026-10-01T00:00:00Z",today,zone))
    }
    @Test fun forecastSortsDatesAndKeepsRemainingAmountsAndCurrenciesSeparate() {
        val bill=PlanEvent(1,1,today,null,"Power",0,null,category="Bills",billAmountMinor=10000)
        val payment=BillPayment("p",2500,today,"",false)
        val rows=listOf(bill.copy(payments=listOf(payment)),bill.copy(id=2,date=today.minusDays(1),billCurrency="USD"),
            bill.copy(id=3,paid=true),bill.copy(id=4,skipped=true),bill.copy(id=5,date=today.plusMonths(1)),bill.copy(id=6,billAmountMinor=null))
        assertEquals(listOf(2L,1L,6L),Bills.forecast(rows,YearMonth.from(today)).map { it.id })
        val summary=Bills.summary(rows,YearMonth.from(today))
        assertEquals(mapOf("AUD" to BigDecimal("75.00"),"USD" to BigDecimal("100.00")),summary.totals)
        assertEquals(1,summary.withoutAmount)
    }
}
