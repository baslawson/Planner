package com.example.itinerary.data

import android.content.Context
import java.io.File

/**
 * Files or photos shared to Planner, made into an event, task, bill or note with them attached. They are copied into
 * the attachment store as soon as the share arrives (Android lets Planner read them only for a while), and kept, with
 * any text that came with them, in a small staging file. The share itself then goes on as text: [MARKER] and the
 * staging file's id, so it waits, is saved and reopens as a text share does.
 */
object SharedFiles {
    const val MARKER = "\u0000planner-shared-files:"
    // As many as a task can hold (Tasks.validate).
    const val MAX_FILES = 100
    private val ID = Regex("[A-Za-z0-9-]{1,64}")

    fun key(id: String): String = MARKER + id
    /** The staging id a share's text stands for, or null for an ordinary text share. */
    fun idOf(text: String?): String? = text?.takeIf { it.startsWith(MARKER) }?.removePrefix(MARKER)?.takeIf(ID::matches)

    class Staged(val caption: String?, val subject: String?, val files: List<Attachment>)

    /**
     * What the editor opens with: text that came with the files is read as a shared text is; without it, the subject,
     * else the first file's name.
     */
    fun draft(staged: Staged): SharedDraft {
        staged.caption?.takeIf { it.isNotBlank() }?.let { return SharedText.draft(it, staged.subject) }
        val title = staged.subject?.trim()?.takeIf { it.isNotBlank() }
            ?: staged.files.firstOrNull()?.name?.let(::withoutExtension) ?: "Shared file"
        return SharedDraft(title.replace('\n', ' ').take(500), "")
    }

    fun withoutExtension(name: String): String = name.substringBeforeLast('.').takeIf { it.isNotBlank() } ?: name

    /** "2 files: receipt.jpg, invoice.pdf", naming the first three. */
    fun summary(files: List<Attachment>): String {
        val names = files.take(3).joinToString(", ") { it.name } + if (files.size > 3) " and ${files.size - 3} more" else ""
        return if (files.size == 1) "1 file: $names" else "${files.size} files: $names"
    }
}

/** The staging files of shares of files (see [SharedFiles]), until the share is closed. */
class SharedFilesStore(context: Context) {
    private val dir = File(context.filesDir, "shared-files")

    private fun file(id: String) = File(dir, "$id.json")

    fun write(id: String, staged: SharedFiles.Staged) {
        dir.mkdirs()
        val json = org.json.JSONObject()
            .put("caption", staged.caption)
            .put("subject", staged.subject)
            .put("files", DraftCodec.attachments(staged.files))
        val target = file(id)
        val temp = File(dir, "$id.tmp")
        try {
            temp.writeText(json.toString())
            if (!temp.renameTo(target)) { target.delete(); check(temp.renameTo(target)) { "Couldn't keep the shared files" } }
        } finally { temp.delete() }
    }

    /**
     * Shares left behind (Android ended Planner while one was being copied, or before it opened): staging files older than
     * [maxAgeMs], with leftover .tmp files. Returns the attachment files they held, for the caller to release (only those
     * nothing uses are then removed). A share still open after so long reads as gone ("Share them again").
     */
    fun sweep(now: Long = System.currentTimeMillis(), maxAgeMs: Long = 7 * 24 * 60 * 60_000L): List<String> {
        val old = dir.listFiles().orEmpty().filter { now - it.lastModified() > maxAgeMs }
        val files = old.filter { it.name.endsWith(".json") }.flatMap { f -> read(f.name.removeSuffix(".json"))?.files.orEmpty().map { it.fileName } }
        old.forEach { it.delete() }
        return files
    }

    fun read(id: String): SharedFiles.Staged? = runCatching {
        val json = org.json.JSONObject(file(id).readText())
        SharedFiles.Staged(json.optString("caption").takeIf { json.has("caption") && !json.isNull("caption") },
            json.optString("subject").takeIf { json.has("subject") && !json.isNull("subject") },
            DraftCodec.attachments(json.optJSONArray("files")))
    }.getOrNull()

    fun delete(id: String) { file(id).delete() }
}
