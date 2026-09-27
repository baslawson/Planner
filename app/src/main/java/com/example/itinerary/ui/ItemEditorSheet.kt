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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.SideEffect
import com.example.itinerary.data.DraftCodec
import com.example.itinerary.data.EditorDraftStore
import org.json.JSONObject
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
import com.example.itinerary.data.PlanEvent
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
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

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

// The New event / Edit event form.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    onSave: suspend (ItineraryItem, List<Attachment>, List<Attachment>, List<Reminder>, List<Reminder>, EventSaveOptions) -> Unit,
    onDelete: suspend (ItineraryItem, Boolean) -> Unit,
    startWithScan: Boolean = false,
    startWithBillScan: Boolean = false,
    initialAddedReminders: List<Reminder> = emptyList(),
    initialRepeatCount: Int = 12,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
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
    var duplicateBills by remember { mutableStateOf<List<ItineraryItem>>(emptyList()) }
    var viewingDuplicate by remember { mutableStateOf<Long?>(null) }
    var readingText by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var repeat by remember { mutableStateOf(draft?.optString("repeat")?.let(RepeatRule::valueOf) ?: RepeatRule.entries.find { it.name == initial.repeatRule } ?: RepeatRule.NONE) }
    var repeatCount by remember { mutableStateOf(draft?.optString("count") ?: initialRepeatCount.toString()) }
    var entireSeries by remember { mutableStateOf(draft?.optBoolean("entireSeries") ?: false) }
    var deleting by remember { mutableStateOf(false) }
    val repository = remember { (context.applicationContext as ItineraryApp).repository }
    val allEvents by repository.allItems.collectAsStateWithLifecycle(initialValue = emptyList())

    // Attachment changes are held here until Save, because a new event has no id to link to yet.
    // Files added during this edit are deleted again unless the edit is saved.
    val added = remember { mutableStateListOf<Attachment>().apply { addAll(DraftCodec.attachments(recovered?.optJSONArray("added"))) } }
    val removed = remember { mutableStateListOf<Attachment>().apply { addAll(DraftCodec.attachments(recovered?.optJSONArray("removed"))) } }
    fun discardAddedFile(attachment: Attachment) {
        // Reading text changes metadata only; an existing/shared document still belongs to its saved event.
        if (existingAttachments.none { it.fileName == attachment.fileName }) store.delete(attachment.fileName)
    }
    var committed by remember { mutableStateOf(value = false) }
    var disposed by remember { mutableStateOf(false) }
    var pendingPhoto by remember { mutableStateOf(draft?.optString("pendingPhoto")?.takeIf { it.isNotEmpty() }?.let(store::fileFor)) }
    var addingLink by remember { mutableStateOf(value = false) }
    val shownAttachments by remember(existingAttachments) {
        derivedStateOf {
            existingAttachments.filter { it !in removed } + added
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
            (existingReminders.filter { it !in removedReminders } + addedReminders)
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

    DisposableEffect(Unit) {
        onDispose { disposed = true }
    }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
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
    val validDuration = time == null || durationText.isBlank() || duration != null && duration in 1..1440
    var lastTimedTime by remember { mutableStateOf(draft?.optString("lastTimedTime")?.takeIf { it.isNotEmpty() }?.let(LocalTime::parse) ?: values.startTime ?: LocalTime.of(9, 0)) }
    var location by remember { mutableStateOf(values.location) }
    var notes by remember { mutableStateOf(values.notes) }
    var category by remember { mutableStateOf(values.category) }
    val billTask = category == "Bills"
    fun selectCategory(selected: String) {
        if (selected == "Bills" && category != selected && shownReminders.isEmpty()) {
            addedReminders += Reminder(itemId = 0, amount = 3, unit = com.example.itinerary.data.ReminderUnit.DAYS)
            Toast.makeText(context, "Reminder added: 3 days before", Toast.LENGTH_SHORT).show()
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
    // Read inputs during composition so even a title-only edit invalidates this scope.
    // Reading them only inside SideEffect misses changes handled by a nested editor scope.
    val draftSnapshot by remember(existingAttachments, existingReminders) { derivedStateOf {
        if (committed) null else JSONObject().put("token", draftToken).put("initial", DraftCodec.item(initial))
                    .put("existingAttachments", DraftCodec.attachments(existingAttachments))
                    .put("existingReminders", DraftCodec.reminders(existingReminders))
                    .put("item", DraftCodec.item(values.copy(title = title, date = date, startTime = time,
                        location = location, notes = notes, category = category, colorIndex = colorIndex,
                        paymentLink = paymentLink, paymentReference = paymentReference, bpayBillerCode = bpayBillerCode, bpayReference = bpayReference,
                        customColor = customColor, checklist = checklist, paid = paid, payments = payments, billAmountMinor = Bills.parse(billAmountText), billCurrency = billCurrency)))
                    .put("added", DraftCodec.attachments(added.toList())).put("removed", DraftCodec.attachments(removed.toList()))
                    .put("addedReminders", DraftCodec.reminders(addedReminders.toList()))
                    .put("removedReminders", DraftCodec.reminders(removedReminders.toList()))
                    .put("state", JSONObject().put("duplicating", duplicating).put("repeat", repeat.name)
                        .put("billAmountText", billAmountText).put("count", repeatCount).put("entireSeries", entireSeries).put("durationText", durationText)
                        .put("beforeText", beforeText).put("afterText", afterText)
                        .put("lastTimedTime", lastTimedTime.toString()).put("pendingPhoto", pendingPhoto?.name).put("scanningPdf", scanningPdf).put("billReviewFiles", org.json.JSONArray(billReviewFiles)).put("billReviewRequested", billReviewRequested))
    } }
    // This holder is deliberately not Compose state: recording a successful write must not redraw the editor.
    val writtenDraft = remember { arrayOfNulls<JSONObject>(1) }
    // Observe the derived state during composition, not only inside SideEffect. Otherwise an edit
    // handled by a nested text-field scope would not schedule the parent's persistence effect.
    val snapshotToWrite = draftSnapshot
    SideEffect {
        val snapshot = snapshotToWrite
        if (snapshot != null && !committed && writtenDraft[0] !== snapshot) {
            try { draftStore.write(snapshot); writtenDraft[0] = snapshot }
            catch (_: Exception) { error = "Couldn't protect this draft. Keep the app open and save your event." }
        }
    }
    val count = repeatCount.toIntOrNull()
    val creatingSeries = isNew || initial.seriesId == null
    val changeRepeat = !isNew && entireSeries && repeat.name != initial.repeatRule
    val validRepeat = !creatingSeries || repeat == RepeatRule.NONE || count != null && count in 2..365
    val plannedDates = remember(date, repeat, count, isNew, entireSeries, allEvents) {
        when {
            creatingSeries -> repeat.dates(date, count?.coerceIn(1, 365) ?: 1)
            entireSeries -> {
                val shift = java.time.temporal.ChronoUnit.DAYS.between(initial.date, date)
                val members = allEvents.filter { it.seriesId == initial.seriesId }.sortedBy { it.date }
                if (changeRepeat && repeat != RepeatRule.NONE && members.isNotEmpty())
                    repeat.dates(members.first().date.plusDays(shift), members.size)
                else members.map { it.date.plusDays(shift) }
            }
            else -> listOf(date)
        }
    }
    val clashes = remember(allEvents, plannedDates, time, duration, before, after, isNew, entireSeries) {
        val excluded = if (isNew) emptySet() else if (entireSeries && initial.seriesId != null) {
            allEvents.filter { it.seriesId == initial.seriesId }.mapTo(HashSet()) { it.id }
        } else setOf(initial.id)
        overlappingEvents(allEvents, plannedDates, time, duration?.takeIf { it in 1..1440 }, excluded, before.coerceIn(0, 1440), after.coerceIn(0, 1440))
    }

    fun currentItem(copy: Boolean = false): ItineraryItem = initial.copy(
            id = if (copy) 0 else initial.id,
            linkedTaskId = if (copy) null else initial.linkedTaskId,
            paid = paid, skipped = !copy && initial.skipped,
            payments = payments,
            paymentLink = paymentLink.trim(), paymentReference = paymentReference.trim(),
            bpayBillerCode = bpayBillerCode.trim(), bpayReference = bpayReference.trim(),
            billAmountMinor = if (category == "Bills" || payments.isNotEmpty()) Bills.parse(billAmountText) else null, billCurrency = billCurrency,
            seriesId = if (copy) null else initial.seriesId,
            repeatRule = if (copy) "NONE" else initial.repeatRule,
            date = date, title = title.trim(), startTime = time,
            durationMinutes = if (time == null) null else duration,
            bufferBeforeMinutes = if (time == null || billTask) 0 else before,
            bufferAfterMinutes = if (time == null || billTask) 0 else after,
            checklist = checklist.map { it.copy(text = it.text.trim()) },
            location = location.trim(), notes = notes.trim(), category = category,
            colorIndex = colorIndex, customColor = customColor,
        )
    fun applyTemplate(content: TemplateContent) {
        val item = content.forDate(date)
        paymentLink = item.paymentLink; paymentReference = item.paymentReference
        bpayBillerCode = item.bpayBillerCode; bpayReference = item.bpayReference
        title = item.title; time = item.startTime; lastTimedTime = item.startTime ?: LocalTime.of(9, 0)
        location = item.location; notes = item.notes; category = item.category; colorIndex = item.colorIndex
        beforeText = item.bufferBeforeMinutes.toString(); afterText = item.bufferAfterMinutes.toString()
        customColor = item.customColor; durationText = item.durationMinutes?.toString().orEmpty(); checklist = item.checklist
        if (payments.isEmpty()) { billAmountText = Bills.input(item.billAmountMinor); billCurrency = item.billCurrency; paid = false }
        repeat = content.repeat; repeatCount = content.count.toString(); entireSeries = false
        addedReminders.clear(); removedReminders.clear(); removedReminders.addAll(existingReminders)
        addedReminders.addAll(content.reminders.map { it.copy(id = 0, itemId = 0, snoozedUntil = null) })
    }
    fun save(allowDuplicate: Boolean = false) {
        if (busy) return
        busy = true
        error = null
        val copy = duplicating
        val item = currentItem(copy)
        val attachmentsToAdd = if (copy) shownAttachments.map { it.copy(id = 0, itemId = 0) } else added.toList()
        val remindersToAdd = if (copy) shownReminders.map { it.copy(id = 0, itemId = 0, snoozedUntil = null) } else addedReminders.toList()
        val attachmentsToRemove = if (copy) emptyList() else removed.toList()
        val remindersToRemove = if (copy) emptyList() else removedReminders.toList()
        val options = EventSaveOptions(if (creatingSeries || changeRepeat) repeat else RepeatRule.NONE,
            if (creatingSeries && repeat != RepeatRule.NONE) count!! else 1, !isNew && entireSeries, changeRepeat, draftToken,
            paymentBaseline = paymentBaseline.takeUnless { isNew })
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
                    onSave(item, attachmentsToAdd, attachmentsToRemove, remindersToAdd, remindersToRemove, options)
                    draftStore.clear()
                    File(context.filesDir, "draft-scan").deleteRecursively()
                    if (!disposed) onDismiss()
                } catch (e: Exception) {
                    committed = false
                    error = (e as? com.example.itinerary.data.PaymentUpdateException)?.message ?: "Couldn't save the ${if (billTask) "bill" else "event"}. Your changes are still here; try again."
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
    BackHandler { if (!busy && !readingText) discard() }
    val editorScroll = rememberScrollState()

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
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(editorScroll)
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
            TemplateActions(isNew = isNew, title = title, billTask = billTask, canApply = !busy && !readingText,
                enabled = !busy && !readingText && title.isNotBlank() && validBillAmount && validDuration && (billTask || validBuffers) && validRepeat && checklist.all { it.text.isNotBlank() },
                content = { TemplateContent(currentItem(), shownReminders, repeat,
                    if (repeat == RepeatRule.NONE) 1 else if (!creatingSeries && initial.seriesId != null) allEvents.count { it.seriesId == initial.seriesId }.coerceIn(2, 365) else count?.coerceIn(2, 365) ?: 12) }, onApply = ::applyTemplate)
            initial.linkedTaskId?.let { LinkedTaskSection(it) }
            if (initial.skipped && !isNew) Text("This occurrence is skipped. Restore it from its action menu to resume reminders.")
            if (!isNew) {
                TextButton(enabled = !busy, onClick = { duplicating = true; entireSeries = false; repeat = RepeatRule.NONE; paid = false; payments = emptyList(); checklist = checklist.map { it.copy(done = false) } }) {
                    Text(if (billTask) "Duplicate bill" else "Duplicate event")
                }
            }
            if (recovered != null) Text("Unfinished draft recovered. Save to keep your changes.")
            if (duplicating) Text("Edit this copy, then Save to add it. The original is kept.")
            if (!isNew && initial.seriesId != null) {
                Text("${RepeatRule.entries.find { it.name == initial.repeatRule }?.label ?: "Repeating"} series")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !entireSeries, onClick = { entireSeries = false }, label = { Text(if (billTask) "This bill" else "This event") })
                    FilterChip(selected = entireSeries, onClick = { entireSeries = true }, label = { Text("Entire series") })
                }
                if (entireSeries) Text("Changes apply to every remaining occurrence, including earlier dates. Moving the date shifts all occurrences by the same number of days.")
            }
            // Saffron pill so the day being edited is easy to spot; tap it to pick another date.
            Text(
                "${if (billTask) "Due " else ""}${date.dayLabel(LocalDateFormat.current)} ▾",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable(role = Role.Button, onClickLabel = if (billTask) "Change due date" else "Change date") { pickingDate = true }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.replace('\n', ' ') },
                label = { Text(if (billTask) "Bill title" else "What are you doing?") },
                // Grows as the text wraps; Done closes the keyboard instead of adding a line break.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            if (category == "Bills") {
                OutlinedTextField(value = billAmountText, onValueChange = {
                    billAmountText = it.take(16)
                    if (payments.isNotEmpty()) paid = payments.any { p -> !p.reversed } && Bills.parse(billAmountText)?.let { n -> paidTotal >= n } == true
                },
                    label = { Text("Bill amount (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    isError = !validBillAmount, supportingText = { if (!validBillAmount) Text("Enter an amount with up to two decimal places, covering recorded payments.") },
                    modifier = Modifier.fillMaxWidth())
                SettingsDropdown(label = "Currency", current = billCurrency, options = if (payments.isEmpty()) Bills.currencies else listOf(billCurrency),
                    onSelect = { billCurrency = it }, entry = { Text(it) })
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
                BillPaymentDetails(paymentLink, paymentReference, bpayBillerCode, bpayReference,
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
                Row(
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
                    onValueChange = { durationText = it.filter(Char::isDigit).take(4) },
                    label = { Text("Duration in minutes (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    singleLine = true,
                    isError = !validDuration,
                    supportingText = { Text(if (!validDuration) "Enter 1–1440 minutes, or leave blank" else
                        duration?.let { eventEndLabel(date, time, it, LocalTimeFormat.current, context) }.orEmpty()) },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "No duration", 30 to "30 min", 60 to "1 hour", 120 to "2 hours").forEach { (minutes, label) ->
                        FilterChip(selected = durationText == minutes?.toString().orEmpty(),
                            onClick = { durationText = minutes?.toString().orEmpty() }, label = { Text(label) })
                    }
                }
                Text("Travel and preparation buffers", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(beforeText, { beforeText = it.filter(Char::isDigit).take(4) },
                        label = { Text("Before (min)") }, singleLine = true, isError = before !in 0..1440,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(afterText, { afterText = it.filter(Char::isDigit).take(4) },
                        label = { Text("After (min)") }, singleLine = true, isError = after !in 0..1440,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                Text("Reserve 0–1440 minutes on each side for travel or preparation. Included in clashes and free time; reminders keep the event's start time.", style = MaterialTheme.typography.bodySmall)
                if ((before > 0 || after > 0) && duration == null)
                    Text("Set an end time for precise buffers. Without one, clashes use the start time and free time uses your chosen default duration.", style = MaterialTheme.typography.bodySmall)
            }
            if (creatingSeries || entireSeries) {
                SettingsDropdown(
                    label = "Repeat",
                    current = repeat.label,
                    options = RepeatRule.entries,
                    onSelect = { repeat = it },
                    entry = { Text(it.label) },
                )
                if (repeat != RepeatRule.NONE && creatingSeries) {
                    OutlinedTextField(
                        value = repeatCount,
                        onValueChange = { value -> repeatCount = value.filter(Char::isDigit).take(3) },
                        label = { Text(if (billTask) "Bills (including this one)" else "Occurrences (including this event)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        singleLine = true,
                        isError = !validRepeat,
                        supportingText = { Text(if (validRepeat) "Last occurrence: ${plannedDates.last().dayLabel(LocalDateFormat.current)}" else "Enter a number from 2 to 365") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (repeat == RepeatRule.MONTHLY) Text("Shorter months use their last day, then return to this day of the month.")
                    if (repeat == RepeatRule.YEARLY && date.monthValue == 2 && date.dayOfMonth == 29)
                        Text("Uses 28 February in non-leap years, then returns to 29 February in leap years.")
                }
            }
            if (changeRepeat) Text(if (repeat == RepeatRule.NONE)
                "All saved occurrences will become separate ${if (billTask) "bills" else "events"}. Their dates are kept."
                else "Keeps all ${plannedDates.size} saved occurrences and spaces them ${repeat.label.lowercase()} from ${plannedDates.firstOrNull()?.dayLabel(LocalDateFormat.current).orEmpty()}.")
            if (category != "Bills" && payments.isNotEmpty()) {
                Text("Payment history", style = MaterialTheme.typography.titleMedium)
                Text("Recorded payments are kept when you change the category.", style = MaterialTheme.typography.bodySmall)
                payments.forEach { Text("${Bills.format(it.amount, billCurrency)} · ${it.date.fullLabel()}${if (it.reversed) " · Reversed" else ""}") }
            }
            if (!billTask && clashes.isNotEmpty()) {
                Text(
                    "${if (duration == null && clashes.all { it.durationMinutes == null }) "Conflicts with (including buffers)" else "Overlaps with (including buffers)"} ${clashes.take(3).joinToString { it.title }}${if (clashes.size > 3) " and ${clashes.size - 3} more" else ""}. You can still save.",
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            OutlinedTextField(
                value = location,
                onValueChange = { location = it.replace('\n', ' ') },
                label = { Text(if (billTask) "Payee / location (optional)" else "Location") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
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
                            else if (attachment !in removed) { removed.add(attachment); added.add(indexed.copy(id = 0, itemId = 0)) }
                        } finally { readingText = false }
                    }
                },
                onViewText = { textPreview = it },
                onSuggestBill = { billSuggestion = it },
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
            ChecklistSection(checklist, onChange = { checklist = it })
            RemindersSection(
                billTask = billTask,
                eventDate = date,
                eventTime = time,
                reminders = shownReminders,
                notificationsOn = notifications.enabled,
                onEnableNotifications = notifications.enable,
                onAdd = { amount, unit ->
                    val minutes = amount * unit.minutes
                    if (shownReminders.any { it.offsetMinutes == minutes }) {
                        Toast.makeText(context, "That reminder is already set", Toast.LENGTH_SHORT).show()
                    } else {
                        addedReminders += Reminder(itemId = 0, amount = amount, unit = unit)
                        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        if (needsPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onRemove = { reminder ->
                    if (reminder in addedReminders) addedReminders.remove(reminder) else removedReminders += reminder
                },
                onToggleRing = { reminder, ring ->
                    val changed = reminder.copy(id = 0, itemId = 0, ringUntilDismissed = ring)
                    val index = addedReminders.indexOf(reminder)
                    if (index >= 0) {
                        addedReminders[index] = changed
                    } else {
                        // A saved reminder is replaced by a changed copy when the event is saved.
                        removedReminders += reminder
                        addedReminders += changed
                    }
                },
            )
            } // Close the inner scrollable Column

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isNew) {
                    DangerButton(enabled = !busy && !readingText, onClick = { if (initial.seriesId != null || billTask) deleting = true else delete(false) }) { Text("Delete") }
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    enabled = !busy && !readingText,
                    onClick = ::discard,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text("Discard")
                }
                Button(enabled = title.isNotBlank() && validRepeat && validDuration && (billTask || validBuffers) && validBillAmount && !readingText && checklist.all { it.text.isNotBlank() } && !busy, onClick = { save() }) {
                    Text(if (busy) "Saving…" else "Save")
                }
            }
        }
    }

    if (duplicateBills.isNotEmpty()) AlertDialog(
        onDismissRequest = { if (!busy) duplicateBills = emptyList() },
        title = { Text("Possible duplicate bill") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text("A bill with the same title, amount and due date already exists:")
            duplicateBills.forEach { bill ->
                Text("${bill.title} · ${Bills.format(bill.billAmountMinor!!, bill.billCurrency)} · ${bill.date.fullLabel()}")
                TextButton(enabled = !busy, onClick = { viewingDuplicate = bill.id }) { Text("Open existing bill") }
            }
        } },
        dismissButton = { TextButton(enabled = !busy, onClick = { duplicateBills = emptyList() }) { Text("Go back") } },
        confirmButton = { TextButton(enabled = !busy, onClick = { save(allowDuplicate = true) }) { Text("Save anyway") } },
    )

    viewingDuplicate?.let { ExistingBillDialog(it) { viewingDuplicate = null } }

    textPreview?.let { attachment ->
        AlertDialog(onDismissRequest = { textPreview = null }, title = { Text("Recognised text") },
            text = { Column {
                Text("Recognition can make mistakes. Check the original document.", style = MaterialTheme.typography.bodySmall)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(attachment.recognizedText, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()))
                }
            } }, confirmButton = { TextButton(onClick = { textPreview = null }) { Text("Close") } })
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { HeadingText(if (billTask) { if (initial.seriesId == null) "Delete bill?" else "Delete repeating bill?" } else "Delete repeating event?") },
            text = { Text(if (initial.seriesId == null) "This bill, its payment history and attachments will be kept in Recently deleted for 30 days." else "Delete just this occurrence or every remaining entry in this series? An Undo action will be available afterwards.") },
            confirmButton = {
                Column {
                    DangerButton(onClick = { delete(false) }) { Text(if (initial.seriesId == null) "Delete bill" else if (billTask) "This bill" else "This event") }
                    if (initial.seriesId != null) DangerButton(onClick = { delete(true) }) { Text("Entire series") }
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }

    if (scannedBillSuggestion != null && !billReviewRequested && billSuggestion == null) {
        AlertDialog(
            onDismissRequest = { billReviewFiles = emptyList() },
            title = { HeadingText("Read details from this scan?") },
            text = { Text("Review suggested bill details before applying them. Your scan stays attached either way.") },
            confirmButton = { TextButton(onClick = { billReviewRequested = true }) { Text("Read details") } },
            dismissButton = { TextButton(onClick = { billReviewFiles = emptyList() }) { Text("Not now") } },
        )
    }
    (billSuggestion ?: scannedBillSuggestion?.takeIf { billReviewRequested })?.let { attachment ->
        BillSuggestionDialog(attachment, title, billAmountText, billCurrency,
            onDismiss = { billSuggestion = null; billReviewFiles = emptyList(); billReviewRequested = false }, onApply = { suggestedTitle, suggestedDate, suggestedAmount, suggestedCurrency ->
                suggestedTitle?.let { title = it }
                suggestedDate?.let { date = it }
                suggestedAmount?.let {
                    if (payments.isEmpty() || suggestedCurrency == billCurrency) {
                        billAmountText = Bills.input(it); billCurrency = suggestedCurrency
                        if (payments.isNotEmpty()) paid = payments.any { p -> !p.reversed } && paidTotal >= it
                    }
                    else error = "The scanned currency differs from the bill's payment history. Check the amount manually."
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
                if (shownAttachments.any { it.url == url }) {
                    Toast.makeText(context, "That link is already added", Toast.LENGTH_SHORT).show()
                } else {
                    // Held until Save like the other attachments; a link has no file, so its file name is empty.
                    added += Attachment(itemId = 0, name = name, fileName = "", mimeType = Links.MIME_TYPE, url = url)
                }
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
            onConfirm = { date = it; pickingDate = false },
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
