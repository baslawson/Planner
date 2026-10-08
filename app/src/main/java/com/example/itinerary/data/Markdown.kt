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

    // A closing run of #s counts only after a space: "# Learn C#" keeps its #.
    private val heading = Regex("^(#{1,6})\\s+(.*?)(?:\\s+#+)?\\s*$")
    private val check = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s?(.*)$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val numbered = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val quote = Regex("^\\s*>\\s?(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val fence = Regex("^\\s*```.*$")

    fun parse(content: String): List<Block> {
        // Windows (CRLF) and old Mac (CR) line ends read as plain ones.
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').split('\n')
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
    fun plain(content: String): String = plain(parse(content))
    // From blocks already parsed, so a card that also counts its checklist parses once (UI-4).
    fun plain(blocks: List<Block>): String = blocks.joinToString("\n") { block ->
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
    fun checklist(content: String): Pair<Int, Int> = checklist(parse(content))
    fun checklist(blocks: List<Block>): Pair<Int, Int> {
        val checks = blocks.filterIsInstance<Check>()
        return checks.count { it.done } to checks.size
    }

    /** [content] with the checklist box on line [line] ticked or cleared; anything else is left exactly as it was. */
    fun toggle(content: String, line: Int): String {
        // Hunt 23: the lines [parse] counts (Windows and old Mac line ends too), each line end left as it was.
        val ends = Regex("\r\n|\r|\n").findAll(content).map { it.range }.toList()
        if (line < 0 || line > ends.size) return content
        val start = if (line == 0) 0 else ends[line - 1].last + 1
        val end = ends.getOrNull(line)?.first ?: content.length
        val match = Regex("^(\\s*[-*+]\\s+\\[)([ xX])(].*)$").find(content.substring(start, end)) ?: return content
        val (before, mark, after) = match.destructured
        return content.substring(0, start) + before + (if (mark == " ") "x" else " ") + after + content.substring(end)
    }

    /** An edit from the toolbar: the new text and where the selection goes. */
    data class Edit(val text: String, val start: Int, val end: Int)

    /**
     * Wraps the selection in [mark] (or puts a pair to type into, with nothing selected); unwraps it if already wrapped.
     * A single mark inside a double one is part of it: Italic on **bold** gives ***bold***, not *bold*.
     */
    fun wrap(text: String, start: Int, end: Int, mark: String): Edit {
        val s = minOf(start, end).coerceIn(0, text.length); val e = maxOf(start, end).coerceIn(0, text.length)
        val m = mark.length
        val c = mark[0]
        // How many of the mark's character run up to the selection, and on from its end.
        val before = (s - 1 downTo 0).takeWhile { text[it] == c }.count()
        val after = (e until text.length).takeWhile { text[it] == c }.count()
        val wrapped = if (m == 1) before % 2 == 1 && after % 2 == 1 else before >= m && after >= m && (before != 1 && after != 1)
        if (wrapped) return Edit(text.removeRange(e, e + m).removeRange(s - m, s), s - m, e - m)
        return Edit(text.substring(0, s) + mark + text.substring(s, e) + mark + text.substring(e), s + m, e + m)
    }

    // The mark a line starts with, after its indent: a checklist box (ticked or not, any bullet), a bullet, or a heading.
    private val lineMark = Regex("^([ \\t]*)([-*+] \\[[ xX]] |[-*+] |#{1,6} )")
    // Which mark it is: every checklist box is one mark, as is every bullet; each heading level is its own.
    private fun markKind(mark: String) = when { '[' in mark -> "[ ]"; mark.startsWith('#') -> mark; else -> "-" }

    /** Starts every line of the selection with [prefix] ("# ", "- ", "- [ ] "), or takes it off if they all have it. */
    fun prefixLines(text: String, start: Int, end: Int, prefix: String): Edit {
        val s = minOf(start, end).coerceIn(0, text.length); val e = maxOf(start, end).coerceIn(0, text.length)
        val first = text.lastIndexOf('\n', s - 1) + 1
        val last = text.indexOf('\n', e).let { if (it < 0) text.length else it }
        val lines = text.substring(first, last).split('\n')
        // A quote holds the line as it is (a list item or heading stays one inside it): "> " goes in front, or comes off
        // when every line has it (bug hunt 19, P4: it was taken for a bullet).
        if (prefix.startsWith('>')) {
            val off = lines.all { it.startsWith(">") }
            return shifted(text, s, e, first, last, lines, lines.map { if (off) it.removePrefix(">").removePrefix(" ") else prefix + it })
        }
        val kind = markKind(prefix)
        fun markOf(line: String) = lineMark.find(line)?.groupValues?.get(2).orEmpty()
        // Off only when every line has this mark ("- " on a checklist line is a different mark, not this one).
        val remove = lines.all { markOf(it).let { m -> m.isNotEmpty() && markKind(m) == kind } }
        val changed = lines.map { line ->
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            val mark = markOf(line)
            val rest = line.substring(indent.length + mark.length)
            when {
                remove -> indent + rest
                // Already this mark (a ticked box, a "*" bullet): left as it is.
                mark.isNotEmpty() && markKind(mark) == kind -> line
                // A heading starts the line; a list item keeps its indent (a nested item stays nested).
                kind.startsWith('#') -> prefix + rest
                // A line keeps one mark: a bullet made a checklist line has its "- " replaced, not added to.
                else -> indent + prefix + rest
            }
        }
        return shifted(text, s, e, first, last, lines, changed)
    }

    // The lines first..last of [text] replaced by [changed], with the selection [s]..[e] moved to match.
    private fun shifted(text: String, s: Int, e: Int, first: Int, last: Int, lines: List<String>, changed: List<String>): Edit {
        val block = changed.joinToString("\n")
        val result = text.substring(0, first) + block + text.substring(last)
        // The selection keeps to the same text: the first line's start moves by its own change, the end by the total.
        val firstShift = changed.first().length - lines.first().length
        val newStart = (s + firstShift).coerceIn(first, first + changed.first().length)
        val newEnd = if (s == e) newStart else (e + block.length - (last - first)).coerceIn(newStart, result.length)
        return Edit(result, newStart, newEnd)
    }

    private val checkMark = Regex("^(\\s*)([-*+])\\s+\\[[ xX]]\\s?")
    private val bulletMark = Regex("^(\\s*)([-*+])\\s+")
    private val numberMark = Regex("^(\\s*)(\\d{1,9})([.)])\\s+")

    /**
     * Enter typed in a list item carries the list on, as a checklist app does: "- [ ] milk⏎" starts "- [ ] " (always
     * unticked), "- " and "3. " give "- " and "4. ". Enter on an item with nothing in it ends the list: the mark goes and
     * no line is added. Only a single "\n" typed at [cursor] counts ([new] is [old] with it), so pasting, the toolbar
     * and autocorrect are left alone; so are code blocks, rules and Enter typed inside the mark. Null when nothing changes.
     */
    fun continueList(old: String, new: String, cursor: Int): Edit? {
        val at = cursor - 1
        if (new.length != old.length + 1 || at !in 0..old.length || new[at] != '\n' ||
            new.substring(0, at) != old.substring(0, at) || new.substring(cursor) != old.substring(at)) return null
        val lineStart = old.lastIndexOf('\n', at - 1) + 1
        val lineEnd = old.indexOf('\n', at).let { if (it < 0) old.length else it }
        val line = old.substring(lineStart, lineEnd)
        if (rule.matches(line) || old.substring(0, lineStart).split('\n').count { fence.matches(it) } % 2 == 1) return null
        val (mark, next) = checkMark.find(line)?.let { it.value to it.groupValues[1] + it.groupValues[2] + " [ ] " }
            ?: numberMark.find(line)?.let { m -> m.destructured.let { (indent, n, sep) -> m.value to "$indent${n.toLong() + 1}$sep " } }
            ?: bulletMark.find(line)?.let { it.value to it.groupValues[1] + it.groupValues[2] + " " }
            ?: return null
        if (at < lineStart + mark.length) return null
        if (line.substring(mark.length).isBlank()) // an empty item: Enter leaves the list
            return (old.substring(0, lineStart) + old.substring(lineEnd)).let { Edit(it, lineStart, lineStart) }
        return Edit(new.substring(0, cursor) + next + new.substring(cursor), cursor + next.length, cursor + next.length)
    }
}
