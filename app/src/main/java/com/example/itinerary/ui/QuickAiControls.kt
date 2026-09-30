package com.example.itinerary.ui

import com.example.itinerary.ui.MatrixTextButton as TextButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.itinerary.data.*
import kotlinx.coroutines.*

@Composable
fun QuickAiSettingsButton() {
    val app = LocalContext.current.applicationContext as com.example.itinerary.ItineraryApp
    val enabled by app.settings.aiFeaturesEnabled.collectAsState()
    var open by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = { value ->
        try { app.settings.setAiFeaturesEnabled(value); error = null; if (!value) open = false }
        catch (_: Exception) { error = "Couldn't save AI preference. Try again." }
    }).padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Enable AI features", Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = null)
    }
    Text("Off hides AI controls and stops AI requests. Offline entry stays available; saved keys are kept.",
        style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (enabled) {
        TextButton(onClick = { open = true }) { Text("AI assistance") }
        if (open) QuickAiSetup { open = false }
    }
}

@Composable
fun QuickAiSetup(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as com.example.itinerary.ItineraryApp
    val aiEnabled by app.settings.aiFeaturesEnabled.collectAsState()
    if (!aiEnabled) return
    val store = remember { app.quickAiConnectionStore }
    val scope = rememberCoroutineScope()
    var connection by remember { mutableStateOf<QuickAiConnection?>(null) }
    var provider by remember { mutableStateOf(QuickAiProvider.GEMINI) }
    var model by rememberSaveable { mutableStateOf(QuickAiConnection.DEFAULT_MODEL) }
    // Personal keys never enter saved activity state, drafts, backups or prefilled text fields.
    var key by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) {
        try { provider = withContext(Dispatchers.IO) { store.selected() }; connection = withContext(Dispatchers.IO) { store.load(provider) }; model = connection?.model ?: provider.defaultModel }
        catch (e: QuickAiException) { error = e.message }
        finally { loading = false }
    }
    fun change(block: suspend () -> Unit) {
        loading = true; error = null
        job = scope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = (e as? QuickAiException)?.message ?: "Couldn't update AI settings. Try again." }
            finally { loading = false }
        }
    }
    PlannerDialog("AI assistance", onDismissRequest = { job?.cancel(); onDismiss() },
        dismiss = DialogAction("Done") { job?.cancel(); onDismiss() }) {
                // Room on the right for the scroll bar.
                Column(Modifier.fillMaxWidth().padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickAiProvider.entries.forEach { choice ->
                            FilterChip(selected = provider == choice, enabled = !loading, label = { Text(choice.label) }, onClick = {
                                if (provider != choice) change {
                                    withContext(Dispatchers.IO) { store.select(choice) }
                                    provider = choice; key = ""; connection = null; model = choice.defaultModel
                                    connection = withContext(Dispatchers.IO) { store.load(choice) }
                                    model = connection?.model ?: choice.defaultModel
                                }
                            })
                        }
                    }
                    Text("Use your own ${provider.label} key")
                    Text("Understand with AI sends your entry text, date, timezone, mode/type and clarification answers directly to ${provider.label}. Requests use your API quota and may incur charges. Check the preview before adding.")
                    Text("Typing sends nothing. Calendar contents, scans and attachments are not sent. Provider data policies apply. Switching providers discards unsaved settings edits.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(provider.keyUrl))) }) { Text("Get a ${provider.label} API key") }
                    if (provider == QuickAiProvider.OPENAI) Text("OpenAI API billing is separate from a ChatGPT subscription.", style = MaterialTheme.typography.bodySmall)
                    val saved = connection
                    if (saved != null) Text("API key saved on this phone.")
                    OutlinedTextField(key, { if (it.length <= 2048) key = it }, enabled = !loading,
                        label = { Text(if (saved == null) "${provider.label} API key" else "Replacement API key (optional)") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false))
                    OutlinedTextField(model, { if (it.length <= 110) model = it.filterNot { c -> c == '\n' } }, enabled = !loading,
                        label = { Text("${provider.label} model") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false, imeAction = androidx.compose.ui.text.input.ImeAction.Done))
                    TextButton(enabled = !loading && (key.isNotBlank() || saved != null) && model.isNotBlank(), onClick = {
                        change {
                            val value = QuickAiConnection.create(key.ifBlank { saved?.apiKey.orEmpty() }, model,
                                provider = provider)
                            withContext(Dispatchers.IO) { store.save(value) }
                            connection = value; key = ""; model = value.model
                        }
                    }) { Text("Save AI settings") }
                    Text("Your key is encrypted on this phone and excluded from backups. Saving settings makes no API request.", style = MaterialTheme.typography.bodySmall)
                    if (saved != null || error != null) TextButton(enabled = !loading, onClick = { change {
                        withContext(Dispatchers.IO) { store.clear(provider) }; connection = null; key = ""; model = provider.defaultModel
                    } }) { Text("Remove API key") }
                    if (loading) Text("Updating…")
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
    }
}

/** Snapshot includes all manual corrections; editing/mode changes invalidate both answers and responses. */
@Composable
fun QuickAiAction(input: QuickInput, enabled: Boolean = true,
                  onWorking: (Boolean) -> Unit = {}, onResult: (List<QuickAiEntry>) -> Unit) {
    val app = LocalContext.current.applicationContext as com.example.itinerary.ItineraryApp
    val aiEnabled by app.settings.aiFeaturesEnabled.collectAsState()
    if (aiEnabled) QuickAiActionEnabled(input, enabled, onWorking, onResult)
}

@Composable
private fun QuickAiActionEnabled(input: QuickInput, enabled: Boolean,
    onWorking: (Boolean) -> Unit, onResult: (List<QuickAiEntry>) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as com.example.itinerary.ItineraryApp
    val store = remember { app.quickAiConnectionStore }
    val client = remember { app.quickAiClient }
    val scope = rememberCoroutineScope()
    val latestInput by rememberUpdatedState(input)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestResult by rememberUpdatedState(onResult)
    var connection by remember { mutableStateOf<QuickAiConnection?>(null) }
    var setup by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var question by remember { mutableStateOf<String?>(null) }
    var answer by remember { mutableStateOf("") }
    var answers by remember { mutableStateOf<List<Pair<String,String>>>(emptyList()) }
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(setup) {
        if (!setup) try { connection = null; connection = withContext(Dispatchers.IO) { store.load() } }
        catch (e: QuickAiException) { message = e.message }
    }
    fun cancel() { generation++; job?.cancel(); job = null; loading = false; onWorking(false) }
    LaunchedEffect(input, enabled, setup) {
        cancel(); question = null; answer = ""; answers = emptyList()
        if (message != null) message = null
    }
    DisposableEffect(Unit) { onDispose { job?.cancel(); onWorking(false) } }
    fun submit() {
        val saved = connection ?: return
        if (loading || !enabled || setup || !app.settings.aiFeaturesEnabled.value) return
        val snapshot = input; val revision = ++generation
        val nextAnswers = if (question != null) answers + (question!! to answer.trim()) else answers
        answers = nextAnswers; question = null; answer = ""; message = null; loading = true; onWorking(true)
        job = scope.launch {
            try {
                val result = client.understand(saved, snapshot, nextAnswers)
                ensureActive()
                if (revision != generation || latestInput != snapshot || !latestEnabled || !app.settings.aiFeaturesEnabled.value) return@launch
                when (result.status) {
                    "ready" -> { latestResult(result.entries); message = "AI interpretation ready. Check the preview before adding." }
                    "clarify" -> if (nextAnswers.size < 5) question = result.message else message = "Please make the wording more precise and try again."
                    else -> message = result.message
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (revision == generation) message = (e as? QuickAiException)?.message ?: "AI couldn't finish. Your draft is unchanged; retry or continue offline." }
            finally { if (revision == generation) { loading = false; onWorking(false) } }
        }
    }
    if (connection != null) {
        if (loading) Row {
            Text("Understanding…", Modifier.weight(1f))
            TextButton(onClick = { cancel(); message = "Cancelled. Your draft is unchanged." }) { Text("Cancel AI") }
        } else TextButton(enabled = enabled && question == null && input.text.isNotBlank() && input.text.length <= 500, onClick = { submit() }) { Text("Understand with AI") }
        Text("Sends this text to ${connection?.provider?.label}. Nothing is saved automatically.", style = MaterialTheme.typography.bodySmall)
    } else TextButton(enabled = enabled, onClick = { setup = true }) { Text("Set up AI assistance") }
    if (connection != null) TextButton(enabled = !loading, onClick = { setup = true }) { Text("AI provider: ${connection?.provider?.label}") }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (question != null) {
        Text(question!!)
        OutlinedTextField(answer, { if (it.length <= 1000) answer = it }, label = { Text("Your clarification") })
        Row {
            TextButton(enabled = answer.isNotBlank() && enabled, onClick = { submit() }) { Text("Send answer") }
            TextButton(onClick = { question = null; answers = emptyList(); answer = "" }) { Text("Continue offline") }
        }
    }
    if (setup) QuickAiSetup { setup = false }
}
