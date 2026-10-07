package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BudgetLinkTest {
    private fun bill(amount: Long? = 14280, currency: String = "AUD") = ItineraryItem(id = 7, tripId = 1,
        date = LocalDate.of(2026, 10, 9), startTime = null, title = "Electricity", category = "Bills",
        billAmountMinor = amount, billCurrency = currency)

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
