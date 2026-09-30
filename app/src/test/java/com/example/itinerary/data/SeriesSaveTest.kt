package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class SeriesSaveTest {
    private val day = LocalDate.of(2026, 10, 1)
    private fun bill(id: Long, date: LocalDate, amount: Long = 1000, payments: List<BillPayment> = emptyList(), paid: Boolean = false) =
        ItineraryItem(id = id, tripId = 1, date = date, startTime = LocalTime.of(9, 0), title = "Rent", category = "Bills",
            billAmountMinor = amount, payments = payments, paid = paid, seriesId = "s", repeatRule = "MONTHLY")

    @Test fun paidSiblingKeepsItsMoneyWhileOtherDetailsFollow() {
        val paidSibling = bill(2, day.plusMonths(1), payments = listOf(BillPayment(amount = 1000)), paid = true)
        for (edited in listOf(bill(1, day, amount = 1200), bill(1, day, amount = 800), bill(1, day).copy(billCurrency = "EUR"), bill(1, day).copy(category = "Other"))) {
            val saved = seriesOccurrence(edited.copy(title = "Rent (flat)", location = "Home"), paidSibling, paidSibling.date, false, RepeatRule.MONTHLY)
            assertEquals("Rent (flat)", saved.title); assertEquals("Home", saved.location)
            assertEquals(1000L, saved.billAmountMinor); assertEquals("AUD", saved.billCurrency); assertEquals("Bills", saved.category)
            assertTrue(saved.paid); assertEquals(paidSibling.payments, saved.payments)
            Payments.validate(saved)
        }
    }

    @Test fun unpaidSiblingTakesTheNewAmount() {
        val sibling = bill(3, day.plusMonths(2))
        val saved = seriesOccurrence(bill(1, day, amount = 1500).copy(billCurrency = "EUR"), sibling, day.plusMonths(3), false, RepeatRule.MONTHLY)
        assertEquals(1500L, saved.billAmountMinor); assertEquals("EUR", saved.billCurrency)
        assertEquals(3L, saved.id); assertEquals(day.plusMonths(3), saved.date); assertFalse(saved.paid)
    }

    @Test fun editedOccurrenceKeepsItsOwnPaymentsAndTicks() {
        val edited = bill(1, day, payments = listOf(BillPayment(amount = 400))).copy(checklist = listOf(ChecklistEntry("a", "Transfer", true)))
        val saved = seriesOccurrence(edited, bill(1, day.minusDays(1)), day, true, RepeatRule.NONE)
        assertEquals(edited.payments, saved.payments); assertEquals(edited.checklist, saved.checklist)
        assertNull(saved.seriesId); assertEquals("NONE", saved.repeatRule)
    }

    @Test fun siblingsKeepTheirOwnChecklistTicks() {
        val edited = bill(1, day).copy(checklist = listOf(ChecklistEntry("a", "Transfer (renamed)", true), ChecklistEntry("b", "Receipt", true), ChecklistEntry("new", "File it", true)))
        val sibling = bill(2, day.plusMonths(1)).copy(checklist = listOf(ChecklistEntry("a", "Transfer", false), ChecklistEntry("b", "Receipt", true), ChecklistEntry("gone", "Old", true)))
        val saved = seriesOccurrence(edited, sibling, sibling.date, false, RepeatRule.MONTHLY)
        assertEquals(listOf(ChecklistEntry("a", "Transfer (renamed)", false), ChecklistEntry("b", "Receipt", true), ChecklistEntry("new", "File it", false)), saved.checklist)
        assertEquals(emptyList<ChecklistEntry>(), keepChecklistTicks(emptyList(), sibling.checklist))
    }

    @Test fun siblingWithOnlyReversedPaymentsFollowsMoneyEdits() {
        val unticked = bill(2, day.plusMonths(1), payments = listOf(BillPayment(amount = 1000, reversed = true)))
        val saved = seriesOccurrence(bill(1, day, amount = 1200).copy(billCurrency = "EUR", category = "Other"), unticked, unticked.date, false, RepeatRule.MONTHLY)
        assertEquals(1200L, saved.billAmountMinor); assertEquals("EUR", saved.billCurrency); assertEquals("Other", saved.category)
        assertFalse(saved.paid); assertEquals(unticked.payments, saved.payments)
        assertFalse(Payments.anyLive(unticked.payments)); assertTrue(Payments.anyLive(listOf(BillPayment(amount = 5))))
    }
}
