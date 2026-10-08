package com.example.itinerary.data

import java.time.LocalDate

/**
 * Paid bills handed to MyBudget (Settings → "Send paid bills to MyBudget"): MyBudget opens a screen that asks for the
 * category and account, and keeps the expense. Each payment goes by its id, so MyBudget adds it once and finds it again
 * when the payment is undone here. Each goes with its bill's currency: MyBudget keeps the ones in its budget's currency
 * (MyBudget 0.0.7 and earlier keep AUD only). MainActivity sends these; the Repository reports them.
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
    // Hunt 23 P4: MyBudget 0.0.8 and later also say which currency their budget is in, so bills in another currency (which
    // MyBudget can't keep) aren't opened there at all.
    const val EXTRA_BUDGET_CURRENCY = "budgetCurrency"
    const val NOTE = "From Planner"
    // The only currency of MyBudget 0.0.7 and earlier, which read the `bills` list and plan every bill in it.
    private const val OLD_CURRENCY = "AUD"

    // Upcoming bills (sent quietly, as a broadcast to MyBudget only, when Planner opens and closes with the setting on):
    // MyBudget shows them, plans for them, and replaces its list with each one sent. They're never money there: the
    // expense still comes from marking the bill paid here.
    const val ACTION_UPCOMING = "com.mybudget.app.action.UPCOMING_BILLS"
    const val EXTRA_BILLS = "bills" // JSON array of {id, billKey, payee, due (YYYY-MM-DD), amountCents (optional), paid (optional, Hunt 24 E4: ids of the payments taken off amountCents)}: AUD only
    // The same with every currency, each with its `currency`: MyBudget 0.0.8 and later read this one and keep the bills
    // in their budget's currency. `bills` stays for older MyBudget, which would take any bill in it as AUD.
    const val EXTRA_BILLS_ALL = "billsAll"
    private const val DAYS_BACK = 31L
    private const val DAYS_AHEAD = 62L
    const val MAX_UPCOMING = 200

    // Hunt 24 E4: [paid], the ids of the bill's payments that count (the paymentId each went to MyBudget with, newest last, at
    // most [MAX_PAID_IDS]), so MyBudget doesn't take a part payment off again that it has planned for already ([amount] is
    // what is left) while that payment's ADD_EXPENSE still waits in the outbox.
    data class Upcoming(val id: String, val billKey: String, val payee: String, val due: LocalDate, val amount: Long?, val currency: String = OLD_CURRENCY,
                        val paid: List<String> = emptyList())
    const val MAX_PAID_IDS = 20

    /** The bills older MyBudget can read (the `bills` list). */
    fun forOldBudget(bills: List<Upcoming>): List<Upcoming> = bills.filter { it.currency == OLD_CURRENCY }

    /** Unpaid bills from a month back (overdue) to two months ahead, by date: what's left to pay, or no amount. */
    fun upcoming(items: List<ItineraryItem>, today: LocalDate): List<Upcoming> = items.asSequence()
        .filter { it.category == "Bills" && !it.skipped && it.date in today.minusDays(DAYS_BACK)..today.plusDays(DAYS_AHEAD) }
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
        .map { (item, left) -> Upcoming(upcomingId(item), billKey(item), item.title.take(80), item.date, left, item.billCurrency,
            item.payments.filterNot { it.reversed }.map { it.id }.takeLast(MAX_PAID_IDS)) }
        .toList()

    fun upcomingId(item: ItineraryItem): String = "planner-bill-${item.id}$installId"

    /**
     * Hunt 23: added to the ids made from a bill's database id, which start again from 1 when Planner is installed afresh
     * (or its data cleared): without it a new bill could be taken for an old one in MyBudget ("Already in MyBudget").
     * Empty for an install that had data before this came in, so the ids MyBudget already holds still match.
     */
    @Volatile var installId = ""
        private set

    /** Called before the database first opens; [freshData]: it doesn't exist yet. */
    fun useInstallId(context: android.content.Context, freshData: Boolean) {
        val prefs = context.getSharedPreferences("budget_link", android.content.Context.MODE_PRIVATE)
        installId = prefs.getString("installId", null) ?: (if (freshData) "-" + java.util.UUID.randomUUID().toString().take(8) else "")
            .also { prefs.edit().putString("installId", it).commit() }
    }

    /**
     * Hunt 24 E5: a restored backup brings back its bills' database ids, so it brings back the install id they went to
     * MyBudget with too ("planner-paid-5-…"): otherwise MyBudget can't find them to undo, and paying again adds a second
     * expense. A backup without one ([raw] null) is from before Hunt 23, when every install had "".
     */
    fun backupInstallId(raw: String?): String = raw?.takeIf { it.isEmpty() || Regex("-[A-Za-z0-9]{1,36}").matches(it) } ?: ""

    /** Hunt 24 E5: on restore, both here and on disk (read again when Planner next starts). */
    fun adoptInstallId(context: android.content.Context, id: String) {
        context.getSharedPreferences("budget_link", android.content.Context.MODE_PRIVATE).edit().putString("installId", id).commit()
        installId = id
    }

    // Messages waiting for MyBudget are kept on disk (BudgetOutbox), one line each: fields separated by tabs, with
    // backslash, tab and line breaks escaped. null for a line this version can't read (it's dropped).
    fun encode(m: Message): String = when (m) {
        is Message.Add -> listOf("A", m.paymentId, m.billKey, m.payee, m.amount?.toString() ?: "", m.date.toString(), m.upcomingId, m.currency)
        is Message.Undone -> listOf("U", m.paymentId, m.currency)
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
            // Lines written before currencies were sent are AUD (only AUD bills went then).
            "A" -> Message.Add(f[1], f[2], f[3], f[4].takeIf { it.isNotEmpty() }?.toLong(), LocalDate.parse(f[5]), f.getOrElse(6) { "" }, f.getOrElse(7) { OLD_CURRENCY })
            "U" -> Message.Undone(f[1], f.getOrElse(2) { OLD_CURRENCY })
            else -> null // also "N" (another currency, not sent) from before: nothing to send now
        }
    }.getOrNull()

    sealed interface Message {
        data class Add(val paymentId: String, val billKey: String, val payee: String, val amount: Long?, val date: LocalDate, val upcomingId: String = "", val currency: String = OLD_CURRENCY) : Message
        data class Undone(val paymentId: String, val currency: String = OLD_CURRENCY) : Message
    }

    // The same for every occurrence of a repeating bill, so MyBudget can suggest the category it had last time.
    fun billKey(item: ItineraryItem): String = item.seriesId?.let { "planner-series-$it" } ?: "planner-bill-${item.id}$installId"

    // A bill without an amount gets no payment entry when it is marked paid (Payments.setPaid): it goes by the bill.
    fun unpricedPaymentId(item: ItineraryItem): String = "planner-paid-${item.id}$installId"

    // Hunt 23 P4 / Hunt 24 E3: an Add in a currency other than MyBudget's budget ([budgetCurrency]), which it would only refuse.
    // Never an Undone: MyBudget finds an expense by its id whatever the currency, and answers at once for one it never added.
    fun refusedLocally(message: Message, budgetCurrency: String?): Boolean =
        message is Message.Add && budgetCurrency != null && !message.currency.equals(budgetCurrency, ignoreCase = true)

    /** What MyBudget should hear about a bill going from [before] to [after]: payments that now count and ones undone. */
    fun changes(before: ItineraryItem, after: ItineraryItem): List<Message> {
        if (after.category != "Bills") return emptyList()
        val liveBefore = before.payments.filterNot { it.reversed }
        val liveAfter = after.payments.filterNot { it.reversed }
        val added = liveAfter.filter { new -> liveBefore.none { it.id == new.id } }
        val undone = liveBefore.filter { old -> liveAfter.none { it.id == old.id } }.map { it.id }.toMutableList()
        val unpriced = after.billAmountMinor == null && liveAfter.isEmpty() && liveBefore.isEmpty()
        val paidNow = unpriced && after.paid && !before.paid
        // Hunt 22 L1: paid without an amount (MyBudget has it by the bill) and no longer paid that way, whatever its amount
        // now: given one later and then unpaid, MyBudget was never told, and paying again made a second expense there.
        if (before.paid && liveBefore.isEmpty() && !(after.paid && liveAfter.isEmpty())) undone += unpricedPaymentId(after)
        // Every currency goes: MyBudget adds only bills in its budget's currency and says so for others. An undone payment
        // it never added finds nothing there and asks nothing.
        val currency = after.billCurrency
        return undone.map { Message.Undone(it, currency) } +
            added.map { Message.Add(it.id, billKey(after), after.title, it.amount, it.date, upcomingId(after), currency) } +
            listOfNotNull(if (paidNow) Message.Add(unpricedPaymentId(after), billKey(after), after.title, null, LocalDate.now(), upcomingId(after), currency) else null)
    }
}
