package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// The editors' suggestions as you type: titles, places, task titles and currencies used before.
class EntryHistoryTest {
    private val today = LocalDate.of(2026, 10, 7)
    private fun event(id: Long, title: String, date: LocalDate, location: String = "", category: String = Categories.OTHER,
                      amount: Long? = null, currency: String = "AUD") =
        ItineraryItem(id = id, tripId = 0, date = date, startTime = null, title = title, location = location, category = category,
            billAmountMinor = amount, billCurrency = currency)

    private val items = listOf(
        event(1, "Dentist", today.minusDays(40), "Old Dental"),
        event(2, "Dentist", today.minusDays(3), "Smile Dental", category = "Health"),
        event(3, "dentist", today.plusDays(30), "Future Dental"),
        event(4, "Café with Ana", today.minusDays(1), "Brew"),
        event(5, "Electricity", today.minusDays(20), "AGL", category = "Bills", amount = 21000),
        event(6, "Gym", today.minusDays(2), "  "),
    )

    @Test fun titlesMatchAnywhereNewestFirstOnce() {
        val dent = EntryHistory.titles(items, "ent", bills = false, today = today)
        assertEquals(listOf("Dentist"), dent.map { it.title })
        // The newest that happened, not the one still to come or the older one: its place and category come with it.
        assertEquals("Smile Dental", dent.single().location); assertEquals("Health", dent.single().category)
        assertEquals(listOf("Café with Ana"), EntryHistory.titles(items, "cafe", bills = false, today = today).map { it.title })
        assertTrue("nothing before a letter is typed", EntryHistory.titles(items, " ", bills = false, today = today).isEmpty())
        assertTrue("not what is already typed", EntryHistory.titles(items, "gym", bills = false, today = today).isEmpty())
        assertTrue("not the event being edited", EntryHistory.titles(items, "caf", bills = false, today = today, except = 4).isEmpty())
    }

    @Test fun billsSuggestBillsWithTheirPayeeAndAmount() {
        assertTrue(EntryHistory.titles(items, "elec", bills = false, today = today).isEmpty())
        val bill = EntryHistory.titles(items, "elec", bills = true, today = today).single()
        assertEquals("AGL", bill.location); assertEquals(21000L, bill.amountMinor); assertEquals("AUD", bill.currency)
    }

    @Test fun placesOnceNewestFirst() {
        assertEquals(listOf("Smile Dental", "Old Dental", "Future Dental"), EntryHistory.locations(items, "dental", today))
        assertEquals(listOf("AGL"), EntryHistory.locations(items, "ag", today))
    }

    @Test fun taskTitlesAndCurrencies() {
        val tasks = listOf(PlannerTask(id = "a", title = "Take out bins", dueDate = today.minusDays(7)),
            PlannerTask(id = "b", title = "Take out bins", dueDate = today), PlannerTask(id = "c", title = "Water plants"))
        assertEquals(listOf("Take out bins"), EntryHistory.taskTitles(tasks, "bin", today))
        assertEquals(listOf("Water plants"), EntryHistory.taskTitles(tasks, "PLANT", today))
        val common = Bills.currencies
        assertEquals("used first, then the rest", listOf("USD", "AUD") + (common - setOf("USD", "AUD")),
            EntryHistory.currencies(listOf("USD", "USD", "AUD"), "", common))
        assertEquals(listOf("NZD"), EntryHistory.currencies(emptyList(), "n", common))
        assertTrue(EntryHistory.currencies(emptyList(), "aud", common).isEmpty())
    }
}
