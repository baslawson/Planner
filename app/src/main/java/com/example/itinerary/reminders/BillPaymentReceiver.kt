package com.example.itinerary.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.example.itinerary.ItineraryApp
import kotlinx.coroutines.*

class BillPaymentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, 0)
        val token = intent.getStringExtra(ReminderScheduler.EXTRA_BILL_TOKEN) ?: return
        if (id <= 0) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val repo = (context.applicationContext as ItineraryApp).repository
                if (intent.action == UNDO) {
                    val undone = repo.undoPayment(token)
                    val manager = context.getSystemService(android.app.NotificationManager::class.java)
                    if (manager.activeNotifications.any { it.id == id.toInt() && it.notification.extras.getString(PAYMENT_TOKEN) == token })
                        manager.cancel(id.toInt())
                    Toast.makeText(context, if (undone) "Payment undone" else "Undo expired or this bill changed. Open Planner to check.", Toast.LENGTH_SHORT).show()
                } else {
                    val saved = repo.payFromReminder(id, token)
                    if (saved) repo.pendingPayments.value.lastOrNull { it.remindersAfter.any { r -> r.id == id } }?.let { change ->
                        showUndo(context, id, change.token, change.before.title)
                    }
                    Toast.makeText(context, if (saved) "Bill marked paid" else "Bill changed or already paid. Open Planner to check.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.w("BillPaymentReceiver", "Couldn't mark bill paid", e)
                Toast.makeText(context, (e as? com.example.itinerary.data.PaymentUpdateException)?.message ?: "Couldn't mark paid. Please try in Planner.", Toast.LENGTH_LONG).show()
            } finally { pending.finish() }
        }
    }
    companion object {
        private const val UNDO = "com.example.itinerary.UNDO_PAYMENT"
        private const val PAYMENT_TOKEN = "payment_undo_token"
        private fun showUndo(context: Context, id: Long, token: String, title: String) {
            val undo = PendingIntent.getBroadcast(context, id.toInt(), Intent(context, BillPaymentReceiver::class.java)
                .setAction(UNDO).setData(Uri.parse("planner://undo-payment/$token"))
                .putExtra(ReminderScheduler.EXTRA_REMINDER_ID, id).putExtra(ReminderScheduler.EXTRA_BILL_TOKEN, token),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = androidx.core.app.NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
                .setSmallIcon(com.example.itinerary.R.drawable.ic_notification)
                .setContentTitle("$title · Paid").setContentText("Payment saved. Undo is available briefly.")
                .setSilent(true).setTimeoutAfter(10_000).setAutoCancel(true)
                .addExtras(android.os.Bundle().apply { putString(PAYMENT_TOKEN, token) })
                .addAction(0, "Undo", undo).build()
            try { androidx.core.app.NotificationManagerCompat.from(context).notify(id.toInt(), notification) }
            catch (_: SecurityException) { /* Payment remains saved if notification permission changed. */ }
        }
        fun action(context: Context, id: Long, token: String): PendingIntent = PendingIntent.getBroadcast(
            context, id.toInt(), Intent(context, BillPaymentReceiver::class.java)
                .setData(Uri.parse("planner://bill-payment/$id/$token"))
                .putExtra(ReminderScheduler.EXTRA_REMINDER_ID, id)
                .putExtra(ReminderScheduler.EXTRA_BILL_TOKEN, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
