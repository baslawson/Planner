package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test

/** Bug hunt 3 Oct, notes (NA-2, NA-4, NA-7, NA-9, NA-10). */
class NotesFixesOct3Test {
    // NA-2: "Merged with a change made elsewhere" kept the old Importance.
    @Test fun mergeKeepsMyImportance() {
        val base = PlannerNote(id = "n", title = "T", content = "body", position = -5)
        val mine = base.copy(priority = TaskPriority.HIGH)
        val theirs = base.copy(content = "synced body", modified = 9, position = -7)
        assertEquals(base.copy(content = "synced body", modified = 9, position = -7, priority = TaskPriority.HIGH), mergeNotes(base, mine, theirs))
        // Their Importance change is kept too, and both changing it differently is for the user to decide.
        assertEquals(TaskPriority.LOW, mergeNotes(base, base.copy(content = "mine"), base.copy(priority = TaskPriority.LOW))?.priority)
        assertNull(mergeNotes(base, mine, base.copy(priority = TaskPriority.LOW)))
    }

    // NA-4: a note that left the page mid-drag crashed the drop.
    @Test fun reorderSkipsNotesNoLongerShown() {
        val a = PlannerNote(id = "a", title = "A", position = -30)
        val b = PlannerNote(id = "b", title = "B", position = -20)
        val c = PlannerNote(id = "c", title = "C", position = -10)
        assertEquals(mapOf("b" to -30L, "a" to -20L), Notes.reorder(listOf(a, b), listOf("gone", "b", "a")))
        assertEquals(mapOf("b" to -30L, "a" to -20L), Notes.reorder(listOf(a, b), listOf("b", "gone", "a")))
        // A note that joined mid-drag keeps its place; the others take each other's.
        assertEquals(mapOf("b" to -30L, "a" to -20L), Notes.reorder(listOf(a, b, c), listOf("b", "a")))
        assertEquals(emptyMap<String, Long>(), Notes.reorder(listOf(a, b), listOf("gone")))
    }

    // NA-7: line marks after an indent, "* [ ]" items and ticked items.
    @Test fun checklistMarkIsRecognisedIndentedTickedAndStarred() {
        assertEquals("  milk", Markdown.prefixLines("  - [ ] milk", 0, 0, "- [ ] ").text)
        assertEquals("milk", Markdown.prefixLines("* [ ] milk", 0, 0, "- [ ] ").text)
        // Checklist on a ticked item takes the box off rather than unticking it.
        assertEquals("paid", Markdown.prefixLines("- [x] paid", 0, 0, "- [ ] ").text)
        assertEquals("paid\nrent", Markdown.prefixLines("- [x] paid\n- [ ] rent", 0, 20, "- [ ] ").text)
        // An indented line or bullet keeps its indent and gets one mark.
        assertEquals("  - [ ] milk", Markdown.prefixLines("  milk", 0, 0, "- [ ] ").text)
        assertEquals("  - [ ] milk", Markdown.prefixLines("  - milk", 0, 0, "- [ ] ").text)
        assertEquals("  - milk", Markdown.prefixLines("  * [x] milk", 0, 0, "- ").text)
        assertEquals("  milk", Markdown.prefixLines("  * milk", 0, 0, "- ").text)
        // Mixed: the line without a box gets one; the ticked line keeps its own, tick and all.
        assertEquals("- [x] paid\n- [ ] rent", Markdown.prefixLines("- [x] paid\nrent", 0, 15, "- [ ] ").text)
        // A heading still replaces a list mark, at the start of the line.
        assertEquals("# a", Markdown.prefixLines("  - [ ] a", 0, 0, "# ").text)
        // The cursor stays on the same text.
        val e = Markdown.prefixLines("  milk", 4, 4, "- [ ] ")
        assertEquals("  - [ ] milk", e.text); assertEquals(10, e.start)
    }

    // NA-10: screen readers move a card one place at a time, as a drag would.
    @Test fun aNoteMovesOnePlaceEarlierOrLater() {
        val p = PlannerNote(id = "p", pinned = true); val a = PlannerNote(id = "a"); val b = PlannerNote(id = "b"); val c = PlannerNote(id = "c")
        val page = listOf(p, a, b, c)
        assertEquals(listOf("p", "b", "a", "c"), Notes.moved(page, "b", -1))
        assertEquals(listOf("p", "a", "c", "b"), Notes.moved(page, "b", 1))
        assertNull(Notes.moved(page, "c", 1)) // the end
        assertNull(Notes.moved(page, "a", -1)) // not above a pinned note
        assertNull(Notes.moved(page, "p", 1)) // nor a pinned one below the rest
        assertNull(Notes.moved(page, "gone", 1))
    }

    // NA-9: Title A–Z still sorts by name, untitled notes by their first line, with each name worked out once.
    @Test fun titleSortReadsEachNameOnce() {
        val notes = listOf(PlannerNote(id = "1", content = "# zebra\nmore"), PlannerNote(id = "2", title = "Apple"),
            PlannerNote(id = "3", content = "mango"), PlannerNote(id = "4", title = "banana", pinned = true))
        val order = Notes.order(NoteSort.TITLE)
        assertEquals(listOf("4", "2", "3", "1"), notes.sortedWith(order).map { it.id })
        // The same comparator again, and on a changed copy, still sorts by each note's own name.
        assertEquals(listOf("4", "2", "3", "1"), notes.reversed().sortedWith(order).map { it.id })
        assertEquals(listOf("4", "1", "2", "3"), notes.map { if (it.id == "1") it.copy(content = "aardvark") else it }.sortedWith(order).map { it.id })
    }
}
