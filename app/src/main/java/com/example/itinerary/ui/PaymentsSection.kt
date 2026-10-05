package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixTextButton as TextButton
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.example.itinerary.data.*
import java.time.LocalDate

@Composable
fun BillBalance(amount: Long?, currency: String, paid: Boolean, payments: List<BillPayment>) {
    if (amount != null && payments.any { !it.reversed }) {
        Text("${Bills.format(Payments.total(payments), currency)} paid · ${Bills.format(Payments.remaining(amount, paid, payments)!!, currency)} remaining",
            style = MaterialTheme.typography.bodySmall)
    }
}

// The bill editor's payments: a summary (how much is paid and left, a progress bar, the latest payment), Record payment,
// and the full history behind "Payment history (n)", closed each time the bill is opened.
// "AUD 30.00 of AUD 100.00 paid · AUD 70.00 remaining" (or "Paid in full"), a progress bar and the latest payment. Nothing
// until a payment counts (a bill marked paid without payments shows its Paid status elsewhere).
@Composable
fun PaymentSummary(amount: Long?, currency: String, paid: Boolean, payments: List<BillPayment>) {
    if (!Payments.anyLive(payments)) return
    val summary = Payments.summary(amount, paid, payments)
    val fmt = { minor: Long -> Bills.format(minor, currency) }
    Text(when {
        amount == null -> "${fmt(summary.paid)} paid"
        summary.remaining == 0L -> "Paid in full · ${fmt(amount)}"
        else -> "${fmt(summary.paid)} of ${fmt(amount)} paid · ${fmt(summary.remaining!!)} remaining"
    }, style = MaterialTheme.typography.bodyMedium)
    summary.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
    summary.last?.let { Text("Last payment: ${fmt(it.amount)} on ${it.date.fullLabel()}", style = MaterialTheme.typography.bodySmall) }
}

// "Payment history (n)" that opens the full list, newest first; reversed payments greyed. [onReverse]: null = read-only.
@Composable
fun PaymentHistory(payments: List<BillPayment>, currency: String, onReverse: ((BillPayment) -> Unit)?) {
    if (payments.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    // A screen reader hears whether the list is open, as with the unpaid bills card (MonthlyBills).
    TextButton(onClick = { open = !open }, modifier = Modifier.semantics { stateDescription = if (open) "Expanded" else "Collapsed" }) {
        Text("Payment history (${payments.size})")
        Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null)
    }
    if (open) Payments.newestFirst(payments).forEach { payment ->
        val dim = if (payment.reversed) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${Bills.format(payment.amount, currency)} · ${payment.date.fullLabel()}", color = dim)
                if (payment.note.isNotBlank()) Text(payment.note, style = MaterialTheme.typography.bodySmall, color = dim)
                if (payment.reversed) Text("Reversed", style = MaterialTheme.typography.bodySmall, color = dim)
            }
            if (onReverse != null && !payment.reversed) TextButton(onClick = { onReverse(payment) }) { Text("Reverse") }
        }
    }
}

@Composable
fun PaymentsSection(amount: Long?, currency: String, paid: Boolean, payments: List<BillPayment>,
    onChange: (List<BillPayment>) -> Unit) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var reverse by remember { mutableStateOf<BillPayment?>(null) }
    PaymentSummary(amount, currency, paid, payments)
    val remaining = Payments.remaining(amount, paid, payments)
    OutlinedButton(enabled = remaining != null && remaining > 0 && payments.size < Payments.MAX_ENTRIES, onClick = { adding = true }) { Text("Record payment") }
    if (payments.size >= Payments.MAX_ENTRIES) Text(Payments.LIMIT_MESSAGE, style = MaterialTheme.typography.bodySmall)
    if (amount == null) Text("Enter the bill amount to record a payment.", style = MaterialTheme.typography.bodySmall)
    PaymentHistory(payments, currency, onReverse = { reverse = it })
    if (payments.isNotEmpty()) Text("Marking unpaid reverses the automatic settlement. Other payments stay unless they fully cover the bill. History is kept.", style = MaterialTheme.typography.bodySmall)
    reverse?.let { payment -> PlannerDialog("Reverse payment?", { reverse = null },
        primary = DialogAction("Reverse payment", danger = true) { onChange(payments.map { if (it.id == payment.id) it.copy(reversed = true) else it }); reverse = null },
        dismiss = DialogAction("Cancel") { reverse = null }) {
        Text("${Bills.format(payment.amount, currency)} will be added back to the balance when you save the bill.")
    } }
    if (adding) {
        var input by rememberSaveable { mutableStateOf("") }
        var note by rememberSaveable { mutableStateOf("") }
        var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
        var picking by remember { mutableStateOf(false) }
        val parsed = Bills.parse(input)
        val valid = parsed != null && parsed > 0 && remaining != null && parsed <= remaining
        PlannerDialog("Record payment", { adding = false },
            primary = DialogAction("Add payment", enabled = valid) {
                onChange(payments + BillPayment(amount = parsed!!, date = LocalDate.parse(date), note = note.trim())); adding = false
            },
            dismiss = DialogAction("Cancel") { adding = false }) {
                Text("Remaining: ${remaining?.let { Bills.format(it, currency) }.orEmpty()}")
                OutlinedTextField(input, { input = it.take(16) }, label = { Text("Payment amount") }, singleLine = true,
                    isError = input.isNotBlank() && !valid)
                TextButton(onClick = { picking = true }) { Text(LocalDate.parse(date).fullLabel()) }
                OutlinedTextField(note, { note = it.take(200) }, label = { Text("Payment note (optional)") })
                Text("Applied when you save the bill.", style = MaterialTheme.typography.bodySmall)
        }
        if (picking) SingleDateDialog(LocalDate.parse(date), { picking = false }, { date = it.toString(); picking = false })
    }
}
