package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixTextButton as TextButton
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.*
import java.time.LocalDate

@Composable
fun BillBalance(amount: Long?, currency: String, paid: Boolean, payments: List<BillPayment>) {
    if (amount != null && payments.any { !it.reversed }) {
        Text("${Bills.format(Payments.total(payments), currency)} paid · ${Bills.format(Payments.remaining(amount, paid, payments)!!, currency)} remaining",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun PaymentsSection(amount: Long?, currency: String, paid: Boolean, payments: List<BillPayment>,
    onChange: (List<BillPayment>) -> Unit) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var reverse by remember { mutableStateOf<BillPayment?>(null) }
    BillBalance(amount, currency, paid, payments)
    val remaining = Payments.remaining(amount, paid, payments)
    OutlinedButton(enabled = remaining != null && remaining > 0 && payments.size < Payments.MAX_ENTRIES, onClick = { adding = true }) { Text("Record payment") }
    if (payments.size >= Payments.MAX_ENTRIES) Text(Payments.LIMIT_MESSAGE, style = MaterialTheme.typography.bodySmall)
    if (amount == null) Text("Enter the bill amount to record a payment.", style = MaterialTheme.typography.bodySmall)
    payments.forEach { payment ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("${Bills.format(payment.amount, currency)} · ${payment.date.fullLabel()}")
                if (payment.note.isNotBlank()) Text(payment.note, style = MaterialTheme.typography.bodySmall)
                if (payment.reversed) Text("Reversed", style = MaterialTheme.typography.bodySmall)
            }
            if (!payment.reversed) TextButton(onClick = { reverse = payment }) { Text("Reverse") }
        }
    }
    if (payments.isNotEmpty()) Text("Marking the bill unpaid reverses its recorded payments. History is kept.", style = MaterialTheme.typography.bodySmall)
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
