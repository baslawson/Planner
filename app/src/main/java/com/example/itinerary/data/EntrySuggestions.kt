package com.example.itinerary.data

import java.time.LocalDate
import java.time.format.ResolverStyle
import java.util.Locale

data class BillSuggestion(val title: String?, val date: LocalDate?, val amount: Long?, val currency: String?,
    val warnings: Map<String, String> = emptyMap())

/** Conservative extraction from labelled lines. Ambiguous numeric dates and amounts are left for the user. */
object BillSuggestions {
    private val amountLabel = Regex("(?i)\\b(?:total\\s+amount\\s+due|amount\\s+due|balance\\s+due|total\\s+due|grand\\s+total|total)\\b\\s*[:=-]?\\s*")
    private val dueLabel = Regex("(?i)\\b(?:payment\\s+due(?:\\s+date)?|due\\s+date|pay\\s+by|due\\s+by|due)\\b\\s*[:=-]?\\s*")
    private val money = Regex("(?i)^(?:(AUD|USD|GBP|EUR|NZD|CAD|SGD|IDR)\\s*)?([£€$])?\\s*([0-9]{1,9}(?:\\.[0-9]{1,2})?|[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?)(?:\\s*(?:(AUD|USD|GBP|EUR|NZD|CAD|SGD|IDR)|([£€$])))?$")
    private fun lines(text: String) = text.take(200_000).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    private fun values(lines: List<String>, label: Regex): List<String> = lines.mapIndexedNotNull { i, line ->
        if (label == dueLabel && amountLabel.containsMatchIn(line)) return@mapIndexedNotNull null
        label.find(line)?.let {
            line.substring(it.range.last + 1).trim().ifEmpty { lines.getOrNull(i + 1).orEmpty() }
        }
    }
    fun parse(text: String): BillSuggestion {
        val lines = lines(text)
        val amountValues = values(lines, amountLabel)
        val dateValues = values(lines, dueLabel)
        val amounts = amountValues.mapNotNull { value -> money.matchEntire(value)?.let { match ->
            val amount = Bills.parse(match.groupValues[3].replace(",", "")) ?: return@let null
            val code = match.groupValues[1].ifEmpty { match.groupValues[4] }.uppercase(Locale.ROOT).ifEmpty {
                when (match.groupValues[2].ifEmpty { match.groupValues[5] }) { "£" -> "GBP"; "€" -> "EUR"; else -> "" }
            }.ifEmpty { null }
            amount to code
        } }.distinct()
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
        }
        return BillSuggestion(title, dates.singleOrNull(), amounts.singleOrNull()?.first, amounts.singleOrNull()?.second, warnings)
    }
    // An amount with its currency anywhere in a sentence ("your bill of EUR 84.20", "€84,20", "1,234.50 GBP", "84,20 €",
    // SQX-8; not "for 2 $40": a sign before a number is that number's). Tried only from the start of a run of digits, dots
    // and commas: from every digit of a long run ("12.50,13.20,…") it read the rest of the run again each time (SQ9-10).
    // Thousands may be set apart by a space ("2 450,00 €", SQX-8), at most three times, so that stays short too.
    private val number = "(?<![0-9.,])(?:[0-9]{1,3}(?:[ \\u00A0\\u202F][0-9]{3}){1,3}(?:[.,][0-9]{1,2})?(?![0-9.,])|[0-9](?:[0-9.,]*[0-9])?)"
    private const val codes = "AUD|USD|GBP|EUR|NZD|CAD|SGD|IDR"
    private val looseMoney = Regex("(?i)(?<![A-Za-z])($codes)\\s?($number)|([£€$])\\s?($number)|($number)\\s?(?:($codes)(?![A-Za-z])|([£€$])(?!\\s?[0-9]))")
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
        val loose = looseMoney.findAll(text.take(200_000)).mapNotNull { m ->
            val g = m.groupValues
            val amount = looseAmount(g[2].ifEmpty { g[4] }.ifEmpty { g[5] }) ?: return@mapNotNull null
            val code = g[1].ifEmpty { g[6] }.uppercase(Locale.ROOT).ifEmpty {
                when (g[3].ifEmpty { g[7] }) { "£" -> "GBP"; "€" -> "EUR"; else -> "" }
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
