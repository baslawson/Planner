package com.example.itinerary.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.example.itinerary.data.BudgetLink

// Needs the <queries> entry for MyBudget in the manifest: Android 11 and later hide other apps without it.
fun budgetLinkInstalled(context: Context): Boolean =
    Intent(BudgetLink.ACTION_ADD).setPackage(BudgetLink.PACKAGE).resolveActivity(context.packageManager) != null

fun budgetIntent(message: BudgetLink.Message): Intent = when (message) {
    is BudgetLink.Message.Add -> Intent(BudgetLink.ACTION_ADD).setPackage(BudgetLink.PACKAGE)
        .putExtra(BudgetLink.EXTRA_PAYMENT_ID, message.paymentId).putExtra(BudgetLink.EXTRA_BILL_KEY, message.billKey)
        .putExtra(BudgetLink.EXTRA_PAYEE, message.payee).putExtra(BudgetLink.EXTRA_CURRENCY, message.currency)
        .putExtra(BudgetLink.EXTRA_DATE, message.date.toString()).putExtra(BudgetLink.EXTRA_NOTE, BudgetLink.NOTE)
        .putExtra(BudgetLink.EXTRA_UPCOMING_ID, message.upcomingId)
        .apply { message.amount?.let { putExtra(BudgetLink.EXTRA_AMOUNT, it) } }
    is BudgetLink.Message.Undone -> Intent(BudgetLink.ACTION_UNDONE).setPackage(BudgetLink.PACKAGE)
        .putExtra(BudgetLink.EXTRA_PAYMENT_ID, message.paymentId)
}

/**
 * Upcoming bills for MyBudget (BudgetLink.upcoming), as a broadcast to MyBudget's package only. On Android 14 and later
 * Planner shares its identity with it, so MyBudget can check the list came from Planner.
 */
fun sendUpcomingBills(context: Context, bills: List<BudgetLink.Upcoming>) {
    if (!budgetLinkInstalled(context)) return
    fun json(list: List<BudgetLink.Upcoming>, withCurrency: Boolean) = org.json.JSONArray().apply {
        list.forEach { b ->
            put(org.json.JSONObject().put("id", b.id).put("billKey", b.billKey).put("payee", b.payee).put("due", b.due.toString())
                .apply { b.amount?.let { put("amountCents", it) }; if (withCurrency) put("currency", b.currency) }
                // Hunt 24 E4: the payments already taken off amountCents, which MyBudget mustn't take off again.
                .apply { if (b.paid.isNotEmpty()) put("paid", org.json.JSONArray(b.paid)) })
        }
    }.toString()
    // Also to a MyBudget that was force-stopped or never opened yet: Android skips such apps unless asked not to.
    val intent = Intent(BudgetLink.ACTION_UPCOMING).setPackage(BudgetLink.PACKAGE)
        .putExtra(BudgetLink.EXTRA_BILLS, json(BudgetLink.forOldBudget(bills), withCurrency = false))
        .putExtra(BudgetLink.EXTRA_BILLS_ALL, json(bills, withCurrency = true))
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
    if (android.os.Build.VERSION.SDK_INT >= 34)
        context.sendBroadcast(intent, null, android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
    else context.sendBroadcast(intent)
}

/**
 * MainActivity's messages for MyBudget, from [outbox], one screen at a time: the next opens when MyBudget answers the
 * last, and only then does a message leave the outbox. If Android ends Planner while MyBudget is open, the message is
 * sent again next time; MyBudget adds a payment once, so nothing is doubled.
 */
class BudgetLinkSender(private val activity: Activity, private val outbox: com.example.itinerary.data.BudgetOutbox) {
    private val queue = ArrayDeque<BudgetLink.Message>()
    private var waiting: BudgetLink.Message? = null
    lateinit var launcher: ActivityResultLauncher<Intent>

    /**
     * Hunt 23 P5: called from MainActivity.onCreate with what [saved] kept. Recreated (or after Android ended Planner), the
     * message MyBudget has open is still waited for, so its answer is matched to it and it isn't opened a second time.
     * Started afresh, nothing is open any more: what was in flight goes again (MyBudget adds a payment once).
     */
    fun restore(saved: String?) {
        val message = saved?.let { BudgetLink.decode(it) }?.takeIf { it in outbox.pending() }
        waiting = message; outbox.inFlight = message
    }

    /** What [restore] needs after a recreation. */
    fun saved(): String? = waiting?.let { BudgetLink.encode(it) }

    /** Sends what's waiting in the outbox (when Planner starts, and when a bill is paid or unpaid). */
    fun resume() = next()

    fun answered(resultCode: Int, data: Intent?) {
        val sent = waiting ?: return
        waiting = null; outbox.inFlight = null
        outbox.remove(sent)
        data?.getStringExtra(BudgetLink.EXTRA_BUDGET_CURRENCY)?.trim()?.takeIf { it.length == 3 }?.let { outbox.budgetCurrency = it }
        val summary = data?.getStringExtra(BudgetLink.EXTRA_SUMMARY)?.take(160)?.takeIf { it.isNotBlank() }
        when {
            resultCode == Activity.RESULT_OK && summary != null -> toast(summary)
            sent is BudgetLink.Message.Add && resultCode != Activity.RESULT_OK -> toast("Not added to MyBudget. The bill stays paid.")
            // Hunt 24 E7: dismissed (or MyBudget couldn't save): the expense is still there, which the user should know.
            sent is BudgetLink.Message.Undone && resultCode != Activity.RESULT_OK -> toast("Not removed from MyBudget.")
        }
        next()
    }

    private fun next() {
        if (waiting != null) return
        if (queue.isEmpty()) queue += outbox.pending() // what's left: answered ones have gone from the outbox
        val message = queue.removeFirstOrNull() ?: return
        // L3: taken back meanwhile (paid and undone before it went): it has left the outbox.
        if (message !in outbox.pending()) return next()
        // Hunt 23 P4: MyBudget said its budget is in another currency: it would only refuse this one, so it isn't opened.
        // Hunt 24 E3: an Add only. An undo goes whatever its currency: MyBudget finds the expense by its id (one added before
        // its budget's currency changed, say), and one it never added it answers at once without asking.
        val budgetCurrency = outbox.budgetCurrency
        if (message is BudgetLink.Message.Add && BudgetLink.refusedLocally(message, budgetCurrency)) {
            outbox.remove(message)
            toast("Not sent to MyBudget: this bill is in ${message.currency}, but your budget is in $budgetCurrency.")
            return next()
        }
        val intent = budgetIntent(message)
        // Not reachable: nothing waits for a MyBudget that isn't there (it would pile up and arrive long after).
        if (!budgetLinkInstalled(activity)) {
            queue.clear(); outbox.clear()
            if (message is BudgetLink.Message.Add) toast("MyBudget isn't installed, so the bill wasn't sent.")
            return
        }
        try { waiting = message; outbox.inFlight = message; launcher.launch(intent) }
        catch (_: android.content.ActivityNotFoundException) { waiting = null; outbox.inFlight = null; queue.clear(); outbox.clear(); toast("Couldn't open MyBudget.") }
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}
