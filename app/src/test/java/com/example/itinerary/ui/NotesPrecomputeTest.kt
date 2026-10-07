package com.example.itinerary.ui

import com.example.itinerary.data.Markdown
import com.example.itinerary.data.NoteFilter
import com.example.itinerary.data.NoteSort
import com.example.itinerary.data.Notes
import com.example.itinerary.data.PlannerNote
import com.example.itinerary.data.Search
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

// UI-3, UI-4, UI-8: the Notes page's work done once per change to the notes gives what it gave when done every time.
class NotesPrecomputeTest {
    private val words = listOf("Café", "cafe", "École", "ecole", "#tag", "- [ ] buy milk", "- [x] done", "**bold**", "# Heading",
        "> quote", "```", "1. first", "Ünïcödé", "plain words", "", "  ", "---", "[link](https://x.y)", "naïve", "NAIVE")
    private fun text(random: Random) = List(random.nextInt(0, 8)) { words.random(random) }.joinToString(if (random.nextBoolean()) "\n" else " ")
    private fun notes(random: Random) = List(random.nextInt(0, 25)) { i ->
        PlannerNote(id = "n$i", title = if (random.nextInt(3) == 0) "" else text(random).replace('\n', ' '), content = text(random),
            notebook = listOf("", "Work", "Home", "École").random(random), archived = random.nextInt(4) == 0, pinned = random.nextInt(4) == 0,
            tags = List(random.nextInt(0, 3)) { listOf("red", "Rouge", "café", "x").random(random) }, modified = random.nextLong(0, 1000))
    }

    // Notes.visible as it was before UI-3: every field normalised on every call.
    private fun oldVisible(notes: List<PlannerNote>, filter: NoteFilter, query: String, sort: NoteSort): List<PlannerNote> {
        val needle = Search.normalize(query.trim())
        return notes.filter { note ->
            when (filter) {
                NoteFilter.All -> !note.archived
                is NoteFilter.Notebook -> !note.archived && note.notebook == filter.name
                is NoteFilter.Tag -> !note.archived && filter.name in note.tags
                NoteFilter.Archive -> note.archived
            } && (needle.isEmpty() || listOf(note.title, note.content, note.notebook).any { Search.normalize(it).contains(needle) } ||
                note.tags.any { Search.normalize(it).contains(needle) })
        }.sortedWith(Notes.order(sort))
    }

    @Test fun prenormalisedSearchMatchesTheOldOne() {
        val random = Random(31)
        val queries = listOf("", " ", "cafe", "CAFÉ", "ecole", "milk", "red", "rouge", "work", "naive", "e", "x", "bold", "**", "café work")
        repeat(300) {
            val all = notes(random)
            val fields = Notes.searchFields(all)
            val filters = listOf(NoteFilter.All, NoteFilter.Archive) + Notes.notebooks(all).map { NoteFilter.Notebook(it) } +
                Notes.tags(all).map { NoteFilter.Tag(it) }
            filters.forEach { filter -> queries.forEach { query -> NoteSort.entries.forEach { sort ->
                assertEquals(oldVisible(all, filter, query, sort), Notes.visible(all, filter, query, sort, fields))
                assertEquals(oldVisible(all, filter, query, sort), Notes.visible(all, filter, query, sort))
            } } }
        }
    }

    @Test fun showMenuCountsMatchVisible() {
        val random = Random(7)
        repeat(300) {
            val all = notes(random)
            val counts = noteFilterCounts(all)
            val choices = listOf(NoteFilter.All, NoteFilter.Archive) + Notes.notebooks(all).map { NoteFilter.Notebook(it) } +
                Notes.tags(all).map { NoteFilter.Tag(it) }
            choices.forEach { assertEquals(it.toString(), Notes.visible(all, it, "").size, counts[it] ?: 0) }
        }
    }

    @Test fun cardTextMatchesLabelPlainAndChecklist() {
        val random = Random(11)
        repeat(1000) {
            val note = notes(random).firstOrNull() ?: return@repeat
            val card = noteCardText(note)
            val body = Markdown.plain(note.content).lines().filter { it.isNotBlank() }
            assertEquals(Notes.label(note), card.label)
            // The first line is left out when it is the name: no title, or a title that is that line (auto title).
            val named = note.title.isBlank() || note.title.trim() == body.firstOrNull()?.trim()?.take(com.example.itinerary.data.Notes.MAX_TITLE)
            assertEquals((if (named) body.drop(1) else body).joinToString("\n"), card.preview)
            assertEquals(Markdown.checklist(note.content), card.done to card.total)
        }
    }
}
