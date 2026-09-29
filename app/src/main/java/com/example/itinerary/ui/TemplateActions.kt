package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixTextButton as TextButton
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.EventTemplate
import com.example.itinerary.data.TemplateContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplateActions(isNew: Boolean, title: String, enabled: Boolean, content: () -> TemplateContent,
    onApply: (TemplateContent) -> Unit, canApply: Boolean = true, billTask: Boolean = false) {
    val repo = (LocalContext.current.applicationContext as ItineraryApp).repository
    val templates by repo.templates.collectAsStateWithLifecycle(initialValue = emptyList())
    val matchingTemplates = remember(templates, billTask) {
        templates.filter { runCatching { (TemplateContent.decode(it.payload).item.category == "Bills") == billTask }.getOrDefault(false) }
    }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<EventTemplate?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isNew) OutlinedButton(enabled = canApply, onClick = { choosing = true; error = null }) { Text("Use template") }
        TextButton(enabled = enabled, onClick = { name = title.take(80); saving = true; error = null }) { Text("Save as template") }
    }
    if (saving) AlertDialog(onDismissRequest = { if (!busy) saving = false }, title = { Text(if (billTask) "Save bill template" else "Save event template") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("Template name") }, singleLine = true)
            Text("Saves details, checklist, amount, repeats and reminders. Dates, paid status and attachments aren't copied.")
            if (templates.any { it.name.equals(name.trim(), true) }) Text("The template with this name will be replaced.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = {
            busy = true
            scope.launch {
                try { repo.saveTemplate(name, content()); saving = false }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "Couldn't save this template. Please try again." }
                finally { busy = false }
            }
        }) { Text("Save template") } }, dismissButton = { TextButton(enabled = !busy, onClick = { saving = false }) { Text("Cancel") } })
    if (choosing && deleting == null) AlertDialog(onDismissRequest = { choosing = false }, title = { Text(if (billTask) "Bill templates" else "Event templates") },
        text = { ScrollHints(rememberScrollState(), Modifier.fillMaxWidth().heightIn(max = 420.dp), fitContent = true) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Applying replaces details and reminders. Your chosen date and attachments stay.")
            if (matchingTemplates.isEmpty()) Text("No templates yet. Fill in the details and choose Save as template.")
            matchingTemplates.forEach { template ->
                Column {
                    Text(template.name, style = MaterialTheme.typography.titleMedium)
                    Row {
                        TextButton(onClick = {
                            try { onApply(TemplateContent.decode(template.payload)); choosing = false }
                            catch (_: Exception) { error = "Couldn't read this template." }
                        }) { Text("Apply ${template.name}") }
                        DangerButton(onClick = { deleting = template }) { Text("Delete") }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } } }, confirmButton = { TextButton(onClick = { choosing = false }) { Text("Close") } })
    deleting?.let { template -> AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("Delete ${template.name}?") },
        text = { Text("Entries made from this template are kept.") },
        confirmButton = { DangerButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try { repo.deleteTemplate(template); deleting = null }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "Couldn't delete this template."; deleting = null }
                finally { busy = false }
            }
        }) { Text("Delete template") } }, dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text("Cancel") } }) }
}
