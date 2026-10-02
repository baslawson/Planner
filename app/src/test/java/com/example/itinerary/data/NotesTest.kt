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
        assertThrows(IllegalArgumentException::class.java) { Notes.validate(PlannerNote()) }
        assertEquals(Notes.MAX_CONTENT, Notes.clean(PlannerNote(content = "x".repeat(Notes.MAX_CONTENT + 10))).content.length)
    }
}
