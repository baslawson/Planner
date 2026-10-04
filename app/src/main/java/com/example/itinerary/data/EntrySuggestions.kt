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
    private val money = Regex("(?i)^(?:(AUD|USD|GBP|EUR|NZD|CAD|SGD|IDR)\\s*)?([£€$])?\\s*([0-9]{1,9}(?:\\.[0-9]{1,2})?|[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?)(?:\\s*(AUD|USD|GBP|EUR|NZD|CAD|SGD|IDR))?$")
    fun parse(text: String): BillSuggestion {
        val lines = text.take(200_000).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        fun values(label: Regex): List<String> = lines.mapIndexedNotNull { i, line ->
            if (label == dueLabel && amountLabel.containsMatchIn(line)) return@mapIndexedNotNull null
            label.find(line)?.let {
            line.substring(it.range.last + 1).trim().ifEmpty { lines.getOrNull(i + 1).orEmpty() }
        } }
        val amountValues = values(amountLabel)
        val dateValues = values(dueLabel)
        val amounts = amountValues.mapNotNull { value -> money.matchEntire(value)?.let { match ->
            val amount = Bills.parse(match.groupValues[3].replace(",", "")) ?: return@let null
            val code = match.groupValues[1].ifEmpty { match.groupValues[4] }.uppercase(Locale.ROOT).ifEmpty {
                when (match.groupValues[2]) { "£" -> "GBP"; "€" -> "EUR"; else -> "" }
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
    // An amount with its currency anywhere in a sentence ("your bill of EUR 84.20", "€84,20", "1,234.50 GBP").
    private val number = "[0-9](?:[0-9.,]*[0-9])?"
    private val looseMoney = Regex("(?i)(?<![A-Za-z])(AUD|USD|GBP|EUR|NZD|CAD|SGD|IDR)\\s?($number)|([£€$])\\s?($number)|($number)\\s?(AUD|USD|GBP|EUR|NZD|CAD|SGD|IDR)(?![A-Za-z])")
    private fun looseAmount(text: String): Long? = when {
        Regex("[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?").matches(text) -> Bills.parse(text.replace(",", ""))
        Regex("[0-9]{1,3}(?:\\.[0-9]{3})+(?:,[0-9]{1,2})?").matches(text) -> Bills.parse(text.replace(".", ""))
        else -> Bills.parse(text)
    }

    /**
     * A bill from an email's wording: the labelled lines [parse] reads, else one amount with a currency written anywhere
     * and [due], the one day the email names ([SharedDates]). Several different amounts are left for the person.
     */
    fun parseMessage(text: String, due: LocalDate?): BillSuggestion {
        val labelled = parse(text)
        val loose = looseMoney.findAll(text.take(200_000)).mapNotNull { m ->
            val g = m.groupValues
            val amount = looseAmount(g[2].ifEmpty { g[4] }.ifEmpty { g[5] }) ?: return@mapNotNull null
            val code = g[1].ifEmpty { g[6] }.uppercase(Locale.ROOT).ifEmpty {
                when (g[3]) { "£" -> "GBP"; "€" -> "EUR"; else -> "" }
            }.ifEmpty { null }
            amount to code
        }.toList()
        val amounts = loose.map { it.first }.distinct()
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
