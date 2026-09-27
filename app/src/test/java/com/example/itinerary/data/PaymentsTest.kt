package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PaymentsTest {
    private fun bill() = ItineraryItem(tripId=1, date=LocalDate.of(2026,9,26), startTime=null,
        title="Power", category="Bills", billAmountMinor=10000, payments=listOf(BillPayment(amount=3000)))
    @Test fun settlementRecordsOnlyRemainingAndReversalKeepsHistory() {
        val settled=Payments.setPaid(bill(),true)
        assertEquals(listOf(3000L,7000L),settled.payments.map { it.amount })
        assertEquals(0L,Payments.remaining(settled.billAmountMinor,settled.paid,settled.payments))
        val reversed=Payments.setPaid(settled,false)
        assertEquals(2,reversed.payments.size)
        assertTrue(reversed.payments.all { it.reversed })
        assertEquals(10000L,Payments.remaining(reversed.billAmountMinor,false,reversed.payments))
        assertEquals(7000L,Payments.remaining(10000,false,bill().payments))
    }
    @Test fun invalidPaymentBalanceAndRepeatedIdsAreRejected() {
        assertTrue(runCatching { Payments.validate(bill().copy(billAmountMinor=2000)) }.isFailure)
        val payment=bill().payments.single()
        assertTrue(runCatching { Payments.validate(listOf(payment,payment)) }.isFailure)
        assertTrue(runCatching { Payments.validate(listOf(payment.copy(amount=-1))) }.isFailure)
    }
    @Test fun markingPaidAtHistoryLimitCannotCreateUnreadableLedger() {
        val full = bill().copy(paid = true, billAmountMinor = 1000,
            payments = List(1000) { BillPayment(id = "entry-$it", amount = 1) })
        Payments.validate(full)
        val unpaid = Payments.setPaid(full, false)
        Payments.validate(unpaid)
        val failure = runCatching { Payments.setPaid(unpaid, true) }.exceptionOrNull()
        assertNotNull("The 1001st payment must be rejected", failure)
        assertTrue(failure!!.message.orEmpty().contains("1,000"))
        val room = unpaid.copy(payments = unpaid.payments.dropLast(1))
        val settled = Payments.setPaid(room, true)
        assertEquals(1000, settled.payments.size)
        Payments.validate(settled)
    }
    @Test fun uncertainScansExplainAmbiguityWithoutInventingConfidence() {
        val unclear=BillSuggestions.parse("Power company\nAmount due: $100.00\nDue date: 03/04/2026")
        assertNull(unclear.date)
        assertTrue(unclear.warnings.keys.containsAll(listOf("title","date","currency")))
        val conflict=BillSuggestions.parse("Power company\nAmount due: AUD 100.00\nTotal: AUD 200.00\nDue date: 2026-09-30")
        assertNull(conflict.amount)
        assertTrue(conflict.warnings.containsKey("amount"))
        val clear=BillSuggestions.parse("Power company\nAmount due: AUD 100.00\nDue date: 2026-09-30")
        assertEquals(setOf("title"),clear.warnings.keys)
    }
}
