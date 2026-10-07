package com.example.itinerary.data

import java.time.LocalDate

/**
 * Paid bills handed to MyBudget (Settings → "Send paid bills to MyBudget"): MyBudget opens a screen that asks for the
 * category and account, and keeps the expense. Each payment goes by its id, so MyBudget adds it once and finds it again
 * when the payment is undone here. MyBudget keeps AUD only. MainActivity sends these; the Repository reports them.
 */
object BudgetLink {
    const val PACKAGE = "com.mybudget.app"
    const val ACTION_ADD = "com.mybudget.app.action.ADD_EXPENSE"
    const val ACTION_UNDONE = "com.mybudget.app.action.PAYMENT_UNDONE"
    const val EXTRA_PAYMENT_ID = "paymentId"
    const val EXTRA_BILL_KEY = "billKey"
    const val EXTRA_PAYEE = "payee"
    // Cents; left out when the bill has no amount (MyBudget then asks for it).
    const val EXTRA_AMOUNT = "amountCents"
    const val EXTRA_CURRENCY = "currency"
    const val EXTRA_DATE = "date"
    const val EXTRA_NOTE = "note"
    // MyBudget's reply: one line Planner shows as a toast ("Added to MyBudget: Utilities −$142.80").
    const val EXTRA_SUMMARY = "summary"
    const val NOTE = "From Planner"
    private const val CURRENCY = "AUD"

    sealed interface Message {
        data class Add(val paymentId: String, val billKey: String, val payee: String, val amount: Long?, val date: LocalDate) : Message
        data class Undone(val paymentId: String) : Message
        data class NotAud(val currency: String) : Message
    }

    // The same for every occurrence of a repeating bill, so MyBudget can suggest the category it had last time.
    fun billKey(item: ItineraryItem): String = item.seriesId?.let { "planner-series-$it" } ?: "planner-bill-${item.id}"

    // A bill without an amount gets no payment entry when it is marked paid (Payments.setPaid): it goes by the bill.
    fun unpricedPaymentId(item: ItineraryItem): String = "planner-paid-${item.id}"

    /** What MyBudget should hear about a bill going from [before] to [after]: payments that now count and ones undone. */
    fun changes(before: ItineraryItem, after: ItineraryItem): List<Message> {
        if (after.category != "Bills") return emptyList()
        val liveBefore = before.payments.filterNot { it.reversed }
        val liveAfter = after.payments.filterNot { it.reversed }
        val added = liveAfter.filter { new -> liveBefore.none { it.id == new.id } }
        val undone = liveBefore.filter { old -> liveAfter.none { it.id == old.id } }.map { it.id }.toMutableList()
        val unpriced = after.billAmountMinor == null && liveAfter.isEmpty() && liveBefore.isEmpty()
        val paidNow = unpriced && after.paid && !before.paid
        if (unpriced && before.paid && !after.paid) undone += unpricedPaymentId(after)
        // MyBudget never had a payment in another currency, so it isn't told about one being undone either.
        if (after.billCurrency != CURRENCY) return if (added.isNotEmpty() || paidNow) listOf(Message.NotAud(after.billCurrency)) else emptyList()
        return undone.map { Message.Undone(it) } +
            added.map { Message.Add(it.id, billKey(after), after.title, it.amount, it.date) } +
            listOfNotNull(if (paidNow) Message.Add(unpricedPaymentId(after), billKey(after), after.title, null, LocalDate.now()) else null)
    }
}
