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
fun EventActionsMenu(eventId: Long, title: String, date: LocalDate, today: LocalDate, onMove: suspend () -> Unit, onShare: () -> Unit, billId: Long? = null, paid: Boolean = false, repeatId: Long? = null, skipped: Boolean = false, repeating: Boolean = false, onEdit: (() -> Unit)? = null) {
    val exportCalendar = rememberCalendarExporter(eventId)
    var history by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var makingTask by remember { mutableStateOf(false) }
    val conversions = LocalConversions.current
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun delete(entireSeries: Boolean) {
        busy = true
        scope.launch {
            withContext(NonCancellable) {
                try { (context.applicationContext as com.example.itinerary.ItineraryApp).repository.deleteWithUndo(eventId, entireSeries) }
                catch (_: Exception) { Toast.makeText(context, "Couldn't delete the ${if (billId != null) "bill" else "event"}. Please try again.", Toast.LENGTH_LONG).show() }
                finally { busy = false }
            }
        }
    }
    OverlayMenuAnchor(title = "Actions for $title", button = { open ->
        IconButton(enabled = !busy, onClick = open) {
            Icon(Icons.Default.MoreVert, contentDescription = "Actions for $title")
        }
    }, items = { close ->
        // Bug notes 5: straight to the editor, from screens where a tap on the card opens something else.
        if (onEdit != null) DropdownMenuItem(text = { Text(if (billId != null) "Edit bill" else "Edit event") }, onClick = { close(); onEdit() })
        if (repeatId != null) DropdownMenuItem(text = { Text(if (skipped) "Restore occurrence" else "Skip this occurrence") }, onClick = {
            close(); busy = true
            scope.launch {
                withContext(NonCancellable) {
                    try { (context.applicationContext as com.example.itinerary.ItineraryApp).repository.setSkipped(repeatId, !skipped) }
                    catch (_: Exception) { Toast.makeText(context, "Couldn't update this occurrence. Please try again.", Toast.LENGTH_LONG).show() }
                    finally { busy = false }
                }
            }
        })
        if (billId != null) DropdownMenuItem(text = { BillStatus(!paid, label = if (paid) "Mark unpaid" else "Mark paid", style = MaterialTheme.typography.bodyLarge) }, onClick = {
            close(); busy = true
            scope.launch {
                withContext(NonCancellable) {
                    try { (context.applicationContext as com.example.itinerary.ItineraryApp).repository.setPaid(billId, !paid) }
                    catch (e: Exception) { Toast.makeText(context, (e as? com.example.itinerary.data.PaymentUpdateException)?.message ?: "Couldn't update this bill. Please try again.", Toast.LENGTH_LONG).show() }
                    finally { busy = false }
                }
            }
        })
        if (billId != null) DropdownMenuItem(text = { Text("Bill history") }, onClick = { close(); history = true })
        // Wish list #1: this event as a task instead (not a bill: that is its own kind).
        if (conversions != null && billId == null) DropdownMenuItem(text = { Text("Make it a task") }, enabled = !busy, onClick = {
            close(); if (repeating) makingTask = true else conversions.eventToTask(eventId, false)
        })
        DropdownMenuItem(text = { Text("Export to calendar (.ics)") }, onClick = { close(); exportCalendar() })
        DropdownMenuItem(text = { Text(if (billId != null) "Share bill" else "Share event") }, onClick = { close(); onShare() })
        DropdownMenuItem(text = { Text(if (date == today.plusDays(1)) "Already tomorrow" else if (billId != null) "Due tomorrow" else "Move to tomorrow") },
            enabled = !busy && date != today.plusDays(1), onClick = {
                close()
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
        // Last and red, as on a task's ⋮. Through the same Undo bar and dialog as the event editor's Delete.
        DropdownMenuItem(text = { Text(if (billId != null) "Delete bill" else "Delete event", color = MaterialTheme.colorScheme.error) },
            enabled = !busy, onClick = { close(); if (deleteAsks(billId != null, repeating)) deleting = true else delete(false) })
    })
    if (history && billId != null) BillHistoryDialog(billId) { history = false }
    if (makingTask) MakeTaskSeriesChoice(onChoose = { whole -> makingTask = false; conversions?.eventToTask(eventId, whole) },
        onDismiss = { makingTask = false })
    if (deleting) DeleteEventDialog(bill = billId != null, repeating = repeating, onDismiss = { deleting = false },
        onDelete = { deleting = false; delete(it) })
}
