package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BudgetLinkTest {
    private fun bill(amount: Long? = 14280, currency: String = "AUD") = ItineraryItem(id = 7, tripId = 1,
        date = LocalDate.of(2026, 10, 9), startTime = null, title = "Electricity", category = "Bills",
        billAmountMinor = amount, billCurrency = currency)

    @Test fun outboxLinesRoundTripEveryMessage() {
        val messages = listOf(
            BudgetLink.Message.Add("pay-1", "planner-series-s", "Tab\there, new\nline and back\\slash", 14280, LocalDate.of(2026, 10, 7), "planner-bill-7"),
            BudgetLink.Message.Add("planner-paid-3", "planner-bill-3", "No amount", null, LocalDate.of(2026, 10, 8)),
            BudgetLink.Message.Undone("pay-1"),
            BudgetLink.Message.NotAud("USD"))
        for (m in messages) {
            val line = BudgetLink.encode(m)
            assertFalse("one line: $line", line.contains('\n'))
            assertEquals(m, BudgetLink.decode(line))
        }
        assertNull(BudgetLink.decode("Z\tfrom a newer Planner"))
        assertNull(BudgetLink.decode("A\ttoo\tfew"))
        // Written before the upcoming id existed: still read.
        assertEquals(BudgetLink.Message.Add("p", "k", "x", 5, LocalDate.of(2026, 1, 2)), BudgetLink.decode("A\tp\tk\tx\t5\t2026-01-02"))
    }

    @Test fun paidBillsNameTheirUpcomingEntry() {
        val before = bill()
        val add = BudgetLink.changes(before, Payments.setPaid(before, true)).single() as BudgetLink.Message.Add
        assertEquals("planner-bill-7", add.upcomingId)
        assertEquals(add.upcomingId, BudgetLink.upcoming(listOf(before), before.date).single().id)
        val unpriced = bill(amount = null)
        val paidNow = BudgetLink.changes(unpriced, unpriced.copy(paid = true)).single() as BudgetLink.Message.Add
        assertEquals("planner-bill-7", paidNow.upcomingId)
    }

    @Test fun upcomingBillsAreUnpaidAudBillsNearToday() {
        val today = LocalDate.of(2026, 10, 7)
        fun at(id: Long, date: LocalDate, amount: Long? = 5000) = bill(amount).copy(id = id, date = date, title = "Bill $id")
        val items = listOf(
            at(1, today.plusDays(3)),
            at(2, today.minusDays(10)),                                                // overdue: still to pay
            Payments.setPaid(at(3, today.plusDays(5)), true),                          // paid
            at(4, today.plusDays(4)).copy(payments = listOf(BillPayment(id = "p", amount = 2000))), // part paid: what's left
            at(5, today.plusDays(6), amount = null),                                   // no amount
            at(6, today.plusDays(7), amount = null).copy(paid = true),                 // no amount, paid
            at(7, today.plusDays(8)).copy(billCurrency = "USD"),                       // not AUD
            at(8, today.plusDays(9)).copy(skipped = true),                             // skipped
            at(9, today.plusDays(63)),                                                 // too far ahead
            at(10, today.minusDays(32)),                                               // too long ago
            at(11, today.plusDays(2)).copy(category = "Food"),                         // not a bill
            at(12, today.plusDays(1)).copy(seriesId = "s1"))
        val upcoming = BudgetLink.upcoming(items, today)
        assertEquals(listOf(2L, 12L, 1L, 4L, 5L), upcoming.map { it.id.removePrefix("planner-bill-").toLong() })
        assertEquals(3000L, upcoming.first { it.id == "planner-bill-4" }.amount)
        assertNull(upcoming.first { it.id == "planner-bill-5" }.amount)
        assertEquals("planner-series-s1", upcoming.first { it.id == "planner-bill-12" }.billKey)
        assertEquals("planner-bill-1", upcoming.first { it.id == "planner-bill-1" }.billKey)
        assertEquals(BudgetLink.MAX_UPCOMING, BudgetLink.upcoming((1L..300L).map { at(it, today) }, today).size)
    }

    @Test fun markingPaidSendsTheMarkedPaidEntry() {
        val before = bill()
        val after = Payments.setPaid(before, true)
        val add = BudgetLink.changes(before, after).single() as BudgetLink.Message.Add
        assertEquals(after.payments.single().id, add.paymentId)
        assertEquals(14280L, add.amount)
        assertEquals("Electricity", add.payee)
        assertEquals("planner-bill-7", add.billKey)
    }

    @Test fun markingUnpaidUndoesThatPaymentOnly() {
        val part = BillPayment(id = "part", amount = 4000)
        val paid = Payments.setPaid(bill().copy(payments = listOf(part)), true)
        val unpaid = Payments.setPaid(paid, false)
        assertEquals(listOf(BudgetLink.Message.Undone(paid.payments.last().id)), BudgetLink.changes(paid, unpaid))
    }

    @Test fun aPartPaymentIsSentAndAnUnchangedBillSendsNothing() {
        val before = bill()
        val after = before.copy(payments = listOf(BillPayment(id = "p1", amount = 5000, date = LocalDate.of(2026, 10, 3))))
        val add = BudgetLink.changes(before, after).single() as BudgetLink.Message.Add
        assertEquals("p1" to 5000L, add.paymentId to add.amount)
        assertEquals(LocalDate.of(2026, 10, 3), add.date)
        assertTrue(BudgetLink.changes(after, after.copy(title = "Power")).isEmpty())
    }

    @Test fun aBillWithoutAnAmountGoesByTheBill() {
        val before = bill(amount = null)
        val paid = Payments.setPaid(before, true)
        assertTrue(paid.payments.isEmpty())
        val add = BudgetLink.changes(before, paid).single() as BudgetLink.Message.Add
        assertEquals("planner-paid-7", add.paymentId)
        assertNull(add.amount)
        assertEquals(listOf(BudgetLink.Message.Undone("planner-paid-7")), BudgetLink.changes(paid, Payments.setPaid(paid, false)))
    }

    // Hunt 22 L1: paid without an amount, given one later, then unpaid: MyBudget still hears it was undone.
    @Test fun aBillPaidWithoutAnAmountIsUndoneAfterGettingOne() {
        val paid = Payments.setPaid(bill(amount = null), true)
        val priced = paid.copy(billAmountMinor = 5000)
        assertTrue("giving it an amount alone sends nothing", BudgetLink.changes(paid, priced).isEmpty())
        val unpaid = Payments.setPaid(priced, false)
        assertEquals(listOf(BudgetLink.Message.Undone("planner-paid-7")), BudgetLink.changes(priced, unpaid))
        // Paid again, now with its amount: a new payment, after the old one was taken back.
        val again = Payments.setPaid(unpaid, true)
        assertEquals(listOf(again.payments.single().id), BudgetLink.changes(unpaid, again).map { (it as BudgetLink.Message.Add).paymentId })
    }

    @Test fun otherCurrenciesAreNotSent() {
        val before = bill(currency = "USD")
        val paid = Payments.setPaid(before, true)
        assertEquals(listOf(BudgetLink.Message.NotAud("USD")), BudgetLink.changes(before, paid))
        assertTrue(BudgetLink.changes(paid, Payments.setPaid(paid, false)).isEmpty())
    }

    @Test fun repeatingBillsShareAKeyAndOtherCategoriesSendNothing() {
        assertEquals("planner-series-s1", BudgetLink.billKey(bill().copy(seriesId = "s1")))
        val event = bill().copy(category = "Work")
        assertTrue(BudgetLink.changes(event, event.copy(payments = listOf(BillPayment(amount = 100)))).isEmpty())
    }
}
