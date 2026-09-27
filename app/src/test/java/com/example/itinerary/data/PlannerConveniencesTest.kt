package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class PlannerConveniencesTest {
    private val friday = LocalDate.of(2026, 9, 25)
    @Test fun quickEntryHandlesRelativeDatesWeekdaysAndTime() {
        val result = QuickEntry.parse("Dentist Tuesday 3 pm", friday)
        assertEquals("Dentist", result.title); assertEquals(LocalDate.of(2026,9,29), result.date)
        assertEquals(LocalTime.of(15,0), result.time); assertNull(result.error)
        assertEquals(friday.plusDays(1), QuickEntry.parse("Shopping tomorrow", friday).date)
        assertEquals(LocalTime.MIDNIGHT, QuickEntry.parse("Flight today 12 am", friday).time)
        assertEquals(LocalTime.NOON, QuickEntry.parse("Lunch at 12 pm", friday).time)
        assertEquals(LocalTime.of(23,59), QuickEntry.parse("Train 2026-10-01 at 23:59", friday).time)
        assertEquals("Train", QuickEntry.parse("Train on 2026-10-01 at 23:59", friday).title)
        assertEquals(friday, QuickEntry.parse("Dentist Friday", friday).date)
        assertEquals(LocalDate.of(2026,9,29), QuickEntry.parse("Dentist next Tuesday", friday).date)
        assertEquals(LocalDate.of(2026,9,22), QuickEntry.parse("Dentist this Tuesday", friday).date)
        assertEquals(friday, QuickEntry.parse("Unscheduled task", friday).date)
        assertEquals("Call 12345", QuickEntry.parse("Call 12345", friday).title)
    }
    @Test fun quickEntryRejectsInvalidDatesTimesAndConflictingTokens() {
        for (text in listOf("Dentist 2026-02-30", "Dentist 25:00", "Dentist 0 pm", "Dentist 13 pm", "Dentist 3:60 pm",
            "Dentist today tomorrow", "Dentist 3 pm 4 pm", "Tuesday 3 pm", "")) assertNotNull(text, QuickEntry.parse(text,friday).error)
        assertEquals(LocalDate.of(2028,2,29), QuickEntry.parse("Leap day 2028-02-29",friday).date)
    }
    @Test fun billSuggestionsPreferLabelledValuesAndRequireUnambiguousDates() {
        val result=BillSuggestions.parse("Acme Energy\nTax invoice\nInvoice date: 2026-09-01\nAccount: 12345\nAmount due: AUD 1,234.56\nDue date: 30 September 2026")
        assertEquals("Acme Energy",result.title);assertEquals(123456L,result.amount);assertEquals("AUD",result.currency)
        assertEquals(LocalDate.of(2026,9,30),result.date)
        assertEquals(1234L,BillSuggestions.parse("Total due\n$12.34").amount)
        assertNull(BillSuggestions.parse("Total due: $12.34").currency)
        assertEquals("GBP",BillSuggestions.parse("Total: £12.34").currency)
        assertNull(BillSuggestions.parse("Due date: 03/04/2026").date)
        assertEquals(LocalDate.of(2026,9,30),BillSuggestions.parse("Due date: 30/09/2026").date)
        assertEquals(LocalDate.of(2026,9,30),BillSuggestions.parse("Pay by: 09/30/2026").date)
    }
    @Test fun billSuggestionsDoNotGuessConflictingOrMalformedValues() {
        for (text in listOf("Amount due: 1,23.45", "Total: -12.34", "Total: 1e3", "Subtotal: 50.00", "Account 12345", "Total: 2.50\nAmount due: 3.50"))
            assertNull(text,BillSuggestions.parse(text).amount)
        assertNull(BillSuggestions.parse("Due date: 2026-02-30").date)
        assertNull(BillSuggestions.parse("Due date: 2026-09-30\nPay by: 2026-10-01").date)
        assertEquals(0L,BillSuggestions.parse("Total: 0.00").amount)
    }
}
