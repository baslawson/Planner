package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

// Bug hunt 24 (8 Oct 2026): quick entry ranges with a repeat that holds one occurrence, MyBudget's messages and upcoming
// bills, and the install id a backup carries.
class BugHunt24Test {
    private val today = LocalDate.of(2026, 10, 8) // a Thursday
    private fun parse(text: String) = QuickEntry.parse(text, today)

    // E6: a range holding one occurrence of its repeat is an entry over the days that repeats, as before Hunt 23.
    @Test fun aRangeWithOneOccurrenceRepeatsOverItsDays() {
        parse("Kids at dad's weekly Fri-Sun").let {
            assertNull(it.error)
            assertEquals(LocalDate.of(2026, 10, 9), it.date)
            assertEquals(LocalDate.of(2026, 10, 11), it.endDate)
            assertEquals(RepeatRule.WEEKLY, it.repeat)
            assertFalse(it.repeatCountSpecified)
        }
        parse("Festival every year 12-16 Oct").let {
            assertNull(it.error)
            assertEquals(LocalDate.of(2026, 10, 12), it.date)
            assertEquals(LocalDate.of(2026, 10, 16), it.endDate)
            assertEquals(RepeatRule.YEARLY, it.repeat)
        }
        // Ended another way too: that is the repeat's end, the range its days.
        parse("Kids at dad's weekly Fri-Sun for 6 times").let {
            assertNull(it.error)
            assertEquals(LocalDate.of(2026, 10, 11), it.endDate)
            assertEquals(6, it.repeatCount)
        }
        // Two or more in the range: still when the repeat runs (Hunt 23 P2).
        parse("Physio daily 12-16 Oct").let { assertNull(it.error); assertNull(it.endDate); assertEquals(5, it.repeatCount) }
    }

    // E6: a window under way starts today, not with the occurrences already gone.
    @Test fun aRepeatWindowUnderWayStartsToday() {
        parse("Physio daily 1-31 Oct").let {
            assertNull(it.error)
            assertEquals(today, it.date)
            assertNull(it.endDate)
            assertEquals(24, it.repeatCount)
            assertFalse(it.pastDate)
        }
        // Weekly from Thursday 1 Oct: the next Thursday on or after today, today itself.
        parse("Swim weekly 1-29 Oct").let { assertNull(it.error); assertEquals(today, it.date); assertEquals(4, it.repeatCount) }
    }

    // E3: only an Add is kept from a MyBudget in another currency; an undo always goes.
    @Test fun onlyAnAddIsRefusedForAnotherCurrency() {
        val add = BudgetLink.Message.Add("pay-1", "planner-bill-1", "Hotel", 9900, today, "planner-bill-1", "USD")
        assertTrue(BudgetLink.refusedLocally(add, "AUD"))
        assertFalse(BudgetLink.refusedLocally(add, "usd"))
        assertFalse(BudgetLink.refusedLocally(add, null))
        assertFalse(BudgetLink.refusedLocally(BudgetLink.Message.Undone("pay-1", "USD"), "AUD"))
    }

    // E4: the upcoming list names the payments already taken off what is left, newest last, at most 20.
    @Test fun upcomingBillsNameTheirPayments() {
        val bill = ItineraryItem(id = 7, tripId = 1, date = LocalDate.of(2026, 10, 9), startTime = null, title = "Electricity",
            category = "Bills", billAmountMinor = 10_000,
            payments = listOf(BillPayment("old", 1_000, today, reversed = true), BillPayment("pay-30", 3_000, today)))
        BudgetLink.upcoming(listOf(bill), today).single().let {
            assertEquals(7_000L, it.amount)
            assertEquals(listOf("pay-30"), it.paid)
        }
        assertEquals(emptyList<String>(), BudgetLink.upcoming(listOf(bill.copy(payments = emptyList())), today).single().paid)
        val many = bill.copy(payments = (1..25).map { BillPayment("p$it", 100, today) })
        assertEquals((6..25).map { "p$it" }, BudgetLink.upcoming(listOf(many), today).single().paid)
    }

    // E5: a backup's install id is taken back as it was; a backup without one is from before Hunt 23 (""), and one that
    // isn't an install id is read the same way.
    @Test fun aBackupsInstallIdIsReadBack() {
        assertEquals("-1a2b3c4d", BudgetLink.backupInstallId("-1a2b3c4d"))
        assertEquals("", BudgetLink.backupInstallId(""))
        assertEquals("", BudgetLink.backupInstallId(null))
        assertEquals("", BudgetLink.backupInstallId("-has/slash"))
        assertEquals("", BudgetLink.backupInstallId("no-dash-first"))
    }
}
