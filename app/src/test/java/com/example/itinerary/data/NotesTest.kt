package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test

class NotesTest {
    @Test fun blocksAreReadLineByLine() {
        val blocks = Markdown.parse("# Trip\nPack light\nand early\n\n- socks\n  - wool\n1. passport\n- [ ] charger\n- [x] tickets\n> quoted\n---\n```\ncode *not bold*\n```")
        assertEquals(Markdown.Heading(0, 1, "Trip"), blocks[0])
        assertEquals(Markdown.Paragraph(1, "Pack light\nand early"), blocks[1])
        assertEquals(Markdown.Bullet(4, 0, "socks"), blocks[2])
        assertEquals(Markdown.Bullet(5, 1, "wool"), blocks[3])
        assertEquals(Markdown.Numbered(6, 0, "1", "passport"), blocks[4])
        assertEquals(Markdown.Check(7, 0, false, "charger"), blocks[5])
        assertEquals(Markdown.Check(8, 0, true, "tickets"), blocks[6])
        assertEquals(Markdown.Quote(9, "quoted"), blocks[7])
        assertEquals(Markdown.Rule(10), blocks[8])
        assertEquals(Markdown.Code(11, "code *not bold*"), blocks[9])
        assertEquals(10, blocks.size)
    }

    @Test fun inlineMarksAndLinks() {
        assertEquals(listOf(Markdown.Run("a "), Markdown.Run("b", bold = true), Markdown.Run(" "), Markdown.Run("c", italic = true),
            Markdown.Run(" "), Markdown.Run("d", strike = true), Markdown.Run(" "), Markdown.Run("e", code = true)),
            Markdown.inline("a **b** *c* ~~d~~ `e`"))
        assertEquals(listOf(Markdown.Run("see "), Markdown.Run("site", url = "https://example.com")), Markdown.inline("see [site](https://example.com)"))
        // Not links or emphasis: other schemes, snake_case, a lone star, an unclosed pair, an escaped mark.
        assertEquals("[x](javascript:alert)", Markdown.inline("[x](javascript:alert)").joinToString("") { it.text })
        assertEquals(listOf(Markdown.Run("snake_case_name")), Markdown.inline("snake_case_name"))
        assertEquals(listOf(Markdown.Run("2 * 3 = 6")), Markdown.inline("2 * 3 = 6"))
        assertEquals(listOf(Markdown.Run("**open")), Markdown.inline("**open"))
        assertEquals(listOf(Markdown.Run("*not*")), Markdown.inline("\\*not\\*"))
        assertEquals(listOf(Markdown.Run("both", bold = true, italic = true)), Markdown.inline("***both***").filter { it.text.isNotEmpty() })
    }

    @Test fun checklistLinesTickWithoutTouchingTheRest() {
        val text = "Shopping\n- [ ] milk\n  * [X] bread\n- not a box"
        assertEquals("Shopping\n- [x] milk\n  * [X] bread\n- not a box", Markdown.toggle(text, 1))
        assertEquals("Shopping\n- [ ] milk\n  * [ ] bread\n- not a box", Markdown.toggle(text, 2))
        assertEquals(text, Markdown.toggle(text, 3))
        assertEquals(text, Markdown.toggle(text, 99))
        assertEquals(1 to 2, Markdown.checklist(text))
    }

    // Types Enter where "|" is; the result has "|" where the cursor ends up, or null when Enter is left as it is.
    private fun enter(before: String): String? {
        val at = before.indexOf('|'); val old = before.removeRange(at, at + 1)
        val new = old.substring(0, at) + "\n" + old.substring(at)
        return Markdown.continueList(old, new, at + 1)?.let { it.text.substring(0, it.start) + "|" + it.text.substring(it.start) }
    }

    @Test fun enterCarriesAListOn() {
        assertEquals("- [ ] test\n- [ ] |", enter("- [ ] test|"))
        assertEquals("- [x] done\n- [ ] |", enter("- [x] done|")) // a new box is never ticked
        assertEquals("- [ ] a\n  * [ ] b\n  * [ ] |", enter("- [ ] a\n  * [ ] b|"))
        assertEquals("- milk\n- |", enter("- milk|"))
        assertEquals("+ milk\n+ |", enter("+ milk|"))
        assertEquals("9. nine\n10. |", enter("9. nine|"))
        assertEquals("1) one\n2) |\nafter", enter("1) one|\nafter"))
        // Enter in the middle of an item splits it: the rest goes into the new item.
        assertEquals("- [ ] bread\n- [ ] |and milk", enter("- [ ] bread|and milk"))
        // An empty item ends the list: the mark goes, no line is added.
        assertEquals("- [ ] eggs\n|", enter("- [ ] eggs\n- [ ] |"))
        assertEquals("- eggs\n|\nnext", enter("- eggs\n- |\nnext"))
        assertEquals("|", enter("- [ ]  |"))
    }

    @Test fun enterIsLeftAloneOutsideLists() {
        assertNull(enter("plain|"))
        assertNull(enter("# Heading|"))
        assertNull(enter("---|"))
        assertNull(enter("- -|- -")) // inside a rule's dashes
        assertNull(enter("|- [ ] milk")) // before the mark
        assertNull(enter("- [| ] milk")) // inside the mark
        assertNull(enter("```\n- in code|\n```"))
        assertNull(enter("2026-10-03|")) // a date, not a numbered item
        // Not a single Enter at the cursor: pasted text, or an Enter somewhere else.
        assertNull(Markdown.continueList("- milk", "- milk\nbread", 13))
        assertNull(Markdown.continueList("- milk", "- milk\n", 3))
        assertNull(Markdown.continueList("- milk", "- milks", 7))
    }

    @Test fun notebooksAndTagsAreSuggestedAsYouType() {
        val names = listOf("Errands", "Home", "Homework", "Work", "Old home")
        assertEquals(names, Notes.suggest(names, ""))
        // Starting with it first, then containing it; capitals ignored.
        assertEquals(listOf("Home", "Homework", "Old home"), Notes.suggest(names, "hom"))
        assertEquals(listOf("Work", "Homework"), Notes.suggest(names, " WOR "))
        assertEquals(listOf("Homework", "Old home"), Notes.suggest(names, "hom", taken = listOf("home")))
        assertEquals(emptyList<String>(), Notes.suggest(names, "xyz"))
        // A name typed in other capitals is the existing one; a new name stays as typed.
        assertEquals("Home", Notes.existingSpelling(names, "home"))
        assertEquals("Garden", Notes.existingSpelling(names, "Garden"))
    }

    @Test fun toolbarWrapsAndPrefixes() {
        assertEquals(Markdown.Edit("a **bc** d", 4, 6), Markdown.wrap("a bc d", 2, 4, "**"))
        assertEquals(Markdown.Edit("a bc d", 2, 4), Markdown.wrap("a **bc** d", 4, 6, "**"))
        assertEquals(Markdown.Edit("****", 2, 2), Markdown.wrap("", 0, 0, "**"))
        assertEquals("- [ ] one\n- [ ] two\nthree", Markdown.prefixLines("one\ntwo\nthree", 0, 5, "- [ ] ").text)
        assertEquals("one\ntwo", Markdown.prefixLines("- one\n- two", 0, 11, "- ").text)
        // A bullet made a checklist line keeps one mark.
        assertEquals("- [ ] milk", Markdown.prefixLines("- milk", 3, 3, "- [ ] ").text)
        assertEquals(Markdown.Edit("# Title", 4, 4), Markdown.prefixLines("Title", 2, 2, "# "))
    }

    @Test fun plainTextForCardsAndNames() {
        assertEquals("Trip\n• socks\n☐ charger\n☑ tickets\nbold and link", Markdown.plain("# Trip\n- socks\n- [ ] charger\n- [x] tickets\n**bold** and [link](https://a.b)"))
        assertEquals("Trip", Notes.label(PlannerNote(content = "# Trip\nmore")))
        assertEquals("Named", Notes.label(PlannerNote(title = " Named ", content = "# Trip")))
        assertEquals("Untitled note", Notes.label(PlannerNote(content = "---")))
    }

    @Test fun filtersSearchAndOrder() {
        val old = PlannerNote(id = "a", title = "Groceries", notebook = "Home", modified = 1, tags = listOf("food"))
        val newer = PlannerNote(id = "b", title = "Ideas", content = "Paint the fence", modified = 5)
        val pinned = PlannerNote(id = "c", title = "Wifi", notebook = "Home", pinned = true, modified = 0)
        val archived = PlannerNote(id = "d", title = "Old trip", archived = true, modified = 9)
        val all = listOf(old, newer, pinned, archived)
        assertEquals(listOf("c", "b", "a"), Notes.visible(all, NoteFilter.All, "").map { it.id })
        assertEquals(listOf("c", "a"), Notes.visible(all, NoteFilter.Notebook("Home"), "").map { it.id })
        assertEquals(listOf("d"), Notes.visible(all, NoteFilter.Archive, "").map { it.id })
        assertEquals(listOf("a"), Notes.visible(all, NoteFilter.Tag("food"), "").map { it.id })
        assertEquals(listOf("b"), Notes.visible(all, NoteFilter.All, "fence").map { it.id })
        assertEquals(listOf("c", "a"), Notes.visible(all, NoteFilter.All, "home").map { it.id })
        assertEquals(listOf("Home"), Notes.notebooks(all))
    }

    @Test fun cleanAndValidate() {
        val cleaned = Notes.clean(PlannerNote(title = " Line\nbreak ", content = "body \n\n", notebook = " /Work/ ", tags = listOf("#a", " a", "", "b")))
        assertEquals("Line break", cleaned.title)
        assertEquals("body", cleaned.content)
        assertEquals("Work", cleaned.notebook)
        assertEquals(listOf("a", "b"), cleaned.tags)
        Notes.validate(cleaned)
        // Empty is refused when saving (Repository.saveNote), not when reading a backup (see anEmptyNoteIsReadableButNotSaved).
        assertFalse(Notes.hasContent(PlannerNote()))
        assertEquals(Notes.MAX_CONTENT, Notes.clean(PlannerNote(content = "x".repeat(Notes.MAX_CONTENT + 10))).content.length)
    }

    @Test fun attachmentsAreStoredFilesWithPlainNames() {
        fun note(vararg files: String) = PlannerNote(title = "x", attachments = files.map { Attachment(itemId = 0, name = "File", fileName = it, mimeType = "text/plain") })
        Notes.validate(note("a1.txt", "photo_2.jpg"))
        listOf("../escape", "/abs", ".hidden", "a b").forEach { bad ->
            assertThrows(bad, IllegalArgumentException::class.java) { Notes.validate(note(bad)) }
        }
        assertThrows(IllegalArgumentException::class.java) { Notes.validate(note("same.txt", "same.txt")) }
        // Attachments alone make a note worth keeping.
        Notes.validate(note("only.txt").copy(title = ""))
    }

    // Bug hunt 2 Oct (N-2..N-6, U-5, N-1, U-1, S-3).
    @Test fun toolbarSelectionsStayInsideTheText() {
        // N-2: these made the selection end negative, which crashed the editor.
        val a = Markdown.prefixLines("- [ ] a\n- [ ] b", 0, 8, "- [ ] ")
        assertEquals("a\nb", a.text); assertTrue(a.start in 0..a.end && a.end <= a.text.length)
        val h = Markdown.prefixLines("- [ ] a", 0, 2, "# ")
        assertEquals("# a", h.text); assertTrue(h.start in 0..h.end && h.end <= h.text.length)
        // Every selection of a few texts, with every mark, stays valid.
        val texts = listOf("", "x", "- [ ] a\n- [x] b", "# T\n- one\n\n* two", "**b** *i* ~~s~~", "a\n\n\nb")
        for (t in texts) for (st in 0..t.length) for (en in st..t.length) {
            for (mark in listOf("# ", "- ", "- [ ] ")) Markdown.prefixLines(t, st, en, mark).let { assertTrue("$t $st $en $mark $it", it.start in 0..it.end && it.end <= it.text.length) }
            for (mark in listOf("**", "*", "~~", "`")) Markdown.wrap(t, st, en, mark).let { assertTrue("$t $st $en $mark $it", it.start in 0..it.end && it.end <= it.text.length) }
        }
    }

    @Test fun bulletAndChecklistMarksSwap() {
        // N-3: • on checklist lines makes them bullets, not "[ ] milk".
        assertEquals("- milk\n- eggs", Markdown.prefixLines("- [ ] milk\n- [x] eggs", 0, 21, "- ").text)
        assertEquals("- [ ] milk", Markdown.prefixLines("- milk", 0, 6, "- [ ] ").text)
        assertEquals("milk", Markdown.prefixLines("- [ ] milk", 0, 10, "- [ ] ").text)
    }

    @Test fun headingsKeepAHashInsideAWord() {
        assertEquals(Markdown.Heading(0, 1, "Learn C#"), Markdown.parse("# Learn C#").single())
        assertEquals(Markdown.Heading(0, 2, "Closed"), Markdown.parse("## Closed ##").single())
    }

    @Test fun italicOnBoldAddsItalic() {
        // N-6: it was unwrapping one star of the bold.
        assertEquals("a ***b*** c", Markdown.wrap("a **b** c", 4, 5, "*").text)
        assertEquals("a b c", Markdown.wrap("a *b* c", 3, 4, "*").text)
        assertEquals("a *b* c", Markdown.wrap("a ***b*** c", 5, 6, "**").text)
    }

    @Test fun windowsLineEndsReadAsPlainOnes() {
        val crlf = "Shopping\r\n- [ ] milk\r\n- [x] eggs\r\n"
        assertEquals(1 to 2, Markdown.checklist(crlf))
        assertEquals("Shopping\n- [ ] milk\n- [x] eggs", Notes.clean(PlannerNote(content = crlf)).content)
    }

    @Test fun anEmptyNoteIsReadableButNotSaved() {
        // N-1: a backup's note whose only file has gone stays readable.
        Notes.validate(PlannerNote())
        assertFalse(Notes.hasContent(PlannerNote()))
        assertTrue(Notes.hasContent(PlannerNote(content = "x")))
    }

    @Test fun mergeTakesEachSidesChanges() {
        val base = PlannerNote(id = "n", title = "T", content = "body", notebook = "A", reminderAt = 5)
        val mine = base.copy(content = "my body")
        // Theirs changed other things (notebook; the reminder cleared by Done): both kept.
        val theirs = base.copy(notebook = "B", reminderAt = null, modified = 9)
        assertEquals(base.copy(content = "my body", notebook = "B", reminderAt = null, modified = 9), mergeNotes(base, mine, theirs))
        // Both changed the text: the user decides.
        assertNull(mergeNotes(base, mine, base.copy(content = "their body")))
        // The same change on both sides is no conflict.
        assertEquals(mine, mergeNotes(base, mine, mine))
    }

    @Test fun nextcloudTidiedTitlesStillMatch() {
        assertTrue(NoteMapping.sameTitle("Plans: 2026/27?", "Plans 202627"))
        assertTrue(NoteMapping.sameTitle("Groceries", "Groceries (2)"))
        assertFalse(NoteMapping.sameTitle("Groceries", "Recipes"))
        val remote = RemoteNote(1, "e", "Plans 202627", "Plans: 2026/27?\nmore", "", false)
        assertEquals("Plans: 2026/27?", NoteMapping.localTitle(remote, "Plans: 2026/27?"))
        assertTrue(NoteMapping.sameText("a\r\nb\n", "a\nb"))
    }
}
