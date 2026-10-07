package com.example.itinerary.data

import com.example.itinerary.data.MarkdownHighlight.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownHighlightTest {
    private fun styled(text: String, style: Style) = MarkdownHighlight.spans(text).filter { it.style == style }.map { text.substring(it.start, it.end) }

    @Test fun boldItalicStrikeAndCodeAreStyledWithFaintSigns() {
        val text = "A **gene** is *basic*, ~~old~~ and `code **here**`."
        assertEquals(listOf("gene"), styled(text, Style.BOLD))
        assertEquals(listOf("basic"), styled(text, Style.ITALIC))
        assertEquals(listOf("old"), styled(text, Style.STRIKE))
        assertEquals(listOf("code **here**"), styled(text, Style.CODE))
        assertEquals(listOf("`", "`", "**", "**", "~~", "~~", "*", "*"), styled(text, Style.MARK))
    }

    @Test fun headingsListsChecklistsAndQuotes() {
        val text = "## Genes\n- one\n- [ ] todo\n1. first\n> quoted\n#not a heading"
        assertEquals(listOf("Genes"), styled(text, Style.HEADING))
        assertEquals(listOf("-", "-", "[ ] ", "1."), styled(text, Style.LIST))
        assertTrue(styled(text, Style.MARK).containsAll(listOf("## ", ">")))
    }

    @Test fun plainTextAndHalfTypedSignsStayPlain() {
        assertTrue(MarkdownHighlight.spans("Nothing special here").isEmpty())
        assertTrue(MarkdownHighlight.spans("5 * 3 and a ** b").none { it.style == Style.BOLD || it.style == Style.ITALIC })
        assertTrue(MarkdownHighlight.spans("snake_case_name").none { it.style == Style.ITALIC })
        // Every span sits inside the text, whatever is typed.
        for (text in listOf("**", "`", "# ", "- [", "*a", "~~x~", "**a**b**")) MarkdownHighlight.spans(text).forEach { assertTrue(it.end <= text.length) }
    }
}
