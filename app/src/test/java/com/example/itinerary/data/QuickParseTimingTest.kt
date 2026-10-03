package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

// UI-5: how long Quick entry takes to read typical entries, typed letter by letter as the dialog sees them. Prints a
// timing (no limit: machines differ); its results must not depend on what was parsed before (patterns are kept).
class QuickParseTimingTest {
    private val today = LocalDate.of(2026, 10, 3)
    private val now = LocalDateTime.of(2026, 10, 3, 9, 30)
    private val corpus = listOf(
        "Dentist tomorrow at 3pm for 45 minutes",
        "Lunch with Sam next Friday 12:30-2pm at Cafe Luna",
        "Pay rent on the 1st of every month remind me 1 day before",
        "Gym every Mon Wed Fri 6am for 1 hour",
        "Flight to Sydney 14/11 9.45am",
        "Team meeting every 2 weeks on Tuesday 10am until 20 December",
        "Call mum tonight",
        "todo: renew passport by end of next month",
        "Book table for 4 at Rising Sun Saturday 7pm",
        "Holiday from 20th to 27th December",
        "Pick up parcel in 2 hours",
        "Doctor the first Monday in November at half past ten",
    )

    @Test fun typingTheCorpus() {
        fun run(): List<String> = corpus.flatMap { entry ->
            (1..entry.length).map { n -> val text = entry.take(n)
                val parsed = QuickEntry.parse(text, today, now = now)
                "$parsed|${quickCompletions(text, n, emptyList(), task = false)}|${QuickInput(text = entry).edited(text)}"
            }
        }
        val first = run()
        val times = List(7) { val t = System.nanoTime(); assertEquals(first, run()); (System.nanoTime() - t) / 1e6 }.sorted()
        val parses = corpus.sumOf { it.length }
        // The fingerprint of every result, to compare with a run of the code before the change.
        println("UI-5 timing (JVM): %d parses, median %.1f ms in all, %.3f ms per parse; results %08x".format(parses, times[3], times[3] / parses,
            first.joinToString("\n").hashCode()))
    }
}
