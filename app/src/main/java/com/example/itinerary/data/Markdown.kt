package com.example.itinerary.data

/**
 * The Markdown a note is written in, read just far enough to show it: headings, paragraphs, bullet and numbered lists,
 * checklists ("- [ ]" / "- [x]", tickable), quotes, fenced code and rules; inside a line **bold**, *italic*, ~~strike~~,
 * `code` and [links](https://…). Anything else (tables, images, HTML) shows as the text it is. Pure Kotlin, no Android.
 */
object Markdown {
    sealed interface Block { val line: Int }
    data class Heading(override val line: Int, val level: Int, val text: String) : Block
    data class Paragraph(override val line: Int, val text: String) : Block
    data class Bullet(override val line: Int, val indent: Int, val text: String) : Block
    data class Numbered(override val line: Int, val indent: Int, val number: String, val text: String) : Block
    data class Check(override val line: Int, val indent: Int, val done: Boolean, val text: String) : Block
    data class Quote(override val line: Int, val text: String) : Block
    data class Code(override val line: Int, val text: String) : Block
    data class Rule(override val line: Int) : Block

    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val check = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s?(.*)$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val numbered = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val quote = Regex("^\\s*>\\s?(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val fence = Regex("^\\s*```.*$")

    fun parse(content: String): List<Block> {
        val lines = content.split('\n')
        val blocks = mutableListOf<Block>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                fence.matches(line) -> {
                    val start = i; val body = mutableListOf<String>(); i++
                    while (i < lines.size && !fence.matches(lines[i])) { body += lines[i]; i++ }
                    blocks += Code(start, body.joinToString("\n")); i++
                    continue
                }
                line.isBlank() -> {}
                check.matches(line) -> check.find(line)!!.destructured.let { (indent, mark, text) ->
                    blocks += Check(i, indent.length / 2, mark != " ", text) }
                rule.matches(line) -> blocks += Rule(i)
                heading.matches(line) -> heading.find(line)!!.destructured.let { (marks, text) -> blocks += Heading(i, marks.length, text) }
                bullet.matches(line) -> bullet.find(line)!!.destructured.let { (indent, text) -> blocks += Bullet(i, indent.length / 2, text) }
                numbered.matches(line) -> numbered.find(line)!!.destructured.let { (indent, n, text) -> blocks += Numbered(i, indent.length / 2, n, text) }
                quote.matches(line) -> blocks += Quote(i, quote.find(line)!!.groupValues[1])
                else -> {
                    // Lines that follow each other make one paragraph, keeping their line breaks.
                    val start = i; val text = StringBuilder(line.trim())
                    while (i + 1 < lines.size && continues(lines[i + 1])) { i++; text.append('\n').append(lines[i].trim()) }
                    blocks += Paragraph(start, text.toString())
                }
            }
            i++
        }
        return blocks
    }

    private fun continues(line: String) = line.isNotBlank() && !fence.matches(line) && !check.matches(line) && !rule.matches(line) &&
        !heading.matches(line) && !bullet.matches(line) && !numbered.matches(line) && !quote.matches(line)

    /** A piece of a line with its look. */
    data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false, val strike: Boolean = false,
                   val code: Boolean = false, val url: String? = null)

    fun inline(text: String): List<Run> {
        val runs = mutableListOf<Run>()
        fun walk(s: String, bold: Boolean, italic: Boolean, strike: Boolean) {
            val plain = StringBuilder()
            fun flush() { if (plain.isNotEmpty()) { runs += Run(plain.toString(), bold, italic, strike); plain.clear() } }
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_~[]()#") { plain.append(s[i + 1]); i += 2; continue }
                if (c == '`') {
                    val end = s.indexOf('`', i + 1)
                    if (end > i + 1) { flush(); runs += Run(s.substring(i + 1, end), bold, italic, strike, code = true); i = end + 1; continue }
                }
                if (c == '[') {
                    val close = s.indexOf("](", i + 1)
                    val end = if (close > i) s.indexOf(')', close + 2) else -1
                    val url = if (end > close) s.substring(close + 2, end).trim() else ""
                    if (close > i + 1 && end > close && (url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:"))) {
                        flush(); runs += Run(s.substring(i + 1, close), bold, italic, strike, url = url); i = end + 1; continue
                    }
                }
                // ***both*** is bold and italic at once.
                val triple = listOf("***", "___").firstOrNull { s.startsWith(it, i) }
                if (triple != null) {
                    val end = s.indexOf(triple, i + 3)
                    if (end > i + 3) { flush(); walk(s.substring(i + 3, end), true, true, strike); i = end + 3; continue }
                }
                val pair = listOf("**", "__", "~~").firstOrNull { s.startsWith(it, i) }
                if (pair != null) {
                    val end = s.indexOf(pair, i + 2)
                    if (end > i + 2) {
                        flush()
                        walk(s.substring(i + 2, end), bold || pair != "~~", italic, strike || pair == "~~")
                        i = end + 2; continue
                    }
                }
                if ((c == '*' || c == '_') && i + 1 < s.length && !s[i + 1].isWhitespace() &&
                    // snake_case and 2*3*4 stay as written: an underscore inside a word isn't emphasis.
                    !(c == '_' && i > 0 && s[i - 1].isLetterOrDigit())) {
                    var end = s.indexOf(c, i + 1)
                    while (end > 0 && (s[end - 1].isWhitespace() || (c == '_' && end + 1 < s.length && s[end + 1].isLetterOrDigit()))) end = s.indexOf(c, end + 1)
                    if (end > i + 1) { flush(); walk(s.substring(i + 1, end), bold, true, strike); i = end + 1; continue }
                }
                plain.append(c); i++
            }
            flush()
        }
        walk(text, bold = false, italic = false, strike = false)
        return runs
    }

    /** The text without its Markdown marks: for a card's preview and a note's name. */
    fun plain(content: String): String = parse(content).joinToString("\n") { block ->
        when (block) {
            is Heading -> runsText(block.text)
            is Paragraph -> runsText(block.text)
            is Bullet -> "• " + runsText(block.text)
            is Numbered -> "${block.number}. " + runsText(block.text)
            is Check -> (if (block.done) "☑ " else "☐ ") + runsText(block.text)
            is Quote -> runsText(block.text)
            is Code -> block.text
            is Rule -> ""
        }
    }
    private fun runsText(text: String) = inline(text).joinToString("") { it.text }

    /** Ticked and total checklist lines. */
    fun checklist(content: String): Pair<Int, Int> {
        val checks = parse(content).filterIsInstance<Check>()
        return checks.count { it.done } to checks.size
    }

    /** [content] with the checklist box on line [line] ticked or cleared; anything else is left exactly as it was. */
    fun toggle(content: String, line: Int): String {
        val lines = content.split('\n').toMutableList()
        val match = lines.getOrNull(line)?.let { Regex("^(\\s*[-*+]\\s+\\[)([ xX])(].*)$").find(it) } ?: return content
        val (before, mark, after) = match.destructured
        lines[line] = before + (if (mark == " ") "x" else " ") + after
        return lines.joinToString("\n")
    }

    /** An edit from the toolbar: the new text and where the selection goes. */
    data class Edit(val text: String, val start: Int, val end: Int)

    /** Wraps the selection in [mark] (or puts a pair to type into, with nothing selected); unwraps it if already wrapped. */
    fun wrap(text: String, start: Int, end: Int, mark: String): Edit {
        val s = minOf(start, end).coerceIn(0, text.length); val e = maxOf(start, end).coerceIn(0, text.length)
        val m = mark.length
        if (s >= m && e + m <= text.length && text.substring(s - m, s) == mark && text.substring(e, e + m) == mark)
            return Edit(text.removeRange(e, e + m).removeRange(s - m, s), s - m, e - m)
        return Edit(text.substring(0, s) + mark + text.substring(s, e) + mark + text.substring(e), s + m, e + m)
    }

    /** Starts every line of the selection with [prefix] ("# ", "- ", "- [ ] "), or takes it off if they all have it. */
    fun prefixLines(text: String, start: Int, end: Int, prefix: String): Edit {
        val s = minOf(start, end).coerceIn(0, text.length); val e = maxOf(start, end).coerceIn(0, text.length)
        val first = text.lastIndexOf('\n', s - 1) + 1
        val last = text.indexOf('\n', e).let { if (it < 0) text.length else it }
        val lines = text.substring(first, last).split('\n')
        val remove = lines.all { it.startsWith(prefix) }
        // A line keeps one list mark: making a bullet a checklist line replaces "- " rather than adding to it.
        val listMark = Regex("^(- \\[[ xX]] |[-*+] |#{1,6} )")
        val changed = lines.map { if (remove) it.removePrefix(prefix) else prefix + it.replaceFirst(listMark, "") }
        val block = changed.joinToString("\n")
        val shift = block.length - (last - first)
        val caret = if (s == e) (if (remove) s - prefix.length else s + (changed.first().length - lines.first().length)).coerceAtLeast(first) else s
        return Edit(text.substring(0, first) + block + text.substring(last), caret, if (s == e) caret else e + shift)
    }
}
