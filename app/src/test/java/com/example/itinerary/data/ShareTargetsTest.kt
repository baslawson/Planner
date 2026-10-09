package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

// "Add to existing…" for a share: what is offered, in what order, and what adding does to an item.
class ShareTargetsTest {
    private val today = LocalDate.of(2026, 10, 9)
    private fun event(id: Long, title: String, date: LocalDate, tripId: Long = 1, category: String = Categories.OTHER, bill: Long? = null) =
        ItineraryItem(id = id, tripId = tripId, date = date, startTime = LocalTime.of(9, 0), title = title, category = category, billAmountMinor = bill)
    private fun file(name: String) = Attachment(itemId = 0, name = "$name.jpg", fileName = "$name.jpg", mimeType = "image/jpeg")

    @Test fun eventsNearestTodayFirstBillsApartAndOtherCalendarsLeftOut() {
        val items = listOf(event(1, "Old trip", today.minusDays(30)), event(2, "Dentist", today.plusDays(3)), event(3, "Lunch", today.minusDays(3)),
            event(4, "Power", today.plusDays(10), category = "Bills"), event(5, "Rent", today.plusDays(1), bill = 150_000),
            event(6, "Work calendar", today, tripId = OutsideCalendars.TRIP_ID))
        val got = ShareTargets.targets(items, emptyList(), emptyList(), today, "")
        assertEquals(listOf("Dentist", "Lunch", "Old trip"), got.filter { it.kind == ShareTargets.Kind.EVENT }.map { it.title })
        assertEquals(listOf("Rent", "Power"), got.filter { it.kind == ShareTargets.Kind.BILL }.map { it.title })
        assertFalse("Read-only calendars aren't offered", got.any { it.title == "Work calendar" })
    }

    @Test fun tasksNotDoneFirstAndNotesNewestFirstArchivedLeftOut() {
        val tasks = listOf(PlannerTask(id = "a", title = "Done one", done = true), PlannerTask(id = "b", title = "Later", dueDate = today.plusDays(5)),
            PlannerTask(id = "c", title = "Soon", dueDate = today.plusDays(1)), PlannerTask(id = "d", title = "Someday"))
        val notes = listOf(PlannerNote(id = "n1", title = "Old", content = "x", modified = 1), PlannerNote(id = "n2", title = "", content = "Shopping list\n- milk", modified = 5),
            PlannerNote(id = "n3", title = "Archived", content = "y", archived = true, modified = 9))
        val got = ShareTargets.targets(emptyList(), tasks, notes, today, "")
        assertEquals(listOf("Soon", "Later", "Someday", "Done one"), got.filter { it.kind == ShareTargets.Kind.TASK }.map { it.title })
        assertEquals("An untitled note shows its first line", listOf("Shopping list", "Old"), got.filter { it.kind == ShareTargets.Kind.NOTE }.map { it.title })
    }

    @Test fun searchNeedsEveryWordAndToleratesCapitalsAndAccents() {
        val items = listOf(event(1, "Café with Sam", today), event(2, "Dentist", today))
        val tasks = listOf(PlannerTask(id = "t", title = "Pay invoice", notes = "for the CAFE"))
        val got = ShareTargets.targets(items, tasks, emptyList(), today, "cafe")
        assertEquals(setOf("Café with Sam", "Pay invoice"), got.map { it.title }.toSet())
        assertEquals(listOf("Café with Sam"), ShareTargets.targets(items, tasks, emptyList(), today, "cafe sam").map { it.title })
    }

    @Test fun textGoesAfterTheNotesOnceAndWithinTheLimit() {
        assertEquals("Bring ID\n\nFrom Sam: 3pm", ShareTargets.withText("Bring ID  ", "  From Sam: 3pm ", 100))
        assertEquals("Only text", ShareTargets.withText("", "Only text", 100))
        assertEquals("No text: unchanged", "Bring ID", ShareTargets.withText("Bring ID", null, 100))
        assertEquals("Already there: not twice", "a\n\nb", ShareTargets.withText("a\n\nb", "b", 100))
        assertEquals("Cut to fit", 10, ShareTargets.withText("12345", "67890abc", 10).length)
        val full = "x".repeat(30)
        assertEquals("Notes already over the limit aren't shortened", full, ShareTargets.withText(full, "more", 20))
    }

    @Test fun filesGoAfterTheItemsOwnNotTwiceAndNotPastTheLimit() {
        val have = listOf(file("a"), file("b"))
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), ShareTargets.withFiles(have, listOf(file("b"), file("c"))).map { it.fileName })
        assertThrows(IllegalArgumentException::class.java) { ShareTargets.withFiles(List(99) { file("f$it") }, listOf(file("x"), file("y"))) }
        assertTrue(ShareTargets.withFiles(List(99) { file("f$it") }, listOf(file("x"))).size == 100)
    }
}
