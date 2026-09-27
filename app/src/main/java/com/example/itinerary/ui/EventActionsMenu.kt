package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixIconButton as IconButton

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun EventActionsMenu(eventId: Long, title: String, date: LocalDate, today: LocalDate, onMove: suspend () -> Unit, onShare: () -> Unit, billId: Long? = null, paid: Boolean = false, repeatId: Long? = null, skipped: Boolean = false) {
    val exportCalendar = rememberCalendarExporter(eventId)
    var history by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    androidx.compose.foundation.layout.Box {
        IconButton(enabled = !busy, onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Actions for $title")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (repeatId != null) DropdownMenuItem(text = { Text(if (skipped) "Restore occurrence" else "Skip this occurrence") }, onClick = {
                expanded = false; busy = true
                scope.launch {
                    withContext(NonCancellable) {
                        try { (context.applicationContext as com.example.itinerary.ItineraryApp).repository.setSkipped(repeatId, !skipped) }
                        catch (_: Exception) { Toast.makeText(context, "Couldn't update this occurrence. Please try again.", Toast.LENGTH_LONG).show() }
                        finally { busy = false }
                    }
                }
            })
            if (billId != null) DropdownMenuItem(text = { BillStatus(!paid, label = if (paid) "Mark unpaid" else "Mark paid", style = MaterialTheme.typography.bodyLarge) }, onClick = {
                expanded = false; busy = true
                scope.launch {
                    withContext(NonCancellable) {
                        try { (context.applicationContext as com.example.itinerary.ItineraryApp).repository.setPaid(billId, !paid) }
                        catch (e: Exception) { Toast.makeText(context, (e as? com.example.itinerary.data.PaymentUpdateException)?.message ?: "Couldn't update this bill. Please try again.", Toast.LENGTH_LONG).show() }
                        finally { busy = false }
                    }
                }
            })
            if (billId != null) DropdownMenuItem(text = { Text("Bill history") }, onClick = { expanded = false; history = true })
            DropdownMenuItem(text = { Text("Export to calendar (.ics)") }, onClick = { expanded = false; exportCalendar() })
            DropdownMenuItem(text = { Text(if (billId != null) "Share bill" else "Share event") }, onClick = { expanded = false; onShare() })
            DropdownMenuItem(text = { Text(if (date == today.plusDays(1)) "Already tomorrow" else if (billId != null) "Due tomorrow" else "Move to tomorrow") },
                enabled = !busy && date != today.plusDays(1), onClick = {
                    expanded = false
                    busy = true
                    scope.launch {
                        withContext(NonCancellable) {
                            try {
                                onMove()
                            } catch (_: Exception) {
                                Toast.makeText(context, "Couldn't change the date. Please try again.", Toast.LENGTH_LONG).show()
                            } finally { busy = false }
                        }
                    }
                })
        }
    }
    if (history && billId != null) BillHistoryDialog(billId) { history = false }
}
