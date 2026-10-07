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
    // The paid bill's entry in the upcoming list (Upcoming.id), so MyBudget drops it from there at once.
    const val EXTRA_UPCOMING_ID = "upcomingId"
    // MyBudget's reply: one line Planner shows as a toast ("Added to MyBudget: Utilities −$142.80").
    const val EXTRA_SUMMARY = "summary"
    const val NOTE = "From Planner"
    private const val CURRENCY = "AUD"

    // Upcoming bills (sent quietly, as a broadcast to MyBudget only, when Planner opens and closes with the setting on):
    // MyBudget shows them, plans for them, and replaces its list with each one sent. They're never money there: the
    // expense still comes from marking the bill paid here.
    const val ACTION_UPCOMING = "com.mybudget.app.action.UPCOMING_BILLS"
    const val EXTRA_BILLS = "bills" // JSON array of {id, billKey, payee, due (YYYY-MM-DD), amountCents (optional)}
    private const val DAYS_BACK = 31L
    private const val DAYS_AHEAD = 62L
    const val MAX_UPCOMING = 200

    data class Upcoming(val id: String, val billKey: String, val payee: String, val due: LocalDate, val amount: Long?)

    /** Unpaid AUD bills from a month back (overdue) to two months ahead, by date: what's left to pay, or no amount. */
    fun upcoming(items: List<ItineraryItem>, today: LocalDate): List<Upcoming> = items.asSequence()
        .filter { it.category == "Bills" && !it.skipped && it.billCurrency == CURRENCY && it.date in today.minusDays(DAYS_BACK)..today.plusDays(DAYS_AHEAD) }
        .mapNotNull { item ->
            val left = Payments.remaining(item.billAmountMinor, item.paid, item.payments)
            when {
                item.billAmountMinor == null -> if (item.paid) null else item to null
                left == null || left <= 0L -> null
                else -> item to left
            }
        }
        .sortedWith(compareBy({ it.first.date }, { it.first.id }))
        .take(MAX_UPCOMING)
        .map { (item, left) -> Upcoming(upcomingId(item), billKey(item), item.title.take(80), item.date, left) }
        .toList()

    fun upcomingId(item: ItineraryItem): String = "planner-bill-${item.id}"

    // Messages waiting for MyBudget are kept on disk (BudgetOutbox), one line each: fields separated by tabs, with
    // backslash, tab and line breaks escaped. null for a line this version can't read (it's dropped).
    fun encode(m: Message): String = when (m) {
        is Message.Add -> listOf("A", m.paymentId, m.billKey, m.payee, m.amount?.toString() ?: "", m.date.toString(), m.upcomingId)
        is Message.Undone -> listOf("U", m.paymentId)
        is Message.NotAud -> listOf("N", m.currency)
    }.joinToString("\t") { it.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r") }

    fun decode(line: String): Message? = runCatching {
        val f = mutableListOf<String>(); val cell = StringBuilder(); var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && i + 1 < line.length -> { cell.append(when (line[i + 1]) { 't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'; else -> line[i + 1] }); i++ }
                c == '\t' -> { f += cell.toString(); cell.clear() }
                else -> cell.append(c)
            }
            i++
        }
        f += cell.toString()
        when (f[0]) {
            "A" -> Message.Add(f[1], f[2], f[3], f[4].takeIf { it.isNotEmpty() }?.toLong(), LocalDate.parse(f[5]), f.getOrElse(6) { "" })
            "U" -> Message.Undone(f[1])
            "N" -> Message.NotAud(f[1])
            else -> null
        }
    }.getOrNull()

    sealed interface Message {
        data class Add(val paymentId: String, val billKey: String, val payee: String, val amount: Long?, val date: LocalDate, val upcomingId: String = "") : Message
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
            added.map { Message.Add(it.id, billKey(after), after.title, it.amount, it.date, upcomingId(after)) } +
            listOfNotNull(if (paidNow) Message.Add(unpricedPaymentId(after), billKey(after), after.title, null, LocalDate.now(), upcomingId(after)) else null)
    }
}
