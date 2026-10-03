package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Test

// DU-10: the line splitting ServerEvents.patch and ServerTasks.patch now share.
class IcsBlocksTest {
    @Test fun splitsIntoLogicalLinesKeepingTheirPhysicalLines() {
        val text = "﻿BEGIN:VCALENDAR\r\nSUMMARY:Long\r\n  title\r\n\tmore\r\n\r\nDTSTART;TZID=Australia/Sydney:20261003T090000\rEND:VCALENDAR\n"
        assertEquals(listOf(
            listOf("BEGIN:VCALENDAR"),
            listOf("SUMMARY:Long", "  title", "\tmore"),
            listOf("DTSTART;TZID=Australia/Sydney:20261003T090000"),
            listOf("END:VCALENDAR"),
        ), Ics.logicalBlocks(text))
    }

    @Test fun aContinuationLineFirstStartsItsOwnBlock() {
        assertEquals(listOf(listOf(" orphan"), listOf("X:1", " 2")), Ics.logicalBlocks(" orphan\nX:1\n 2"))
    }

    @Test fun blankLineInsideAFoldDoesNotEndIt() {
        assertEquals(listOf(listOf("X:1", " 2")), Ics.logicalBlocks("X:1\n\n 2"))
    }

    @Test fun names() {
        assertEquals("DTSTART", Ics.blockName(listOf("dtstart;TZID=x:1")))
        assertEquals("SUMMARY", Ics.blockName(listOf("Summary:a;b")))
        assertEquals("X-NO-VALUE", Ics.blockName(listOf("x-no-value")))
    }
}
