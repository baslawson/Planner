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
        // Unticking "paid" reverses only the "Marked paid" entry: the part-payment still counts.
        val reversed=Payments.setPaid(settled,false)
        assertEquals(listOf(false,true),reversed.payments.map { it.reversed })
        assertEquals(7000L,Payments.remaining(reversed.billAmountMinor,false,reversed.payments))
        assertEquals(7000L,Payments.remaining(10000,false,bill().payments))
    }
    // Review R6: $100 bill, $40 part-payment, ticked paid (adds $60 "Marked paid"), unticked: $60 owing again.
    @Test fun untickingPaidKeepsRealPartPayments() {
        val bill=bill().copy(payments=listOf(BillPayment(amount=4000,note="Cash")))
        val paid=Payments.setPaid(bill,true)
        assertEquals(listOf(4000L,6000L),paid.payments.map { it.amount }); assertEquals("Marked paid",paid.payments.last().note)
        val unpaid=Payments.setPaid(paid,false)
        assertFalse(unpaid.paid)
        assertEquals(listOf(false,true),unpaid.payments.map { it.reversed })
        assertEquals(6000L,Payments.remaining(unpaid.billAmountMinor,false,unpaid.payments))
        // Ticked and unticked again: a new "Marked paid" entry, reversed in its turn; the history stays.
        val again=Payments.setPaid(Payments.setPaid(unpaid,true),false)
        assertEquals(listOf(false,true,true),again.payments.map { it.reversed }); assertEquals(4000L,Payments.total(again.payments))
        // Paid in full by real payments: unticking can't leave them covering the bill, so they are reversed as before.
        val full=bill().copy(paid=true,payments=listOf(BillPayment(amount=4000),BillPayment(amount=6000)))
        assertTrue(Payments.setPaid(full,false).payments.all { it.reversed })
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
