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

    // Hunt 21 S5: prepared once, then only filtered; the event being edited doesn't hide its title's older uses.
    @Test fun preparedOnceAndTheEditedEventsTitleStillSuggested() {
        val prepared = EntryHistory.prepare(items, today)
        assertEquals(EntryHistory.titles(items, "ent", bills = false, today = today), prepared.titles("ent", bills = false))
        // Editing event 2 (the newest "Dentist"): the older one stands in, with its own place.
        assertEquals("Old Dental", prepared.titles("dent", bills = false, except = 2).single().location)
        assertEquals(listOf("Smile Dental", "Old Dental", "Future Dental"), prepared.locations("dental"))
        assertTrue(prepared.titles("", bills = false).isEmpty())
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

    // User, 10 Oct: checklist items used before are offered as one is typed.
    @Test fun checklistItemsMostUsedFirstSeriesOnceAndNotTwice() {
        fun list(vararg t: String) = t.map { ChecklistEntry(text = it) }
        val events = listOf(
            // One repeating event (three dates): its items count once.
            event(10, "Trip", today, ).copy(seriesId = "trip-series", checklist = list("Charger", "Passport")),
            event(11, "Trip", today.plusDays(7)).copy(seriesId = "trip-series", checklist = list("Charger", "Passport")),
            event(12, "Trip", today.plusDays(14)).copy(seriesId = "trip-series", checklist = list("Charger", "Passport")),
            event(13, "Gym", today).copy(checklist = list("Towel", "  ", "passport ")),
            // Two separate events: Sunscreen counts twice, more than the repeating event's Charger (once).
            event(14, "Beach", today).copy(checklist = list("Sunscreen")), event(15, "Swim", today).copy(checklist = list("Sunscreen")),
        )
        val tasks = listOf(PlannerTask(title = "Pack", checklist = list("Passport", "Tickets", "Café card")))
        val past = EntryHistory.checklistItems(events, tasks)
        // Passport: series once + Gym + task = 3; Charger: series once = 1. Capitals and spaces don't make a new item.
        assertEquals(listOf("Passport"), past.like("pass"))
        assertEquals(listOf("Charger"), past.like("char"))
        assertEquals(listOf("Café card"), past.like("cafe"))
        // Already in the checklist being edited, what is typed exactly, and an empty box: none.
        assertTrue(past.like("pass", others = listOf("PASSPORT")).isEmpty())
        assertTrue(past.like("Charger").isEmpty())
        assertTrue(past.like("  ").isEmpty())
        // Most used first: Passport (3) before the ones used once.
        assertEquals("Passport", past.like("s").first())
        assertEquals(listOf("Sunscreen", "Charger"), past.like("r").filter { it == "Sunscreen" || it == "Charger" })
    }
}
