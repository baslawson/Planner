package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class QuickFlexibilityTest {
    private val today = LocalDate.of(2030, 3, 1)
    private fun parse(raw: String) = QuickEntry.parse(raw, today)

    @Test fun naturalDurationsKeepTheWholePhraseAndRespectBounds() {
        for ((phrase, length) in listOf("two hours" to 120, "an hour and a half" to 90,
            "a quarter of an hour" to 15, "quarter of an hour" to 15, "three hours and a half" to 210,
            "two hours and five minutes" to 125, "twelve minutes" to 12, "half an hour" to 30)) {
            val raw = "Study tomorrow 3pm for $phrase"
            val result = parse(raw)
            assertNull(raw, result.error); assertEquals("Study", result.title); assertEquals(length, result.durationMinutes)
            assertEquals("for $phrase", result.phrases.single { it.kind == QuickPhraseKind.DURATION }.let { raw.substring(it.start, it.end) })
        }
        for (raw in listOf("Study for 25 hours", "Study for two", "Study for an hour and a", "Study for -2 hours"))
            assertNotNull(raw, parse(raw).error)
        assertEquals(120, parse("Study notify me two hours before").reminderMinutes)
    }
    @Test fun clockSpellingsWorkAloneAndInRangesWithOriginalOffsets() {
        for (clock in listOf("3.30pm", "3:30 p.m.", "15h30", "15H30", "3.30 p.m")) {
            val raw = "Study tomorrow at $clock"
            val result = parse(raw)
            assertNull(raw, result.error); assertEquals("Study", result.title); assertEquals(LocalTime.of(15,30), result.time)
            assertEquals("at $clock", result.phrases.single { it.kind == QuickPhraseKind.TIME }.let { raw.substring(it.start, it.end) })
        }
        assertEquals(LocalTime.of(15,0), parse("Study 3 p.m.").time)
        assertEquals(LocalTime.MIDNIGHT, parse("Study midnight").time)
        for (range in listOf("3.30–4.30pm", "3 p.m.–4 p.m.", "15h30–16h30")) {
            val r = parse("Study tomorrow $range")
            assertNull(range,r.error); assertEquals("Study",r.title); assertEquals(60,r.durationMinutes)
        }
        for (clock in listOf("25h30", "3.75pm", "13 p.m.", "9–5 p.m.", "3.300pm", "15h300")) assertNotNull(clock,parse("Study $clock").error)
    }
    @Test fun timeCorrectionsUseFinalClockAndRejectConflictsAndUnfinishedReplacements() {
        for (phrase in listOf("at 3pm—actually 4pm", "3 p.m., actually at 4 p.m.", "15h30 actually 16h00", "3pm actually 5pm actually 4pm")) {
            val r = parse("Study Friday $phrase for an hour and a half")
            assertNull(phrase,r.error); assertEquals("Study",r.title); assertEquals(LocalTime.of(16,0),r.time)
            assertEquals(90,r.durationMinutes)
        }
        for (phrase in listOf("3pm 4pm", "3pm actually", "3pm actually 25h30", "3pm actually 4", "3pm–4pm actually 5pm"))
            assertNotNull(phrase,parse("Study $phrase").error)
        val choice = parse("Study 3pm actually at 4")
        assertTrue(choice.ambiguousTime); assertEquals(2,choice.timeChoices.size)
    }
    @Test fun literalsAndManualCorrectionsSurviveTitleEditsAndBatchReview() {
        assertEquals("3 p.m. actually 4pm",parse("\"3 p.m. actually 4pm\"").title)
        assertNull(parse("\"for two hours\"").durationMinutes)
        val raw = "Study tomorrow 3.30pm actually 4pm for two hours"
        val input = QuickInput(raw,baseDate=today,timeOverride="17:00",durationText="45")
        assertEquals("17:00",input.edited(raw.replace("Study","Study maths")).timeOverride)
        assertNull(input.edited(raw.replace("4pm","5pm")).timeOverride)
        assertNull(input.edited(raw.replace("two hours","three hours")).durationText)
        val rows = QuickBatch.review("$raw\nGym tomorrow 15h30 for a quarter of an hour",today,emptyList())
        assertTrue(rows.all { it.input.suggestion().error == null })
        assertEquals(15,rows[1].input.suggestion().durationMinutes)
    }
    @Test fun typoSuggestionsAreExplicitNarrowAndRespectProtectedText() {
        for (typo in listOf("tommorow","tomorow","tommorrow","tuesdayy","wedensday")) {
            val raw = "Study $typo"
            val suggestion = quickCompletions(raw,raw.length,emptyList(),false).single()
            assertEquals(raw,parse(raw).title)
            assertEquals("Study",parse(suggestion.apply(raw).first).title)
        }
        for (raw in listOf("Study tomorrow", "Study tomato", "Study someday", "Study \"tommorow"))
            assertTrue(raw,quickCompletions(raw,raw.length,emptyList(),false).isEmpty())
        val raw = "Study tommorow at Cafe"
        val option = quickCompletions(raw,14,emptyList(),false).single()
        assertEquals("Study tomorrow at Cafe",option.apply(raw).first)
        assertTrue(quickCompletions(raw,14,listOf(6..13),false).isEmpty())
    }
}
