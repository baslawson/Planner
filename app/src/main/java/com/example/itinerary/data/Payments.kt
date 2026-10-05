package com.example.itinerary.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

data class BillPayment(val id: String = UUID.randomUUID().toString(), val amount: Long,
    val date: LocalDate = LocalDate.now(), val note: String = "", val reversed: Boolean = false,
    val automaticSettlement: Boolean? = false)

class PaymentUpdateException(message: String) : IllegalArgumentException(message)

/** Financial state captured when the editor opens (also present in a recovered draft's initial item). */
data class PaymentState(val paid: Boolean, val payments: List<BillPayment>, val amount: Long?,
    val currency: String, val category: String) {
    companion object {
        fun of(item: ItineraryItem) = PaymentState(item.paid, item.payments, item.billAmountMinor, item.billCurrency, item.category)
    }
}

object Payments {
    const val MAX_ENTRIES = 1000
    const val LIMIT_MESSAGE = "Payment history has reached its 1,000-entry limit. No new payment was recorded."

    fun mergeEditor(edited: ItineraryItem, latest: ItineraryItem, baseline: PaymentState): ItineraryItem {
        val current = PaymentState.of(latest)
        val proposed = PaymentState.of(edited)
        if (current == baseline || proposed == current) return edited
        if (proposed != baseline) throw PaymentUpdateException(
            "Payment details changed while this bill was open. Reopen the bill before changing its payments.")
        return edited.copy(paid = latest.paid, payments = latest.payments,
            billAmountMinor = latest.billAmountMinor, billCurrency = latest.billCurrency, category = latest.category)
    }

    fun total(payments: List<BillPayment>): Long = payments.filterNot { it.reversed }.sumOf { it.amount }
    // Payments that still count toward the balance.
    fun anyLive(payments: List<BillPayment>): Boolean = payments.any { !it.reversed }
    fun validate(payments: List<BillPayment>) {
        if (payments.size > MAX_ENTRIES) throw PaymentUpdateException(LIMIT_MESSAGE)
        require(payments.map { it.id }.toSet().size == payments.size)
        require(payments.all { it.id.isNotBlank() && it.amount in 1..Bills.MAX_MINOR && it.note.length <= 200 })
        require(total(payments) <= Bills.MAX_MINOR)
    }
    fun remaining(amount: Long?, paid: Boolean, payments: List<BillPayment>): Long? =
        amount?.let { if (paid) 0 else (it - total(payments)).coerceAtLeast(0) }

    // The bill editor's summary instead of every payment: what's paid (reversed ones don't count), what's left, how far
    // along (0..1, null without an amount) and the latest payment that still counts (by date; of two on one day, the one
    // recorded later).
    data class Summary(val paid: Long, val remaining: Long?, val progress: Float?, val last: BillPayment?)
    fun summary(amount: Long?, paid: Boolean, payments: List<BillPayment>): Summary {
        val total = total(payments)
        return Summary(total, remaining(amount, paid, payments),
            amount?.takeIf { it > 0 }?.let { if (paid) 1f else (total.toFloat() / it).coerceIn(0f, 1f) },
            newestFirst(payments).firstOrNull { !it.reversed })
    }
    // Newest first by date; of two on one day, the one recorded later first.
    fun newestFirst(payments: List<BillPayment>): List<BillPayment> = payments.withIndex()
        .sortedWith(compareByDescending<IndexedValue<BillPayment>> { it.value.date }.thenByDescending { it.index }).map { it.value }

    fun validate(item: ItineraryItem) {
        validate(item.payments)
        val total = total(item.payments)
        require(total == 0L || item.billAmountMinor != null && total <= item.billAmountMinor)
    }

    /** Ticking "paid" records what is left as a "Marked paid" entry; unticking reverses only that entry, so real
     *  part-payments still count. Payments that would still cover the bill are reversed too: it can't be unpaid with them. */
    fun setPaid(item: ItineraryItem, paid: Boolean): ItineraryItem {
        if (paid == item.paid) return item
        val amount = remaining(item.billAmountMinor, false, item.payments)
        val entries = if (paid && amount != null && amount > 0)
            item.payments + BillPayment(amount = amount, note = MARKED_PAID, automaticSettlement = true)
        else if (!paid) {
            // Only old unclassified records retain the historical marker rule. New manual entries carry false.
            val unmarked = item.payments.map {
                if (it.automaticSettlement == true || it.automaticSettlement == null && it.note == MARKED_PAID)
                    it.copy(reversed = true) else it
            }
            val bill = item.billAmountMinor
            if (bill != null && anyLive(unmarked) && total(unmarked) >= bill) item.payments.map { it.copy(reversed = true) } else unmarked
        } else item.payments
        return item.copy(paid = paid, payments = entries).also(::validate)
    }
    private const val MARKED_PAID = "Marked paid"
    fun encode(payments: List<BillPayment>): String = JSONArray().apply {
        validate(payments)
        payments.forEach { payment -> put(JSONObject().put("id", payment.id).put("amount", payment.amount)
            .put("date", payment.date.toString()).put("note", payment.note).put("reversed", payment.reversed)
            .apply { payment.automaticSettlement?.let { put("automaticSettlement", it) } }) }
    }.toString()
    fun decode(text: String): List<BillPayment> = JSONArray(text).let { a -> List(a.length()) { i ->
        val p = a.getJSONObject(i)
        BillPayment(p.getString("id"), p.getLong("amount"), LocalDate.parse(p.getString("date")),
            // Preserve legacy classification separately from new manual entries; it cannot be recovered reliably.
            p.optString("note"), p.optBoolean("reversed"),
            if (p.has("automaticSettlement") && !p.isNull("automaticSettlement")) p.getBoolean("automaticSettlement") else null)
    } }.also(::validate)
}
