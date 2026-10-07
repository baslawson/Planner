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

fun budgetIntent(message: BudgetLink.Message): Intent? = when (message) {
    is BudgetLink.Message.Add -> Intent(BudgetLink.ACTION_ADD).setPackage(BudgetLink.PACKAGE)
        .putExtra(BudgetLink.EXTRA_PAYMENT_ID, message.paymentId).putExtra(BudgetLink.EXTRA_BILL_KEY, message.billKey)
        .putExtra(BudgetLink.EXTRA_PAYEE, message.payee).putExtra(BudgetLink.EXTRA_CURRENCY, "AUD")
        .putExtra(BudgetLink.EXTRA_DATE, message.date.toString()).putExtra(BudgetLink.EXTRA_NOTE, BudgetLink.NOTE)
        .apply { message.amount?.let { putExtra(BudgetLink.EXTRA_AMOUNT, it) } }
    is BudgetLink.Message.Undone -> Intent(BudgetLink.ACTION_UNDONE).setPackage(BudgetLink.PACKAGE)
        .putExtra(BudgetLink.EXTRA_PAYMENT_ID, message.paymentId)
    is BudgetLink.Message.NotAud -> null
}

/**
 * MainActivity's messages for MyBudget, one screen at a time: the next opens when MyBudget answers the last. Lost if
 * Android ends Planner while MyBudget is open; MyBudget adds a payment once, so nothing is doubled either way.
 */
class BudgetLinkSender(private val activity: Activity) {
    private val queue = ArrayDeque<BudgetLink.Message>()
    private var waiting: BudgetLink.Message? = null
    lateinit var launcher: ActivityResultLauncher<Intent>

    fun send(messages: List<BudgetLink.Message>) { queue += messages; next() }

    fun answered(resultCode: Int, data: Intent?) {
        val sent = waiting ?: return
        waiting = null
        val summary = data?.getStringExtra(BudgetLink.EXTRA_SUMMARY)?.take(160)?.takeIf { it.isNotBlank() }
        when {
            resultCode == Activity.RESULT_OK && summary != null -> toast(summary)
            sent is BudgetLink.Message.Add && resultCode != Activity.RESULT_OK -> toast("Not added to MyBudget. The bill stays paid.")
        }
        next()
    }

    private fun next() {
        if (waiting != null) return
        val message = queue.removeFirstOrNull() ?: return
        val intent = budgetIntent(message)
        if (intent == null) { toast("Not sent to MyBudget: it keeps AUD bills only."); return next() }
        if (!budgetLinkInstalled(activity)) {
            queue.clear()
            if (message is BudgetLink.Message.Add) toast("MyBudget isn't installed, so the bill wasn't sent.")
            return
        }
        try { waiting = message; launcher.launch(intent) }
        catch (_: android.content.ActivityNotFoundException) { waiting = null; queue.clear(); toast("Couldn't open MyBudget.") }
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}
