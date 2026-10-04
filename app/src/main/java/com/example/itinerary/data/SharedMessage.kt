package com.example.itinerary.data

import java.time.LocalDate
import java.time.LocalTime

/**
 * An email shared as text with its headers on top, as Thunderbird (K-9) sends it: "Subject: …", "Date: …", "From: …",
 * "To: …", "Cc: …" (labels in the phone's language, Subject and Date left out when the message has none), a blank line,
 * then the body. Read by position rather than by label words, so any language works: the headers end in a run of lines
 * with addresses (From, To, Cc), at least two of them, and at most Subject and Date come before it.
 */
object EmailHeaders {
    data class Parsed(val subject: String?, val from: String, val body: String)

    private val header = Regex("^([^:\\s][^:]{0,39}):[ \\t]*(.*)$")
    private const val ADDRESS = "[^\\s<>@,;()]+@[^\\s<>@,;()]+\\.[^\\s<>@,;()]+"
    // One address: "Name <address>", "\"Doe, Jane\" <address>", "Smith, Sam <address>" or the address alone, with an RFC
    // comment after it ("sam@example.com (Sam Smith)", SQ8-5). Tested one at a time on a short part (SQ8-6).
    private val oneAddress = Regex("^(?:(?:\"[^\"]*\"|[^<>\"])*<$ADDRESS>|$ADDRESS)(?:\\s*\\([^()]*\\))?$")
    private const val MAX_ADDRESS = 400
    // Thunderbird's sent date, in any of its formats: a year and a clock time, the year in two digits too ("01.10.26, 14:30",
    // SQ-12). The year stands alone: "Order 120345, pickup 10:30" has none (SQ8-1).
    private val sentDate = Regex("(?<!\\d)(?:19|20)\\d\\d(?!\\d).*\\d{1,2}[:.]\\d\\d|\\d{1,2}[:.]\\d\\d.*(?<!\\d)(?:19|20)\\d\\d(?!\\d)|" +
        "\\b\\d{1,2}[./-]\\d{1,2}[./-]\\d{2}\\b.*\\b\\d{1,2}[:.]\\d\\d\\b")
    private val emailHeaderShape = Regex("<$ADDRESS>|[,;]")
    private val contactLabel = Regex("mail", RegexOption.IGNORE_CASE)

    /**
     * Whether a header's value is its addresses: "Name <a@b.c>" or a bare address, one or a list. "Subject: Meet
     * sam@example.com" isn't one (SQ-13). Split at "," and ";" outside quotes and "<…>" and each part tested alone, so a
     * long To or Cc list takes time in step with its length and can't overflow the regex engine's stack (SQ8-6). A part
     * without an address is the first half of a "Last, First <address>" name (SQ8-5).
     */
    private fun addressValue(value: String): Boolean {
        if (value.isBlank()) return false
        var pending = ""
        var start = 0
        var quoted = false
        var angled = false
        fun part(end: Int): Boolean {
            val text = (if (pending.isEmpty()) "" else "$pending, ") + value.substring(start, end).trim()
            start = end + 1
            if (text.length > MAX_ADDRESS) return false
            if (oneAddress.matches(text)) { pending = ""; return true }
            if (text.isBlank() || text.any { it == '@' || it == '<' || it == '>' }) return false
            pending = text; return true
        }
        for (i in value.indices) when (value[i]) {
            '"' -> if (!angled) quoted = !quoted
            '<' -> if (!quoted) angled = true
            '>' -> if (!quoted) angled = false
            ',', ';' -> if (!quoted && !angled && !part(i)) return false
        }
        return part(value.length) && pending.isEmpty()
    }

    // The words a sent date is written with in a mail app's language: month and weekday names, and a few short ones.
    private val dateWords: Set<String> by lazy {
        val styles = listOf(java.time.format.TextStyle.FULL, java.time.format.TextStyle.SHORT,
            java.time.format.TextStyle.FULL_STANDALONE, java.time.format.TextStyle.SHORT_STANDALONE)
        val names = mutableSetOf("at", "um", "kl", "klo", "om", "alle", "las", "às", "à", "в", "de", "del", "di", "den",
            "am", "pm", "gmt", "utc", "cet", "cest", "bst", "est", "edt", "pst", "pdt", "mez", "mesz", "sept", "tues", "weds", "thurs")
        for (tag in listOf("en", "de", "fr", "es", "it", "nl", "pt", "sv", "da", "nb", "fi", "pl", "cs", "ru", "tr", "hu", "el", "ro")) {
            val locale = java.util.Locale.forLanguageTag(tag)
            for (style in styles) {
                java.time.Month.values().forEach { names += it.getDisplayName(style, locale).lowercase(locale).trimEnd('.') }
                java.time.DayOfWeek.values().forEach { names += it.getDisplayName(style, locale).lowercase(locale).trimEnd('.') }
            }
        }
        names
    }
    private val word = Regex("\\p{L}+")
    // A line that is only a sent date: a year and a clock time, and no word but a month, a weekday or a short joining
    // word ("Fri, 2 Oct 2026 10:00", "01.10.26, 14:30"). "Meeting 12/10/26 10:30" is a subject (SQ8-5).
    private fun onlyADate(line: String) = line.length <= 60 && sentDate.containsMatchIn(line) &&
        word.findAll(line).all { it.value.length <= 2 || it.value.lowercase() in dateWords }

    /** The headers and body of [text], or null when it doesn't start with an email's header block. */
    fun read(text: String): Parsed? {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        val blank = lines.indexOfFirst { it.isBlank() }.let { if (it < 0) lines.size else it }
        if (blank !in 2..10) return null
        // A folded header goes on over lines that start with a space.
        val block = mutableListOf<String>()
        for (line in lines.take(blank)) {
            if (line.first().isWhitespace() && block.isNotEmpty()) block[block.size - 1] += " " + line.trim() else block += line.trim()
        }
        val matches = block.map { header.matchEntire(it) ?: return null }
        val values = matches.map { it.groupValues[2].trim() }
        // From, To and Cc: the trailing run of lines that are addresses (SH-1: one address is a contact card, not an email).
        val from = values.indices.reversed().takeWhile { addressValue(values[it]) }.lastOrNull() ?: return null
        if (values.size - from < 2 || from > 2) return null
        // SQ-13: a contact card's "Email: … / Work email: …" is no header block: an email has a "Name <address>" or a list,
        // and no label naming mail.
        val run = from until values.size
        if (run.none { emailHeaderShape.containsMatchIn(values[it]) } && run.any { contactLabel.containsMatchIn(matches[it].groupValues[1]) }) return null
        val subject = when (from) {
            2 -> values[0]
            // One line before From: the subject, unless it is the sent date (a message without a subject, SH-2).
            1 -> values[0].takeUnless { onlyADate(it) }
            else -> null
        }?.ifBlank { null }
        return Parsed(subject, block[from], lines.drop(blank + 1).joinToString("\n").trim())
    }

    // An earlier message quoted without ">": "-----Original Message-----", a line of underscores (SH-4, SQ-4).
    private val quoteIntro = Regex("^\\s*(?:-{2,}\\s*Original Message\\s*-{2,}|_{5,})\\s*$", RegexOption.IGNORE_CASE)
    // "… wrote:" in a mail app's language, with a date in the line, or Gmail's "On … wrote:": "Sam wrote:" alone may be the
    // person quoting a line into their own message. Who wrote it may follow the verb: "schrieb Sam Smith <sam@…>:",
    // "napisał(a):", "ezt írta (időpont: …):" (SQ9-3).
    private val wroteIntro = Regex("^(?=.*\\d).{0,200}(?<!\\p{L})(?i:wrote|writes|schrieb|a écrit|escribió|escribio|scrisse|ha scritto|" +
        "schreef|skrev|kirjoitti|napisał(?:\\(a\\))?|napisal|napsal(?:\\(a\\))?|napsala|escreveu|написал|написала|yazdı|írta)" +
        "(?:\\s*\\([^()]{0,80}\\))?(?:\\s+(?:\\p{Lu}[\\p{L}.'-]*|\"[^\"]{0,80}\"|\\(?<[^<>\\s]{1,100}>\\)?|[^\\s<>@()]+@[^\\s<>@()]+)){0,6}" +
        "\\s*:\\s*$|^\\s*On .{1,200} wrote:\\s*$")
    // In a language not listed above: the line starts with the sent date (at most two short or date words before it), has
    // a year and a clock time, and ends in who wrote it, a name or an address, just before the ":": "Den 30.09.2026 kl.
    // 10:00 ritade Jo:", "2026-09-30 10:00 GMT+02:00 Jo <jo@…>:". The person's own "Booking confirmed for 2026-10-20 at
    // 10:30:", "Ihr Termin am 20.10.2026 um 10:30 Uhr:" and "Your appointment on 20.10.2026 with Dr Smith:" start with
    // their own words; "AM:" or "Uhr:" is no writer, and the "20.10" of a date no clock time (SQ8-1, SQ9-2).
    private val sentenceInside = Regex("[.!?]\\s+\\p{Lu}")
    private val introYear = Regex("(?<!\\d)(?:19|20)\\d\\d(?!\\d)|\\b\\d{1,2}[./-]\\d{1,2}[./-]\\d{2}\\b")
    private val introClock = Regex("(?<![\\d.:])\\d{1,2}(?::\\d\\d|\\.\\d\\d(?![\\d.]))")
    private val writerBeforeColon = Regex("(?:(?<![\\p{L}'-])(\\p{Lu}[\\p{L}'-]*)|>|$ADDRESS)\\s*:\\s*$")
    private fun dateIntro(line: String): Boolean {
        if (line.length > 300 || !line.trimEnd().endsWith(":") || !introYear.containsMatchIn(line) || !introClock.containsMatchIn(line) ||
            sentenceInside.containsMatchIn(line)) return false
        val opening = word.findAll(line.takeWhile { !it.isDigit() }).toList()
        if (opening.size > 2 || opening.any { it.value.length > 4 && it.value.lowercase() !in dateWords }) return false
        val writer = writerBeforeColon.find(line) ?: return false
        return writer.groupValues[1].lowercase().let { it.isEmpty() || it !in dateWords && it != "uhr" && it != "h" }
    }
    private val signature = Regex("^(?:-- ?|Sent from my .*|Get Outlook for .*)$")
    // A forwarded message's marker: what follows it is what the person shares (SQ8-3), Apple Mail's in other languages too
    // (SQ9-7).
    private val forwardMarker = Regex("^\\s*(?:-{2,}\\s*(?:Forwarded message|Weitergeleitete Nachricht|Message transféré|" +
        "Mensaje reenviado|Messaggio inoltrato|Doorgestuurd bericht|Mensagem encaminhada|Vidarebefordrat meddelande|" +
        "Videresendt (?:meddelelse|melding)|Välitetty viesti|Wiadomość przekazana)\\s*-{2,}|Begin forwarded message:|" +
        "Anfang der weitergeleiteten Nachricht:|Début du message réexpédié\\s*:?|Begin doorgestuurd bericht:|" +
        "Inicio del mensaje reenviado:|Inizio messaggio inoltrato:|Início da mensagem reencaminhada:)\\s*$", RegexOption.IGNORE_CASE)
    // A forward's subject, Outlook's prefixes in a few languages: "Fwd:", "FW:", "WG:", "TR:", "RV:", "I:", "Doorst:", "VB:",
    // "ENC:", "PD:" (SQ9-7). "VS:" is left out: Danish forwards with it, Finnish replies.
    private val forwardPrefix = Regex("^\\s*(?:fwd?|wg|tr|rv|i|doorst|vb|vl|enc|pd|ilt)\\s*:", RegexOption.IGNORE_CASE)
    /** Whether a share's [subject] says it is a forward ("Fwd: …", "FW: …"). */
    fun forwardSubject(subject: String?) = subject != null && forwardPrefix.containsMatchIn(subject)
    // A P.S. under the signature is the person's own again (SQ9-9).
    private val postscript = Regex("^\\s*(?i:p\\.?\\s?(?:p\\.?\\s?)?s)(?:[.:\\s]|$)")
    // Outlook's header block above a forwarded or quoted message, no ">" (SQ-4): From, To and Subject among a few lines
    // of "Label: value", labels in a mail app's language (SQ8-10: Finnish and a few more). A travel plan's "From: … To: …"
    // has no Subject; a web form's notification has its "Message:" in the block (SQ8-3).
    private val headerLine = Regex("^\\s*\\*?([\\p{L}][\\p{L} .-]{0,24}?)\\*?\\s*:\\*?\\s*\\S.*$")
    private val fromLabel = Regex("from|von|de|van|da|fra|från|od|от|lähettäjä|feladó|kimden|差出人|发件人|寄件者", RegexOption.IGNORE_CASE)
    private val toLabel = Regex("to|an|à|a|aan|para|til|till|do|кому|vastaanottaja|komu|címzett|kime|宛先|收件人|收件者", RegexOption.IGNORE_CASE)
    private val subjectLabel = Regex("subject|betreff|objet|onderwerp|asunto|oggetto|assunto|emne|ämne|temat|aihe|тема|předmět|tárgy|konu|件名|主题|主旨",
        RegexOption.IGNORE_CASE)
    private val formLabel = Regex("message|comments?|enquiry|inquiry|details|nachricht|mitteilung|bericht|mensaje|messaggio|mensagem|" +
        "meddelande|besked|viesti|kommentar|opmerkingen?|commentaire", RegexOption.IGNORE_CASE)
    private const val calendarWords = "january|february|march|april|may|june|july|august|september|october|november|december|" +
        "jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec|monday|tuesday|wednesday|thursday|friday|saturday|sunday|" +
        "mon|tues|tue|wed|thurs|thur|thu|fri|sat|sun"
    // A title after a name: capitalised words ("Senior Manager", "Ph.D.", "Head of Sales"), no digit, not a month or a
    // weekday: "Wednesday, October 14 at 7pm then" is a sentence (SQ9-6).
    private const val nameTitle = "(?!(?i:$calendarWords)\\b)\\p{Lu}[\\p{L}'&/-]*(?:\\.\\p{L}+)*\\.?" +
        "(?:\\s+(?:\\p{Lu}[\\p{L}'&/-]*(?:\\.\\p{L}+)*\\.?|of|and|for|&|und|en|et|y|e|de)){0,5}"
    // A name on its own line under a sign-off: "Sam", "Jo van Dijk", "Dr. A. Smith", "SAM SMITH | Senior Manager" (SQ8-10),
    // "Sam x", "Sam - Acme", "Sam Smith (she/her)", "Sam Smith, Ph.D." (SQ9-6). Each word capitalised but a name's
    // particle, and no sentence: "Monday works for me." and "Sounds good." aren't one (SQ8-4).
    private val nameLine = Regex("^\\s*\\p{Lu}[\\p{L}'.-]*(?:\\s+(?:\\p{Lu}[\\p{L}'.-]*|van|von|de|der|den|du|da|di|del|della|le|la|" +
        "ten|ter|bin|al)){0,3}(?:\\s*\\([^()]{1,30}\\))?(?:\\s+x{1,3})?\\s*(?:[|•·]\\s*\\S.*|(?:,|\\s[-–—])\\s*$nameTitle)?\\s*$")
    // A sign-off on its own line, the signature block under it (SQ-5).
    private val signOff = Regex("^\\s*(?:kind regards|best regards|warm regards|regards|many thanks|thanks|thank you|cheers|best|" +
        "best wishes|all the best|groeten|met vriendelijke groet(?:en)?|vriendelijke groet(?:en)?|mit freundlichen grüßen|viele grüße|" +
        "liebe grüße|cordialement|bien à vous|saludos|un saludo|atentamente|cordiali saluti|saluti|med vänlig hälsning|hälsningar|" +
        "med venlig hilsen|hilsen|mvh)\\s*,?\\s*$", RegexOption.IGNORE_CASE)

    /** A header block: where it ends (the index after it) and its Subject's value. */
    private class Block(val end: Int, val subject: String?)

    /**
     * The header block starting at line [i], or null when none starts there: Outlook's, From first, or under a forward
     * marker ([forwarded]) a From and a Subject in any order (Gmail: From, Date, Subject, To). A line starting with a space
     * goes on the header above it: a To list wrapped onto the next line (SQ8-10). Reads a few lines at most, so [message]
     * stays in step with the body's length (SQ8-7).
     */
    private fun headerBlock(lines: List<String>, i: Int, forwarded: Boolean): Block? {
        val labels = mutableListOf<String>()
        var subject: String? = null
        var j = i
        while (j < lines.size && labels.size < 8 && lines[j].length <= 2_000) {
            val label = headerLine.matchEntire(lines[j])?.groupValues?.get(1)?.trim()
            if (label == null && labels.isNotEmpty() && lines[j].isNotBlank() && lines[j].first().isWhitespace()) { j++; continue }
            labels += label ?: break
            if (subject == null && subjectLabel.matches(label)) subject = lines[j].substringAfter(':').trim().removePrefix("*").trim()
            j++
        }
        val block = labels.any(subjectLabel::matches) && labels.none(formLabel::matches) &&
            if (forwarded) labels.any(fromLabel::matches) else labels.firstOrNull()?.let(fromLabel::matches) == true && labels.any(toLabel::matches)
        return if (block) Block(j, subject) else null
    }

    /**
     * A forward shared with its marker on top: the forwarded message's subject, so the marker line isn't the title when
     * the share has no subject of its own (SQ9-8). Null when [body] doesn't start with a forward or an earlier message.
     */
    fun forwardedSubject(body: String): String? {
        val lines = body.replace("\r\n", "\n").replace('\r', '\n').lines()
        val first = lines.indexOfFirst { it.isNotBlank() }.takeIf { it >= 0 } ?: return null
        if (!startsAnEarlierMessage(lines[first])) return null
        val start = (first + 1 until lines.size).firstOrNull { lines[it].isNotBlank() } ?: return null
        return headerBlock(lines, start, forwarded = true)?.subject?.ifBlank { null }
    }
    /** Whether [line] marks a forward, or an earlier message ("Original Message", a line of underscores). */
    fun startsAnEarlierMessage(line: String) = forwardMarker.matches(line) || quoteIntro.matches(line)

    /**
     * The words of [body] that are the message itself. Left out: quoted lines (">") and the line introducing them; from an
     * earlier message quoted without ">" ("… wrote:" with no ">" under it, "Original Message", Outlook's From/To/Subject
     * block) onwards; and a signature ("-- ", or the lines under a sign-off such as "Kind regards"). The person's own lines
     * between and after quoted ones are kept (SQ-3), and so is a forwarded message under its marker, less its header block
     * (SQ8-3). An earlier message's block is a forward, read on below its headers, when nothing of the person's own comes
     * before it, when the share's subject says so ([forwarded], "Fwd: …") or the block's own Subject does (SQ9-7). Below a
     * signature, a forward or a P.S. is the person's again (SQ9-1, SQ9-9). Only these are read for a date: an earlier
     * message's sent date or a signature's opening hours aren't this message's appointment (SH-4). Each line is looked at
     * a bounded number of times (SQ8-7).
     */
    fun message(body: String, forwarded: Boolean = false): String {
        val lines = body.replace("\r\n", "\n").replace('\r', '\n').lines()
        fun quoted(i: Int) = lines.getOrNull(i)?.trimStart()?.startsWith(">") == true
        // For each line: the next one from it on with words; the next with words that isn't quoted; and where the
        // person's words start again below a signature, the next forward marker or P.S., unless an earlier message comes
        // first.
        val nextWords = IntArray(lines.size + 1) { lines.size }
        val nextOwn = IntArray(lines.size + 1) { lines.size }
        val resume = IntArray(lines.size + 1) { lines.size }
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            nextWords[i] = if (line.isNotBlank()) i else nextWords[i + 1]
            nextOwn[i] = if (line.isNotBlank() && !quoted(i)) i else nextOwn[i + 1]
            resume[i] = when {
                forwardMarker.matches(line) || postscript.containsMatchIn(line) -> i
                quoted(i) || quoteIntro.matches(line) || wroteIntro.matches(line) || dateIntro(line) ||
                    line.length <= 2_000 && headerLine.matchEntire(line)?.groupValues?.get(1)?.trim()?.let(fromLabel::matches) == true -> lines.size
                else -> resume[i + 1]
            }
        }
        // The next line with words starts a quote: "On … wrote:", "Agenda:" before "> item one".
        fun introducesQuote(i: Int) = lines[i].trimEnd().endsWith(":") && quoted(nextWords[i + 1])
        // A sign-off after the message, then a name on its own line and a short block up to where the person's words
        // start again: the signature.
        fun signedOff(i: Int): Boolean {
            if (!signOff.matches(lines[i])) return false
            val first = nextOwn[i + 1]
            val stop = resume[i + 1]
            var rest = 0
            var j = first
            while (j < stop && rest <= 10) { rest++; j = nextOwn[j + 1] }
            return rest in 1..10 && lines[first].length <= 200 && nameLine.matches(lines[first])
        }
        val kept = mutableListOf<String>()
        var ownWords = false
        var afterForward = false
        var forwardRead = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (quoted(i) || introducesQuote(i)) { i++; continue }
            if (forwardMarker.matches(line)) { afterForward = true; forwardRead = true; i++; continue }
            // A header block under a forward marker is the forwarded message's: read on below it (SQ8-3).
            if (afterForward && line.isNotBlank()) {
                afterForward = false
                val block = headerBlock(lines, i, forwarded = true)
                if (block != null) { i = block.end; continue }
            }
            // "Original Message", a line of underscores, or Outlook's block under the person's own words (at the very top
            // it is what was shared): an earlier message, unless it is a forward (SQ9-7).
            val intro = quoteIntro.matches(line)
            if (intro || ownWords && line.isNotBlank() && headerBlock(lines, i, forwarded = false) != null) {
                val block = headerBlock(lines, if (intro) nextWords[i + 1] else i, forwarded = true)
                if (block != null && (!ownWords || forwarded && !forwardRead || forwardSubject(block.subject))) {
                    forwardRead = true; i = block.end; continue
                }
                break
            }
            // "… wrote:", or a line ending in ":" with a year and a clock time and then who wrote it, introduces an earlier
            // message in any language (SQ-4, SQ8-1).
            if (wroteIntro.matches(line) || dateIntro(line)) break
            if (signature.matches(line.trimEnd().let { if (it == "--") "-- " else it }) || ownWords && signedOff(i)) {
                if (resume[i + 1] < lines.size) { i = resume[i + 1]; continue }
                break
            }
            kept += line
            if (line.isNotBlank()) ownWords = true
            i++
        }
        return kept.joinToString("\n").trim()
    }
}

/**
 * When a shared text is about: [date] and [time] when it names one day (and one time), every day it names in [dates],
 * and for a time Quick entry can't place ("10:30": morning or evening?) the readings to pick from in [timeChoices].
 */
data class SharedWhen(val date: LocalDate?, val time: LocalTime?, val dates: List<LocalDate>,
                      val timeChoices: List<LocalTime> = emptyList())

/**
 * The days a message names, read sentence by sentence with Quick entry's grammar, but only where it is plainly a day:
 * a date with its month written ("14 October", "Oct 14"), a numeric date with its year (14/10/2026), or a weekday with a
 * time in the same sentence ("Saturday at 7pm"). Not repeats or ranges ("Monday to Friday", "every Monday"), "until …",
 * short number pairs ("24/7", "1/2", "9-5"), a weekday alone ("We spoke on Monday"), past days or a time already gone
 * today. A day is offered only when it is the only one; several are listed, and nothing is guessed (SH-3, Q6-2, Q6-3).
 */
object SharedDates {
    /** How much of a message is read: its opening, where an appointment is said; the rest is left (SH-5). */
    const val MAX_READ = 8_000
    private const val MAX_SENTENCES = 200
    private const val weekdays = "monday|mon|tuesday|tues|tue|wednesday|weds|wed|thursday|thurs|thur|thu|friday|fri|saturday|sat|sunday|sun"
    private const val months = "january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|august|aug|september|sept|sep|october|oct|november|nov|december|dec"
    private val monthWord = Regex("\\b(?:$months)\\b", RegexOption.IGNORE_CASE)
    private val weekdayWord = Regex("\\b(?:$weekdays)\\b", RegexOption.IGNORE_CASE)
    private val numericWithYear = Regex("\\b\\d{1,2}[/.-]\\d{1,2}[/.-](?:\\d{4}|\\d{2})\\b|\\b\\d{4}-\\d{2}-\\d{2}\\b")
    private val until = Regex("\\b(?:until|till|til|through|thru|ends?|valid|expires?)\\b", RegexOption.IGNORE_CASE)
    // A full stop after these is no sentence end: "Oct. 12", "Mon. 12 Oct", "10 a.m. on Friday", "Dr. Smith" (Q6-7).
    private const val shortNames = "jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun"
    private val abbreviation = Regex("(?:\\b(?:dr|mr|mrs|ms|approx|incl)|\\b[ap]\\.m)$", RegexOption.IGNORE_CASE)
    // These only before a number or a word in lower case ("Sat. 12", "No. 5", "a. m."): "on Sat. See you", "Plan A. Then",
    // "5 Main St. The …" end a sentence (SQ-2).
    private val shortBeforeNumber = Regex("\\b(?:$shortNames|st|no|[ap])$", RegexOption.IGNORE_CASE)
    // Quick entry reads "Oct 12" but not "Oct. 12".
    private val shortNameDot = Regex("\\b($shortNames)\\.(?=\\s)", RegexOption.IGNORE_CASE)
    private val sentenceEnd = Regex("[.!?]+\\s+")

    private data class Found(val date: LocalDate, val time: LocalTime?, val choices: List<LocalTime>)

    // Whether the words just before [end] in [line] (not before [start]) end in [regex]: only its last few characters are
    // looked at, so a long line of ". " that don't split takes time in step with its length (SQ9-10).
    private fun endsIn(regex: Regex, line: String, start: Int, end: Int) =
        regex.toPattern().matcher(line).region(maxOf(start, end - 12), end).useTransparentBounds(true).find()

    // The message's sentences: lines, split after . ! ? except after an abbreviation; a long one also at , and ; (Q6-12).
    internal fun sentences(text: String): List<String> = text.lines().flatMap { line ->
        val parts = mutableListOf<String>()
        var start = 0
        for (end in sentenceEnd.findAll(line)) {
            val next = line.getOrNull(end.range.last + 1)
            if (endsIn(abbreviation, line, start, end.range.first) ||
                endsIn(shortBeforeNumber, line, start, end.range.first) && next != null && (next.isDigit() || next.isLowerCase())) continue
            parts += line.substring(start, end.range.first + 1); start = end.range.last + 1
        }
        parts += line.substring(start)
        parts.flatMap { if (it.length > QuickEntry.MAX_LENGTH) it.split(Regex("[,;]\\s+")) else listOf(it) }
    }.map { it.trim() }.filter { it.length in 3..QuickEntry.MAX_LENGTH }

    /**
     * The first [MAX_READ] characters of [text], ending at a line or sentence end before that: cut inside a word, "at
     * 10:30am" would read as "at 1" and "EUR 84.20" as "EUR 8" (SQ-11).
     */
    fun opening(text: String): String {
        if (text.length <= MAX_READ) return text
        val head = text.take(MAX_READ + 1)
        val end = (MAX_READ - 1 downTo MAX_READ / 2).firstOrNull { i ->
            head[i] == '\n' || head[i] in ".!?" && head[i + 1].isWhitespace()
        } ?: (MAX_READ downTo MAX_READ / 2).firstOrNull { head[it].isWhitespace() }?.minus(1) ?: (MAX_READ - 1)
        return head.take(end + 1)
    }

    fun find(text: String, today: LocalDate, now: LocalTime? = null): SharedWhen {
        val found = sentences(opening(text)).asSequence().take(MAX_SENTENCES).mapNotNull { said ->
            val sentence = said.replace(shortNameDot, "$1")
            val s = runCatching { QuickEntry.parse(sentence, today) }.getOrNull() ?: return@mapNotNull null
            if (!s.dateSpecified || s.pastDate || s.dateChoices.isNotEmpty() || s.date.isBefore(today) || s.error == QuickEntry.TOO_LONG ||
                s.repeat != RepeatRule.NONE || s.endDate != null) return@mapNotNull null
            val dateWords = s.phrases.filter { it.kind == QuickPhraseKind.DATE }.joinToString(" ") { sentence.substring(it.start, it.end) }
            val choices = s.timeChoices.takeIf { s.ambiguousTime }.orEmpty()
            val time = s.time?.takeIf { s.timed() && choices.isEmpty() }
            val plainDay = monthWord.containsMatchIn(dateWords) || numericWithYear.containsMatchIn(dateWords) ||
                weekdayWord.containsMatchIn(dateWords) && (time != null || choices.isNotEmpty())
            if (!plainDay || until.containsMatchIn(sentence)) return@mapNotNull null
            // Earlier today is gone: a reply's "today at 8:00" isn't ahead.
            if (s.date == today && now != null && time != null && time < now) return@mapNotNull null
            Found(s.date, time, choices)
        }.toList()
        val dates = found.map { it.date }.distinct().sorted()
        val date = dates.singleOrNull() ?: return SharedWhen(null, null, dates)
        val times = found.mapNotNull { it.time }.distinct()
        val choices = found.map { it.choices }.filter { it.isNotEmpty() }.distinct()
        return when {
            times.size == 1 && choices.all { times.single() in it } -> SharedWhen(date, times.single(), dates)
            times.isEmpty() && choices.size == 1 -> SharedWhen(date, null, dates, choices.single())
            else -> SharedWhen(date, null, dates)
        }
    }
}
