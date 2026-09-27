package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixTextButton as TextButton

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.*
import java.time.LocalDate

@Composable
fun BillSuggestionDialog(attachment: Attachment, currentTitle: String, currentAmount: String, currentCurrency: String,
    onDismiss: () -> Unit, onApply: (String?, LocalDate?, Long?, String) -> Unit) {
    val suggestion = remember(attachment) { BillSuggestions.parse(attachment.recognizedText) }
    var title by rememberSaveable(attachment.fileName) { mutableStateOf(suggestion.title.orEmpty()) }
    var date by rememberSaveable(attachment.fileName) { mutableStateOf(suggestion.date?.toString().orEmpty()) }
    var amount by rememberSaveable(attachment.fileName) { mutableStateOf(Bills.input(suggestion.amount)) }
    var currency by rememberSaveable(attachment.fileName) { mutableStateOf(suggestion.currency ?: currentCurrency) }
    var useTitle by rememberSaveable(attachment.fileName) { mutableStateOf(currentTitle.isBlank() && suggestion.title != null) }
    var useDate by rememberSaveable(attachment.fileName) { mutableStateOf(false) }
    var useAmount by rememberSaveable(attachment.fileName) { mutableStateOf(currentAmount.isBlank() && suggestion.amount != null) }
    val parsedDate = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
    val valid = (useTitle || useDate || useAmount) && (!useTitle || title.isNotBlank()) && (!useDate || parsedDate != null) &&
        (!useAmount || Bills.parse(amount) != null && currency.uppercase() in Bills.currencies)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Review bill details") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("From ${attachment.name}. Check against the document, then tick the fields to apply. Nothing is saved until you save the event.")
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
            OutlinedTextField(currency, { currency = it.uppercase().take(3) }, label = { Text("Currency code") }, isError = useAmount && currency !in Bills.currencies, modifier = Modifier.fillMaxWidth())
            ScanUncertainty(suggestion.warnings["currency"])
            Text("Blank fields could not be identified reliably. A $ symbol alone keeps your current currency ($currentCurrency). Applying details selects Bills.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = { onApply(title.trim().takeIf { useTitle }, parsedDate.takeIf { useDate }, Bills.parse(amount).takeIf { useAmount }, currency) }) { Text("Apply selected") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
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
