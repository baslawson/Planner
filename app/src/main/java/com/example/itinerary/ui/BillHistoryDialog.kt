package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixTextButton as TextButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.*

@Composable
fun BillHistoryDialog(id: Long, onDismiss: () -> Unit) {
    val repo = (LocalContext.current.applicationContext as ItineraryApp).repository
    val loaded by repo.allItems.collectAsStateWithLifecycle(initialValue = null)
    val history = remember(loaded, id) { billHistory(loaded.orEmpty(), id) }
    val today = rememberCurrentDate()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Bill history") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saved occurrences in this series. Dates are due dates, not payment dates.")
                if (loaded == null) Text("Loading…")
                else if (history.isEmpty()) Text("This bill is no longer available.")
                else {
                    Text("${history.count { it.paid && !it.skipped }} paid · ${history.count { !it.paid && !it.skipped }} unpaid · ${history.count { it.skipped }} skipped")
                    LazyScrollHints(Modifier.heightIn(max = 400.dp)) { hintState -> LazyColumn(Modifier.fillMaxWidth(), state = hintState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(history, key = { it.id }) { item ->
                            Column {
                                Text(item.title, style = MaterialTheme.typography.titleSmall)
                                Text(item.date.dayLabel(LocalDateFormat.current))
                                Text(item.billAmountMinor?.let { Bills.format(it, item.billCurrency) } ?: "No amount entered")
                                if (item.skipped) Text("Skipped") else BillStatus(item.paid)
                BillBalance(item.billAmountMinor, item.billCurrency, item.paid, item.payments)
                                OverdueBill(item.date, item.paid, item.skipped, today)
                            }
                        }
                    } }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
fun OverdueBill(date: java.time.LocalDate, paid: Boolean, skipped: Boolean, today: java.time.LocalDate) {
    if (billOverdue(date, paid, skipped, today))
        Text("Overdue", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
}
