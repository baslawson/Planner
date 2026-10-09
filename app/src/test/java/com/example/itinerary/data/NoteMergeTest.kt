package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// A note changed in Planner and on Nextcloud since the last sync: merged when they changed different lines, else none
// (NoteSync then makes a conflict copy, as before).
class NoteMergeTest {
    private val base = SentNote("n", "acct", 1, "e1", "Shopping", "milk\nbread\neggs\nbutter", "Home", false)
    private fun note(title: String = "Shopping", content: String = base.content, notebook: String = "Home", pinned: Boolean = false) =
        PlannerNote(id = "n", title = title, content = content, notebook = notebook, pinned = pinned)

    @Test fun differentLinesAreBothKept() {
        val merged = NoteMerge.merge(base, note(content = "milk\nbread\neggs\nbutter\ncheese"), note(content = "oat milk\nbread\neggs\nbutter"))!!
        assertEquals("oat milk\nbread\neggs\nbutter\ncheese", merged.content)
    }

    @Test fun anInsertAndADeleteInDifferentPlacesAreBothKept() {
        assertEquals("milk\njam\nbread\nbutter", NoteMerge.text(base.content, "milk\njam\nbread\neggs\nbutter", "milk\nbread\nbutter"))
    }

    @Test fun theSameChangeOnBothSidesIsTakenOnce() {
        assertEquals("milk\nbread\nfree-range eggs\nbutter",
            NoteMerge.text(base.content, "milk\nbread\nfree-range eggs\nbutter", "milk\nbread\nfree-range eggs\nbutter"))
    }

    @Test fun theSameLineChangedDifferentlyIsNoMerge() {
        assertNull(NoteMerge.text(base.content, "milk\nbread\n6 eggs\nbutter", "milk\nbread\n12 eggs\nbutter"))
        assertNull(NoteMerge.merge(base, note(content = "milk\nbread\n6 eggs\nbutter"), note(content = "milk\nbread\n12 eggs\nbutter")))
    }

    @Test fun neighbouringLinesChangedOnEachSideAreNoMergeAsInGit() {
        assertNull(NoteMerge.text(base.content, "MILK\nbread\neggs\nbutter", "milk\nBREAD\neggs\nbutter"))
    }

    @Test fun twoInsertsAtTheSamePlaceAreNoMerge() {
        assertNull(NoteMerge.text(base.content, "milk\nbread\neggs\nbutter\ncheese", "milk\nbread\neggs\nbutter\nham"))
    }

    @Test fun oneSideUnchangedTakesTheOtherWhole() {
        assertEquals("anything else", NoteMerge.text(base.content, base.content, "anything else"))
        assertEquals("mine", NoteMerge.text(base.content, "mine", base.content))
    }

    @Test fun titlesMergeAsOneValueAndNotebookOrPinFollowNextcloudWhenBothChanged() {
        val merged = NoteMerge.merge(base, note(title = "Groceries", notebook = "Kitchen", pinned = true), note(notebook = "Errands", pinned = false,
            content = "milk\nbread\neggs\nbutter\ncheese"))!!
        assertEquals("Groceries", merged.title)
        assertEquals("milk\nbread\neggs\nbutter\ncheese", merged.content)
        assertEquals("Both changed the notebook: Nextcloud's", "Errands", merged.notebook)
        assertEquals("Only Planner changed the pin", true, merged.pinned)
        assertNull("Titles changed differently: no merge", NoteMerge.merge(base, note(title = "A"), note(title = "B")))
    }

    @Test fun lineEndsAndAnEmptyLastLineSurvive() {
        assertEquals("milk\nbread\neggs\nbutter\n\nnote", NoteMerge.text("milk\nbread\neggs\nbutter\n", "milk\nbread\neggs\nbutter\n\nnote", "milk\nbread\neggs\nbutter\n"))
        assertEquals("First line changed here, last line removed there", "x\nb", NoteMerge.text("a\nb\nc", "x\nb\nc", "a\nb"))
    }
}
