package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test

// Entire-series save: the edit's attachment/reminder changes reach each other occurrence as a diff (B1).
class SeriesSiblingsTest {
    private fun file(id: Long, itemId: Long, fileName: String, text: String = "") =
        Attachment(id = id, itemId = itemId, name = fileName, fileName = fileName, mimeType = "image/jpeg", recognizedText = text)
    private fun link(id: Long, itemId: Long, url: String, name: String = url) =
        Attachment(id = id, itemId = itemId, name = name, fileName = "", mimeType = Links.MIME_TYPE, url = url)
    private fun reminder(id: Long, itemId: Long, amount: Int, unit: ReminderUnit, ring: Boolean = false) =
        Reminder(id = id, itemId = itemId, amount = amount, unit = unit, ringUntilDismissed = ring)

    @Test fun addingAFileKeepsEachOccurrencesOwnReceipts() {
        val added = listOf(file(0, 0, "contract.pdf"))
        val (delete, insert) = seriesSiblingAttachments(listOf(file(2, 2, "receipt-nov.jpg")), added, emptyList())
        assertEquals(emptyList<Attachment>(), delete)
        assertEquals(listOf("contract.pdf"), insert.map { it.fileName })
    }

    @Test fun removingAFileRemovesItOnlyWhereItIsHeld() {
        val shared = file(1, 1, "contract.pdf")
        val holder = listOf(file(2, 2, "contract.pdf"), file(4, 2, "receipt-nov.jpg"))
        val (delete, insert) = seriesSiblingAttachments(holder, emptyList(), listOf(shared))
        assertEquals(listOf(2L), delete.map { it.id }); assertEquals(emptyList<Attachment>(), insert)
        val (delete2, insert2) = seriesSiblingAttachments(listOf(file(5, 3, "receipt-dec.jpg")), emptyList(), listOf(shared))
        assertEquals(emptyList<Attachment>(), delete2); assertEquals(emptyList<Attachment>(), insert2)
    }

    @Test fun readTextReplacesTheFileOnlyWhereItIsHeld() {
        val doc = file(1, 1, "contract.pdf")
        val removed = listOf(doc); val added = listOf(file(0, 0, "contract.pdf", text = "Signed"))
        val (delete, insert) = seriesSiblingAttachments(listOf(file(2, 2, "contract.pdf"), file(4, 2, "receipt-nov.jpg")), added, removed)
        assertEquals(listOf(2L), delete.map { it.id }); assertEquals(listOf("Signed"), insert.map { it.recognizedText })
        val (delete2, insert2) = seriesSiblingAttachments(listOf(file(5, 3, "receipt-dec.jpg")), added, removed)
        assertEquals(emptyList<Attachment>(), delete2); assertEquals(emptyList<Attachment>(), insert2)
    }

    // C1: a web link has no file (fileName ""), so links are told apart by their url.
    @Test fun removingALinkRemovesOnlyThatLinkWhereItIsHeld() {
        val removed = listOf(link(1, 1, "https://a.example/tickets"))
        val own = listOf(link(2, 2, "https://a.example/tickets"), link(3, 2, "https://b.example/menu"), file(4, 2, "receipt-nov.jpg"))
        val (delete, insert) = seriesSiblingAttachments(own, emptyList(), removed)
        assertEquals(listOf(2L), delete.map { it.id }); assertEquals(emptyList<Attachment>(), insert)
        val (delete2, _) = seriesSiblingAttachments(listOf(link(5, 3, "https://b.example/menu")), emptyList(), removed)
        assertEquals(emptyList<Attachment>(), delete2)
    }

    @Test fun addingALinkReachesOccurrencesThatHaveOtherLinks() {
        val added = listOf(link(0, 0, "https://a.example/tickets"))
        val (delete, insert) = seriesSiblingAttachments(listOf(link(2, 2, "https://b.example/menu")), added, emptyList())
        assertEquals(emptyList<Attachment>(), delete); assertEquals(listOf("https://a.example/tickets"), insert.map { it.url })
        // One that already holds the same url is not doubled.
        val (_, again) = seriesSiblingAttachments(listOf(link(3, 3, "https://a.example/tickets")), added, emptyList())
        assertEquals(emptyList<Attachment>(), again)
    }

    @Test fun renamingALinkRenamesItOnlyWhereItIsHeld() {
        val removed = listOf(link(1, 1, "https://a.example/tickets")); val added = listOf(link(0, 0, "https://a.example/tickets", name = "Tickets"))
        val (delete, insert) = seriesSiblingAttachments(listOf(link(2, 2, "https://a.example/tickets"), link(3, 2, "https://b.example/menu")), added, removed)
        assertEquals(listOf(2L), delete.map { it.id }); assertEquals(listOf("Tickets"), insert.map { it.name })
        val (delete2, insert2) = seriesSiblingAttachments(listOf(link(4, 3, "https://b.example/menu")), added, removed)
        assertEquals(emptyList<Attachment>(), delete2); assertEquals(emptyList<Attachment>(), insert2)
    }

    @Test fun swappingOneLinkForAnotherAddsItWhereTheOldOneIsNotHeld() {
        val (delete, insert) = seriesSiblingAttachments(listOf(file(2, 2, "receipt-nov.jpg")), listOf(link(0, 0, "https://a.example/x")), listOf(link(1, 1, "https://c.example/y")))
        assertEquals(emptyList<Attachment>(), delete); assertEquals(listOf("https://a.example/x"), insert.map { it.url })
    }

    @Test fun addingAReminderKeepsEachOccurrencesOwn() {
        val own = listOf(reminder(2, 2, 1, ReminderUnit.DAYS, ring = true))
        val (delete, insert) = seriesSiblingReminders(own, listOf(reminder(0, 0, 1, ReminderUnit.HOURS)), emptyList())
        assertEquals(emptyList<Reminder>(), delete); assertEquals(listOf(60L), insert.map { it.offsetMinutes })
        // One it already has at that time is not doubled.
        val (_, again) = seriesSiblingReminders(listOf(reminder(3, 3, 60, ReminderUnit.MINUTES)), listOf(reminder(0, 0, 1, ReminderUnit.HOURS)), emptyList())
        assertEquals(emptyList<Reminder>(), again)
    }

    @Test fun togglingRingChangesOnlyOccurrencesWithThatReminder() {
        val old = reminder(1, 1, 1, ReminderUnit.HOURS)
        val removed = listOf(old); val added = listOf(old.copy(id = 0, itemId = 0, ringUntilDismissed = true))
        val (delete, insert) = seriesSiblingReminders(listOf(reminder(2, 2, 1, ReminderUnit.HOURS), reminder(4, 2, 2, ReminderUnit.DAYS)), added, removed)
        assertEquals(listOf(2L), delete.map { it.id }); assertEquals(listOf(true), insert.map { it.ringUntilDismissed })
        val (delete2, insert2) = seriesSiblingReminders(listOf(reminder(5, 3, 2, ReminderUnit.DAYS)), added, removed)
        assertEquals(emptyList<Reminder>(), delete2); assertEquals(emptyList<Reminder>(), insert2)
        // A sibling whose own 1-hour reminder already rings keeps it.
        val (delete3, insert3) = seriesSiblingReminders(listOf(reminder(6, 4, 1, ReminderUnit.HOURS, ring = true)), added, removed)
        assertEquals(emptyList<Reminder>(), delete3); assertEquals(emptyList<Reminder>(), insert3)
    }

    @Test fun removingAReminderRemovesItOnlyWhereTheSameOneIsSet() {
        val old = reminder(1, 1, 1, ReminderUnit.DAYS)
        val (delete, _) = seriesSiblingReminders(listOf(reminder(2, 2, 1, ReminderUnit.DAYS), reminder(3, 2, 2, ReminderUnit.HOURS)), emptyList(), listOf(old))
        assertEquals(listOf(2L), delete.map { it.id })
        val (delete2, insert2) = seriesSiblingReminders(listOf(reminder(4, 3, 2, ReminderUnit.HOURS)), emptyList(), listOf(old))
        assertEquals(emptyList<Reminder>(), delete2); assertEquals(emptyList<Reminder>(), insert2)
    }
}
