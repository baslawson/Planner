package com.example.itinerary.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

data class BillPayment(val id: String = UUID.randomUUID().toString(), val amount: Long,
    val date: LocalDate = LocalDate.now(), val note: String = "", val reversed: Boolean = false)

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
    // Payments that still count: unticking "paid" keeps them only as reversed history.
    fun anyLive(payments: List<BillPayment>): Boolean = payments.any { !it.reversed }
    fun validate(payments: List<BillPayment>) {
        if (payments.size > MAX_ENTRIES) throw PaymentUpdateException(LIMIT_MESSAGE)
        require(payments.map { it.id }.toSet().size == payments.size)
        require(payments.all { it.id.isNotBlank() && it.amount in 1..Bills.MAX_MINOR && it.note.length <= 200 })
        require(total(payments) <= Bills.MAX_MINOR)
    }
    fun remaining(amount: Long?, paid: Boolean, payments: List<BillPayment>): Long? =
        amount?.let { if (paid) 0 else (it - total(payments)).coerceAtLeast(0) }

    fun validate(item: ItineraryItem) {
        validate(item.payments)
        val total = total(item.payments)
        require(total == 0L || item.billAmountMinor != null && total <= item.billAmountMinor)
    }

    fun setPaid(item: ItineraryItem, paid: Boolean): ItineraryItem {
        if (paid == item.paid) return item
        val amount = remaining(item.billAmountMinor, false, item.payments)
        val entries = if (paid && amount != null && amount > 0)
            item.payments + BillPayment(amount = amount, note = "Marked paid")
        else if (!paid) item.payments.map { it.copy(reversed = true) } else item.payments
        return item.copy(paid = paid, payments = entries).also(::validate)
    }
    fun encode(payments: List<BillPayment>): String = JSONArray().apply {
        validate(payments)
        payments.forEach { put(JSONObject().put("id", it.id).put("amount", it.amount)
            .put("date", it.date.toString()).put("note", it.note).put("reversed", it.reversed)) }
    }.toString()
    fun decode(text: String): List<BillPayment> = JSONArray(text).let { a -> List(a.length()) { i ->
        val p = a.getJSONObject(i)
        BillPayment(p.getString("id"), p.getLong("amount"), LocalDate.parse(p.getString("date")),
            p.optString("note"), p.optBoolean("reversed"))
    } }.also(::validate)
}
