package com.example.itinerary

import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class QuickBatchTest {
    private val date = LocalDate.of(2030, 4, 3)
    private fun event(title: String = "Existing", day: LocalDate = date, time: LocalTime? = LocalTime.NOON, duration: Int? = 60) =
        ItineraryItem(tripId = 0, title = title, date = day, startTime = time, durationMinutes = duration)
    private fun candidate(title: String = "New", day: LocalDate = date, time: LocalTime? = LocalTime.NOON, duration: Int? = 30) =
        QuickCandidate("new", QuickEntrySuggestion(title, day, time, durationMinutes = duration), false)
    @Test fun unsupportedPhrasesRequireReviewButLiteralsRemainAvailable() {
        listOf("every weekend", "after lunch", "before dinner", "tomorrow morning", "tonight", "every other month").forEach { phrase ->
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
    @Test fun listsStripPrefixesWithoutDroppingInvalidRowsOrSplittingSinglePhrases() {
        assertEquals(listOf("Buy milk", "Dentist tomorrow 3pm", "", "Plan 2027"), QuickBatch.lines("• Buy milk\n\n2. Dentist tomorrow 3pm\n-\nPlan 2027"))
        assertNotNull(QuickBatch.problem((1..51).joinToString("\n") { "Task $it" }))
        assertNotNull(QuickBatch.problem("a".repeat(25001)))
        val rows = QuickBatch.review("Buy milk\nDentist tomorrow 3pm", date, emptyList())
        assertFalse(rows[0].typeChosen); assertTrue(rows[1].typeChosen)
        assertEquals(date.plusDays(1), rows[1].input.suggestion().date)
    }
    @Test fun rebuildingPreservesCorrectionsAndSeparateIdenticalRowIdentities() {
        val original = QuickBatch.review("Buy milk\nBuy milk", date, emptyList())
        assertNotEquals(original[0].id, original[1].id)
        val edited = original.mapIndexed { i, r -> r.copy(typeChosen = true, input = r.input.copy(task = true, dateOverride = date.plusDays(i.toLong()).toString())) }
        assertEquals(edited, QuickBatch.review("- Buy milk\n2. Buy milk", date.plusDays(5), edited))
        assertEquals(date, edited[0].input.baseDate)
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
    @Test fun failureAfterCommitReconcilesAndRetryNeverAddsSavedRows() = runBlocking {
        val rows = QuickBatch.review("First\nSecond\nThird", date, emptyList()).map { it.copy(typeChosen = true, input = it.input.copy(task = true)) }
        var persisted = rows
        val committed = mutableListOf<PlannerTask>()
        try {
            saveQuickRows(rows, { persisted = it }) { s, _, id ->
                committed += s.quickTask().copy(id = id)
                if (s.title == "Second") error("simulate interrupted receipt")
            }
            fail("Expected failure")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf("saved", "attempted", "pending"), persisted.map { it.status })
        persisted = reconcileQuickRows(persisted, emptyList(), committed)
        saveQuickRows(persisted, { persisted = it }) { s, _, id -> committed += s.quickTask().copy(id = id) }
        assertEquals(3, committed.size); assertEquals(3, committed.map { it.id }.toSet().size)
        assertTrue(persisted.all { it.status == "saved" })
    }
    @Test fun failedIntentWritePreventsDatabaseSaveAndUnselectedRowsRemain() = runBlocking {
        val rows = QuickBatch.review("First\nSecond", date, emptyList()).mapIndexed { i, r -> r.copy(typeChosen = true, selected = i == 0, input = r.input.copy(task = true)) }
        var saves = 0
        try { saveQuickRows(rows, { error("disk full") }) { _, _, _ -> saves++ } } catch (_: IllegalStateException) { }
        assertEquals(0, saves)
        var persisted = rows
        saveQuickRows(rows, { persisted = it }) { _, _, _ -> saves++ }
        assertEquals(1, saves); assertEquals("pending", persisted[1].status)
    }
}
