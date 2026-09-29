package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixTextButton as TextButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun BillForecastDialog(events: List<PlanEvent>, month: YearMonth, today: LocalDate, onDismiss: () -> Unit) {
    val format = LocalDateFormat.current
    val summary = Bills.summary(events, month)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Bill forecast · ${month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))}") },
        text = { LazyScrollHints { hintState -> LazyColumn(state = hintState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Remaining unpaid this month", style = MaterialTheme.typography.titleMedium)
                summary.totals.forEach { (currency, amount) -> Text(Bills.format(amount, currency)) }
                if (events.isEmpty()) Text("No unpaid bills this month")
                if (summary.withoutAmount > 0) Text("${summary.withoutAmount} without an amount; excluded from totals")
                Text("Includes overdue bills dated in this month. Other months are separate.", style = MaterialTheme.typography.bodySmall)
            }
            items(events, key = { it.id }) { bill ->
                Column {
                    Text(bill.title, style = MaterialTheme.typography.titleSmall)
                    Text("${bill.date.dayLabel(format)} · ${if (bill.date < today) "Overdue" else if (bill.date == today) "Due today" else "Upcoming"}")
                    Text(Payments.remaining(bill.billAmountMinor, bill.paid, bill.payments)?.let { Bills.format(it, bill.billCurrency) } ?: "Amount not entered")
                }
            }
        } } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
