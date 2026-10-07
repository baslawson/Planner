package com.example.itinerary.data

/**
 * Live formatting while a note is typed (bug notes 7, as Quillpad's editor): what to style in [text], without changing
 * it. Headings, **bold**, *italic*, ~~strike~~ and `code` look like themselves, and their Markdown signs stay but
 * faint, so the text and every cursor position are exactly what is typed. Ranges are [start, end) in [text].
 */
object MarkdownHighlight {
    enum class Style { HEADING, BOLD, ITALIC, STRIKE, CODE, MARK, LIST }
    data class Span(val start: Int, val end: Int, val style: Style)

    private val heading = Regex("^(#{1,6})([ \\t]+)(.*)$", RegexOption.MULTILINE)
    private val list = Regex("^([ \\t]*)([-*+]|\\d+[.)])([ \\t]+)(\\[[ xX]\\][ \\t]+)?", RegexOption.MULTILINE)
    private val quote = Regex("^([ \\t]*>)", RegexOption.MULTILINE)
    private val code = Regex("`([^`\\n]+)`")
    private val bold = Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
    private val strike = Regex("~~(?=\\S)(.+?)(?<=\\S)~~")
    private val italic = Regex("(?<![*\\w])([*_])(?![*_\\s])(.+?)(?<![*_\\s])\\1(?![*\\w])")

    fun spans(text: String): List<Span> {
        val out = mutableListOf<Span>()
        val codeRanges = mutableListOf<IntRange>()
        for (m in code.findAll(text)) {
            out += Span(m.range.first, m.range.first + 1, Style.MARK)
            out += Span(m.range.first + 1, m.range.last, Style.CODE)
            out += Span(m.range.last, m.range.last + 1, Style.MARK)
            codeRanges += m.range
        }
        fun inCode(at: Int) = codeRanges.any { at in it }
        for (m in heading.findAll(text)) {
            val marks = m.groups[1]!!.range
            out += Span(marks.first, m.groups[2]!!.range.last + 1, Style.MARK)
            out += Span(m.groups[3]!!.range.first, m.range.last + 1, Style.HEADING)
        }
        for (m in list.findAll(text)) {
            out += Span(m.groups[2]!!.range.first, m.groups[2]!!.range.last + 1, Style.LIST)
            m.groups[4]?.let { box -> out += Span(box.range.first, box.range.last + 1, Style.LIST) }
        }
        for (m in quote.findAll(text)) out += Span(m.groups[1]!!.range.last, m.groups[1]!!.range.last + 1, Style.MARK)
        fun paired(regex: Regex, style: Style, markGroup: Int?, innerGroup: Int) {
            for (m in regex.findAll(text)) {
                if (inCode(m.range.first)) continue
                val inner = m.groups[innerGroup]!!.range
                val markLength = markGroup?.let { m.groups[it]!!.value.length } ?: (inner.first - m.range.first)
                out += Span(m.range.first, m.range.first + markLength, Style.MARK)
                out += Span(inner.first, inner.last + 1, style)
                out += Span(m.range.last + 1 - markLength, m.range.last + 1, Style.MARK)
            }
        }
        paired(bold, Style.BOLD, 1, 2)
        paired(strike, Style.STRIKE, null, 1)
        paired(italic, Style.ITALIC, 1, 2)
        return out.filter { it.start < it.end && it.start >= 0 && it.end <= text.length }
    }
}
