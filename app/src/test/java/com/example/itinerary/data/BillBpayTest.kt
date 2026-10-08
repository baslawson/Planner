package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BillBpayTest {
    private val bill = ItineraryItem(id = 1, tripId = 1, date = LocalDate.of(2026, 10, 9), startTime = null,
        title = "Electricity", category = "Bills", billAmountMinor = 12000, paymentReference = "INV-7",
        bpayBillerCode = "12345", bpayReference = "678901")

    // BPAY is Australian: an AUD bill keeps its details, a bill in another currency is saved without them.
    @Test fun onlyAudBillsKeepBpayDetails() {
        assertTrue(Bills.hasBpay("AUD"))
        assertFalse(Bills.hasBpay("USD"))
        assertEquals(bill, Bills.bpayForCurrency(bill))
        val usd = Bills.bpayForCurrency(bill.copy(billCurrency = "USD"))
        assertEquals("", usd.bpayBillerCode)
        assertEquals("", usd.bpayReference)
        assertEquals("the other payment details stay", "INV-7", usd.paymentReference)
    }
}
