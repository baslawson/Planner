package com.example.itinerary.data

import java.io.File
import java.net.URLEncoder

/**
 * Report a bug (wish list #6): a new issue on Planner's public GitHub, opened in the browser already filled in. Nothing is
 * sent by Planner itself: the person reads the report in Planner, then submits it on GitHub with their own account. Only
 * what they wrote, the app and Android versions and the phone model go in, and the last crash only if they tick it;
 * never events, tasks, notes or Nextcloud details. A crash's own messages can quote text Planner was handling (a file
 * name, a date it could not read), so the dialog says to check it before sending (SR-4).
 */
object BugReport {
    const val NEW_ISSUE = "https://github.com/baslawson/Planner/issues/new"
    // Browsers and GitHub refuse very long links; the crash, then the text, is shortened to keep under this.
    const val MAX_URL = 7_500

    fun title(description: String): String =
        description.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.takeWhole(80) ?: "Bug report"

    fun body(description: String, version: String, android: String, device: String, crash: String?): String = buildString {
        append(description.trim().ifEmpty { "(No description)" })
        append("\n\n---\nPlanner $version · Android $android · $device")
        if (crash != null) append("\n\nLast crash:\n```\n").append(crash.trim()).append("\n```")
    }

    /**
     * A report as it goes in the link: [body] is exactly what is sent, so the preview shows this one (SR-2), with what had
     * to be shortened to keep the link under [MAX_URL]: the crash first, then the person's own text (SR-3).
     */
    data class Report(val body: String, val url: String, val crashCut: Boolean, val crashLeftOut: Boolean, val descriptionCut: Boolean)

    fun report(description: String, version: String, android: String, device: String, crash: String?): Report {
        val title = encode(title(description))
        fun build(d: String, c: String?) = NEW_ISSUE + "?title=" + title + "&body=" + encode(body(d, version, android, device, c))
        var cut = crash
        var link = build(description, cut)
        var keep = crash?.length ?: 0
        while (link.length > MAX_URL && cut != null) {
            keep = (keep * 3 / 4).coerceAtMost(keep - 100)
            cut = if (keep > 0) shortenCrash(crash!!, keep) else null
            link = build(description, cut)
        }
        // SR-3: non-Latin text takes up to 12 link characters each, so the text itself can be too long even without the crash.
        var text = description
        var textKeep = description.trim().length
        while (link.length > MAX_URL && textKeep > 0) {
            textKeep = (textKeep * 3 / 4).coerceAtMost(textKeep - 20)
            text = description.trim().takeWhole(textKeep.coerceAtLeast(0)).trimEnd() + "\n…"
            link = build(text, null)
        }
        return Report(body(text, version, android, device, cut), link, crashCut = cut != null && cut != crash,
            crashLeftOut = crash != null && cut == null, descriptionCut = text !== description)
    }

    /** The link that opens the new issue; see [report]. */
    fun url(description: String, version: String, android: String, device: String, crash: String?): String =
        report(description, version, android, device, crash).url

    /**
     * The top [keep] characters of a crash, then the "Caused by:" lines it lost (SR-2): in a wrapped exception the root
     * cause is at the bottom, and it is the part that tells most.
     */
    internal fun shortenCrash(crash: String, keep: Int): String {
        val head = crash.takeWhole(keep)
        val causes = crash.substring(head.length).lineSequence().map { it.trim() }.filter { it.startsWith("Caused by:") }
            .map { it.takeWhole(300) }.toList()
        return head.trimEnd() + "\n…" + causes.joinToString("") { "\n$it" }
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}

/** The first [n] characters, one fewer rather than half an emoji (a lone surrogate would go in the link as "?", SR-3). */
fun String.takeWhole(n: Int): String = if (n in 1 until length && this[n - 1].isHighSurrogate()) take(n - 1) else take(n)

/**
 * The last time Planner crashed, kept on this phone only (files/last-crash.txt, left out of Android backup and transfer,
 * SR-5), for a bug report to include if the person ticks it. Written by an uncaught-exception handler just before Android
 * ends the app; the next crash replaces it, and a report that took it clears it.
 */
class CrashLog(private val dir: File) {
    // [sent]: opened in a report already (AB-1): still kept, as the person may not have submitted it, but not ticked again.
    data class Crash(val at: Long, val text: String, val sent: Boolean = false)

    private val file get() = File(dir, "last-crash.txt")
    private val sentFile get() = File(dir, "last-crash.sent")

    fun write(at: Long, version: String, error: Throwable) {
        // SR-6: not Log.getStackTraceString, which gives "" when any cause is an UnknownHostException (a crash while offline).
        val trace = error.stackTraceToString().takeWhole(MAX_TEXT)
        runCatching { file.writeText("$at\n$version\n$trace"); sentFile.delete() }
    }

    fun read(): Crash? = runCatching {
        val lines = file.takeIf { it.isFile }?.readText()?.split('\n', limit = 3) ?: return null
        Crash(lines[0].toLong(), "Planner ${lines[1]}\n${lines.getOrElse(2) { "" }}".trim(),
            sent = runCatching { sentFile.readText().toLong() }.getOrNull() == lines[0].toLong())
    }.getOrNull()

    fun clear() { runCatching { file.delete(); sentFile.delete() } }

    /** The crash of [at] went into a report. */
    fun markSent(at: Long) { runCatching { sentFile.writeText(at.toString()) } }

    companion object {
        const val MAX_TEXT = 12_000

        /** Keeps the last crash, then lets Android end the app as it would have. */
        fun install(dir: File, version: () -> String) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { CrashLog(dir).write(System.currentTimeMillis(), version(), error) }
                previous?.uncaughtException(thread, error)
            }
        }
    }
}
