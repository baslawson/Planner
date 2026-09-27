package com.example.itinerary.data

import java.math.BigDecimal
import java.time.YearMonth
import java.text.NumberFormat
import java.util.Currency

object Bills {
    private val titleWhitespace = Regex("[\\s\\p{Z}]+")
    private fun matchingTitle(title: String) = title.trim().replace(titleWhitespace, " ").lowercase(java.util.Locale.ROOT)

    fun duplicates(item: ItineraryItem, events: List<ItineraryItem>,
                   dates: Set<java.time.LocalDate> = setOf(item.date),
                   excludedIds: Set<Long> = setOf(item.id)): List<ItineraryItem> {
        if (item.category != "Bills" || item.billAmountMinor == null) return emptyList()
        val title = matchingTitle(item.title)
        if (title.isEmpty()) return emptyList()
        return events.filter {
            it.id !in excludedIds && it.category == "Bills" && it.date in dates &&
                it.billAmountMinor == item.billAmountMinor && it.billCurrency == item.billCurrency &&
                matchingTitle(it.title) == title
        }.sortedWith(compareBy({ it.date }, { it.id }))
    }

    val currencies = listOf("AUD", "USD", "GBP", "EUR", "NZD", "CAD", "SGD", "IDR")
    const val MAX_MINOR = 99_999_999_999L
    fun parse(text: String): Long? = runCatching {
        require(Regex("[0-9]{1,9}([.,][0-9]{1,2})?").matches(text.trim()))
        BigDecimal(text.trim().replace(',', '.')).movePointRight(2).longValueExact().also { require(it in 0..MAX_MINOR) }
    }.getOrNull()
    fun validate(amount: Long?, currency: String) { require(amount == null || amount in 0..MAX_MINOR); require(currency in currencies) }
    fun input(amount: Long?): String = amount?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty()
    fun format(amount: Long, currency: String): String = format(BigDecimal.valueOf(amount, 2), currency)
    fun format(amount: BigDecimal, code: String): String = "$code " + NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 2; maximumFractionDigits = 2
    }.format(amount)
    data class Summary(val totals: Map<String, BigDecimal>, val withoutAmount: Int, val unpaidCount: Int)
    fun forecast(events: List<PlanEvent>, month: YearMonth): List<PlanEvent> = events.filter {
        it.category == "Bills" && !it.paid && !it.skipped && YearMonth.from(it.date) == month
    }.sortedWith(compareBy({ it.date }, { it.title.lowercase(java.util.Locale.ROOT) }, { it.id }))

    fun summary(events: List<PlanEvent>, month: YearMonth): Summary {
        val bills = forecast(events, month)
        val totals = bills.filter { it.billAmountMinor != null }.groupBy { it.billCurrency }.mapValues { (_, rows) ->
            rows.fold(BigDecimal.ZERO) { sum, row -> sum + BigDecimal.valueOf(Payments.remaining(row.billAmountMinor, row.paid, row.payments)!!, 2) }
        }
        return Summary(totals.toSortedMap(), bills.count { it.billAmountMinor == null }, bills.size)
    }
}
