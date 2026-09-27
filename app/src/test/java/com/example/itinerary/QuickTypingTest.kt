package com.example.itinerary

import com.example.itinerary.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class QuickTypingTest {
    private val date = LocalDate.of(2030, 3, 1)
    private fun input() = QuickInput("Dentist tomorrow at 3 every week remind me 30 minutes before for 3 occurrences", baseDate = date,
        dateOverride = "2030-03-04", timeOverride = "15:00", countText = "2", removeReminder = true, durationText = "45")
    @Test fun titleEditsPreserveAllCorrectionsAndLiteralSpans() {
        val original = input()
        val edited = original.edited(original.text.replace("Dentist", "Dentist check-up"))
        assertEquals(original.copy(text = edited.text), edited)
        val literal = QuickInput("Call Friday tomorrow", literals = listOf(5..10), dateOverride = "2030-03-05", baseDate = date)
        val moved = literal.edited("Please Call Friday tomorrow")
        assertEquals(listOf(12..17), moved.literals);assertEquals("2030-03-05", moved.dateOverride)
    }
    @Test fun onlyChangedSchedulingDetailsLoseTheirOverrides() {
        val original = input()
        val dateEdit = original.edited(original.text.replace("tomorrow", "Friday"))
        assertNull(dateEdit.dateOverride); assertEquals("15:00", dateEdit.timeOverride);assertEquals("2", dateEdit.countText)
        val timeEdit = original.edited(original.text.replace("at 3", "at 4"))
        assertNull(timeEdit.timeOverride);assertNull(timeEdit.durationText);assertEquals(original.dateOverride,timeEdit.dateOverride)
        val repeatEdit = original.edited(original.text.replace("3 occurrences", "5 occurrences"))
        assertNull(repeatEdit.countText);assertEquals("15:00",repeatEdit.timeOverride)
        assertFalse(original.edited(original.text.replace("30 minutes before", "1 hour before")).removeReminder)
    }
    @Test fun incompleteUnrelatedPhraseDoesNotWipeRecognisedCorrections() {
        val original = input()
        val changed = original.edited(original.text + " every weekday")
        assertEquals(original.dateOverride, changed.dateOverride)
        assertEquals(original.timeOverride, changed.timeOverride)
        assertEquals(original.countText, changed.countText)
        assertEquals(original.durationText, changed.durationText)
    }
    @Test fun removalAndNewWeekdayAnchorReconsiderRelevantChoice() {
        assertNull(input().edited(input().text.replace("tomorrow ", "")).dateOverride)
        val anchored = QuickInput("Gym every Monday at 3", baseDate = date, dateOverride = "2030-03-05", timeOverride = "16:00")
        val next = anchored.edited("Gym every Tuesday at 3")
        assertNull(next.dateOverride);assertEquals("16:00",next.timeOverride)
        assertEquals(QuickInput(task = true, baseDate = date), input().copy(task = true).edited(""))
    }
    @Test fun completionInMiddlePreservesSuffixAndPlacesCursorAfterInsertion() {
        val text = "Dentist tom at Cafe"
        val suggestion = quickCompletions(text, 11, emptyList(), false).single()
        val (result, cursor) = suggestion.apply(text)
        assertEquals("Dentist tomorrow at Cafe",result);assertEquals("Dentist tomorrow".length,cursor)
        assertEquals("Tomorrow",suggestion.label)
    }
    @Test fun suggestionsRespectQuotesLiteralsWordBoundariesAndTaskType() {
        assertTrue(quickCompletions("Discuss \"tom",12,emptyList(),false).isEmpty())
        assertTrue(quickCompletions("Dentist tom",11,listOf(8..10),false).isEmpty())
        assertTrue(quickCompletions("Dentist tomato",11,emptyList(),false).isEmpty())
        assertTrue(quickCompletions("Dentist for",11,emptyList(),true).isEmpty())
        assertEquals(3,quickCompletions("Dentist for",11,emptyList(),false).size)
        assertTrue(quickCompletions("Dentist tomorrow",16,emptyList(),false).isEmpty())
    }
    @Test fun reminderAndRepeatCompletionsUseExistingParserGrammar() {
        for (prefix in listOf("Dentist tomorrow 3pm remind me", "Dentist tomorrow 3pm every")) {
            val options = quickCompletions(prefix,prefix.length,emptyList(),false)
            assertEquals(3,options.size)
            options.forEach { option -> assertNull(QuickEntry.parse(option.apply(prefix).first,date).error) }
        }
    }
    @Test fun durationOverrideAndRemovalStayExplicitWithoutHidingParserErrors() {
        val original = QuickInput("Dentist tomorrow 3pm for 1h",baseDate=date,durationText="45")
        assertEquals(45,original.suggestion().durationMinutes)
        assertEquals(45,original.edited("Dentist check-up tomorrow 3pm for 1h").suggestion().durationMinutes)
        assertEquals(30,original.edited("Dentist tomorrow 3pm for 30min").suggestion().durationMinutes)
        assertNull(original.copy(durationText="").suggestion().durationMinutes)
        assertNotNull(original.copy(text="Dentist tomorrow Friday",durationText="45").suggestion().error)
    }
}
