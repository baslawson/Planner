package com.example.itinerary.ui
import com.example.itinerary.ui.MatrixAssistChip as AssistChip
import com.example.itinerary.ui.MatrixFilterChip as FilterChip
import com.example.itinerary.ui.MatrixTextButton as TextButton
import com.example.itinerary.ui.MatrixOutlinedButton as OutlinedButton
import com.example.itinerary.ui.MatrixButton as Button

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.mutableIntStateOf

import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import com.example.itinerary.data.DraftCodec
import com.example.itinerary.data.EditorDraftStore
import com.example.itinerary.data.EventText
import org.json.JSONObject
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.itinerary.ItineraryApp
import com.example.itinerary.data.Bills
import com.example.itinerary.data.TemplateContent
import com.example.itinerary.data.EventSaveOptions
import com.example.itinerary.data.RepeatRule
import com.example.itinerary.data.seriesEditDates
import com.example.itinerary.data.overlappingEvents
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.example.itinerary.data.Attachment
import com.example.itinerary.data.Categories
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.Links
import com.example.itinerary.data.PlanColors
import com.example.itinerary.data.Reminder
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// The event as read from the database: [item] null once it is gone.
private class StoredEvent(val item: ItineraryItem?)

// One of two side-by-side choices: solid with a tick when it is the current one, an outline when it is not.
@Composable
private fun ModeButton(selected: Boolean, text: String, onClick: () -> Unit) {
    val state = Modifier.semantics { this.selected = selected }
    if (selected) {
        Button(onClick = onClick, modifier = state) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = state) { Text(text) }
    }
}

// The New event / Edit event form. Save stores the event and the form stays open on it as saved (a new event becomes
// one to edit, with Delete); Close leaves, first asking "Save changes?" when something is unsaved. [onSave] returns the
// id of the event it saved (Repository.saveItemId), which is read back here with its files and reminders as stored.
@Composable
fun ItemEditorSheet(
    initial: ItineraryItem,
    existingAttachments: List<Attachment>,
    existingReminders: List<Reminder>,
    categoryCounts: Map<String, Int>,
    hiddenCategories: Set<String>,
    onRemoveCategories: (Set<String>) -> Unit,
    onShowCategory: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: suspend (ItineraryItem, List<Attachment>, List<Attachment>, List<Reminder>, List<Reminder>, EventSaveOptions) -> Long,
    onDelete: suspend (ItineraryItem, Boolean) -> Unit,
    startWithScan: Boolean = false,
    startWithBillScan: Boolean = false,
    initialAddedReminders: List<Reminder> = emptyList(),
    initialRepeatCount: Int = 12,
    // A monthly series' day of the month from Quick entry (31: each month's last day); 0 = the date's own.
    initialRepeatAnchorDay: Int = 0,
    // Opened filled in from a share: unsaved until saved, so Close asks "Save changes?" rather than dropping it.
    prefilled: Boolean = false,
    checkCurrency: Boolean = false,
    // A new event's files to start with (a task's, when it is made into an event), and what to say above the form.
    initialAddedAttachments: List<Attachment> = emptyList(),
    notice: String? = null,
) {
    val repository = (LocalContext.current.applicationContext as ItineraryApp).repository
    // One open editor for the widget's wait (D10) for the whole visit, saves included.
    val windowEditors = LocalWindowEditors.current
    DisposableEffect(Unit) {
        EditorDraftStore.editorOpened(); windowEditors?.let { it.events++ }
        onDispose { EditorDraftStore.editorClosed(); windowEditors?.let { it.events-- } }
    }
    // After a Save: the event as stored (read in one transaction, like BillTaskEditor) and which save it was, so each
    // save starts the form afresh from it.
    var saved by remember { mutableStateOf<Pair<Int, Triple<ItineraryItem, List<Attachment>, List<Reminder>>>?>(null) }
    val onSaved: suspend (Long) -> Unit = { id ->
        val details = repository.eventDetails(id)
        if (details == null) onDismiss() else saved = (saved?.first ?: 0) + 1 to details
    }
    val current = saved
    if (current == null) ItemEditorForm(initial, existingAttachments, existingReminders, categoryCounts, hiddenCategories,
        onRemoveCategories, onShowCategory, onDismiss, onSave, onSaved, onDelete, startWithScan, startWithBillScan,
        initialAddedReminders, initialRepeatCount, initialRepeatAnchorDay = initialRepeatAnchorDay, prefilled = prefilled, checkCurrency = checkCurrency,
        initialAddedAttachments = initialAddedAttachments, notice = notice)
    else key(current.first) {
        val (item, attachments, reminders) = current.second
        ItemEditorForm(item, attachments, reminders, categoryCounts, hiddenCategories, onRemoveCategories, onShowCategory,
            onDismiss, onSave, onSaved, onDelete, initialRepeatCount = initialRepeatCount, justSaved = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ItemEditorForm(
    initial: ItineraryItem,
    existingAttachments: List<Attachment>,
    existingReminders: List<Reminder>,
    categoryCounts: Map<String, Int>,
    hiddenCategories: Set<String>,
    onRemoveCategories: (Set<String>) -> Unit,
    onShowCategory: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: suspend (ItineraryItem, List<Attachment>, List<Attachment>, List<Reminder>, List<Reminder>, EventSaveOptions) -> Long,
    onSaved: suspend (Long) -> Unit,
    onDelete: suspend (ItineraryItem, Boolean) -> Unit,
    startWithScan: Boolean = false,
    startWithBillScan: Boolean = false,
    initialAddedReminders: List<Reminder> = emptyList(),
    initialRepeatCount: Int = 12,
    initialRepeatAnchorDay: Int = 0,
    // Opened again right after a Save: the Save button says "Saved" until something changes.
    justSaved: Boolean = false,
    prefilled: Boolean = false,
    checkCurrency: Boolean = false,
    initialAddedAttachments: List<Attachment> = emptyList(),
    notice: String? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val store = remember { (context.applicationContext as ItineraryApp).attachmentStore }
    val draftStore = remember { EditorDraftStore(context) }
    val recovered = remember { runCatching { draftStore.read() }.getOrNull()?.takeIf {
        DraftCodec.item(it.getJSONObject("initial")) == initial
    } }
    val draftToken = remember { recovered?.optString("token")?.takeIf { it.isNotEmpty() } ?: java.util.UUID.randomUUID().toString() }
    val draft = remember { recovered?.getJSONObject("state") }
    val paymentBaseline = remember { com.example.itinerary.data.PaymentState.of(initial) }
    val values = remember { recovered?.getJSONObject("item")?.let(DraftCodec::item) ?: initial }
    var duplicating by remember { mutableStateOf(draft?.optBoolean("duplicating") ?: false) }
    val isNew = initial.id == 0L || duplicating
    var busy by remember { mutableStateOf(false) }
    var askingToSave by remember { mutableStateOf(false) }
    // Set by "Save changes?" → Save: leave once saved (also after a duplicate-bill warning) instead of editing on.
    var closeAfterSave by remember { mutableStateOf(false) }
    var askingMakeTask by remember { mutableStateOf(false) }
    var duplicateBills by remember { mutableStateOf<List<ItineraryItem>>(emptyList()) }
    var viewingDuplicate by remember { mutableStateOf<Long?>(null) }
    var readingText by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var repeat by remember { mutableStateOf(draft?.optString("repeat")?.let { RepeatRule.parse(it, complete = false) } ?: RepeatRule.parse(initial.repeatRule) ?: RepeatRule.NONE) }
    var repeatCount by remember { mutableStateOf(draft?.optString("count") ?: initialRepeatCount.toString()) }
    var entireSeries by remember { mutableStateOf(draft?.optBoolean("entireSeries") ?: false) }
    var deleting by remember { mutableStateOf(false) }
    val repository = remember { (context.applicationContext as ItineraryApp).repository }
    val allEvents by repository.allItems.collectAsStateWithLifecycle(initialValue = emptyList())
    // E10: the event as stored, watched while this form is open. Each form (the first, and each one after a Save or a
    // Reload) watches afresh from its own [initial], so its own save is never taken for a change from elsewhere.
    // Wrapped so "not read yet" (null) differs from "read: gone" (AG-2, deletedElsewhere).
    val storedRead by remember { if (initial.id == 0L) kotlinx.coroutines.flow.flowOf(StoredEvent(null))
        else repository.observeItem(initial.id).map(::StoredEvent) }.collectAsStateWithLifecycle(initialValue = null)
    val stored = storedRead?.item
    var askingStale by remember { mutableStateOf(false) }
    var askingReload by remember { mutableStateOf(false) }

    // Attachment changes are held here until Save, because a new event has no id to link to yet.
    // Files added during this edit are deleted again unless the edit is saved.
    val added = remember { mutableStateListOf<Attachment>().apply {
        if (recovered != null) addAll(DraftCodec.attachments(recovered.optJSONArray("added")))
        else if (initial.id == 0L) addAll(initialAddedAttachments)
    } }
    val removed = remember { mutableStateListOf<Attachment>().apply { addAll(DraftCodec.attachments(recovered?.optJSONArray("removed"))) } }
    // Files this form was handed rather than made (a task's, when it is made into an event): still the task's, so a
    // Discard or ✕ here mustn't delete them (TE-1). Kept in the draft for a form reopened after Android closed Planner.
    val inheritedFiles = remember {
        recovered?.optJSONArray("inherited")?.let { a -> List(a.length()) { a.getString(it) }.toSet() }
            ?: if (initial.id == 0L) initialAddedAttachments.mapTo(HashSet()) { it.fileName } else emptySet()
    }
    fun discardAddedFile(attachment: Attachment) {
        // Reading text changes metadata only; an existing/shared document still belongs to its saved event.
        if (existingAttachments.none { it.fileName == attachment.fileName } && attachment.fileName !in inheritedFiles) store.delete(attachment.fileName)
    }
    var committed by remember { mutableStateOf(value = false) }
    var disposed by remember { mutableStateOf(false) }
    // AG-2: the event is gone (a sync pull, another window). Save keeps the form as a new single event, like Duplicate
    // but with everything as typed; Delete and the series choice go. Not while this form saves or deletes it itself.
    val deletedElsewhere = !committed && !busy && EditorRules.deletedElsewhere(initial, storedRead != null, stored)
    var pendingPhoto by remember { mutableStateOf(draft?.optString("pendingPhoto")?.takeIf { it.isNotEmpty() }?.let(store::fileFor)) }
    var addingLink by remember { mutableStateOf(value = false) }
    val shownAttachments by remember(existingAttachments) {
        derivedStateOf {
            EditorRules.keptAttachments(existingAttachments, removed) + added
        }
    }

    // Reminders are held the same way; nothing is scheduled until Save.
    val addedReminders = remember { mutableStateListOf<Reminder>().apply {
        if (recovered != null) addAll(DraftCodec.reminders(recovered.optJSONArray("addedReminders")))
        else if (initial.id == 0L && initialAddedReminders.isNotEmpty()) addAll(initialAddedReminders)
        else if (initial.id == 0L && initial.category == "Bills" && existingReminders.isEmpty())
            add(Reminder(itemId = 0, amount = 3, unit = com.example.itinerary.data.ReminderUnit.DAYS))
    } }
    val removedReminders = remember { mutableStateListOf<Reminder>().apply { addAll(DraftCodec.reminders(recovered?.optJSONArray("removedReminders"))) } }
    // Longest lead time first, so the list keeps its order when a reminder is changed.
    val shownReminders by remember(existingReminders) {
        derivedStateOf {
            (EditorRules.keptReminders(existingReminders, removedReminders) + addedReminders)
                .sortedByDescending { it.offsetMinutes }
        }
    }

    val notifications = rememberNotificationState()

    // Android 13+ needs the user's permission before any notification can show.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(context, "Notifications are off, so reminders won't show", Toast.LENGTH_LONG).show()
        }
    }

    // B1: the draft is written off the main thread a moment after typing pauses. What is still waiting goes to disk at
    // once when the editor leaves and when Planner goes to the background, before Android might close it.
    DisposableEffect(Unit) { onDispose { disposed = true; runCatching { draftStore.flush() } } }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { runCatching { draftStore.flush() } }
    // S6-4: this form's files are kept from the unused-file clean-up while it is open, drafted or not (opened without an
    // edit there is no draft), so a Save after the event was deleted and emptied elsewhere still has them. While it
    // saves, only the files the saved event will have ([savingFiles]): what the save removes is then free for its clean-up.
    val fileHolder = remember { Any() }
    var savingFiles by remember { mutableStateOf<Set<String>?>(null) }
    val heldFiles by remember(existingAttachments) { derivedStateOf {
        savingFiles ?: ((existingAttachments + added + removed).mapNotNullTo(hashSetOf()) { it.fileName.takeIf(String::isNotBlank) } + listOfNotNull(pendingPhoto?.name))
    } }
    SideEffect { EditorDraftStore.holdFiles(fileHolder, heldFiles) }
    DisposableEffect(Unit) { onDispose { EditorDraftStore.releaseFiles(fileHolder) } }

    val takePhoto =rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val file = pendingPhoto
        pendingPhoto = null
        if (file != null && captured && file.length() > 0L) {
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
            added += Attachment(itemId = 0, name = "Photo $stamp", fileName = file.name, mimeType = "image/jpeg")
        } else {
            file?.delete()
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        // A camera result may have been lost with the old Activity. Recover its private output once.
        if (recovered != null) pendingPhoto?.let { file ->
            pendingPhoto = null
            if (file.length() > 0 && added.none { it.fileName == file.name })
                added += Attachment(itemId = 0, name = "Recovered photo", fileName = file.name, mimeType = "image/jpeg")
            else if (file.length() == 0L) file.delete()
        }
    }
    var scanningPdf by remember { mutableStateOf(
        if (draft?.has("scanningPdf") == true) draft.getBoolean("scanningPdf")
        else if ((startWithScan || startWithBillScan) && recovered == null) {
            File(context.filesDir, "draft-scan").deleteRecursively()
            true
        } else null
    ) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            readingText = true
            scope.launch {
                try {
                val attachment = store.import(uri)
                if (attachment != null) {
                    added += attachment
                } else {
                    Toast.makeText(context, "Couldn't read that file", Toast.LENGTH_SHORT).show()
                }
                } finally { readingText = false }
            }
        }
    }

    var title by remember { mutableStateOf(values.title) }
    var time by remember { mutableStateOf(values.startTime) }
    var checklist by remember { mutableStateOf(values.checklist) }
    var pickingEndTime by remember { mutableStateOf(false) }
    var durationText by remember { mutableStateOf(draft?.optString("durationText") ?: values.durationMinutes?.toString().orEmpty()) }
    var beforeText by remember { mutableStateOf(draft?.optString("beforeText") ?: values.bufferBeforeMinutes.toString()) }
    var afterText by remember { mutableStateOf(draft?.optString("afterText") ?: values.bufferAfterMinutes.toString()) }
    val before = beforeText.toIntOrNull() ?: 0
    val after = afterText.toIntOrNull() ?: 0
    val validBuffers = time == null || (before in 0..1440 && after in 0..1440)
    val duration = durationText.toIntOrNull()
    // A task's time block needs a start and a duration, so All day and No duration aren't offered for it.
    val timeBlock = isNew && !duplicating && initial.linkedTaskId != null
    val validDuration = time == null || durationText.isBlank() && !timeBlock || duration != null && duration in 1..1440
    var lastTimedTime by remember { mutableStateOf(draft?.optString("lastTimedTime")?.takeIf { it.isNotEmpty() }?.let(LocalTime::parse) ?: values.startTime ?: LocalTime.of(9, 0)) }
    var location by remember { mutableStateOf(values.location) }
    var notes by remember { mutableStateOf(values.notes) }
    // Undo and Redo of the typing in this form (wish list #2).
    val undo = rememberEditorUndo()
    Track(undo, "title", title) { title = it }
    Track(undo, "location", location) { location = it }
    Track(undo, "notes", notes) { notes = it }
    // The checklist as one field: typing in a row, adding, removing or ticking one, and a template's list are undone
    // together with the rest, so no step is left for a row that has gone (EU-3, EU-4).
    Track(undo, "checklist", undoChecklist(checklist), merge = ::checklistTyping) { checklist = undoChecklist(it) }
    var category by remember { mutableStateOf(values.category) }
    val billTask = category == "Bills"
    fun selectCategory(selected: String) {
        if (selected == "Bills" && category != selected && shownReminders.isEmpty()) {
            // Shown in Reminders below; no message.
            addedReminders += Reminder(itemId = 0, amount = 3, unit = com.example.itinerary.data.ReminderUnit.DAYS)
            val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needsPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        category = selected
    }

    var colorIndex by remember { mutableIntStateOf(values.colorIndex) }
    // A colour picked freely instead of one of the palette swatches (null = use the palette one).
    var customColor by remember { mutableStateOf(values.customColor) }
    var pickingColor by remember { mutableStateOf(value = false) }
    var date by remember { mutableStateOf(values.date) }
    // Last day of a multi-day event (all day, not a bill); kept while switching to a time so it can come back.
    var endDate by remember { mutableStateOf(values.endDate) }
    val spanEnd = EditorRules.spanEnd(date, endDate, time, category)
    var pickingRange by remember { mutableStateOf(value = false) }
    var pickingTime by remember { mutableStateOf(value = false) }
    var pickingDate by remember { mutableStateOf(value = false) }
    var addingCategory by remember { mutableStateOf(value = false) }
    var removingCategories by remember { mutableStateOf(value = false) }
    // Categories made up in this editor that aren't saved with an event yet, so aren't in the counts.
    var newCategories by remember { mutableStateOf(emptyList<String>()) }
    // Most used first. The one being chosen or just made up is included even before it has been saved.
    // Sorting the counts is worth doing only when they (or the chosen category) change, not on every
    // letter typed into the form.
    val categoryChips = remember(categoryCounts, hiddenCategories, newCategories, category) {
        Categories.ordered(categoryCounts, hiddenCategories).let { ordered ->
            ordered + (newCategories + category).asSequence().filter { it != Categories.OTHER && it !in ordered }.distinct().toList()
        }
    }

    var billAmountText by remember { mutableStateOf(draft?.optString("billAmountText") ?: Bills.input(values.billAmountMinor)) }
    var paymentLink by remember { mutableStateOf(values.paymentLink) }
    var paymentReference by remember { mutableStateOf(values.paymentReference) }
    var bpayBillerCode by remember { mutableStateOf(values.bpayBillerCode) }
    var bpayReference by remember { mutableStateOf(values.bpayReference) }
    var billCurrency by remember { mutableStateOf(values.billCurrency) }
    var payments by remember { mutableStateOf(values.payments) }
    val paidTotal = com.example.itinerary.data.Payments.total(payments)
    val validBillAmount = category != "Bills" ||
        (billAmountText.isBlank() && paidTotal == 0L || Bills.parse(billAmountText)?.let { it >= paidTotal } == true)
    var textPreview by remember { mutableStateOf<Attachment?>(null) }
    var billSuggestion by remember { mutableStateOf<Attachment?>(null) }
    // Persist the pending review with the draft, including scans containing multiple image pages.
    var billReviewFiles by remember { mutableStateOf(
        draft?.optJSONArray("billReviewFiles")?.let { files ->
            List(files.length()) { files.getString(it) }
        }.orEmpty()
    ) }
    var billReviewRequested by remember { mutableStateOf(draft?.optBoolean("billReviewRequested", false) ?: false) }
    val billReviewPages = added.filter { it.fileName in billReviewFiles }
    val scannedBillSuggestion = billReviewPages.firstOrNull()?.let { first ->
        first.copy(name = if (billReviewPages.size > 1) "Scanned bill (${billReviewPages.size} pages)" else first.name,
            recognizedText = billReviewPages.joinToString("\n") { it.recognizedText },
            textStatus = if (billReviewPages.any { it.textStatus != "READY" }) "PARTIAL" else "READY")
    }
    var paid by remember { mutableStateOf(values.paid) }
    fun discard() {
        draftStore.clear()
        added.forEach(::discardAddedFile)
        pendingPhoto?.delete()
        File(context.filesDir, "draft-scan").deleteRecursively()
        committed = true
        onDismiss()
    }
    // E10: the form again from the event as stored now (changed elsewhere); what was unsaved here is dropped, like
    // Discard. [onSaved] loads it, as after a Save.
    fun reload() {
        draftStore.clear()
        added.forEach(::discardAddedFile)
        pendingPhoto?.delete()
        File(context.filesDir, "draft-scan").deleteRecursively()
        committed = true
        scope.launch { withContext(NonCancellable) { onSaved(initial.id) } }
    }
    // Read inputs during composition so even a title-only edit invalidates this scope.
    // Reading them only inside SideEffect misses changes handled by a nested editor scope.
    val draftSnapshot by remember(existingAttachments, existingReminders) { derivedStateOf {
        if (committed) null else JSONObject().put("token", draftToken).put("initial", DraftCodec.item(initial))
                    .put("existingAttachments", DraftCodec.attachments(existingAttachments))
                    .put("existingReminders", DraftCodec.reminders(existingReminders))
                    .put("item", DraftCodec.item(values.copy(title = title, date = date, endDate = EditorRules.spanEnd(date, endDate, time, category), startTime = time,
                        location = location, notes = notes, category = category, colorIndex = colorIndex,
                        paymentLink = paymentLink, paymentReference = paymentReference, bpayBillerCode = bpayBillerCode, bpayReference = bpayReference,
                        customColor = customColor, checklist = checklist, paid = paid, payments = payments, billAmountMinor = Bills.parse(billAmountText), billCurrency = billCurrency)))
                    .put("added", DraftCodec.attachments(added.toList())).put("removed", DraftCodec.attachments(removed.toList()))
                    .put("inherited", org.json.JSONArray(inheritedFiles.toList()))
                    .put("addedReminders", DraftCodec.reminders(addedReminders.toList()))
                    .put("removedReminders", DraftCodec.reminders(removedReminders.toList()))
                    .put("state", JSONObject().put("duplicating", duplicating).put("repeat", repeat.name)
                        .put("billAmountText", billAmountText).put("count", repeatCount).put("entireSeries", entireSeries).put("durationText", durationText)
                        .put("beforeText", beforeText).put("afterText", afterText)
                        .put("lastTimedTime", lastTimedTime.toString()).put("pendingPhoto", pendingPhoto?.name).put("scanningPdf", scanningPdf).put("billReviewFiles", org.json.JSONArray(billReviewFiles)).put("billReviewRequested", billReviewRequested))
    } }
    val count = repeatCount.toIntOrNull()
    val creatingSeries = isNew || initial.seriesId == null
    // A series edit only while the series is there: an event deleted elsewhere is saved as a new single one.
    val seriesEdit = !isNew && !deletedElsewhere && entireSeries
    val changeRepeat = seriesEdit && repeat.name != initial.repeatRule
    // T16-3: Quick entry's month ends hold while its date and repeat do, as a task's repeatAnchorDay does.
    val anchorDay = if (date == initial.date && repeat.name == initial.repeatRule) initialRepeatAnchorDay else 0
    val validRepeat = repeat.valid && (!creatingSeries || repeat == RepeatRule.NONE || count != null && count in 2..365)
    val plannedDates = remember(date, repeat, count, isNew, seriesEdit, allEvents, anchorDay) { runCatching {
        when {
            creatingSeries -> repeat.dates(date, count?.coerceIn(1, 365) ?: 1, anchorDay)
            seriesEdit -> {
                val members = allEvents.filter { it.seriesId == initial.seriesId }.sortedBy { it.date }
                seriesEditDates(members.map { it.date }, java.time.temporal.ChronoUnit.DAYS.between(initial.date, date),
                    RepeatRule.parse(initial.repeatRule), changeRepeat, repeat)
            }
            else -> listOf(date)
        }
    }.getOrDefault(listOf(date)) }
    val clashes = remember(allEvents, plannedDates, time, duration, before, after, isNew, seriesEdit) {
        val excluded = if (isNew) emptySet() else if (seriesEdit && initial.seriesId != null) {
            allEvents.filter { it.seriesId == initial.seriesId }.mapTo(HashSet()) { it.id }
        } else setOf(initial.id)
        overlappingEvents(allEvents, plannedDates, time, duration?.takeIf { it in 1..1440 }, excluded, before.coerceIn(0, 1440), after.coerceIn(0, 1440))
    }

    fun currentItem(copy: Boolean = false): ItineraryItem = Bills.bpayForCurrency(initial.copy(
            id = if (copy) 0 else initial.id,
            linkedTaskId = if (copy) null else initial.linkedTaskId,
            paid = paid, skipped = !copy && initial.skipped,
            payments = payments,
            paymentLink = paymentLink.trim(), paymentReference = paymentReference.trim(),
            bpayBillerCode = bpayBillerCode.trim(), bpayReference = bpayReference.trim(),
            billAmountMinor = if (category == "Bills" || payments.isNotEmpty()) Bills.parse(billAmountText) else null, billCurrency = billCurrency,
            seriesId = if (copy) null else initial.seriesId,
            repeatRule = if (copy) "NONE" else initial.repeatRule,
            date = date, endDate = spanEnd, title = title.trim(), startTime = time,
            durationMinutes = if (time == null) null else duration,
            bufferBeforeMinutes = if (time == null || billTask) 0 else before,
            bufferAfterMinutes = if (time == null || billTask) 0 else after,
            checklist = checklist.map { it.copy(text = it.text.trim()) },
            location = location.trim(), notes = notes.trim(), category = category,
            colorIndex = colorIndex, customColor = customColor,
        ))
    // Unsaved changes: anything Save would store that differs from what this form opened with (the event as last saved,
    // or a new one as offered). Save greys out without them (a new event can still be saved as it is), and Close only
    // asks "Save changes?" with them.
    fun edit() = EditorRules.EventEdit(currentItem(), added.toList(), removed.toList(), shownReminders, repeat.name,
        if (creatingSeries && repeat != RepeatRule.NONE) repeatCount else "", duplicating, listOf(durationText, beforeText, afterText, billAmountText))
    val openedWith = remember { edit() }
    val unsaved = EditorRules.eventUnsaved(openedWith, edit(), recovered = recovered != null || prefilled)
    // Changed underneath this form (a sync pull): a banner offers Reload, and Save asks first. Not once this form has
    // saved (its own write) or is on its way out; a copy being made (Duplicate) isn't affected.
    val changedElsewhere = !committed && !duplicating && EditorRules.changedElsewhere(initial, stored)
    // The draft on disk holds only what is unsaved (a new event's too, as before), so a saved event leaves nothing to
    // recover; edits undone again clear it.
    val keepDraft = unsaved || isNew
    val canSave = title.isNotBlank() && validRepeat && validDuration && (billTask || validBuffers) && validBillAmount && !readingText &&
        checklist.all { it.text.isNotBlank() } && !busy
    // This holder is deliberately not Compose state: recording a successful write must not redraw the editor.
    val writtenDraft = remember { arrayOfNulls<JSONObject>(1) }
    val draftError = "Couldn't protect this draft. Keep the app open and save your event."
    // Observe the derived state during composition, not only inside SideEffect. Otherwise an edit
    // handled by a nested text-field scope would not schedule the parent's persistence effect.
    val snapshotToWrite = draftSnapshot
    SideEffect {
        val snapshot = snapshotToWrite
        if (snapshot != null && !committed) try {
            if (!keepDraft) { if (writtenDraft[0] != null) { draftStore.clear(); writtenDraft[0] = null } }
            else if (writtenDraft[0] !== snapshot) {
                // A write that fails says so as before, a moment later.
                draftStore.schedule(snapshot) { android.os.Handler(android.os.Looper.getMainLooper()).post { error = draftError } }
                writtenDraft[0] = snapshot
            }
        } catch (_: Exception) { error = draftError }
    }
    fun applyTemplate(content: TemplateContent) {
        val item = content.forDate(date)
        // ER-2: one Undo puts the form back as it was, not only its text. EX-1: a field changed again after the template
        // keeps that change; only one still as the template left it goes back (and Redo likewise).
        val kept = listOf(UndoKept({ paymentLink }) { paymentLink = it }, UndoKept({ paymentReference }) { paymentReference = it },
            UndoKept({ bpayBillerCode }) { bpayBillerCode = it }, UndoKept({ bpayReference }) { bpayReference = it },
            UndoKept({ beforeText }) { beforeText = it }, UndoKept({ afterText }) { afterText = it },
            UndoKept({ durationText }) { durationText = it }, UndoKept({ billAmountText }) { billAmountText = it },
            UndoKept({ time }) { time = it }, UndoKept({ lastTimedTime }) { lastTimedTime = it }, UndoKept({ endDate }) { endDate = it },
            UndoKept({ category }) { category = it }, UndoKept({ colorIndex }) { colorIndex = it }, UndoKept({ customColor }) { customColor = it },
            UndoKept({ billCurrency }) { billCurrency = it }, UndoKept({ paid }) { paid = it }, UndoKept({ repeat }) { repeat = it },
            UndoKept({ repeatCount }) { repeatCount = it }, UndoKept({ entireSeries }) { entireSeries = it })
        val wasText = listOf(title, location, notes, undoChecklist(checklist))
        val wasAdded = addedReminders.toList(); val wasRemoved = removedReminders.toList()
        paymentLink = item.paymentLink; paymentReference = item.paymentReference
        bpayBillerCode = item.bpayBillerCode; bpayReference = item.bpayReference
        // A time block keeps its own time and length when the template has none.
        title = item.title; time = item.startTime ?: time.takeIf { timeBlock }; lastTimedTime = item.startTime ?: LocalTime.of(9, 0); endDate = item.endDate
        location = item.location; notes = item.notes; category = item.category; colorIndex = item.colorIndex
        beforeText = item.bufferBeforeMinutes.toString(); afterText = item.bufferAfterMinutes.toString()
        customColor = item.customColor; durationText = item.durationMinutes?.toString() ?: durationText.takeIf { timeBlock }.orEmpty(); checklist = item.checklist
        if (payments.isEmpty()) { billAmountText = Bills.input(item.billAmountMinor); billCurrency = item.billCurrency; paid = false }
        repeat = content.repeat; repeatCount = EditorRules.templateCount(content.repeat, content.count, repeatCount); entireSeries = false
        addedReminders.clear(); removedReminders.clear(); removedReminders.addAll(existingReminders)
        addedReminders.addAll(content.reminders.map { it.copy(id = 0, itemId = 0, snoozedUntil = null) })
        kept.forEach { it.applied() }
        val nowAdded = addedReminders.toList(); val nowRemoved = removedReminders.toList()
        // EX-2: a template that changed nothing is no step.
        if (kept.none { it.changed } && wasText == listOf(title, location, notes, undoChecklist(checklist)) &&
            wasAdded == nowAdded && wasRemoved == nowRemoved) return
        undo.around(undo = {
            kept.forEach { it.back() }; undoShift(addedReminders, nowAdded, wasAdded); undoShift(removedReminders, nowRemoved, wasRemoved)
        }, redo = {
            kept.forEach { it.again() }; undoShift(addedReminders, wasAdded, nowAdded); undoShift(removedReminders, wasRemoved, nowRemoved)
        })
    }
    fun save(allowDuplicate: Boolean = false, allowStale: Boolean = false) {
        if (busy) return
        if (changedElsewhere && !allowStale) { askingStale = true; return }
        busy = true
        // The form is locked while it saves (see lockedWhile): Save stores it as it is now and then shows the saved
        // event, so anything typed in between would be lost.
        focusManager.clearFocus(force = true)
        error = null
        val copy = duplicating || deletedElsewhere
        val item = currentItem(copy)
        // S6-4: a copy never points at a file that is gone (a link has none).
        val attachmentsToAdd = if (copy) shownAttachments.filter { it.fileName.isBlank() || store.fileFor(it.fileName).exists() }
            .map { it.copy(id = 0, itemId = 0) } else added.toList()
        val remindersToAdd = if (copy) shownReminders.map { it.copy(id = 0, itemId = 0, snoozedUntil = null) } else addedReminders.toList()
        val attachmentsToRemove = if (copy) emptyList() else removed.toList()
        val remindersToRemove = if (copy) emptyList() else removedReminders.toList()
        val options = EventSaveOptions(if (creatingSeries || changeRepeat) repeat else RepeatRule.NONE,
            if (creatingSeries && repeat != RepeatRule.NONE) count!! else 1, seriesEdit, changeRepeat, draftToken,
            paymentBaseline = paymentBaseline.takeUnless { copy || isNew }, anchorDay = anchorDay,
            // Hunt 25 E4: a Duplicate starts unpaid; a copy of one deleted elsewhere keeps its paid state, sent already.
            copiesPaid = deletedElsewhere && !duplicating)
        scope.launch {
            withContext(NonCancellable) {
                try {
                    if (!allowDuplicate && item.category == "Bills" && item.billAmountMinor != null) {
                        val matches = repository.duplicateBills(item, options, initial.date)
                        if (disposed) return@withContext
                        if (matches.isNotEmpty()) {
                            duplicateBills = matches
                            return@withContext
                        }
                    }
                    // Only commit after the duplicate warning has been accepted, if needed.
                    committed = true
                    duplicateBills = emptyList()
                    // Until the saved event holds them, only the files it will have stay held (see heldFiles).
                    savingFiles = attachmentsToAdd.mapNotNullTo(hashSetOf()) { it.fileName.takeIf(String::isNotBlank) }
                        .also { EditorDraftStore.holdFiles(fileHolder, it) }
                    val savedId = onSave(item, attachmentsToAdd, attachmentsToRemove, remindersToAdd, remindersToRemove, options)
                    savingFiles = emptySet(); EditorDraftStore.releaseFiles(fileHolder)
                    draftStore.clear()
                    File(context.filesDir, "draft-scan").deleteRecursively()
                    // Save changes? → Save leaves; a plain Save goes on editing the event as stored. That form is new,
                    // with a new draft token, so its next save isn't taken for a retry of this one.
                    if (disposed) Unit
                    else if (closeAfterSave) onDismiss()
                    else onSaved(savedId)
                } catch (e: Exception) {
                    committed = false
                    savingFiles = null; EditorDraftStore.holdFiles(fileHolder, heldFiles)
                    closeAfterSave = false
                    error = EditorRules.saveError(e, billTask)
                } finally { busy = false }
            }
        }
    }
    fun delete(series: Boolean) {
        if (busy) return
        busy = true
        deleting = false
        scope.launch {
            try {
                withContext(NonCancellable) { onDelete(initial, series) }
                committed = true
                draftStore.clear()
                added.forEach(::discardAddedFile)
                pendingPhoto?.delete()
                onDismiss()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Couldn't delete the ${if (billTask) "bill" else "event"}. Please try again." }
            finally { busy = false }
        }
    }
    // Close (and Back) leaves at once when nothing is unsaved; otherwise it asks first.
    // Not once saved: this form is on its way out, and its added files now belong to the saved event.
    fun close() { if (committed) return; if (unsaved) askingToSave = true else discard() }
    BackHandler { if (!busy && !readingText) close() }
    val editorScroll = rememberScrollState()
    val checklistAnchor = remember { ChecklistAnchor() }

    if (scanningPdf == null) Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .undoKeys(undo, enabled = !busy)
        ) {
            ScrollHints(editorScroll, Modifier.weight(1f),
                overlay = { ChecklistJumpButton(checklist, checklistAnchor, editorScroll) }) { Column(
                Modifier
                    .lockedWhile(busy)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            HeadingText(
                if (billTask) { if (duplicating) "Duplicate bill" else if (isNew) "New bill task" else "Edit bill task" }
                else if (duplicating) "Duplicate event" else if (isNew) "New event" else "Edit event",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (isNew && !duplicating) notice?.let { ConversionNotice(it) }
            if (changedElsewhere) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                FlowRow(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(EditorRules.changedElsewhereBanner(billTask),
                        color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.align(Alignment.CenterVertically))
                    TextButton(enabled = !busy && !readingText, onClick = { if (unsaved) askingReload = true else reload() }) { Text("Reload") }
                }
            }
            TemplateActions(isNew = isNew, title = title, billTask = billTask, canApply = !busy && !readingText,
                enabled = !busy && !readingText && title.isNotBlank() && validBillAmount && validDuration && (billTask || validBuffers) && validRepeat && checklist.all { it.text.isNotBlank() },
                content = { TemplateContent(currentItem(), shownReminders, repeat,
                    if (repeat == RepeatRule.NONE) 1 else if (!creatingSeries) allEvents.count { it.seriesId == initial.seriesId }.coerceIn(2, 365) else count?.coerceIn(2, 365) ?: 12) }, onApply = ::applyTemplate)
            initial.linkedTaskId?.let { LinkedTaskSection(it) }
            if (initial.skipped && !isNew) Text("This occurrence is skipped. Restore it from its action menu to resume reminders.")
            if (!isNew) {
                TextButton(enabled = !busy, onClick = {
                    // A copy is a new event: what was typed before isn't undone into it (ER-3).
                    undo.reload()
                    duplicating = true; entireSeries = false; repeat = RepeatRule.NONE; paid = false; payments = emptyList(); checklist = checklist.map { it.copy(done = false) } }) {
                    Text(if (billTask) "Duplicate bill" else "Duplicate event")
                }
                // Wish list #1: the same event as a task instead. From what is saved, so nothing typed is lost.
                val conversions = LocalConversions.current
                if (conversions != null && !billTask && !duplicating && !deletedElsewhere) TextButton(enabled = !busy, onClick = {
                    if (unsaved) error = "Save or discard your changes first, then make it a task."
                    else if (initial.seriesId != null) askingMakeTask = true
                    else { conversions.eventToTask(initial.id, false); onDismiss() }
                }) { Text("Make it a task") }
                if (askingMakeTask) MakeTaskSeriesChoice(onChoose = { whole -> askingMakeTask = false; conversions?.eventToTask(initial.id, whole); onDismiss() },
                    onDismiss = { askingMakeTask = false })
            }
            if (recovered != null) Text("Unfinished draft recovered. Save to keep your changes.")
            if (duplicating) Text("Edit this copy, then Save to add it. The original is kept.")
            if (!isNew && !deletedElsewhere && initial.seriesId != null) {
                Text("${RepeatRule.parse(initial.repeatRule)?.label ?: "Repeating"} series")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !entireSeries, onClick = { entireSeries = false }, label = { Text(if (billTask) "This bill" else "This event") })
                    FilterChip(selected = entireSeries, onClick = { entireSeries = true }, label = { Text("Entire series") })
                }
                if (entireSeries) Text("Changes apply to every remaining occurrence, including earlier dates. Moving the date shifts all occurrences by the same number of days.")
            }
            // Saffron pill so the day being edited is easy to spot; tap it to pick another date.
            Text(
                spanEnd?.let { "${spanLabel(date, it)} ▾" } ?: "${if (billTask) "Due " else ""}${date.dayLabel(LocalDateFormat.current)} ▾",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable(role = Role.Button, onClickLabel = if (billTask) "Change due date" else if (spanEnd != null) "Change dates" else "Change date") {
                        if (spanEnd != null) pickingRange = true else pickingDate = true }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
            // Titles used before, as you type: picking one fills in what went with it last time where nothing is entered
            // yet (an event's place and category; a bill's payee, amount and currency).
            // Prepared once per change to the events (hunt 21 S5), so each letter only filters, not every event again.
            val history = remember(allEvents) { com.example.itinerary.data.EntryHistory.prepare(allEvents) }
            val pastTitles = remember(title, history, category) {
                history.titles(title, bills = category == "Bills", except = initial.id.takeIf { it != 0L })
            }
            // A pick is one Undo step: the title and what it filled in (hunt 21 S4), the way a template is.
            fun pickTitle(t: String) {
                val past = pastTitles.firstOrNull { it.title == t }
                val kept = listOf(UndoKept({ category }) { category = it }, UndoKept({ billAmountText }) { billAmountText = it },
                    UndoKept({ billCurrency }) { billCurrency = it })
                // S8: within the editor's limits, as if typed.
                title = t.take(EventText.MAX_TITLE)
                if (past != null) {
                    if (location.isBlank()) location = past.location.take(EventText.MAX_LOCATION)
                    // S6: a new event takes the category it had last time; a saved one keeps its own.
                    if (initial.id == 0L && category == initial.category && past.category != category && past.category != "Bills" && category != "Bills") category = past.category
                    // S2: the amount and its currency together, only when the amount was still empty; a currency chosen
                    // (or an amount typed) stays.
                    if (category == "Bills" && billAmountText.isBlank() && past.amountMinor != null) {
                        billAmountText = Bills.input(past.amountMinor)
                        if (payments.isEmpty()) billCurrency = past.currency
                    }
                }
                kept.forEach { it.applied() }
                if (kept.any { it.changed }) undo.around(undo = { kept.forEach { it.back() } }, redo = { kept.forEach { it.again() } })
            }
            SuggestField(
                value = title,
                onValueChange = { title = EventText.typed(title, it.replace('\n', ' '), EventText.MAX_TITLE) },
                label = if (billTask) "Bill title" else "What are you doing?",
                suggestions = pastTitles.map { it.title },
                shown = { t -> pastTitles.firstOrNull { it.title == t }?.location?.takeIf { it.isNotBlank() }?.let { "$t · $it" } ?: t },
                onPick = ::pickTitle,
                enabled = !busy,
                // Grows as the text wraps; Done closes the keyboard instead of adding a line break.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                singleLine = false,
            )
            if (category == "Bills") {
                OutlinedTextField(value = billAmountText, readOnly = busy, onValueChange = {
                    billAmountText = it.take(16)
                    if (payments.isNotEmpty()) paid = payments.any { p -> !p.reversed } && Bills.parse(billAmountText)?.let { n -> paidTotal >= n } == true
                },
                    label = { Text("Bill amount (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    isError = !validBillAmount, supportingText = { if (!validBillAmount) Text("Enter an amount with up to two decimal places, covering recorded payments.") },
                    modifier = Modifier.fillMaxWidth())
                SettingsDropdown(label = "Currency", current = billCurrency, options = if (payments.isEmpty()) Bills.currencies else listOf(billCurrency),
                    onSelect = { billCurrency = it }, entry = { Text(it) })
                if (checkCurrency && billCurrency == initial.billCurrency)
                    Text("Check the currency: the text gave only a \$ sign.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(checked = paid, onCheckedChange = {
                        try {
                            val updated = com.example.itinerary.data.Payments.setPaid(currentItem(), it)
                            paid = updated.paid; payments = updated.payments
                        } catch (e: com.example.itinerary.data.PaymentUpdateException) { error = e.message }
                    })
                    BillStatus(paid, style = MaterialTheme.typography.bodyLarge)
                }
                if (paid) Text("Reminders stopped on Save", style = MaterialTheme.typography.bodySmall)
                Text("Paid status applies only to this occurrence.", style = MaterialTheme.typography.bodySmall)
                BillPaymentDetails(paymentLink, paymentReference, bpayBillerCode, bpayReference, Bills.hasBpay(billCurrency),
                    onLink = { paymentLink = it }, onReference = { paymentReference = it },
                    onBiller = { bpayBillerCode = it }, onBpayReference = { bpayReference = it })
                PaymentsSection(Bills.parse(billAmountText), billCurrency, paid, payments) { changed ->
                    payments = changed
                    paid = changed.any { !it.reversed } && Bills.parse(billAmountText)?.let { com.example.itinerary.data.Payments.total(changed) >= it } == true
                }
                if (payments.isNotEmpty()) Text("Currency is fixed while payment history exists.", style = MaterialTheme.typography.bodySmall)
            }
            // Wraps onto more lines rather than scrolling, so the Other button is always in view.
            if (!billTask) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                categoryChips.filterNot { it == "Bills" }.forEach { c ->
                    FilterChip(
                        selected = category == c,
                        // Tapping the chosen category again clears it, which means Other.
                        onClick = { selectCategory(if (category == c) Categories.OTHER else c) },
                        label = { Text(c) },
                    )
                }
                AssistChip(
                    onClick = { addingCategory = true },
                    label = { Text("Other") },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                )
                if (categoryChips.isNotEmpty()) {
                    AssistChip(
                        onClick = { removingCategories = true },
                        label = { Text("Remove…") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                    )
                }
            }
            // Colours the event's bar and title in the day's list, so events can be told apart.
            HeadingText("Colour", style = MaterialTheme.typography.titleSmall)
            PlanColorPicker(
                selected = colorIndex,
                onSelect = { colorIndex = it; customColor = null },
                custom = customColor?.let { Color(it) },
                onPickCustom = { pickingColor = true },
                // Offer every colour used by automatic selection; the swatches wrap as needed.
                count = PlanColors.EVENT_COUNT,
            )
            // Keep the last chosen time when switching temporarily to all-day.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val allDay = time == null
                if (!timeBlock) Row(
                    modifier = Modifier
                        .toggleable(
                            value = allDay,
                            role = Role.Switch,
                            onValueChange = { enabled ->
                                if (enabled) {
                                    time?.let { lastTimedTime = it }
                                    time = null
                                } else {
                                    time = lastTimedTime
                                }
                            },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(if (billTask) "No due time" else "All day", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = allDay, onCheckedChange = null)
                }
                // An all-day event can span several days ("Trip, 3 Oct – 7 Oct"): on picks the dates, off makes it one day.
                if (allDay && !billTask) Row(
                    modifier = Modifier
                        .toggleable(value = spanEnd != null, role = Role.Switch,
                            onValueChange = { on -> if (on) pickingRange = true else endDate = null })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Multiple days", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = spanEnd != null, onCheckedChange = null)
                }
                if (!allDay) {
                    ModeButton(
                        selected = true,
                        text = time!!.label(LocalTimeFormat.current, context),
                    ) { pickingTime = true }
                }
            }
            if (time != null && !billTask) {
                OutlinedButton(onClick = { pickingEndTime = true }) {
                    Text(if (duration != null && duration in 1..1440) "End time: ${time!!.plusMinutes(duration.toLong()).label(LocalTimeFormat.current, context)}" else "Set end time")
                }
                OutlinedTextField(
                    value = durationText,
                    readOnly = busy,
                    onValueChange = { durationText = it.filter(Char::isDigit).take(4) },
                    label = { Text(if (timeBlock) "Duration in minutes" else "Duration in minutes (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    singleLine = true,
                    isError = !validDuration,
                    supportingText = { Text(if (!validDuration) "Enter 1–1440 minutes${if (timeBlock) "" else ", or leave blank"}" else
                        duration?.let { eventEndLabel(date, time, it, LocalTimeFormat.current, context) }.orEmpty()) },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOfNotNull((null to "No duration").takeIf { !timeBlock }, 30 to "30 min", 60 to "1 hour", 120 to "2 hours").forEach { (minutes, label) ->
                        FilterChip(selected = durationText == minutes?.toString().orEmpty(),
                            onClick = { durationText = minutes?.toString().orEmpty() }, label = { Text(label) })
                    }
                }
                Text("Travel and preparation buffers", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(beforeText, { beforeText = it.filter(Char::isDigit).take(4) },
                        label = { Text("Before (min)") }, readOnly = busy, singleLine = true, isError = before !in 0..1440,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(afterText, { afterText = it.filter(Char::isDigit).take(4) },
                        label = { Text("After (min)") }, readOnly = busy, singleLine = true, isError = after !in 0..1440,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                Text("Reserve 0–1440 minutes on each side for travel or preparation. Included in clashes and free time; reminders keep the event's start time.", style = MaterialTheme.typography.bodySmall)
                if ((before > 0 || after > 0) && duration == null)
                    Text("Set an end time for precise buffers. Without one, clashes use the start time and free time uses your chosen default duration.", style = MaterialTheme.typography.bodySmall)
            }
            if (creatingSeries || seriesEdit) {
                SettingsDropdown(
                    label = "Repeat",
                    current = repeat.label,
                    options = repeatChoices(date),
                    onSelect = { repeat = if (it.kind == repeat.kind) repeat else it; repeatCount = EditorRules.countForRepeat(repeat, repeatCount) },
                    entry = { Text(if (it.kind in RepeatRule.customKinds) it.kind.choiceLabel() else it.label) },
                )
                RepeatDetails(repeat, enabled = !busy) { repeat = it }
                if (repeat != RepeatRule.NONE && creatingSeries) {
                    OutlinedTextField(
                        value = repeatCount,
                        readOnly = busy,
                        onValueChange = { value -> repeatCount = value.filter(Char::isDigit).take(3) },
                        label = { Text(if (billTask) "Bills (including this one)" else "Occurrences (including this event)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        singleLine = true,
                        isError = !validRepeat,
                        supportingText = { Text(if (validRepeat) "Last occurrence: ${plannedDates.last().dayLabel(LocalDateFormat.current)}" else "Enter a number from 2 to 365") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (repeat == RepeatRule.WEEKDAYS) Text("Monday to Friday. A weekend start moves to the following Monday.")
                    if (repeat == RepeatRule.MONTHLY) Text("Shorter months use their last day, then return to this day of the month.")
                    if (repeat == RepeatRule.YEARLY && date.monthValue == 2 && date.dayOfMonth == 29)
                        Text("Uses 28 February in non-leap years, then returns to 29 February in leap years.")
                }
            }
            if (changeRepeat) Text(if (repeat == RepeatRule.NONE)
                "All saved occurrences will become separate ${if (billTask) "bills" else "events"}. Their dates are kept."
                else "Keeps all ${plannedDates.size} saved occurrences and spaces them ${repeat.label.replaceFirstChar { it.lowercase() }} from ${plannedDates.firstOrNull()?.dayLabel(LocalDateFormat.current).orEmpty()}.")
            if (category != "Bills" && payments.isNotEmpty()) {
                Text("Payment history", style = MaterialTheme.typography.titleMedium)
                Text("Recorded payments are kept when you change the category.", style = MaterialTheme.typography.bodySmall)
                PaymentSummary(Bills.parse(billAmountText), billCurrency, paid, payments)
                PaymentHistory(payments, billCurrency, onReverse = null)
            }
            if (!billTask && clashes.isNotEmpty()) {
                Text(
                    "${if (duration == null && clashes.all { it.durationMinutes == null }) "Conflicts with (including buffers)" else "Overlaps with (including buffers)"} ${clashes.take(3).joinToString { it.title }}${if (clashes.size > 3) " and ${clashes.size - 3} more" else ""}. You can still save.",
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            // Places (and payees) used before, as you type.
            val pastPlaces = remember(location, history) { history.locations(location) }
            SuggestField(
                value = location,
                onValueChange = { location = EventText.typed(location, it.replace('\n', ' '), EventText.MAX_LOCATION) },
                label = if (billTask) "Payee / location (optional)" else "Location",
                suggestions = pastPlaces,
                onPick = { location = it.take(EventText.MAX_LOCATION) },
                enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                singleLine = false,
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = EventText.typed(notes, it, EventText.MAX_NOTES) },
                readOnly = busy,
                label = { Text("Notes") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            if (readingText) Text("Reading document text…")
            AttachmentsSection(
                attachments = shownAttachments,
                readingText = readingText,
                onReadText = { attachment ->
                    if (!readingText) scope.launch {
                        readingText = true
                        try {
                            val indexed = com.example.itinerary.scanner.DocumentText.index(attachment, store)
                            val index = added.indexOf(attachment)
                            if (index >= 0) added[index] = indexed
                            else if (removed.none { it.id == attachment.id }) { removed.add(attachment); added.add(indexed.copy(id = 0, itemId = 0)) }
                        } finally { readingText = false }
                    }
                },
                onViewText = { textPreview = it },
                // A task's time block can't be a bill (D9), so it isn't offered bill details.
                onSuggestBill = if (initial.linkedTaskId != null && !duplicating) null else { attachment -> billSuggestion = attachment },
                store = store,
                onTakePhoto = {
                    val file = store.newPhotoFile()
                    pendingPhoto = file
                    try {
                        takePhoto.launch(store.uriFor(file.name))
                    } catch (_: ActivityNotFoundException) {
                        pendingPhoto = null
                        Toast.makeText(context, "No camera app available", Toast.LENGTH_SHORT).show()
                    }
                },
                onAttachFile = { pickFile.launch(arrayOf("*/*")) },
                onAddLink = { addingLink = true },
                onScanDocument = {
                    File(context.filesDir, "draft-scan").deleteRecursively()
                    scanningPdf = true
                },
                onRemove = { attachment ->
                    if (attachment in added) {
                        added.remove(attachment)
                        discardAddedFile(attachment)
                    } else {
                        removed += attachment
                    }
                },
                onOpen = { openAttachment(context, store, it) },
            )
            ChecklistSection(checklist, onChange = { checklist = it }, anchor = checklistAnchor)
            RemindersSection(
                billTask = billTask,
                eventDate = date,
                eventTime = time,
                reminders = shownReminders,
                notificationsOn = notifications.enabled,
                onEnableNotifications = notifications.enable,
                onAdd = { amount, unit ->
                    val candidate = Reminder(itemId = 0, amount = amount, unit = unit)
                    // Calendar days and elapsed hours can differ across a clock change.
                    if (shownReminders.none { it.scheduleKey == candidate.scheduleKey }) {
                        addedReminders += candidate
                        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        if (needsPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onRemove = { reminder ->
                    if (reminder in addedReminders) addedReminders.remove(reminder) else removedReminders += reminder
                },
                onSound = { reminder, sound ->
                    val changed = reminder.copy(ringUntilDismissed = sound.ring, ringSeconds = sound.seconds)
                    val index = addedReminders.indexOf(reminder)
                    if (index >= 0) {
                        addedReminders[index] = changed
                    } else {
                        // A saved reminder's changed copy keeps its id: Save updates it in place, keeping its snooze.
                        removedReminders += reminder
                        addedReminders += changed
                    }
                },
                onChange = { reminder, amount, unit ->
                    // Another time for the reminder, keeping its sound: saved as a new reminder in place of it (Save cancels
                    // the old alarm and sets the new one; a snooze ends). Nothing changes if another one is already at that time.
                    val moved = Reminder(itemId = 0, amount = amount, unit = unit,
                        ringUntilDismissed = reminder.ringUntilDismissed, ringSeconds = reminder.ringSeconds)
                    if (shownReminders.none { it != reminder && it.scheduleKey == moved.scheduleKey } && moved.scheduleKey != reminder.scheduleKey) {
                        addedReminders.remove(reminder)
                        if (reminder.id != 0L && removedReminders.none { it.id == reminder.id }) removedReminders += reminder
                        // Hunt 22 P3: back to a saved reminder's own time (changed away and back): that reminder again, with
                        // its record, snooze and shown notification, not a copy (Save would delete the original).
                        val original = existingReminders.firstOrNull { saved -> saved.scheduleKey == moved.scheduleKey && removedReminders.any { it.id == saved.id } }
                        if (original == null) addedReminders += moved
                        else {
                            removedReminders.removeAll { it.id == original.id }
                            if (original.ringUntilDismissed != moved.ringUntilDismissed || original.ringSeconds != moved.ringSeconds) {
                                // Its sound as chosen: changed in place, as the Sound choice does.
                                removedReminders += original
                                addedReminders += original.copy(ringUntilDismissed = moved.ringUntilDismissed, ringSeconds = moved.ringSeconds)
                            }
                        }
                    }
                },
            )
            } } // Close the inner scrollable Column

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
            if (deletedElsewhere) Text(EditorRules.deletedElsewhereNote(billTask), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            EditorActions(
                onDelete = if (isNew || deletedElsewhere) null else ({ if (deleteAsks(billTask, initial.seriesId != null) || unsaved) deleting = true else delete(false) }),
                onClose = ::close, onSave = { save() },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).padding(bottom = 8.dp),
                deleteEnabled = !busy && !readingText, closeEnabled = !busy && !readingText,
                saveEnabled = canSave && (unsaved || isNew || deletedElsewhere), undo = undo, undoEnabled = !busy,
            ) { SaveLabel(busy, saved = justSaved && !unsaved) }
        }
    }

    if (duplicateBills.isNotEmpty()) PlannerDialog("Possible duplicate bill",
        onDismissRequest = { if (!busy) { duplicateBills = emptyList(); closeAfterSave = false } },
        primary = DialogAction("Save anyway", enabled = !busy) { save(allowDuplicate = true, allowStale = true) },
        dismiss = DialogAction("Go back", enabled = !busy) { duplicateBills = emptyList(); closeAfterSave = false },
    ) {
        Text("A bill with the same title, amount and due date already exists:")
        duplicateBills.forEach { bill ->
            Text("${bill.title} · ${Bills.format(bill.billAmountMinor!!, bill.billCurrency)} · ${bill.date.fullLabel()}")
            TextButton(enabled = !busy, onClick = { viewingDuplicate = bill.id }) { Text("Open existing bill") }
        }
    }

    if (askingToSave) PlannerDialog("Save changes?",
        onDismissRequest = { askingToSave = false },
        primary = DialogAction("Save", enabled = canSave) { askingToSave = false; closeAfterSave = true; save() },
        dismiss = DialogAction("Keep editing") { askingToSave = false },
        extra = listOf(DialogAction("Discard", danger = true) { askingToSave = false; discard() }),
    ) {
        Text(if (canSave || busy) "This ${if (billTask) "bill" else "event"} has changes that aren't saved yet."
            else "This ${if (billTask) "bill" else "event"} has changes that can't be saved as they are. Keep editing to fix them, or discard them.")
    }

    // E10: Save while the event changed underneath: keep this version (written over the stored one), or reload that.
    if (askingStale) PlannerDialog("Changed elsewhere",
        onDismissRequest = { askingStale = false; closeAfterSave = false },
        primary = DialogAction("Save anyway", enabled = !busy) { askingStale = false; save(allowStale = true) },
        dismiss = DialogAction("Reload", enabled = !busy) { askingStale = false; closeAfterSave = false; reload() },
    ) {
        Text("This ${if (billTask) "bill" else "event"} changed since you opened it, on Nextcloud or elsewhere in Planner. Save your version anyway?")
        Text("Reload shows it as it is now, without your unsaved changes.", style = MaterialTheme.typography.bodySmall)
    }
    if (askingReload) PlannerDialog("Reload?",
        onDismissRequest = { askingReload = false },
        primary = DialogAction("Reload", danger = true, enabled = !busy) { askingReload = false; reload() },
        dismiss = DialogAction("Keep editing") { askingReload = false },
    ) {
        Text("Your unsaved changes to this ${if (billTask) "bill" else "event"} will be lost.")
    }

    viewingDuplicate?.let { ExistingBillDialog(it) { viewingDuplicate = null } }

    textPreview?.let { attachment ->
        PlannerDialog("Recognised text", { textPreview = null }, dismiss = DialogAction("Close") { textPreview = null }) {
            Text("Recognition can make mistakes. Check the original document.", style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.text.selection.SelectionContainer { Text(attachment.recognizedText) }
        }
    }
    if (deleting) DeleteEventDialog(bill = billTask, repeating = initial.seriesId != null, onDismiss = { deleting = false }, onDelete = ::delete,
        unsaved = unsaved)

    if (scannedBillSuggestion != null && !billReviewRequested && billSuggestion == null) {
        PlannerDialog("Read details from this scan?",
            onDismissRequest = { billReviewFiles = emptyList() },
            primary = DialogAction("Read details") { billReviewRequested = true },
            dismiss = DialogAction("Not now") { billReviewFiles = emptyList() },
        ) {
            Text("Review suggested bill details before applying them. Your scan stays attached either way.")
        }
    }
    (billSuggestion ?: scannedBillSuggestion?.takeIf { billReviewRequested })?.let { attachment ->
        BillSuggestionDialog(attachment, title, billAmountText, billCurrency, bpayBillerCode,
            onDismiss = { billSuggestion = null; billReviewFiles = emptyList(); billReviewRequested = false }, onApply = { suggestedTitle, suggestedDate, suggestedAmount, suggestedCurrency, suggestedBpay ->
                undo.together()
                suggestedTitle?.let { title = it }
                suggestedDate?.let { date = it }
                suggestedAmount?.let {
                    if (payments.isEmpty() || suggestedCurrency == billCurrency) {
                        billAmountText = Bills.input(it); billCurrency = suggestedCurrency
                        if (payments.isNotEmpty()) paid = payments.any { p -> !p.reversed } && paidTotal >= it
                    }
                    else error = "The scanned currency differs from the bill's payment history. Check the amount manually."
                }
                // Offered only for an AUD bill (the dialog checks), so Save keeps them (Bills.bpayForCurrency). Hunt 23: unless
                // the scan's currency was refused above, leaving the bill in one without BPAY: then they aren't taken either.
                suggestedBpay?.let { (biller, reference) ->
                    if (Bills.hasBpay(billCurrency)) { bpayBillerCode = biller; bpayReference = reference }
                    else if (error == null) error = "BPAY is for AUD bills only, so the scanned BPAY numbers weren't used."
                }
                selectCategory("Bills")
                billSuggestion = null
                billReviewFiles = emptyList()
                billReviewRequested = false
            })
    }
    scanningPdf?.let { pdf ->
        ManualDocumentScanner(pdf, store, durable = true, onDismiss = { scanningPdf = null }, onComplete = { attachments ->
            added.addAll(attachments)
            billReviewRequested = false
            if (category == "Bills") billReviewFiles = attachments.map { it.fileName }
            scanningPdf = null
        })
    }

    if (addingLink) {
        AddLinkDialog(
            onDismiss = { addingLink = false },
            onConfirm = { name, url ->
                // Held until Save like the other attachments; a link has no file, so its file name is empty. One already
                // there (shown in Attachments) isn't added twice.
                if (shownAttachments.none { it.url == url })
                    added += Attachment(itemId = 0, name = name, fileName = "", mimeType = Links.MIME_TYPE, url = url)
                addingLink = false
            },
        )
    }
    if (pickingColor) {
        CustomColorDialog(
            // Starts from what the event is showing now.
            initial = customColor?.let { Color(it) } ?: planColor(colorIndex).color,
            onDismiss = { pickingColor = false },
            onConfirm = { customColor = it.toArgb(); pickingColor = false },
        )
    }
    if (addingCategory) {
        CustomCategoryDialog(
            inUse = categoryChips,
            onDismiss = { addingCategory = false },
            onConfirm = { name ->
                selectCategory(name)
                when {
                    // A built-in the user had removed comes back when its name is typed again.
                    name in Categories.BUILT_IN -> onShowCategory(name)
                    name != Categories.OTHER -> newCategories += name
                }
                addingCategory = false
            },
        )
    }
    if (removingCategories) {
        RemoveCategoriesDialog(
            options = categoryChips.filterNot { it == "Bills" }.map { it to (categoryCounts[it] ?: 0) },
            onDismiss = { removingCategories = false },
            onRemove = { names ->
                onRemoveCategories(names)
                // The event being edited may have had one of them.
                if (category in names) category = Categories.OTHER
                newCategories -= names
                removingCategories = false
            },
        )
    }
    if (pickingDate) {
        SingleDateDialog(
            initial = date,
            onDismiss = { pickingDate = false },
            // A span held while the event is timed moves with the start, keeping its length.
            onConfirm = { endDate = EditorRules.movedEndDate(date, it, endDate); date = it; pickingDate = false },
        )
    }
    if (pickingRange) {
        DateRangeDialog(
            start = date,
            end = spanEnd ?: date.plusDays(1),
            onDismiss = { pickingRange = false },
            onConfirm = { start, end ->
                date = start
                // One day chosen = a single-day event; longer than the limit is cut to it.
                endDate = end.takeIf { it > start }?.let { minOf(it, start.plusDays((com.example.itinerary.data.MultiDay.MAX_DAYS - 1).toLong())) }
                pickingRange = false
            },
        )
    }
    if (pickingEndTime && time != null) {
        EndTimeDialog(start = time!!, initial = time!!.plusMinutes((duration?.takeIf { it in 1..1440 } ?: 60).toLong()),
            onDismiss = { pickingEndTime = false },
            onConfirm = { durationText = it.toString(); pickingEndTime = false })
    }
    if (pickingTime) {
        TimePickerDialog(
            initial = time ?: LocalTime.of(9, 0),
            onDismiss = { pickingTime = false },
            onConfirm = { time = it; lastTimedTime = it; pickingTime = false },
        )
    }
}

// Touches don't reach the form while [locked] (an event saving): taps on fields, dates, chips and attachments do nothing.
// Its text fields are also read-only then, which stops text arriving another way (a screen reader, autofill).
internal fun Modifier.lockedWhile(locked: Boolean): Modifier = if (!locked) this else pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
}

// A Save button's words: "Saving…" while it saves, "✓ Saved" once it has and nothing has changed since, else "Save". A
// screen reader announces the change, as it did the old "Saved" message.
@Composable
internal fun SaveLabel(busy: Boolean, saved: Boolean) {
    Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        if (saved && !busy) { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
        Text(if (busy) "Saving…" else if (saved) "Saved" else "Save")
    }
}
