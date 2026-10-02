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

    // The bill editor's summary: paid so far (reversed payments don't count), what's left, progress, the latest payment.
    @Test fun summaryOfPartPayments() {
        val d=LocalDate.of(2026,9,1)
        val payments=listOf(BillPayment(id="a",amount=10000,date=d.minusDays(29)), BillPayment(id="b",amount=20000,date=d),
            BillPayment(id="c",amount=5000,date=d.plusDays(3),reversed=true), BillPayment(id="d",amount=15000,date=d.minusDays(12)))
        val s=Payments.summary(70000,false,payments)
        assertEquals(45000L,s.paid); assertEquals(25000L,s.remaining)
        assertEquals(45f/70f,s.progress!!,0.0001f)
        // The newest that still counts: the reversed one on the 4th is skipped.
        assertEquals("b",s.last!!.id)
        assertEquals(listOf("c","b","d","a"),Payments.newestFirst(payments).map { it.id })
    }
    @Test fun summaryWhenPaidInFullOrWithoutAmount() {
        val one=listOf(BillPayment(id="a",amount=3000))
        assertEquals(0L,Payments.summary(10000,true,one).remaining); assertEquals(1f,Payments.summary(10000,true,one).progress)
        assertNull(Payments.summary(null,false,one).progress); assertNull(Payments.summary(null,false,one).remaining)
        // Only reversed payments: nothing paid, no latest payment.
        val gone=Payments.summary(10000,false,listOf(BillPayment(amount=3000,reversed=true)))
        assertEquals(0L,gone.paid); assertNull(gone.last); assertEquals(0f,gone.progress)
    }
    @Test fun samedayPaymentsNewestRecordedFirst() {
        val d=LocalDate.of(2026,9,1)
        val list=listOf(BillPayment(id="x",amount=1,date=d),BillPayment(id="y",amount=1,date=d))
        assertEquals(listOf("y","x"),Payments.newestFirst(list).map { it.id })
        assertEquals("y",Payments.summary(10,false,list).last!!.id)
    }
    // R-4: a payment's Undo, and the bar offering it, last UNDO_MS; a stale one is neither shown nor holds back later ones.
    @Test fun paymentUndoExpiresAndLeavesTheQueue() {
        val undos=PaymentUndos()
        val a=PendingPayment(before=bill().copy(id=1),paid=true,remindersBefore=emptyList(),remindersAfter=emptyList())
        val b=PendingPayment(before=bill().copy(id=2),paid=true,remindersBefore=emptyList(),remindersAfter=emptyList())
        undos.record(a,0)
        assertEquals(listOf(a),undos.live(listOf(a),PaymentUndos.UNDO_MS))
        undos.record(b,PaymentUndos.UNDO_MS+1)
        // A's bar goes with its Undo, so B's is first.
        assertEquals(listOf(b),undos.live(listOf(a,b),PaymentUndos.UNDO_MS+1))
        assertNull(undos.take(a.token,PaymentUndos.UNDO_MS+1))
        assertEquals(b,undos.take(b.token,PaymentUndos.UNDO_MS+2))
        // Used once only.
        assertNull(undos.take(b.token,PaymentUndos.UNDO_MS+3))
        assertTrue(undos.live(listOf(b),PaymentUndos.UNDO_MS+3).isEmpty())
        // A newer payment of the same bill replaces the older Undo; one past its time can't be taken.
        val a2=a.copy(token="a2")
        undos.record(a,0); undos.record(a2,1)
        assertNull(undos.take(a.token,2))
        assertNull(undos.take(a2.token,1+PaymentUndos.UNDO_MS+1))
    }
}
