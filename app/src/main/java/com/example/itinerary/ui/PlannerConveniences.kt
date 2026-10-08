package com.example.itinerary.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.*
import java.time.LocalDate

@Composable
fun BillSuggestionDialog(attachment: Attachment, currentTitle: String, currentAmount: String, currentCurrency: String, currentBiller: String,
    onDismiss: () -> Unit, onApply: (String?, LocalDate?, Long?, String, Pair<String, String>?) -> Unit) {
    val suggestion = remember(attachment) { BillSuggestions.parse(attachment.recognizedText) }
    var title by rememberSaveable(attachment.fileName) { mutableStateOf(suggestion.title.orEmpty()) }
    var date by rememberSaveable(attachment.fileName) { mutableStateOf(suggestion.date?.toString().orEmpty()) }
    var amount by rememberSaveable(attachment.fileName) { mutableStateOf(Bills.input(suggestion.amount)) }
    var currency by rememberSaveable(attachment.fileName) { mutableStateOf(suggestion.currency ?: currentCurrency) }
    var useTitle by rememberSaveable(attachment.fileName) { mutableStateOf(currentTitle.isBlank() && suggestion.title != null) }
    var useDate by rememberSaveable(attachment.fileName) { mutableStateOf(false) }
    var useAmount by rememberSaveable(attachment.fileName) { mutableStateOf(currentAmount.isBlank() && suggestion.amount != null) }
    // BPAY: offered when the scan has a BPAY box and the bill will be AUD (Bills.hasBpay); ticked when the bill has no biller code yet.
    var biller by rememberSaveable(attachment.fileName) { mutableStateOf(suggestion.bpayBiller.orEmpty()) }
    var bpayRef by rememberSaveable(attachment.fileName) { mutableStateOf(suggestion.bpayReference.orEmpty()) }
    var useBpay by rememberSaveable(attachment.fileName) { mutableStateOf(suggestion.bpayBiller != null && currentBiller.isBlank()) }
    val bpayOffered = Bills.hasBpay(if (useAmount) currency.uppercase() else currentCurrency) && suggestion.warnings["bpay"] != null
    val parsedDate = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
    val valid = (useTitle || useDate || useAmount || useBpay && bpayOffered) && (!useTitle || title.isNotBlank()) && (!useDate || parsedDate != null) &&
        (!useAmount || Bills.parse(amount) != null && currency.uppercase() in Bills.currencies) && (!useBpay || !bpayOffered || biller.isNotBlank())
    PlannerDialog("Review bill details", onDismissRequest = onDismiss,
        primary = DialogAction("Apply selected", enabled = valid) {
            onApply(title.trim().takeIf { useTitle }, parsedDate.takeIf { useDate }, Bills.parse(amount).takeIf { useAmount }, currency,
                (biller.trim() to bpayRef.trim()).takeIf { useBpay && bpayOffered })
        },
        dismiss = DialogAction("Cancel", onClick = onDismiss)) {
            Text("From ${attachment.name}. Check against the document, then tick the fields to apply. Nothing is saved until you save the bill.")
            if (attachment.recognizedText.isBlank()) Text("No readable text was found. You can enter the details here, or cancel and scan again.")
            else if (attachment.textStatus == "PARTIAL") ScanUncertainty("Only part of this document was read. Verify every suggested field.")
            SuggestionCheck("Use title", useTitle) { useTitle = it }
            OutlinedTextField(title, { title = it.replace('\n', ' ') }, label = { Text("Suggested title") }, modifier = Modifier.fillMaxWidth())
            ScanUncertainty(suggestion.warnings["title"])
            SuggestionCheck("Use due date", useDate) { useDate = it }
            OutlinedTextField(date, { date = it }, label = { Text("Due date (YYYY-MM-DD)") }, isError = useDate && parsedDate == null, modifier = Modifier.fillMaxWidth())
            ScanUncertainty(suggestion.warnings["date"])
            SuggestionCheck("Use amount and currency", useAmount) { useAmount = it }
            OutlinedTextField(amount, { amount = it.take(16) }, label = { Text("Suggested amount") }, isError = useAmount && Bills.parse(amount) == null, modifier = Modifier.fillMaxWidth())
            ScanUncertainty(suggestion.warnings["amount"])
            // The currencies Planner takes, those used on bills before first.
            val used by (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.example.itinerary.ItineraryApp).repository.allItems
                .collectAsStateWithLifecycle(initialValue = emptyList())
            val currencyChoices = androidx.compose.runtime.remember(currency, used) {
                com.example.itinerary.data.EntryHistory.currencies(used.filter { it.category == "Bills" }.map { it.billCurrency }, currency, Bills.currencies)
            }
            SuggestField(currency, { currency = it.uppercase().take(3) }, label = "Currency code", suggestions = currencyChoices,
                onPick = { currency = it })
            if (useAmount && currency !in Bills.currencies) Text("Choose one of: ${Bills.currencies.joinToString()}.", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            ScanUncertainty(suggestion.warnings["currency"])
            if (bpayOffered) {
                SuggestionCheck("Use BPAY biller code and reference", useBpay) { useBpay = it }
                OutlinedTextField(biller, { biller = it.filter(Char::isDigit).take(10) }, label = { Text("BPAY biller code") },
                    isError = useBpay && biller.isBlank(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(bpayRef, { bpayRef = it.filter(Char::isDigit).take(20) }, label = { Text("BPAY reference") }, modifier = Modifier.fillMaxWidth())
                ScanUncertainty(suggestion.warnings["bpay"])
            }
            Text("Blank fields could not be identified reliably. A $ symbol alone keeps your current currency ($currentCurrency), " +
                "unless the document has a BPAY box (then AUD). Applying details selects Bills.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ScanUncertainty(message: String?) {
    if (message != null) Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
        Text("Check: $message", Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SuggestionCheck(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChecked),
        verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, null); Text(label) }
}
