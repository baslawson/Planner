package com.example.itinerary.ui

import org.junit.Assert.*
import org.junit.Test

class ConversionRulesTest {
    @org.junit.Test fun blankNewTaskEditorBlocksConversionBeforeADraftExists() {
        org.junit.Assert.assertEquals("Close the new task that's open first, then try again.",
            conversionBlock(false, false, false, false, newTaskEditorOpen = true))
        org.junit.Assert.assertNull(conversionBlock(true, false, false, false, newTaskEditorOpen = true))
    }
    @Test fun theEditorNeededMustBeFree() {
        assertEquals("Close the event that's open first, then try again.", conversionBlock(true, eventDraft = true, eventEditorOpen = true, taskDraft = true))
        assertEquals("You have an unfinished event. Resume or discard it first, then try again.", conversionBlock(true, true, false, false))
        assertNull(conversionBlock(true, false, false, taskDraft = true))
        assertEquals("You have an unfinished new task. Resume or discard it first, then try again.", conversionBlock(false, true, true, true))
        assertNull(conversionBlock(false, true, true, false))
    }

    @Test fun theNoticeSaysWhatHappens() {
        assertEquals("Saving makes this task and moves the original to Recently deleted (Undo takes both back).", conversionNotice("task", emptyList()))
        assertEquals("Saving makes this event and moves the original to Recently deleted (Undo takes both back).\\nWon't carry over:\\n• A\\n• B",
            conversionNotice("event", listOf("A", "B")).replace("\n", "\\n"))
    }
}
