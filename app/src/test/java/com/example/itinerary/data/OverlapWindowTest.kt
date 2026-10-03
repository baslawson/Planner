package com.example.itinerary.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

// UI-6: the clash check that tries each event only against the planned dates that could reach it finds exactly what
// trying every date against every event found.
class OverlapWindowTest {
    // overlappingEvents as it was before UI-6.
    private fun old(events: List<ItineraryItem>, dates: List<LocalDate>, time: LocalTime?, minutes: Int?, excluded: Set<Long>,
                    before: Int = 0, after: Int = 0): List<ItineraryItem> {
        if (time == null) return emptyList()
        fun buffered(m: Int?, b: Int, a: Int): Int? = if (m == null && b == 0 && a == 0) null else (m ?: 0) + b + a
        return events.filter { other ->
            other.category != "Bills" && !other.skipped && other.id !in excluded && other.startTime != null && dates.any { date ->
                overlaps(date.atTime(time), minutes, other.date.atTime(other.startTime), other.durationMinutes) ||
                overlaps(date.atTime(time).minusMinutes(before.toLong()), buffered(minutes, before, after),
                    other.date.atTime(other.startTime).minusMinutes(other.bufferBeforeMinutes.toLong()),
                    buffered(other.durationMinutes, other.bufferBeforeMinutes, other.bufferAfterMinutes))
            }
        }
    }

    private val origin = LocalDate.of(2026, 12, 20) // series cross the year end
    private fun Random.minutes(): Int? = when (nextInt(10)) {
        0, 1 -> null
        2 -> 1440
        3 -> nextInt(1, 30)
        // Stored events aren't held to the editor's limits (imports): zero, negative and multi-day lengths too.
        4 -> listOf(0, -30, 3000, 10_000).random(this)
        else -> nextInt(1, 1441)
    }
    private fun Random.buffer(): Int = when (nextInt(6)) { 0, 1, 2 -> 0; 3 -> 1440; 4 -> listOf(-15, 2000).random(this); else -> nextInt(0, 1441) }
    // Whole minutes mostly, sometimes seconds; midnight and late evening often, for overnight cases.
    private fun Random.time(): LocalTime = when (nextInt(5)) {
        0 -> LocalTime.MIDNIGHT
        1 -> LocalTime.of(23, nextInt(0, 60))
        2 -> LocalTime.of(nextInt(24), nextInt(60), nextInt(60))
        else -> LocalTime.of(nextInt(24), nextInt(0, 60))
    }
    private fun Random.events(n: Int, span: Int) = List(n) { i ->
        ItineraryItem(id = i.toLong(), tripId = 1, date = origin.plusDays(nextLong(-10, span + 10L)),
            startTime = if (nextInt(8) == 0) null else time(), title = "e$i", durationMinutes = minutes(),
            bufferBeforeMinutes = buffer(), bufferAfterMinutes = buffer(),
            category = if (nextInt(15) == 0) "Bills" else Categories.OTHER, skipped = nextInt(20) == 0)
    }

    @Test fun sameClashesAsBefore() {
        val random = Random(20261003)
        var found = 0
        repeat(4000) {
            val span = listOf(1, 7, 40, 400).random(random)
            val events = random.events(random.nextInt(0, 60), span)
            val dates = List(random.nextInt(0, 30)) { origin.plusDays(random.nextLong(0, span.toLong())) }.let {
                if (random.nextBoolean()) it.sorted() else it } // planned dates may repeat and come in any order
            val time = if (random.nextInt(20) == 0) null else random.time()
            val minutes = random.minutes()
            val excluded = events.filter { random.nextInt(10) == 0 }.mapTo(HashSet()) { it.id }
            val before = random.buffer(); val after = random.buffer()
            val expected = old(events, dates, time, minutes, excluded, before, after)
            assertEquals(expected, overlappingEvents(events, dates, time, minutes, excluded, before, after))
            found += expected.size
        }
        // The cases do clash, not only miss.
        org.junit.Assert.assertTrue("only $found clashes", found > 1000)
    }

    @Test fun timingYearSeriesAgainstThreeThousandEvents() {
        val random = Random(5)
        val events = List(3000) { i ->
            ItineraryItem(id = i.toLong(), tripId = 1, date = origin.plusDays(random.nextLong(0, 400)),
                startTime = LocalTime.of(random.nextInt(24), random.nextInt(0, 60)), title = "e$i",
                durationMinutes = random.nextInt(15, 180), bufferBeforeMinutes = random.nextInt(0, 30), bufferAfterMinutes = random.nextInt(0, 30))
        }
        val dates = List(365) { origin.plusDays(it.toLong()) }
        val time = LocalTime.of(9, 0)
        fun median(block: () -> Unit): Double {
            repeat(3) { block() }
            return List(9) { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1e6 }.sorted()[4]
        }
        lateinit var a: List<ItineraryItem>; lateinit var b: List<ItineraryItem>
        val before = median { a = old(events, dates, time, 60, emptySet(), 15, 15) }
        val after = median { b = overlappingEvents(events, dates, time, 60, emptySet(), 15, 15) }
        assertEquals(a, b)
        println("UI-6 timing (JVM, 365 dates x 3000 events, median of 9): before %.1f ms, after %.1f ms, %d clashes".format(before, after, b.size))
    }
}
