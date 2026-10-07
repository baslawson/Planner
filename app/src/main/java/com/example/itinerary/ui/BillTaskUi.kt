package com.example.itinerary.ui
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.itinerary.ItineraryApp
import com.example.itinerary.R
import com.example.itinerary.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun BillTaskCard(bill: PlanEvent, today: LocalDate, selection: EventSelection,
                 documentName: String? = null, onEdit: () -> Unit) {
    val context = LocalContext.current
    val repo = (context.applicationContext as ItineraryApp).repository
    val scope = rememberCoroutineScope()
    val format = LocalTimeFormat.current
    var busy by remember(bill.id) { mutableStateOf(false) }
    TappableRow(onClick = { if (selection.active) selection.toggle(bill.id) else if (!busy) onEdit() },
        onLongClick = { selection.toggle(bill.id) },
        selected = selection.isSelected(bill.id).takeIf { selection.active }, arrow = false,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(bill.title, style = MaterialTheme.typography.titleMedium)
            Text("Bill payment · Task", style = MaterialTheme.typography.labelSmall)
            Text("Due ${bill.date.dayLabel(LocalDateFormat.current)}${bill.startTime?.let { ", ${it.label(format, context)}" }.orEmpty()}",
                style = MaterialTheme.typography.bodySmall)
            bill.billAmountMinor?.let { Text(Bills.format(it, bill.billCurrency), style = MaterialTheme.typography.titleMedium) }
            if (bill.skipped) Text("Skipped · reminders paused", style = MaterialTheme.typography.labelMedium)
            else BillStatus(bill.paid)
            BillBalance(bill.billAmountMinor, bill.billCurrency, bill.paid, bill.payments)
            OverdueBill(bill.date, bill.paid, bill.skipped, today)
            documentName?.let { Text("Matches document: $it", style = MaterialTheme.typography.bodySmall) }
            if (!selection.active && !bill.paid && !bill.skipped) {
                OutlinedButton(enabled = !busy, modifier = Modifier.semantics { contentDescription = "Mark ${bill.title} paid" },
                    onClick = {
                        busy = true
                        scope.launch {
                            try { withContext(NonCancellable) { repo.setPaid(bill.id, true) } }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { android.widget.Toast.makeText(context, (e as? com.example.itinerary.data.PaymentUpdateException)?.message ?: "Couldn't mark this bill paid. Please try again.", android.widget.Toast.LENGTH_LONG).show() }
                            finally { busy = false }
                        }
                    }) { Text("Mark paid") }
            }
        }
        if (selection.active) Checkbox(checked = selection.isSelected(bill.id), onCheckedChange = null)
        else EventActionsMenu(bill.id, bill.title, bill.date, today, onMove = { repo.moveToTomorrow(bill.id) },
            onShare = { shareEvent(context, bill.title, bill.date, bill.startTime, null, bill.location, format) },
            billId = bill.id, paid = bill.paid, repeatId = bill.id.takeIf { bill.seriesId != null || bill.skipped }, skipped = bill.skipped, repeating = bill.seriesId != null,
            onEdit = onEdit)
    }
}

/** Open a bill directly, with its existing attachments/reminders loaded atomically before editing. */
@Composable
fun BillTaskEditor(id: Long, onDismiss: () -> Unit) = StoredEventEditor(id, onDismiss, billOnly = true)

/**
 * Open a saved event or bill in the editor (Agenda's and Search's ⋮ › Edit; a bill card's tap), with its attachments and
 * reminders loaded before editing, and the event categories as the calendar offers them.
 */
@Composable
fun StoredEventEditor(id: Long, onDismiss: () -> Unit, billOnly: Boolean = false) {
    val app = LocalContext.current.applicationContext as ItineraryApp
    val repo = app.repository
    val scope = rememberCoroutineScope()
    val categories = remember { CategoryState(repo, app.settings, scope) }
    val counts by categories.counts.collectAsStateWithLifecycle()
    val hidden by categories.hidden.collectAsStateWithLifecycle()
    val noun = if (billOnly) "bill" else "event"
    var details by remember(id) { mutableStateOf<Triple<ItineraryItem, List<Attachment>, List<Reminder>>?>(null) }
    var failure by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        // U2: AppNav's recovery editor has this item's unsaved edits open already; this one leaves them to it.
        if (com.example.itinerary.data.EditorDraftStore.recoveryOwns(id)) { onDismiss(); return@LaunchedEffect }
        try {
            details = repo.eventDetails(id)
            if (details == null || (billOnly && details?.first?.category != "Bills")) failure = "This $noun is no longer available."
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failure = "Couldn't open this $noun. Close and try again." }
    }
    if (failure != null) {
        PlannerDialog(if (billOnly) "Bill payment" else "Event", onDismiss, dismiss = DialogAction("Close", onClick = onDismiss)) { Text(failure!!) }
    } else details?.let { (bill, attachments, reminders) ->
        key(id) { ItemEditorSheet(initial = bill, existingAttachments = attachments, existingReminders = reminders,
            categoryCounts = counts, hiddenCategories = hidden, onRemoveCategories = categories::remove, onShowCategory = categories::show,
            onDismiss = onDismiss,
            onSave = { item, added, removed, addedReminders, removedReminders, options ->
                repo.saveItemId(item, added, removed, addedReminders, removedReminders, options)
            }, onDelete = { item, entireSeries -> repo.deleteWithUndo(item, entireSeries) }) }
    } ?: run {
        BackHandler(onBack = onDismiss)
        PlannerDialog("Opening $noun…", onDismiss, dismiss = DialogAction("Cancel", onClick = onDismiss)) { CircularProgressIndicator() }
    }
}

@Composable
fun TaskTypeDialog(onTask: () -> Unit, onBill: () -> Unit, onScan: () -> Unit, onDismiss: () -> Unit) {
    // In landscape the last choice is below the visible part: it scrolls, with the scroll bar, as the Themes dialog does.
    PlannerDialog("Add task", onDismiss, dismiss = DialogAction("Cancel", onClick = onDismiss)) {
        // Room on the right for the scroll bar, and the choices a little further apart than plain text.
        Column(Modifier.fillMaxWidth().padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("What would you like to add?", style = MaterialTheme.typography.bodyMedium)
            TaskTypeChoice("To-do task", "Make room for a little win", R.drawable.action_task,
                MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.primary, onTask)
            TaskTypeChoice("Bill payment", "Keep due dates in check", R.drawable.shortcut_bill,
                MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.primary, onBill)
            TaskTypeChoice("Scan bill", "Snap it. Add it. Sorted.", R.drawable.action_camera,
                MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.primary, onScan)
        }
    }
}

@Composable
private fun TaskTypeChoice(title: String, subtitle: String, icon: Int,
    background: Color, foreground: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, border = androidx.compose.foundation.BorderStroke(com.example.itinerary.ui.theme.controlBorderWidth(), foreground), shape = RoundedCornerShape(20.dp), color = background,
        contentColor = foreground, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).background(foreground.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
