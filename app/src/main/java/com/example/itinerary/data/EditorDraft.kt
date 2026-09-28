package com.example.itinerary.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** One private, durable editor session. Explicit Save or Discard is the only thing that clears it. */
class EditorDraftStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "editor-draft.json"))
    @Synchronized fun read(): JSONObject? = if (!file.baseFile.exists()) null else
        JSONObject(file.openRead().bufferedReader().use { it.readText() })
    @Synchronized fun write(json: JSONObject) {
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray()); file.finishWrite(stream) }
        catch (e: Throwable) { file.failWrite(stream); throw e }
    }
    @Synchronized fun clear() { file.delete() }
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
