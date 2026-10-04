package com.example.itinerary.data

import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Every example from the sharing and Quick entry bug hunts (#7 SQ-*, #8 SQ8-*, #9 SQ9-*, #10 SQX-*), the reviewers' "checked and
 * clean" sets among them, run through the code and compared with what they gave when last checked. A fix that changes
 * one of them shows here, so this area can't regress quietly again. `resources/sharequick/cases.txt` holds the inputs
 * (blocks split by "====", the first line the kind of read, "S:<subject>|" before it sharing with that subject, "@today"
 * setting the day); `expected.txt` the results. When a
 * change is meant, read the differences in build/sharequick-actual.txt and copy that file over expected.txt.
 */
class ShareQuickCasesTest {
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\uFEFF", "\\uFEFF")

    private fun read(mode: String, text: String, today: LocalDate, subject: String?): String = runCatching {
        when (mode) {
            "msg" -> esc(EmailHeaders.message(text))
            "read" -> esc(EmailHeaders.read(text).toString())
            "draft" -> SharedText.draft(text, subject).let { "title=${esc(it.title)} | body=${esc(it.body)} | notes=${esc(it.notes)}" }
            "find" -> SharedDates.find(SharedText.draft(text, subject).body, today).toString()
            "findnow" -> SharedDates.find(SharedText.draft(text, subject).body, today, LocalTime.of(10, 0)).toString()
            "sent" -> SharedDates.sentences(text).joinToString(" | ", "[", "]") { esc(it) }
            "bill" -> {
                val body = SharedText.draft(text, subject).body
                val found = SharedDates.find(body, today, LocalTime.of(10, 0))
                "$found || ${BillSuggestions.parseMessage(SharedDates.opening(body), found.date, today)}"
            }
            "qe" -> QuickEntry.parse(text, today).let {
                "title=${esc(it.title)} date=${it.date}${if (it.endDate != null) "..${it.endDate}" else ""} time=${it.time} " +
                    "error=${it.error} choices=${it.timeChoices} dateChoices=${it.dateChoices} specified=${it.dateSpecified} " +
                    "repeat=${it.repeat.kind} minutes=${it.durationMinutes}"
            }
            else -> "?mode"
        }
    }.getOrElse { "THROW $it" }

    @Test fun everyCaseGivesWhatItGaveBefore() {
        val cases = javaClass.getResource("/sharequick/cases.txt")!!.readText()
        val expected = javaClass.getResource("/sharequick/expected.txt")?.readText().orEmpty().split("\n\n").filter { it.isNotBlank() }
        var today = LocalDate.of(2026, 10, 5)
        val actual = mutableListOf<String>()
        for (block in cases.split("\n====\n")) {
            val label = block.substringBefore('\n').trim()
            val text = block.substringAfter('\n').removeSuffix("\n")
            if (label == "@today") { today = LocalDate.parse(text.lines().first().trim()); continue }
            // "S:<subject>|<kind>": shared with that subject, as the app's share passes it (hunt #10).
            val subject = label.takeIf { it.startsWith("S:") }?.substring(2)?.substringBefore('|')
            val mode = if (subject != null) label.substringAfter('|') else label
            actual += "[$label] ${esc(text)}\n   -> ${read(mode, text, today, subject)}"
        }
        File("build/sharequick-actual.txt").apply { parentFile?.mkdirs() }.writeText(actual.joinToString("\n\n") + "\n")
        val differ = actual.indices.filter { actual[it] != expected.getOrNull(it)?.trim('\n') }
        if (differ.isNotEmpty() || expected.size != actual.size) fail("${differ.size} of ${actual.size} cases differ (expected ${expected.size}):\n" +
            differ.take(30).joinToString("\n\n") { "WAS ${expected.getOrNull(it)}\nNOW ${actual[it]}" })
    }
}
