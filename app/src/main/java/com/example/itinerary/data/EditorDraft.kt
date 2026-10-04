package com.example.itinerary.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** One private, durable editor session: the unsaved edits of the open event editor (a new event's too). Save, Discard
 *  or undoing the edits clears it. */
class EditorDraftStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "editor-draft.json"))
    // A draft still waiting for the writer (B1) is the current one; a copy, so a reader can't change what is written.
    fun read(): JSONObject? = (writer.pending(KEY) as JSONObject?)?.let { JSONObject(it.toString()) } ?: synchronized(lock) {
        file.readTextOrNull()?.let(::JSONObject)
    }
    /** Written now, on this thread. */
    fun write(json: JSONObject) = writer.now(KEY) { writeFile(json) }
    /** Written off the main thread shortly after typing pauses ([flush] for at once); only the newest is written.
     *  [json] must not change afterwards. */
    fun schedule(json: JSONObject, onFailure: (Exception) -> Unit) = writer.schedule(KEY, json, onFailure) { writeFile(json) }
    fun flush() = writer.flush()
    /** Also drops a draft still waiting to be written, so none lands after this. */
    fun clear() { writer.now(KEY) { synchronized(lock) { file.delete() } } }
    private fun writeFile(json: JSONObject) = synchronized(lock) { file.writeText(json.toString()) }
    /** E5-1: the files the draft holds (a waiting one's included, as [read] sees it first), which the unused-file
     *  clean-up must leave alone: an editor still open on an event emptied from Recently deleted saves them as new. */
    fun files(): Set<String> = files(::read)

    companion object {
        // S6-8: a draft that can't be read holds no files (as the note and task drafts), so it can't stop every clean-up.
        // S6-4: the files of every event editor open now count too, drafted or not (one opened without an edit has none).
        internal fun files(read: () -> JSONObject?): Set<String> = (runCatching { read() }.getOrNull()?.let { json ->
            listOf("existingAttachments", "added", "removed").flatMap { name ->
                runCatching { DraftCodec.attachments(json.optJSONArray(name)).map { it.fileName } }.getOrDefault(emptyList())
            } + listOfNotNull(json.optJSONObject("state")?.optString("pendingPhoto")?.takeIf { it.isNotBlank() && it != "null" })
        }.orEmpty() + heldFiles.values.flatten()).toSet()

        // S6-4: by editor, the files an open event editor shows or holds (removed, added, a photo being taken), until it
        // closes. An event deleted elsewhere and then emptied from Recently deleted leaves them to that editor's Save.
        private val heldFiles = java.util.concurrent.ConcurrentHashMap<Any, Set<String>>()
        fun holdFiles(editor: Any, files: Set<String>) { heldFiles[editor] = files }
        fun releaseFiles(editor: Any) { heldFiles.remove(editor) }

        private const val KEY = "event"
        private val lock = Any()
        // One for the process: the editor, the recovery check and the widget each make their own store.
        private val writer = DraftWriter()

        // How many event editors are on screen now (D10), saved or not. A widget tap waits while one is open, because moving to
        // another screen would drop the editor without saving or discarding it.
        private val open = EditorCounter()
        val openEditors: StateFlow<Int> = open.open
        fun editorOpened() = open.opened()
        fun editorClosed() = open.closed()

        // U2: the saved event whose draft AppNav's recovery editor has reopened (after process death), while it is up.
        // A bill editor its screen restored for the same event (Agenda and Search keep which bill was open) closes
        // again, so only one editor owns the draft and Discard in one can't delete what the other saved.
        @Volatile private var recovering: Long? = null
        fun recoveryOpened(itemId: Long) { recovering = itemId }
        fun recoveryClosed(itemId: Long) { if (recovering == itemId) recovering = null }
        fun recoveryOwns(itemId: Long): Boolean = recovering == itemId
    }
}

object DraftCodec {
    fun item(item: ItineraryItem): JSONObject = JSONObject().apply {
        put("linkedTaskId", item.linkedTaskId); put("id", item.id); put("tripId", item.tripId); put("date", item.date.toString()); put("payments", JSONArray(Payments.encode(item.payments)))
        put("paymentLink", item.paymentLink); put("paymentReference", item.paymentReference)
        put("bpayBillerCode", item.bpayBillerCode); put("bpayReference", item.bpayReference)
        put("time", item.startTime?.toString()); put("title", item.title); put("location", item.location)
        put("notes", item.notes); put("category", item.category); put("color", item.colorIndex)
        put("customColor", item.customColor); put("seriesId", item.seriesId); put("repeatRule", item.repeatRule)
        put("bufferBeforeMinutes", item.bufferBeforeMinutes); put("bufferAfterMinutes", item.bufferAfterMinutes)
        put("duration", item.durationMinutes); put("checklist", ChecklistCodec.encode(item.checklist)); put("billAmountMinor", item.billAmountMinor); put("billCurrency", item.billCurrency); put("skipped", item.skipped); put("paid", item.paid); put("draftToken", item.draftToken)
        put("endDate", item.endDate?.toString())
    }
    fun item(json: JSONObject): ItineraryItem = ItineraryItem(
        paymentLink = json.optString("paymentLink", ""), paymentReference = json.optString("paymentReference", ""),
        bpayBillerCode = json.optString("bpayBillerCode", ""), bpayReference = json.optString("bpayReference", ""),
        payments = Payments.decode(json.optJSONArray("payments")?.toString() ?: "[]"),
        linkedTaskId = json.optString("linkedTaskId").takeIf { it.isNotBlank() && it != "null" },
        id = json.getLong("id"), tripId = json.getLong("tripId"), date = LocalDate.parse(json.getString("date")),
        startTime = json.optString("time").takeIf { it.isNotEmpty() }?.let(LocalTime::parse),
        title = json.getString("title"), location = json.getString("location"), notes = json.getString("notes"),
        category = json.getString("category"), colorIndex = json.getInt("color"),
        customColor = if (json.has("customColor")) json.getInt("customColor") else null,
        seriesId = json.optString("seriesId").takeIf { it.isNotEmpty() }, repeatRule = json.getString("repeatRule"),
        bufferBeforeMinutes = json.optInt("bufferBeforeMinutes", 0),
        bufferAfterMinutes = json.optInt("bufferAfterMinutes", 0),
        durationMinutes = if (json.has("duration")) json.getInt("duration") else null,
        checklist = JSONArray(json.getString("checklist")).let { entries -> List(entries.length()) { i ->
            val entry = entries.getJSONObject(i)
            ChecklistEntry(entry.getString("id"), entry.getString("text"), entry.getBoolean("done"))
        } }, billAmountMinor = if (json.has("billAmountMinor")) json.getLong("billAmountMinor") else null,
        billCurrency = json.optString("billCurrency", "AUD"), skipped = json.optBoolean("skipped"), paid = json.optBoolean("paid"), draftToken = json.optString("draftToken").takeIf { it.isNotEmpty() },
        endDate = json.optString("endDate").takeIf { it.isNotEmpty() && it != "null" }?.let(LocalDate::parse))
    fun attachments(values: List<Attachment>): JSONArray = JSONArray().apply { values.forEach { a -> put(JSONObject()
        .put("id", a.id).put("itemId", a.itemId).put("name", a.name).put("fileName", a.fileName)
        .put("mimeType", a.mimeType).put("url", a.url).put("recognizedText", a.recognizedText).put("textStatus", a.textStatus)) } }
    fun attachments(json: JSONArray?): List<Attachment> = if (json == null) emptyList() else List(json.length()) { i ->
        val a = json.getJSONObject(i)
        Attachment(a.getLong("id"), a.getLong("itemId"), a.getString("name"), a.getString("fileName"),
            a.getString("mimeType"), a.optString("url").takeIf { it.isNotEmpty() }, a.optString("recognizedText", ""), a.optString("textStatus", "NOT_INDEXED"))
    }
    fun reminders(values: List<Reminder>): JSONArray = JSONArray().apply { values.forEach { r -> put(JSONObject()
        .put("id", r.id).put("itemId", r.itemId).put("amount", r.amount).put("unit", r.unit.name)
        .put("ring", r.ringUntilDismissed).put("snoozedUntil", r.snoozedUntil)) } }
    fun reminders(json: JSONArray?): List<Reminder> = if (json == null) emptyList() else List(json.length()) { i ->
        val r = json.getJSONObject(i)
        Reminder(r.getLong("id"), r.getLong("itemId"), r.getInt("amount"), ReminderUnit.valueOf(r.getString("unit")),
            r.getBoolean("ring"), if (r.has("snoozedUntil")) r.getLong("snoozedUntil") else null)
    }
}
