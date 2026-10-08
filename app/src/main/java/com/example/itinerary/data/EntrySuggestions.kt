package com.example.itinerary.data

import java.time.LocalDate
import java.time.format.ResolverStyle
import java.util.Locale

data class BillSuggestion(val title: String?, val date: LocalDate?, val amount: Long?, val currency: String?,
    val warnings: Map<String, String> = emptyMap(), val bpayBiller: String? = null, val bpayReference: String? = null) {
    // Written as before when there's no BPAY (ShareQuickCasesTest compares these lines with what earlier versions gave).
    override fun toString() = "BillSuggestion(title=$title, date=$date, amount=$amount, currency=$currency, warnings=$warnings" +
        (if (bpayBiller != null || bpayReference != null) ", bpayBiller=$bpayBiller, bpayReference=$bpayReference" else "") + ")"
}

/** Conservative extraction from labelled lines. Ambiguous numeric dates and amounts are left for the user. */
object BillSuggestions {
    private val amountLabel = Regex("(?i)\\b(?:total\\s+amount\\s+due|amount\\s+due|balance\\s+due|total\\s+due|grand\\s+total|total)\\b\\s*[:=-]?\\s*")
    private val dueLabel = Regex("(?i)\\b(?:payment\\s+due(?:\\s+date)?|due\\s+date|pay\\s+by|due\\s+by|due)\\b\\s*[:=-]?\\s*")
    // R18-Q5: a dollar with its country in front, "A$", "NZ$", "US$", "C$", "S$" (see dollarCode).
    private const val dollarPrefixes = "(?-i:A|AU|NZ|US|CA|C|SG|S)(?=\\$)"
    private fun dollarCode(prefix: String) = when (prefix) { "A", "AU" -> "AUD"; "NZ" -> "NZD"; "US" -> "USD"; "C", "CA" -> "CAD"; else -> "SGD" }
    private val money = Regex("(?i)^(?:(AUD|USD|GBP|EUR|NZD|CAD|SGD|IDR)\\s*|($dollarPrefixes))?([£€$])?\\s*([0-9]{1,9}(?:\\.[0-9]{1,2})?|[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?)(?:\\s*(?:(AUD|USD|GBP|EUR|NZD|CAD|SGD|IDR)|([£€$])))?$")
    private fun lines(text: String) = text.take(200_000).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    private fun values(lines: List<String>, label: Regex): List<String> = lines.mapIndexedNotNull { i, line ->
        if (label == dueLabel && amountLabel.containsMatchIn(line)) return@mapIndexedNotNull null
        label.find(line)?.let {
            line.substring(it.range.last + 1).trim().ifEmpty { lines.getOrNull(i + 1).orEmpty() }
        }
    }
    // BPAY (Australia's bill payment system): its box names a biller code and a customer reference number (CRN). A
    // bill with one is an Australian bill, so a plain "$" on it is AUD.
    private val bpayMark = Regex("(?i)\\bBPAY\\b|\\bbiller\\s*code\\b")
    private val billerLabel = Regex("(?i)\\bbiller\\s*(?:code|no\\.?|number)?\\b\\s*[:#=-]?\\s*")
    private val referenceLabel = Regex("(?i)\\b(?:customer\\s+reference(?:\\s+(?:no\\.?|number))?|CRN|ref(?:erence)?(?:\\s*(?:no\\.?|number))?)\\b\\.?\\s*[:#=-]?\\s*")
    // After the label: "(CRN):" or ":" may come before the number; the number stops at the first other character.
    private fun digits(value: String, sizes: IntRange): String? =
        Regex("^[0-9][0-9 ]*[0-9]|^[0-9]").find(value.trim().replace(Regex("^\\([^)]{1,12}\\)\\s*[:#=-]?\\s*"), ""))
            ?.value?.trim()?.replace(" ", "")?.takeIf { it.length in sizes }
    /** The biller code (2–10 digits) and reference (2–20 digits, spaces dropped) from a BPAY box; null when unclear. */
    private fun bpay(lines: List<String>): Pair<String?, String?> {
        if (lines.none { bpayMark.containsMatchIn(it) }) return null to null
        val billers = lines.indices.mapNotNull { i ->
            billerLabel.find(lines[i])?.let { m -> digits(lines[i].substring(m.range.last + 1).ifBlank { lines.getOrNull(i + 1).orEmpty() }, 2..10)?.let { i to it } }
        }
        val biller = billers.map { it.second }.distinct().singleOrNull()
        // The reference: only near the biller code (an invoice's own "Ref" elsewhere isn't the BPAY one).
        val near = billers.map { it.first }.flatMap { (it - 3)..(it + 3) }.toSet()
        val reference = lines.indices.filter { it in near }.mapNotNull { i ->
            referenceLabel.find(lines[i])?.let { m -> digits(lines[i].substring(m.range.last + 1).ifBlank { lines.getOrNull(i + 1).orEmpty() }, 2..20) }
        }.distinct().singleOrNull()
        return biller to reference
    }
    fun parse(text: String): BillSuggestion {
        val lines = lines(text)
        val amountValues = values(lines, amountLabel)
        val dateValues = values(lines, dueLabel)
        val australian = lines.any { bpayMark.containsMatchIn(it) }
        val amounts = amountValues.mapNotNull { value -> money.matchEntire(value)?.let { match ->
            val g = match.groupValues
            val amount = Bills.parse(g[4].replace(",", "")) ?: return@let null
            val code = g[1].ifEmpty { g[5] }.ifEmpty { g[2].takeIf { it.isNotEmpty() }?.let(::dollarCode).orEmpty() }.uppercase(Locale.ROOT).ifEmpty {
                when (g[3].ifEmpty { g[6] }) { "£" -> "GBP"; "€" -> "EUR"; "$" -> if (australian) "AUD" else ""; else -> "" }
            }.ifEmpty { null }
            amount to code
        } }.distinct()
        val (biller, reference) = bpay(lines)
        val dates = dateValues.mapNotNull(::parseDate).distinct()
        val title = lines.take(5).firstOrNull { line ->
            line.length in 3..80 && line.any(Char::isLetter) &&
                !Regex("(?i)invoice|tax invoice|statement|bill|receipt|abn|account|date|total|amount|due|www\\.|@|https?://").containsMatchIn(line) &&
                line.count(Char::isDigit) < 3
        }
        val warnings = buildMap {
            put("title", if (title == null) "No clear bill name found. Enter it manually." else "Suggested from the document heading; confirm the bill name.")
            if (amounts.size > 1) put("amount", "Several different totals were found. Choose the correct amount from the document.")
            else if (amounts.isEmpty()) put("amount", "No clear total found. Check the document and enter the amount.")
            else if (amountValues.any { money.matchEntire(it) == null }) put("amount", "Some amount lines could not be read. Verify this total.")
            if (dates.size > 1) put("date", "Several due dates were found. Choose the correct one.")
            else if (dates.isEmpty()) put("date", if (dateValues.isEmpty()) "No due date found. Enter it manually."
                else "The due date is unclear or ambiguous. Check the day and month.")
            else if (dateValues.any { parseDate(it) == null }) put("date", "Some date lines are ambiguous. Verify this due date.")
            if (amounts.singleOrNull()?.second == null) put("currency", "Currency is uncertain; confirm the currency code. A $ symbol alone does not identify it.")
            else if (australian && amountValues.none { Regex("(?i)AUD|A\\$|AU\\$").containsMatchIn(it) } && amounts.singleOrNull()?.second == "AUD")
                put("currency", "BPAY found: an Australian bill, so the $ is taken as AUD.")
            if (australian && (biller == null || reference == null))
                put("bpay", "BPAY found, but its biller code or reference couldn't be read clearly. Enter them from the document.")
            else if (biller != null) put("bpay", "Read from the BPAY box; check both numbers against the document.")
        }
        return BillSuggestion(title, dates.singleOrNull(), amounts.singleOrNull()?.first, amounts.singleOrNull()?.second, warnings, biller, reference)
    }
    // An amount with its currency anywhere in a sentence ("your bill of EUR 84.20", "€84,20", "1,234.50 GBP", "84,20 €",
    // SQX-8; not "for 2 $40": a sign before a number is that number's). Tried only from the start of a run of digits, dots
    // and commas: from every digit of a long run ("12.50,13.20,…") it read the rest of the run again each time (SQ9-10).
    // Thousands may be set apart by a space ("2 450,00 €", SQX-8), at most three times, so that stays short too.
    private val number = "(?<![0-9.,])(?:[0-9]{1,3}(?:[ \\u00A0\\u202F][0-9]{3}){1,3}(?:[.,][0-9]{1,2})?(?![0-9.,])|[0-9](?:[0-9.,]*[0-9])?)"
    private const val codes = "AUD|USD|GBP|EUR|NZD|CAD|SGD|IDR"
    // R18-Q5: also "AUD$120", "NZ$ 89.50": a code or a country's dollar, then a $ (group 2 is the country).
    private val looseMoney = Regex("(?i)(?<![A-Za-z])(?:($codes)|($dollarPrefixes))\\s?\\$?\\s?($number)|([£€$])\\s?($number)|($number)\\s?(?:($codes)(?![A-Za-z])|([£€$])(?!\\s?[0-9]))")
    // R18-Q5: money coming back is no bill: "-$45", "−$45", "($45.00)", "$45.00 CR", "$45 credit", or a sentence about a
    // refund, a credit, cashback or a reversal ("Credit of $45 applied"). Not a credit card or limit.
    private val creditAfter = Regex("^\\s?(?:CR|Cr|[Cc]redit)\\b")
    private val moneyBack = Regex("(?i)\\b(?:refund(?:s|ed)?|credit(?:s|ed)?(?!\\s+(?:card|limit|union|score|rating))|cash\\s?back|reversal|reversed)\\b")
    private val sentenceStop = Regex("[.!?](?=\\s)|\\n")
    // Things counted under a "total": "Items in total: 3" (SQ8-8).
    private val countWord = Regex("(?i)\\b(?:items?|articles?|pieces?|units?|products?|parcels?|packages?|tickets?|guests?|people|persons?|qty|quantity)\\b")
    private val commaThousands = Regex("[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?")
    private val dotThousands = Regex("[0-9]{1,3}(?:\\.[0-9]{3})+(?:,[0-9]{1,2})?")
    private val thousandsSpace = Regex("[ \\u00A0\\u202F]")
    private fun looseAmount(written: String): Long? = written.replace(thousandsSpace, "").let { text -> when {
        commaThousands.matches(text) -> Bills.parse(text.replace(",", ""))
        dotThousands.matches(text) -> Bills.parse(text.replace(".", ""))
        else -> Bills.parse(text)
    } }

    /**
     * A bill from an email's wording: the labelled lines [parse] reads, else one amount with a currency written anywhere
     * and [due], the one day the email names ([SharedDates]). Several different amounts are left for the person.
     */
    fun parseMessage(text: String, due: LocalDate?, today: LocalDate? = null): BillSuggestion {
        val read = text.take(200_000)
        val loose = looseMoney.findAll(read).mapNotNull { m ->
            val g = m.groupValues
            // R18-Q5: not money coming back (see moneyBack), in the sentence this amount is in.
            val signBefore = read.getOrNull(m.range.first - 1)
            if (signBefore == '-' || signBefore == '−' || signBefore == '(' && read.getOrNull(m.range.last + 1) == ')' ||
                creditAfter.containsMatchIn(read.substring(m.range.last + 1, minOf(read.length, m.range.last + 10)))) return@mapNotNull null
            // Looked at within 300 characters either side, so a long text without full stops stays quick (SQ9-10).
            val before = read.substring(maxOf(0, m.range.first - 300), m.range.first).let { b -> b.substring(sentenceStop.findAll(b).lastOrNull()?.range?.last?.plus(1) ?: 0) }
            val after = read.substring(m.range.last + 1, minOf(read.length, m.range.last + 301)).let { a -> a.substring(0, sentenceStop.find(a)?.range?.first ?: a.length) }
            if (moneyBack.containsMatchIn(before + m.value + after)) return@mapNotNull null
            val amount = looseAmount(g[3].ifEmpty { g[5] }.ifEmpty { g[6] }) ?: return@mapNotNull null
            val code = g[1].ifEmpty { g[7] }.ifEmpty { g[2].takeIf { it.isNotEmpty() }?.let(::dollarCode).orEmpty() }.uppercase(Locale.ROOT).ifEmpty {
                when (g[4].ifEmpty { g[8] }) { "£" -> "GBP"; "€" -> "EUR"; else -> "" }
            }.ifEmpty { null }
            amount to code
        }.toList()
        val amounts = loose.map { it.first }.distinct()
        val labelled = parse(text).let { l ->
            // A labelled count without a currency ("Items in total: 3") gives way to an amount with one (SH-6): a whole
            // number written without cents, below every amount with a currency. A total without a currency ("Total:
            // 120.00", "Total: 120") stays, whatever fee or earlier amount the text also names (SQ-1). A labelled due date
            // already past isn't this bill's next one (SH-7). Only under a plain "total", with a word for things counted on
            // its line or below 10: "Amount due: 85" or "Total: 85" beside a credit limit or last balance is the amount (SQ8-8).
            val count = l.currency == null && amounts.isNotEmpty() && l.amount != null && amounts.all { it > l.amount } &&
                lines(text).let { all -> all.indices.any { i ->
                    val label = amountLabel.find(all[i]) ?: return@any false
                    val value = all[i].substring(label.range.last + 1).trim().ifEmpty { all.getOrNull(i + 1).orEmpty() }
                    label.value.trim().trimEnd(':', '=', '-').trim().equals("total", ignoreCase = true) &&
                        value.matches(Regex("[0-9]{1,9}")) && Bills.parse(value) == l.amount &&
                        (l.amount < 1_000 || countWord.containsMatchIn(all[i]))
                } }
            l.copy(amount = l.amount.takeUnless { count }, date = l.date?.takeUnless { today != null && it.isBefore(today) })
        }
        val amount = labelled.amount ?: amounts.singleOrNull()
        val currency = if (labelled.amount != null) labelled.currency
            else loose.filter { it.first == amount }.mapNotNull { it.second }.distinct().singleOrNull()
        return BillSuggestion(null, labelled.date ?: due, amount, currency, buildMap {
            if (amount == null && amounts.size > 1) put("amount", "Several amounts found")
        })
    }

    fun parseDate(text: String): LocalDate? {
        val value = text.trim()
        runCatching { LocalDate.parse(value) }.getOrNull()?.let { return it }
        for (pattern in listOf("d MMM uuuu", "d MMMM uuuu", "MMM d, uuuu", "MMMM d, uuuu")) {
            runCatching { LocalDate.parse(value, java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive()
                .appendPattern(pattern).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)) }.getOrNull()?.let { return it }
        }
        val m = Regex("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})").matchEntire(value) ?: return null
        val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); val y = m.groupValues[3].toInt()
        return runCatching {
            when { a > 12 -> LocalDate.of(y, b, a); b > 12 -> LocalDate.of(y, a, b); a == b -> LocalDate.of(y, b, a); else -> null }
        }.getOrNull()
    }
}
