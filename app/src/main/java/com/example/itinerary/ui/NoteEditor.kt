package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton
import com.example.itinerary.ui.MatrixFilterChip as FilterChip

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.Markdown
import com.example.itinerary.data.Notes
import com.example.itinerary.data.PlannerNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal val NOTE_COLOR_NAMES = listOf("Green", "Teal", "Blue", "Purple", "Pink", "Red", "Orange", "Brown")

/**
 * A note, full screen: title, notebook, colour and pin, then the body in Markdown — written in Edit, read (and its
 * checklist ticked) in Preview — with the editors' Delete · Close · Save bar. A new note opens in Edit, a saved one in
 * Preview. Save keeps the editor open ("Saved"); Close with changes asks first.
 */
@Composable
fun NoteEditor(initial: PlannerNote, creating: Boolean, notebooks: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repo = (context.applicationContext as ItineraryApp).repository
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(if (creating) null else initial) }
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var content by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial.content)) }
    var notebook by rememberSaveable { mutableStateOf(initial.notebook) }
    var color by rememberSaveable { mutableStateOf(initial.color) }
    var pinned by rememberSaveable { mutableStateOf(initial.pinned) }
    var preview by rememberSaveable { mutableStateOf(!creating && initial.content.isNotBlank()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askingToSave by rememberSaveable { mutableStateOf(false) }
    var justSaved by remember { mutableStateOf(false) }
    val current = (saved ?: initial).copy(title = title, content = content.text, notebook = notebook, color = color, pinned = pinned)
    val base = saved ?: initial.takeIf { !creating }
    val unsaved = base == null && (title.isNotBlank() || content.text.isNotBlank()) ||
        base != null && Notes.clean(current) != Notes.clean(base)
    // Deleted elsewhere (another device, once notes sync): Save can't bring it back, so it says so.
    val stored by remember(initial.id) { repo.observeNote(initial.id) }.collectAsStateWithLifecycle(initialValue = initial)
    val deletedElsewhere = saved != null && stored == null
    val canSave = !busy && !deletedElsewhere && (title.isNotBlank() || content.text.isNotBlank())
    LaunchedEffect(unsaved) { if (unsaved) justSaved = false }

    fun save(then: () -> Unit = {}) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                saved = repo.saveNote(current, create = saved == null)
                justSaved = true
                then()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = if (e is IllegalStateException && e.message == "This note was deleted") "This note was deleted." else "Couldn't save this note. Please try again." }
            finally { busy = false }
        }
    }
    fun close() { if (unsaved) askingToSave = true else onDismiss() }
    fun delete() {
        val id = saved?.id ?: return onDismiss()
        busy = true
        scope.launch {
            try { repo.deleteNote(id); onDismiss() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Couldn't delete this note. Please try again." }
            finally { busy = false }
        }
    }
    BackHandler { if (!busy) close() }

    val scroll = rememberScrollState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            ScrollHints(scroll, Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    HeadingText(if (saved == null) "New note" else "Edit note", style = MaterialTheme.typography.headlineMedium)
                    OutlinedTextField(title, { title = it.replace('\n', ' ').take(Notes.MAX_TITLE) }, Modifier.fillMaxWidth(),
                        label = { Text("Title") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                    // Edit / Preview, and in Edit the Markdown shortcuts.
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(false to "Edit", true to "Preview").forEachIndexed { index, (isPreview, label) ->
                            SegmentedButton(selected = preview == isPreview, onClick = { preview = isPreview },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                                colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    activeContentColor = MaterialTheme.colorScheme.primary)) { Text(label) }
                        }
                    }
                    if (preview) {
                        if (content.text.isBlank()) Text("Nothing written yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else MarkdownView(content.text, Modifier.fillMaxWidth(), onToggle = { line ->
                            content = content.copy(text = Markdown.toggle(content.text, line))
                        })
                    } else {
                        MarkdownToolbar { edit -> content = edit(content) }
                        OutlinedTextField(content, { content = if (it.text.length <= Notes.MAX_CONTENT) it else content },
                            Modifier.fillMaxWidth(), label = { Text("Note") }, minLines = 8,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                        Text("Markdown: **bold**, *italic*, # heading, - list, - [ ] checklist.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                    OutlinedTextField(notebook, { notebook = it.replace('\n', ' ').take(Notes.MAX_NOTEBOOK) }, Modifier.fillMaxWidth(),
                        label = { Text("Notebook (optional)") }, singleLine = true)
                    val others = notebooks.filter { it != notebook.trim() }
                    if (others.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        others.forEach { name -> FilterChip(selected = false, onClick = { notebook = name }, label = { Text(name) }) }
                    }
                    Text("Colour", style = MaterialTheme.typography.titleSmall)
                    ColorChoices(color) { color = it }
                    FilterChip(selected = pinned, onClick = { pinned = !pinned }, label = { Text(if (pinned) "Pinned to the top" else "Pin to the top") },
                        leadingIcon = if (pinned) ({ Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }) else null)
                    if (deletedElsewhere) Text("This note was deleted elsewhere. Copy anything you need before closing.", color = MaterialTheme.colorScheme.error)
                }
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                EditorActions(onDelete = if (saved != null && !deletedElsewhere) ({ delete() }) else null,
                    onClose = ::close, onSave = { save() }, deleteEnabled = !busy, closeEnabled = !busy,
                    saveEnabled = canSave && unsaved) { SaveLabel(busy, saved = justSaved && !unsaved) }
            }
        }
    }
    if (askingToSave) PlannerDialog("Save changes?", onDismissRequest = { askingToSave = false },
        primary = DialogAction("Save", enabled = canSave) { askingToSave = false; save(onDismiss) },
        dismiss = DialogAction("Keep editing") { askingToSave = false },
        extra = listOf(DialogAction("Discard", danger = true) { askingToSave = false; onDismiss() })) {
        Text("Your changes to this note haven't been saved.")
    }
}

// Bold, italic, strike, heading, list, checklist and code, applied to the selection (or where the cursor is).
@Composable
private fun MarkdownToolbar(apply: ((TextFieldValue) -> TextFieldValue) -> Unit) {
    fun wrap(mark: String): (TextFieldValue) -> TextFieldValue = { v ->
        Markdown.wrap(v.text, v.selection.start, v.selection.end, mark).let { v.copy(text = it.text, selection = TextRange(it.start, it.end)) }
    }
    fun prefix(mark: String): (TextFieldValue) -> TextFieldValue = { v ->
        Markdown.prefixLines(v.text, v.selection.start, v.selection.end, mark).let { v.copy(text = it.text, selection = TextRange(it.start, it.end)) }
    }
    val tools = listOf(
        Triple("B", "Bold", wrap("**")), Triple("I", "Italic", wrap("*")), Triple("S", "Strikethrough", wrap("~~")),
        Triple("H", "Heading", prefix("# ")), Triple("•", "Bulleted list", prefix("- ")),
        Triple("☐", "Checklist", prefix("- [ ] ")), Triple("</>", "Code", wrap("`")),
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tools.forEach { (label, name, edit) ->
            OutlinedButton(onClick = { apply(edit) }, modifier = Modifier.semantics { contentDescription = name }.defaultMinSize(minWidth = 44.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text(label, fontFamily = if (label == "</>") FontFamily.Monospace else null,
                    style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// No colour, then the card colours, as round swatches; the chosen one is ringed.
@Composable
internal fun ColorChoices(selected: Int?, onSelect: (Int?) -> Unit) {
    val ring = MaterialTheme.colorScheme.primary
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        (listOf<Int?>(null) + Notes.colors).forEachIndexed { index, argb ->
            val name = if (argb == null) "No colour" else NOTE_COLOR_NAMES[index - 1]
            val chosen = argb == selected
            Box(Modifier.size(40.dp).clip(CircleShape)
                .background(argb?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(if (chosen) 3.dp else 1.dp, if (chosen) ring else MaterialTheme.colorScheme.outline, CircleShape)
                .clickable(role = Role.RadioButton) { onSelect(argb) }
                .semantics { contentDescription = name; this.selected = chosen },
                contentAlignment = Alignment.Center) {
                if (chosen) Icon(Icons.Filled.Check, contentDescription = null, tint = if (argb == null) ring else Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}
