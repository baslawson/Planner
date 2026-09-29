package com.example.itinerary

import com.example.itinerary.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class QuickConflictsTest {
    private val date = LocalDate.of(2030, 4, 3)
    private fun event(title: String = "Existing", day: LocalDate = date, time: LocalTime? = LocalTime.NOON, duration: Int? = 60) =
        ItineraryItem(tripId = 0, title = title, date = day, startTime = time, durationMinutes = duration)
    private fun candidate(title: String = "New", day: LocalDate = date, time: LocalTime? = LocalTime.NOON, duration: Int? = 30) =
        QuickCandidate("new", QuickEntrySuggestion(title, day, time, durationMinutes = duration), false)
    @Test fun unsupportedPhrasesRequireReviewButLiteralsRemainAvailable() {
        listOf("every other year", "after lunch", "before dinner", "tomorrow morning", "tonight").forEach { phrase ->
            val text = "Call $phrase"
            val s = QuickEntry.parse(text, date)
            assertNotNull(phrase, s.error)
            val p = s.phrases.single { it.kind == QuickPhraseKind.UNSUPPORTED || it.kind == QuickPhraseKind.TIME }
            assertNull(QuickEntry.parse(text, date, listOf(p.start until p.end)).error)
            assertNull(QuickEntry.parse("Call \"$phrase\"", date).error)
        }
        assertNull(QuickEntry.parse("Call every 2 weeks 3pm", date).error)
        assertNotNull(QuickEntry.parse("Call at Cafe after lunch", date).error)
    }
    @Test fun adjacentEventsAreAllowedButBuffersAndOvernightAreChecked() {
        assertTrue(quickConflicts(listOf(candidate(time = LocalTime.of(13,0))), listOf(event()), emptyList()).isEmpty())
        assertEquals(1, quickConflicts(listOf(candidate(time = LocalTime.of(13,0))), listOf(event().copy(bufferAfterMinutes = 5)), emptyList()).size)
        assertEquals(1, quickConflicts(listOf(candidate(day = date.plusDays(1), time = LocalTime.of(0,15))), listOf(event(time = LocalTime.of(23,30), duration = 90)), emptyList()).size)
    }
    @Test fun unknownDurationsArePointsAndAllDayEventsDoNotInventConflicts() {
        assertEquals(1, quickConflicts(listOf(candidate(time = LocalTime.of(12,30), duration = null)), listOf(event()), emptyList()).size)
        assertTrue(quickConflicts(listOf(candidate(time = LocalTime.of(12,30))), listOf(event(duration = null)), emptyList()).isEmpty())
        assertTrue(quickConflicts(listOf(candidate()), listOf(event(time = null), event().copy(skipped = true), event().copy(category = "Bills")), emptyList()).isEmpty())
    }
    @Test fun duplicatesIgnoreSpacingAndIncludeUndatedTasksButNotCompletedTasks() {
        assertEquals(1, quickConflicts(listOf(candidate("  EXISTING ", time = null)), listOf(event(time = null)), emptyList()).size)
        val c = QuickCandidate("new", QuickEntrySuggestion("buy milk", date, null), true)
        assertEquals(1, quickConflicts(listOf(c), emptyList(), listOf(PlannerTask(title = "Buy   milk"))).size)
        assertTrue(quickConflicts(listOf(c), emptyList(), listOf(PlannerTask(title = "buy milk", done = true))).isEmpty())
    }
    @Test fun repeatAndWithinBatchConflictsAreCheckedWithRetryTokensExcluded() {
        val c = candidate().copy(suggestion = candidate().suggestion.copy(repeat = RepeatRule.WEEKLY, repeatCount = 3))
        assertEquals(1, quickConflicts(listOf(c), listOf(event(day = date.plusWeeks(2))), emptyList()).size)
        assertEquals(1, quickConflicts(listOf(candidate(), candidate("Second").copy(token = "second")), emptyList(), emptyList()).size)
        assertTrue(quickConflicts(listOf(candidate()), listOf(event().copy(draftToken = "new")), emptyList()).isEmpty())
    }
    @Test fun resumedAllDayDraftWarnsWhenItsBaseDateHasPassedButUndatedTaskDoesNot() {
        val s = QuickInput(text = "Saved draft", baseDate = date).suggestion()
        val now = date.plusDays(1).atStartOfDay(ZoneId.of("Australia/Perth"))
        assertTrue(s.isPast(now, false))
        assertFalse(s.isPast(now, true))
    }
}
