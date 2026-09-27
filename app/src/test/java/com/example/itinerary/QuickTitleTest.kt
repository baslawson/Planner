package com.example.itinerary

import com.example.itinerary.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** The optional Title box: its words are always the title; the when box is parsed as before. */
class QuickTitleTest {
    private val date = LocalDate.of(2030, 4, 3) // a Wednesday
    private val friday = LocalDate.of(2030, 4, 5)

    @Test fun titleWordsAreNeverReadAsDatesOrTimes() {
        listOf("Monday club", "Sun cream", "Tax return 2027", "Meeting 1500", "Dinner at 8 club").forEach { title ->
            val s = QuickInput("Friday 6pm", baseDate = date, title = title).parse()
            assertNull(title, s.error)
            assertEquals(title, title, s.title)
            assertEquals(title, friday, s.date); assertEquals(title, LocalTime.of(18, 0), s.time)
        }
    }

    @Test fun wordsInTheWhenBoxThatAreNotSchedulingJoinTheTitle() {
        val s = QuickInput("Friday 7pm with Bob", baseDate = date, title = "Dinner").parse()
        assertEquals("Dinner with Bob", s.title); assertEquals(friday, s.date)
        val place = QuickInput("at Luigi's Friday 7pm", baseDate = date, title = "Dinner").parse()
        assertEquals("Dinner", place.title); assertEquals("Luigi's", place.location)
    }

    @Test fun emptyTitleParsesExactlyLikeTheSingleBox() {
        listOf("Gym every Monday 6pm", "Dentist Fri 3pm for 45 minutes", "Buy sun cream", "Call \"Friday\" tomorrow").forEach { text ->
            assertEquals(text, QuickEntry.parse(text, date), QuickInput(text, baseDate = date).parse())
            assertEquals(text, QuickEntry.parse(text, date), QuickInput(text, baseDate = date, title = "  ").parse())
        }
    }

    @Test fun phrasePositionsStayRelativeToTheWhenBox() {
        val text = "every Monday 6pm for 1 hour"
        val s = QuickInput(text, baseDate = date, title = "Gym").parse()
        assertTrue(s.phrases.isNotEmpty())
        s.phrases.forEach { assertTrue(it.toString(), it.start >= 0 && it.end <= text.length) }
        assertEquals(listOf("every Monday", "6pm", "for 1 hour"), s.phrases.sortedBy { it.start }.map { text.substring(it.start, it.end).trim() })
    }

    @Test fun keepInTitleRangesInTheWhenBoxStillApply() {
        val text = "every weekend Friday 6pm"
        assertNotNull(QuickInput(text, baseDate = date, title = "Club").parse().error)
        val kept = QuickInput(text, literals = listOf(0 until 13), baseDate = date, title = "Club").parse()
        assertNull(kept.error); assertEquals("Club every weekend", kept.title); assertEquals(friday, kept.date)
    }

    @Test fun quotesInATypedTitleCannotUnbalanceTheParser() {
        val s = QuickInput("Friday", baseDate = date, title = "12\" pizza “night").parse()
        assertNull(s.error); assertEquals("12 pizza night", s.title); assertEquals(friday, s.date)
    }

    @Test fun oneLineFormQuotesTheTitleAndParsesTheSame() {
        val input = QuickInput("Friday 6pm", baseDate = date, title = "Monday club")
        assertEquals("\"Monday club\" Friday 6pm", input.entryText)
        val oneLine = QuickEntry.parse(input.entryText, date)
        assertEquals(input.parse().title, oneLine.title); assertEquals(friday, oneLine.date); assertEquals(LocalTime.of(18, 0), oneLine.time)
        // A title alone stays quoted, so a title such as "Friday" is still a title on one line.
        assertEquals("\"Friday\"", QuickInput("", title = "Friday").entryText)
        assertEquals("Friday", QuickEntry.parse(QuickInput("", title = "Friday").entryText, date).title)
        assertEquals("Gym Monday", QuickInput("Gym Monday").entryText)
    }

    @Test fun titleOnlyIsAValidEntryAndLengthCountsBothBoxes() {
        val titleOnly = QuickInput("", baseDate = date, title = "Buy milk")
        assertFalse(titleOnly.empty); assertEquals("Buy milk", titleOnly.parse().title)
        assertTrue(QuickInput("  ", title = " ").empty)
        assertEquals(502, QuickInput("Friday", baseDate = date, title = "a".repeat(495)).length)
    }

    @Test fun editingTheWhenBoxKeepsTheTitleAndUnrelatedCorrections() {
        val original = QuickInput("Friday 6pm", baseDate = date, title = "Monday club", timeOverride = "18:30")
        val edited = original.edited("Friday 6pm with Sam")
        assertEquals("Monday club", edited.title); assertEquals("18:30", edited.timeOverride)
        val retimed = original.edited("Friday 7pm")
        assertNull(retimed.timeOverride)
        val cleared = original.edited("")
        assertEquals("Monday club", cleared.title); assertEquals("", cleared.text)
    }
}
