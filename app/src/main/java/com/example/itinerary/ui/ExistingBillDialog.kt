package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixTextButton as TextButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.*

@Composable
fun ExistingBillDialog(id: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ItineraryApp
    var details by remember(id) { mutableStateOf<Triple<ItineraryItem, List<Attachment>, List<Reminder>>?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    LaunchedEffect(id) { try { details = app.repository.eventDetails(id) } catch (_: Exception) { details = null } finally { loading = false } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Existing bill") }, text = {
        ScrollHints(rememberScrollState(), Modifier.fillMaxWidth(), fitContent = true) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loading) Text("Loading…") else if (details == null) Text("This bill is no longer available.")
            details?.let { (item, attachments, reminders) ->
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text("Due ${item.date.fullLabel()}")
                Text(item.billAmountMinor?.let { Bills.format(it, item.billCurrency) } ?: "No amount entered")
                BillStatus(item.paid)
                BillBalance(item.billAmountMinor, item.billCurrency, item.paid, item.payments)
                if (item.location.isNotBlank()) Text(item.location)
                if (item.notes.isNotBlank()) Text(item.notes)
                item.payments.forEach { Text("${Bills.format(it.amount, item.billCurrency)} · ${it.date.fullLabel()}${if (it.reversed) " · Reversed" else ""}") }
                attachments.forEach { attachment ->
                    TextButton(onClick = { openAttachment(context, app.attachmentStore, attachment) }) { Text(attachment.name) }
                }
                if (reminders.isNotEmpty()) Text("${reminders.size} saved reminder(s)")
            }
            Text("Your unfinished bill is kept. Close this view to return to it.", style = MaterialTheme.typography.bodySmall)
        } }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Back to draft") } })
}
