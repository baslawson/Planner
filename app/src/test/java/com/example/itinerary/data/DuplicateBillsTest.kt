package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DuplicateBillsTest {
    private val bill = ItineraryItem(id = 1, tripId = 1, date = LocalDate.of(2026, 9, 25),
        startTime = null, title = "Electricity bill", category = "Bills", billAmountMinor = 12000)

    @Test fun matchesWhitespaceCaseAndOtherPlansIncludingPaidBills() {
        val other = bill.copy(id = 2, tripId = 9, title = "  ELECTRICITY\t  bill  ", paid = true)
        assertEquals(listOf(other), Bills.duplicates(bill, listOf(bill, other)))
        assertEquals(listOf(bill), Bills.duplicates(bill.copy(id = 0), listOf(bill)))
    }

    @Test fun requiresEveryFieldAndKnownAmount() {
        val different = listOf(bill.copy(id = 2, title = "Gas bill"), bill.copy(id = 3, billAmountMinor = 12001),
            bill.copy(id = 4, billCurrency = "USD"), bill.copy(id = 5, date = bill.date.plusDays(1)),
            bill.copy(id = 6, category = "Other"), bill.copy(id = 7, billAmountMinor = null))
        assertTrue(Bills.duplicates(bill, different).isEmpty())
        assertTrue(Bills.duplicates(bill.copy(billAmountMinor = null), different).isEmpty())
        assertTrue(Bills.duplicates(bill.copy(category = "Other"), listOf(bill.copy(id = 2))).isEmpty())
        assertEquals(1, Bills.duplicates(bill.copy(billAmountMinor = 0), listOf(bill.copy(id = 2, billAmountMinor = 0))).size)
    }

    @Test fun checksRepeatDatesAndExcludesEditedSeriesMembers() {
        val later = bill.copy(id = 2, date = bill.date.plusMonths(1))
        val outside = later.copy(id = 3)
        assertEquals(listOf(outside), Bills.duplicates(bill, listOf(bill, later, outside),
            setOf(bill.date, later.date), setOf(1, 2)))
    }
}
