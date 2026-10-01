package com.example.itinerary.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// A message that is safe to show the user as it is.
class BackupException(message: String) : Exception(message)

// All rows of the database.
data class DataSnapshot(
    val trips: List<Trip>,
    val items: List<ItineraryItem>,
    val reminders: List<Reminder>,
    val attachments: List<Attachment>,
    val templates: List<EventTemplate> = emptyList(),
    val deleted: List<DeletedEntry> = emptyList(),
    val tasks: List<PlannerTask> = emptyList(),
) {
    val storedAttachments: List<Attachment> get() = attachments + tasks.flatMap { it.attachments } + deleted.flatMap { DeletedCodec.decode(it.payload).storedAttachments }
}

// A backup that has been read and checked but not applied yet. Nothing in the app has changed.
class StagedBackup internal constructor(
    internal val file: File,
    internal val data: DataSnapshot,
    internal val settings: SettingsSnapshot,
    val exportedOn: LocalDate?,
    // Attachments the backup lists but whose file is not inside it; these are left out.
    val missingFiles: Int,
    // Ticked Nextcloud calendars; null for a backup made before calendar sync, which leaves the current ones alone.
    internal val calendars: List<CalendarChoice>? = null,
    // Where Planner sends its events on Nextcloud and what it sent (step 5); null for a backup without it (the calendar
    // stays, the record of what was sent doesn't: see CalendarSync.forgetSent).
    internal val send: Pair<CalendarChoice?, List<SentEvent>>? = null,
    // The same for tasks (see TaskSync.snapshot); null for a backup without it.
    internal val taskSend: Pair<CalendarChoice?, List<SentTask>>? = null,
) {
    val plans: Int get() = data.trips.size
    val tasks: Int get() = data.tasks.size
    val events: Int get() = data.items.size
    val reminders: Int get() = data.reminders.size
    val attachments: Int get() = data.attachments.size + data.tasks.sumOf { it.attachments.size }
    val templates: Int get() = data.templates.size
    val deletedGroups: Int get() = data.deleted.size
}

// A backup is one zip: data.json (plans, events, reminders, attachment records, settings)
// plus attachments/<file> for every attachment file.
class BackupManager(
    private val context: Context,
    private val repo: Repository,
    private val store: AttachmentStore,
    private val settings: SettingsRepository,
    // Which Nextcloud calendars are ticked goes into backups; their events and the login never do.
    private val calendars: CalendarSync? = null,
    // The Nextcloud task list Planner's tasks are kept in sync with, and what was synced.
    private val tasks: TaskSync? = null,
) {
    val status = BackupStatusStore(context)
    suspend fun export(uri: Uri, trackStatus: Boolean = true) {
        if (trackStatus) status.track("File") { writeExport(uri) } else writeExport(uri)
    }
    private suspend fun writeExport(uri: Uri) = withContext(Dispatchers.IO) {
        val snapshot = repo.snapshot()
        // A record whose file has gone missing would only be a broken row in the backup. A link has no file.
        val attachments = snapshot.attachments.filter { it.url != null || store.fileFor(it.fileName).exists() }
        val deleted = filterDeletedAttachments(snapshot.deleted) { it.url != null || store.fileFor(it.fileName).exists() }
        val saved = snapshot.copy(attachments = attachments, deleted = deleted, tasks = filterTaskAttachments(snapshot.tasks) { store.fileFor(it.fileName).exists() })
        val json = toJson(saved, settings.snapshot(), calendars?.choices().orEmpty(), calendars?.sendSnapshot(), tasks?.snapshot())
        try {
            val out = context.contentResolver.openOutputStream(uri) ?: error("Could not open $uri")
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(DATA_ENTRY))
                zip.write(json.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                saved.storedAttachments.filter { it.url == null }.map { it.fileName }.distinct().forEach { name ->
                    zip.putNextEntry(ZipEntry("$ATTACHMENTS_DIR/$name"))
                    store.fileFor(name).inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            // Don't leave a half-written file behind that looks like a good backup.
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw BackupException("Couldn't write the backup file.")
        }
    }

    // Reads and checks the file. Throws [BackupException] if it isn't a usable backup.
    suspend fun stage(uri: Uri): StagedBackup = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, STAGING_FILE)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Could not open $uri")
            input.use { source -> file.outputStream().use { source.copyTo(it) } }
        } catch (e: Exception) {
            file.delete()
            throw BackupException("Couldn't read that file.")
        }
        try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(DATA_ENTRY) ?: throw notABackup()
                val json = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                val parsed = parse(json)
                val present = parsed.data.attachments.filter {
                    it.url != null || zip.getEntry("$ATTACHMENTS_DIR/${it.fileName}") != null
                }
                val deleted = filterDeletedAttachments(parsed.data.deleted) { it.url != null || zip.getEntry("$ATTACHMENTS_DIR/${it.fileName}") != null }
                val saved = parsed.data.copy(attachments = present, deleted = deleted, tasks = filterTaskAttachments(parsed.data.tasks) { zip.getEntry("$ATTACHMENTS_DIR/${it.fileName}") != null })
                StagedBackup(
                    file = file,
                    data = saved,
                    settings = parsed.settings,
                    exportedOn = parsed.exportedOn,
                    missingFiles = parsed.data.storedAttachments.size - saved.storedAttachments.size,
                    calendars = parsed.calendars,
                    send = parsed.send,
                    taskSend = parsed.taskSend,
                )
            }
        } catch (e: Exception) {
            file.delete()
            throw e as? BackupException ?: notABackup()
        }
    }

    // Replaces everything in the app with the staged backup, settings included.
    suspend fun restore(staged: StagedBackup) = withContext(Dispatchers.IO) {
        // Files go in first, so the database never points at a file that isn't there. File names are
        // random, so a name that already exists is the same file and is left alone.
        val created = mutableListOf<File>()
        try {
            ZipFile(staged.file).use { zip ->
                staged.data.storedAttachments.forEach { attachment ->
                    if (attachment.url != null) return@forEach // a link has no file to copy
                    // Written below, so the attachments folder must exist even on a fresh install.
                    val target = store.writableFileFor(attachment.fileName)
                    if (target.exists()) return@forEach
                    created += target
                    val entry = zip.getEntry("$ATTACHMENTS_DIR/${attachment.fileName}") ?: error("Missing entry")
                    zip.getInputStream(entry).use { source -> target.outputStream().use { source.copyTo(it) } }
                }
            }
        } catch (e: Exception) {
            created.forEach { it.delete() }
            throw BackupException("Couldn't copy the attachments out of the backup. Nothing was changed.")
        }
        var failure: Exception? = null
        // No calendar send or pull runs from before the events are replaced until the record of what was sent is (see
        // CalendarSync.paused): one would put rows for the old events back over the restored record, or bring the
        // calendar's files in as new events beside the restored ones. (The task list's sync has its own lock; the same
        // would apply to it around tasks?.restore.)
        suspend fun paused(block: suspend () -> Unit) { val sync = calendars; if (sync != null) sync.paused(block) else block() }
        paused {
            try {
                repo.replaceAll(staged.data)
            } catch (e: Exception) {
                created.forEach { it.delete() }
                throw BackupException("Couldn't restore the backup. Nothing was changed.")
            }
            // The data is in: each remaining step runs even if one before it failed (the first failure is reported after).
            // What was sent to Nextcloud always gets replaced: by the backup's record, or by none (an older backup), since
            // the current record names events that are gone or are other events now.
            suspend fun step(action: suspend () -> Unit) { try { action() } catch (e: Exception) { if (failure == null) failure = e } }
            withContext(NonCancellable) {
                step { settings.applySnapshot(staged.settings) }
                step { staged.calendars?.let { calendars?.restoreChoices(it) } }
                step { val send = staged.send; if (send != null) calendars?.restoreSendLocked(send.first, send.second) else calendars?.forgetSentLocked() }
                // After the calendars, whose restore rebuilds the list rows.
                step { val send = staged.taskSend; if (send != null) tasks?.restore(send.first, send.second) else tasks?.forget() }
            }
        }
        failure?.let { throw it }
        staged.file.delete()
    }

    fun discard(staged: StagedBackup) {
        staged.file.delete()
    }

    private class Parsed(val data: DataSnapshot, val settings: SettingsSnapshot, val exportedOn: LocalDate?, val calendars: List<CalendarChoice>?,
                         val send: Pair<CalendarChoice?, List<SentEvent>>?, val taskSend: Pair<CalendarChoice?, List<SentTask>>?)

    private fun notABackup() = BackupException("That file isn't a Planner backup.")

    private fun filterDeletedAttachments(entries: List<DeletedEntry>, keep: (Attachment) -> Boolean) = entries.map { entry ->
        val data = DeletedCodec.decode(entry.payload)
        entry.copy(payload = DeletedCodec.encode(data.copy(attachments = data.attachments.filter(keep), tasks = filterTaskAttachments(data.tasks, keep))))
    }

    private fun filterTaskAttachments(tasks: List<PlannerTask>, keep: (Attachment) -> Boolean) =
        tasks.map { it.copy(attachments = it.attachments.filter(keep)) }

    private fun toJson(data: DataSnapshot, settings: SettingsSnapshot, calendars: List<CalendarChoice>,
                       send: Pair<CalendarChoice, List<SentEvent>>?, taskSend: Pair<CalendarChoice, List<SentTask>>?): String = JSONObject().apply {
        // Optional (older app versions ignore it): the Nextcloud task list Planner's tasks are kept in sync with and what
        // was synced. Absent = not syncing tasks.
        put("taskSend", JSONObject().apply {
            taskSend?.let { (target, rows) ->
                put("account", target.account).put("href", target.href).put("name", target.name)
                put("sent", rows.toJson { JSONObject().put("taskId", it.taskId).put("uid", it.uid ?: JSONObject.NULL).put("href", it.href ?: JSONObject.NULL)
                    .put("etag", it.etag ?: JSONObject.NULL).put("fingerprint", it.fingerprint).put("problem", it.problem ?: JSONObject.NULL) })
            }
        })
        // Optional (older app versions ignore it): the Nextcloud calendar Planner sends to and what it sent there, so a
        // restore neither sends everything again nor loses track of the copies. Absent = not sending.
        put("calendarSend", JSONObject().apply {
            send?.let { (target, rows) ->
                put("account", target.account).put("href", target.href).put("name", target.name)
                put("sent", rows.toJson { JSONObject().put("itemId", it.itemId).put("uid", it.uid ?: JSONObject.NULL).put("href", it.href ?: JSONObject.NULL)
                    .put("etag", it.etag ?: JSONObject.NULL).put("fingerprint", it.fingerprint).put("problem", it.problem ?: JSONObject.NULL) })
            }
        })
        // Optional (older app versions ignore it): the ticked Nextcloud calendars and the subscribed links (account
        // "link", href = the https link), without events or passwords.
        put("calendars", calendars.toJson {
            JSONObject().put("account", it.account).put("href", it.href).put("name", it.name).put("enabled", it.enabled)
                .put("color", it.color?.let { c -> String.format("#%06X", c and 0xFFFFFF) } ?: JSONObject.NULL)
        })
        put("tasks", TaskCodec.encode(data.tasks))
        put("format", FORMAT)
        put("formatVersion", FORMAT_VERSION)
        put("recentlyDeleted", data.deleted.toJson { JSONObject().put("id", it.id).put("deletedAt", it.deletedAt)
            .put("label", it.label).put("payload", JSONObject(it.payload)) })
        put("exportedAt", Instant.now().toString())
        put(
            "settings",
            JSONObject()
                .put("themeMode", settings.themeMode.name)
                .put("appTheme", settings.appTheme.name)
                .put("timeFormat", settings.timeFormat.name)
                .put("calendarCollapsed", settings.calendarCollapsed)
                .put("hiddenCategories", JSONArray(settings.hiddenCategories.sorted()))
                .put("upcomingOnTop", settings.upcomingOnTop)
                .put("upcomingDays", settings.upcomingDays)
                .put("addButtonSeeThrough", settings.addButtonSeeThrough)
                .put("headingColor", String.format("#%06X", settings.headingColor and 0xFFFFFF))
                .put("scrollBarColor", String.format("#%06X", settings.scrollBarColor and 0xFFFFFF))
                .put("scrollBarSeeThrough", settings.scrollBarSeeThrough)
                .put("calendarBackgroundHours", settings.calendarBackgroundHours)
                .put("font", settings.appFont.name)
                .put("textSize", settings.textSizePercent)
                .put("dateFormat", settings.dateFormat.name)
                .put("savedSearches", SavedSearchCodec.encode(settings.savedSearches))
                .put("agendaRange", settings.agendaRange.name),
        )
        put("trips", data.trips.toJson {
            JSONObject().put("id", it.id).put("name", it.name).put("destination", it.destination)
                .put("startDate", it.startDate.toString()).put("endDate", it.endDate.toString())
                .put("sortOrder", it.sortOrder).put("colorIndex", it.colorIndex)
                .put("customColor", it.customColor?.let { c -> String.format("#%06X", c and 0xFFFFFF) } ?: JSONObject.NULL)
        })
        put("templates", data.templates.toJson { JSONObject().put("id", it.id).put("name", it.name).put("payload", JSONObject(it.payload)) })
        put("items", data.items.toJson {
            JSONObject().put("id", it.id).put("tripId", it.tripId).put("date", it.date.toString())
                .put("startTime", it.startTime?.toString() ?: JSONObject.NULL)
                .put("linkedTaskId", it.linkedTaskId ?: JSONObject.NULL)
                .put("seriesId", it.seriesId ?: JSONObject.NULL)
                .put("repeatRule", it.repeatRule)
                .put("paymentLink", it.paymentLink).put("paymentReference", it.paymentReference)
                .put("bpayBillerCode", it.bpayBillerCode).put("bpayReference", it.bpayReference)
                .put("billAmountMinor", it.billAmountMinor ?: JSONObject.NULL).put("billCurrency", it.billCurrency).put("skipped", it.skipped)
                .put("paid", it.paid).put("draftToken", it.draftToken ?: JSONObject.NULL)
                .put("payments", JSONArray(Payments.encode(it.payments)))
                .put("bufferBeforeMinutes", it.bufferBeforeMinutes).put("bufferAfterMinutes", it.bufferAfterMinutes)
                .put("durationMinutes", it.durationMinutes ?: JSONObject.NULL)
                .put("endDate", it.endDate?.toString() ?: JSONObject.NULL)
                .put("checklist", JSONArray(ChecklistCodec.encode(it.checklist)))
                .put("title", it.title).put("location", it.location).put("notes", it.notes)
                .put("category", it.category).put("colorIndex", it.colorIndex)
                .put("customColor", it.customColor?.let { c -> String.format("#%06X", c and 0xFFFFFF) } ?: JSONObject.NULL)
        })
        put("reminders", data.reminders.toJson {
            JSONObject().put("id", it.id).put("itemId", it.itemId).put("amount", it.amount)
                .put("unit", it.unit.name).put("ringUntilDismissed", it.ringUntilDismissed)
                .put("snoozedUntil", it.snoozedUntil ?: JSONObject.NULL)
        })
        put("attachments", data.attachments.toJson {
            JSONObject().put("id", it.id).put("itemId", it.itemId).put("name", it.name)
                .put("fileName", it.fileName).put("mimeType", it.mimeType)
                .put("url", it.url ?: JSONObject.NULL).put("recognizedText", it.recognizedText).put("textStatus", it.textStatus)
        })
    }.toString(2)

    private fun <T> List<T>.toJson(convert: (T) -> JSONObject) = JSONArray().also { array -> forEach { array.put(convert(it)) } }

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

    // Anything malformed throws; [stage] turns that into "isn't a Planner backup".
    private fun parse(json: String): Parsed {
        val root = JSONObject(json)
        if (root.optString("format") != FORMAT) throw notABackup()
        val version = root.optInt("formatVersion", 0)
        if (version > FORMAT_VERSION) {
            throw BackupException("That backup was made by a newer version of Planner. Update the app first.")
        }
        // Format 1 stored categories as the old fixed list's upper-case names (FLIGHT, SIGHT, ...).
        val legacyCategories = version < 2

        val trips = root.getJSONArray("trips").objects().mapIndexed { position, it ->
            Trip(
                id = it.getLong("id"),
                name = it.getString("name"),
                destination = it.optString("destination", ""),
                startDate = LocalDate.parse(it.getString("startDate")),
                endDate = LocalDate.parse(it.getString("endDate")),
                sortOrder = it.optInt("sortOrder", 0),
                // Backups from before plans had colours don't say; give them different ones by position.
                colorIndex = Math.floorMod(it.optInt("colorIndex", position), PlanColors.COUNT),
                // Optional (older backups have none); a value that isn't #RRGGBB is ignored rather than refusing the file.
                customColor = if (it.isNull("customColor")) null else parseHexColor(it.optString("customColor")),
            )
        }
        // Backups from before events had colours don't say; give events on the same day different ones in turn.
        val perDay = HashMap<Pair<Long, LocalDate>, Int>()
        val items = root.getJSONArray("items").objects().map {
            val tripId = it.getLong("tripId")
            val date = LocalDate.parse(it.getString("date"))
            val positionInDay = perDay.merge(tripId to date, 1, Int::plus)!! - 1
            val paletteIndex = Math.floorMod(it.optInt("colorIndex", positionInDay % PlanColors.EVENT_COUNT), PlanColors.COUNT)
            var custom = if (it.isNull("customColor")) null else parseHexColor(it.optString("customColor"))
            // Events can't use the eighth palette colour any more; one that did keeps that colour as a custom colour.
            if (paletteIndex >= PlanColors.EVENT_COUNT && custom == null) custom = PlanColors.SLATE_ARGB
            ItineraryItem(
                linkedTaskId = it.optString("linkedTaskId").takeIf { id -> id.isNotBlank() && id != "null" }?.also { id -> require(id.length <= 100) },
                paymentLink = it.optString("paymentLink", ""), paymentReference = it.optString("paymentReference", ""),
                bpayBillerCode = it.optString("bpayBillerCode", ""), bpayReference = it.optString("bpayReference", ""),
                payments = Payments.decode(it.optJSONArray("payments")?.toString() ?: "[]"),
                id = it.getLong("id"),
                tripId = tripId,
                date = date,
                startTime = if (it.isNull("startTime")) null else LocalTime.parse(it.getString("startTime")),
                title = it.getString("title"),
                billAmountMinor = if (it.isNull("billAmountMinor")) null else it.getLong("billAmountMinor").also { n -> require(n in 0..Bills.MAX_MINOR) },
                billCurrency = it.optString("billCurrency", "AUD").also { code -> require(code in Bills.currencies) },
                skipped = it.optBoolean("skipped", false),
                paid = it.optBoolean("paid", false),
                draftToken = if (it.isNull("draftToken")) null else it.getString("draftToken"),
                location = it.optString("location", ""),
                notes = it.optString("notes", ""),
                category = it.getString("category")
                    .let { name -> if (legacyCategories) Categories.fromLegacy(name) else name }
                    .let { name -> Categories.clean(name) ?: Categories.OTHER },
                colorIndex = if (paletteIndex >= PlanColors.EVENT_COUNT) 0 else paletteIndex,
                // Optional (older backups have none); a value that isn't #RRGGBB is ignored rather than refusing the file.
                customColor = custom,
                bufferBeforeMinutes = it.optInt("bufferBeforeMinutes", 0).also { n -> require(n in 0..1440) },
                bufferAfterMinutes = it.optInt("bufferAfterMinutes", 0).also { n -> require(n in 0..1440) },
                durationMinutes = if (it.isNull("startTime") || it.isNull("durationMinutes")) null
                    else it.getInt("durationMinutes").also { n -> require(n in 1..1440) },
                checklist = if (it.isNull("checklist")) emptyList() else ChecklistCodec.decode(it.getJSONArray("checklist").toString()),
                seriesId = if (it.isNull("seriesId")) null else it.getString("seriesId").takeIf(String::isNotBlank),
                repeatRule = it.optString("repeatRule", "NONE").takeIf { rule -> RepeatRule.parse(rule) != null } ?: "NONE",
                // Format 16: the last day of a multi-day all-day event (absent or null = one day).
                endDate = if (!it.has("endDate") || it.isNull("endDate")) null else LocalDate.parse(it.getString("endDate")),
            )
        }
        items.forEach { Payments.validate(it); MultiDay.validate(it) }
        val reminders = root.getJSONArray("reminders").objects().map {
            Reminder(
                id = it.getLong("id"),
                itemId = it.getLong("itemId"),
                amount = it.getInt("amount"),
                unit = ReminderUnit.valueOf(it.getString("unit")),
                ringUntilDismissed = it.optBoolean("ringUntilDismissed", false),
                snoozedUntil = if (it.isNull("snoozedUntil")) null else it.getLong("snoozedUntil"),
            )
        }
        val attachments = root.getJSONArray("attachments").objects().mapNotNull {
            // A link (format 3) has a "url" and no file. One whose address isn't a usable web address is left out
            // rather than refusing the whole file.
            val url = if (it.isNull("url")) null else (Links.normalize(it.optString("url")) ?: return@mapNotNull null)
            Attachment(
                id = it.getLong("id"),
                itemId = it.getLong("itemId"),
                name = it.getString("name"),
                fileName = if (url != null) "" else it.getString("fileName"),
                mimeType = if (url != null) Links.MIME_TYPE else it.optString("mimeType", "application/octet-stream"),
                url = url,
                recognizedText = it.optString("recognizedText", "").take(200_000),
                textStatus = it.optString("textStatus", "NOT_INDEXED"),
            )
        }

        val templatesJson = root.optJSONArray("templates")
        val templates = templatesJson?.objects().orEmpty().map {
            val payload = it.getJSONObject("payload").toString()
            TemplateContent.decode(payload)
            EventTemplate(it.getString("id").also { id -> require(id.isNotBlank()) },
                it.getString("name").also { name -> require(name.length in 1..80) }, payload)
        }
        require(templates.map { it.id }.distinct().size == templates.size)
        // The database would refuse a row that points at nothing, so refuse the whole file up front.
        val tripIds = trips.map { it.id }.toSet()
        val itemIds = items.map { it.id }.toSet()
        val consistent = trips.all { it.id > 0 } && tripIds.size == trips.size &&
            items.all { it.id > 0 && it.tripId in tripIds } && itemIds.size == items.size &&
            reminders.all { it.id > 0 && it.itemId in itemIds } &&
            reminders.map { it.id }.toSet().size == reminders.size &&
            attachments.all {
                it.id > 0 && it.itemId in itemIds && (it.url != null || SAFE_FILE_NAME.matches(it.fileName))
            } &&
            attachments.map { it.id }.toSet().size == attachments.size
        if (!consistent) throw notABackup()

        val settingsJson = root.optJSONObject("settings")
        val settings = SettingsSnapshot(
            savedSearches = SavedSearchCodec.decode(settingsJson?.optJSONArray("savedSearches") ?: JSONArray()),
            appTheme = AppTheme.fromStored(settingsJson?.optString("appTheme")),
            themeMode = runCatching { ThemeMode.valueOf(settingsJson?.optString("themeMode").orEmpty()) }
                .getOrDefault(ThemeMode.SYSTEM),
            timeFormat = runCatching { TimeFormat.valueOf(settingsJson?.optString("timeFormat").orEmpty()) }
                .getOrDefault(TimeFormat.SYSTEM),
            calendarCollapsed = settingsJson?.optBoolean("calendarCollapsed", false) ?: false,
            // Optional: backups made before categories could be removed don't have it.
            hiddenCategories = settingsJson?.optJSONArray("hiddenCategories")
                ?.let { array -> List(array.length()) { array.optString(it) } }
                .orEmpty()
                .filter { it in Categories.BUILT_IN }
                .toSet(),
            // Optional: older backups don't have these, so they get the defaults (on, 7 days).
            upcomingOnTop = settingsJson?.optBoolean("upcomingOnTop", true) ?: true,
            upcomingDays = (settingsJson?.optInt("upcomingDays", UpcomingPlans.DEFAULT_DAYS) ?: UpcomingPlans.DEFAULT_DAYS)
                .coerceIn(UpcomingPlans.MIN_DAYS, UpcomingPlans.MAX_DAYS),
            // Optional too; a missing value gets the default.
            addButtonSeeThrough = (settingsJson?.optInt("addButtonSeeThrough", AddButton.DEFAULT_SEE_THROUGH) ?: AddButton.DEFAULT_SEE_THROUGH)
                .coerceIn(AddButton.MIN_SEE_THROUGH, AddButton.MAX_SEE_THROUGH),
            // Optional too; a missing or unreadable colour gets the default (red orange).
            headingColor = settingsJson?.optString("headingColor")?.let(::parseHexColor) ?: HeadingColor.DEFAULT_ARGB,
            // Optional (older backups have none): missing or unreadable values get the defaults.
            scrollBarColor = settingsJson?.optString("scrollBarColor")?.let(::parseHexColor) ?: ScrollBar.DEFAULT_ARGB,
            scrollBarSeeThrough = (settingsJson?.optInt("scrollBarSeeThrough", ScrollBar.DEFAULT_SEE_THROUGH) ?: ScrollBar.DEFAULT_SEE_THROUGH)
                .coerceIn(ScrollBar.MIN_SEE_THROUGH, ScrollBar.MAX_SEE_THROUGH),
            // Optional (older backups have none): calendar sync in the background stays off.
            calendarBackgroundHours = (settingsJson?.optInt("calendarBackgroundHours", BackgroundSync.OFF) ?: BackgroundSync.OFF)
                .takeIf { it in BackgroundSync.CHOICES } ?: BackgroundSync.OFF,
            // Optional; a missing or unknown font gets the default.
            appFont = runCatching { AppFont.valueOf(settingsJson?.optString("font").orEmpty()) }.getOrDefault(AppFont.DEFAULT),
            // Optional; a missing value gets 100 percent.
            textSizePercent = (settingsJson?.optInt("textSize", TextSize.DEFAULT_PERCENT) ?: TextSize.DEFAULT_PERCENT)
                .coerceIn(TextSize.MIN_PERCENT, TextSize.MAX_PERCENT),
            // Optional; a missing or unknown format gets the default.
            agendaRange = runCatching { AgendaRange.valueOf(settingsJson?.optString("agendaRange").orEmpty()) }.getOrDefault(AgendaRange.UPCOMING),
            dateFormat = runCatching { DateFormatChoice.valueOf(settingsJson?.optString("dateFormat").orEmpty()) }
                .getOrDefault(DateFormatChoice.DEFAULT),
        )
        val exportedOn = runCatching {
            Instant.parse(root.getString("exportedAt")).atZone(ZoneId.systemDefault()).toLocalDate()
        }.getOrNull()
        val deleted = root.optJSONArray("recentlyDeleted")?.objects().orEmpty().map {
            val payload = it.getJSONObject("payload").toString()
            DeletedCodec.decode(payload)
            DeletedEntry(it.getString("id").also { id -> require(id.isNotBlank()) },
                it.getLong("deletedAt").also { time -> require(time > 0 && time <= System.currentTimeMillis() + 86400000L) },
                it.getString("label"), payload)
        }
        require(deleted.map { it.id }.distinct().size == deleted.size)
        // Optional: backups from before calendar sync have none. A calendar entry that doesn't make sense is left out.
        val calendars = root.optJSONArray("calendars")?.objects()?.mapNotNull {
            val account = it.optString("account"); val href = it.optString("href"); val name = it.optString("name")
            val validHref = href.startsWith("/") || account == CalendarSync.LINK_ACCOUNT && href.startsWith("https://")
            if (account.isBlank() || !validHref || name.isBlank() || account.length > 2000 || href.length > 4000) return@mapNotNull null
            CalendarChoice(account, href, name.take(200), if (it.isNull("color")) null else parseHexColor(it.optString("color")),
                it.optBoolean("enabled", true))
        }
        // Optional too. An empty object means "not sending" (restoring it stops sending); a missing one keeps the
        // synced calendar but not the record of what was sent.
        val send = root.optJSONObject("calendarSend")?.let { json ->
            val account = json.optString("account"); val href = json.optString("href")
            if (account.isBlank() || !href.startsWith("/")) return@let null to emptyList<SentEvent>()
            val rows = json.optJSONArray("sent")?.objects().orEmpty().mapNotNull {
                val itemId = it.optLong("itemId", -1).takeIf { id -> id > 0 && id in itemIds } ?: return@mapNotNull null
                SentEvent(itemId = itemId, account = account, calendar = href,
                    uid = if (it.isNull("uid")) null else it.optString("uid").takeIf { u -> u.matches(Regex("[A-Za-z0-9@._-]{1,200}")) } ?: return@mapNotNull null,
                    etag = if (it.isNull("etag")) null else it.optString("etag"), fingerprint = it.optString("fingerprint"),
                    problem = if (it.isNull("problem")) null else it.optString("problem"),
                    // The file of an event that came from Nextcloud (step 6); only inside that calendar.
                    href = if (it.isNull("href")) null else it.optString("href").takeIf { h -> h.startsWith(href) && h.length > href.length && '/' !in h.removePrefix(href) })
            }.distinctBy { it.itemId }
            CalendarChoice(account, href, json.optString("name").ifBlank { "Nextcloud calendar" }.take(200), null, false) to rows
        }
        val tasks = TaskCodec.decode(if (version >= 10 || root.has("tasks")) root.getJSONArray("tasks") else JSONArray())
        val taskIds = tasks.mapTo(HashSet()) { it.id }
        // Optional too, read as calendarSend is.
        val taskSend = root.optJSONObject("taskSend")?.let { json ->
            val account = json.optString("account"); val href = json.optString("href")
            if (account.isBlank() || !href.startsWith("/")) return@let null to emptyList<SentTask>()
            val rows = json.optJSONArray("sent")?.objects().orEmpty().mapNotNull {
                val taskId = it.optString("taskId").takeIf { id -> id in taskIds } ?: return@mapNotNull null
                SentTask(taskId = taskId, account = account, list = href,
                    uid = if (it.isNull("uid")) null else it.optString("uid").takeIf { u -> u.matches(Regex("[A-Za-z0-9@._-]{1,200}")) } ?: return@mapNotNull null,
                    etag = if (it.isNull("etag")) null else it.optString("etag"), fingerprint = it.optString("fingerprint"),
                    problem = if (it.isNull("problem")) null else it.optString("problem"),
                    href = if (it.isNull("href")) null else it.optString("href").takeIf { h -> h.startsWith(href) && h.length > href.length && '/' !in h.removePrefix(href) })
            }.distinctBy { it.taskId }
            CalendarChoice(account, href, json.optString("name").ifBlank { "Nextcloud tasks" }.take(200), null, false) to rows
        }
        return Parsed(DataSnapshot(trips, items, reminders, attachments, templates, deleted, tasks), settings, exportedOn, calendars, send, taskSend)
    }

    // "#RRGGBB" to an opaque ARGB int, or null if it isn't that.
    private fun parseHexColor(text: String): Int? =
        if (HEX_COLOR.matches(text)) (0xFF000000L or text.removePrefix("#").toLong(16)).toInt() else null

    companion object {
        private val HEX_COLOR = Regex("#[0-9A-Fa-f]{6}")
        private const val FORMAT = "planner-backup"
        // 2: categories are plain text. 3: attachments can be links. Older files are still read; an older app refuses newer ones.
        // 16: events may carry an endDate (multi-day). An older app refuses the file rather than dropping end dates.
        const val FORMAT_VERSION = 16
        private const val DATA_ENTRY = "data.json"
        private const val ATTACHMENTS_DIR = "attachments"
        private const val STAGING_FILE = "import-staging.zip"

        // Attachment file names become paths inside the app's storage, so nothing but plain names is allowed.
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}
