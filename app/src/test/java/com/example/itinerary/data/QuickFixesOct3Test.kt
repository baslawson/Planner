package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// Bug hunt 3 Oct 2026 (Quick entry): overlapping phrases, a part of the day inside a name, numeric dates beside one.
class QuickFixesOct3Test {
    private val zone = ZoneId.of("Australia/Sydney")
    private val now = ZonedDateTime.of(2026, 10, 5, 14, 0, 0, 0, zone) // a Monday
    private val today = now.toLocalDate()
    private fun task(text: String) = QuickInput(text, task = true, baseDate = today).suggestion(now)
    private fun event(text: String) = QuickInput(text, baseDate = today).suggestion(now)
    private fun oct(day: Int) = LocalDate.of(2026, 10, day)

    // QE-1: two phrases that overlap (a part of the day after "at", "or" across "all day" or a place) crashed the parse.
    @Test fun overlappingPhrasesNoLongerCrash() {
        for (text in listOf("Drinks at tonight with Sam", "Dinner at tonight 7pm", "Market Saturday or all day Sunday",
            "Drinks at tonite with Sam", "Coffee at arvo tea", "Drinks Friday or at the pub Saturday", "Pub at 2nite 4 Sam",
            "Drinks at this evening with Sam", "Meet Sam at every morning standup", "Golf Saturday or all afternoon Sunday",
            "Fri Friday night Monday", "1/1/9999 for 3 nights from Friday", "Drinks at 2night club")) {
            event(text).quickProblem(false, now); task(text).quickProblem(true, now)
        }
    }

    @Test fun aPartOfTheDayAfterAtIsTheTimeNotAPlace() {
        val drinks = event("Drinks at tonight with Sam")
        assertEquals("Drinks with Sam", drinks.title); assertEquals("", drinks.location)
        assertEquals(today, drinks.date); assertTrue(drinks.ambiguousTime)
        assertEquals("What time did you mean by ‘tonight’? Tap Choose time.", drinks.quickProblem(false, now))
        val dinner = event("Dinner at tonight 7pm")
        assertEquals("Dinner", dinner.title); assertEquals(LocalTime.of(19, 0), dinner.time); assertEquals("", dinner.location)
        assertNull(dinner.quickProblem(false, now))
        assertEquals("Pub 4 Sam", event("Pub at 2nite 4 Sam").title)
        assertEquals("", event("Coffee at arvo tea").location)
        // A place after the part of the day is still a place.
        val pub = event("Drinks tonight at the pub")
        assertEquals("Drinks", pub.title); assertEquals("the pub", pub.location)
    }

    @Test fun orBetweenDatesAcrossAnotherPhrase() {
        val market = event("Market Saturday or all day Sunday")
        assertEquals("Market", market.title); assertEquals(listOf(oct(10), oct(11)), market.dateChoices)
        val drinks = event("Drinks Friday or at the pub Saturday")
        assertEquals("Drinks", drinks.title); assertEquals("the pub", drinks.location)
        assertEquals(listOf(oct(9), oct(10)), drinks.dateChoices)
    }

    // Whatever is typed, the parse gives an answer or a message, never an exception.
    @Test fun parseNeverThrows() {
        val parts = listOf("Drinks", "Sam", "the pub", "Tonight Show", "at", "@", "or", "and", "with", "for", "from", "to", "on",
            "tonight", "tonite", "2nite", "2night", "arvo", "this evening", "tomorrow night", "Friday night", "last night",
            "every morning", "all day", "all afternoon", "Saturday", "next Thursday", "due", "by", "Fri", "9/10", "12/10",
            "1/1/9999", "3 nights", "for 3 nights from", "night", "7pm", "9", "10-2", "8am and 8pm", "in 2 hours", "now",
            "remind me to", "remind me 30 min before", "weekly", "every Monday", "until Friday", "for 2 hours", "actually",
            "\"Rising Sun\"", "3rd", "Christmas Day", "noon", "-", ",", "next week", "end of month", "$20", "room 4")
        val random = java.util.Random(20261003)
        val inputs = parts.flatMap { a -> parts.map { b -> "$a $b" } } +
            List(4000) { List(2 + random.nextInt(5)) { parts[random.nextInt(parts.size)] }.joinToString(" ") }
        val failures = mutableListOf<String>()
        for (text in inputs) for (task in listOf(false, true)) {
            runCatching { QuickInput(text, task = task, baseDate = today).suggestion(now).quickProblem(task, now) }
                .onFailure { failures += "${if (task) "task" else "event"} ‘$text’: $it" }
            if (failures.size >= 10) break
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // QE-2: a part of the day inside a name stays in the title, on a task and on an event.
    @Test fun aPartOfTheDayInsideANameStaysInTheTitle() {
        for (text in listOf("Watch The Tonight Show", "Book tickets for Tonight Show", "Watch Monday Night Football",
            "Buy Saturday Night Live tickets", "Read Last Night in Soho review")) {
            for (isTask in listOf(true, false)) {
                val s = if (isTask) task(text) else event(text)
                assertEquals(text, text, s.title); assertFalse(text, s.ambiguousTime); assertFalse(text, s.dateSpecified)
                assertNull(text, s.quickProblem(isTask, now))
            }
        }
        // Written in title case before a when-word it is still the time.
        val dinner = event("Dinner Tonight With Sam")
        assertEquals("Dinner With Sam", dinner.title); assertTrue(dinner.ambiguousTime)
        assertEquals(oct(9), event("Drinks Friday Night with Sam").date)
    }

    @Test fun aPartOfTheDayBeforeMoreTitleWordsIsNotEatenSilently() {
        val book = task("Book 2night club")
        assertNotNull(book.quickProblem(true, now))
        assertTrue(book.quickProblem(true, now)!!.contains("2night"))
        // At the end, or before a when-word, it still gives the due day with nothing to ask.
        for ((text, day) in listOf("Cooking tonight" to today, "Bins tomorrow night" to oct(6), "Bins Friday morning" to oct(9),
            "Drinks tonight with Sam" to today, "Bins Friday Night" to oct(9), "Call Mum tomorrow morning, re flights" to oct(6))) {
            val s = task(text)
            assertNull(text, s.quickProblem(true, now)); assertEquals(text, day, s.date); assertTrue(text, s.dateSpecified)
        }
    }

    // QE-3: "Friday night 9/10": 9/10 is the date, not 9 o'clock with "/10" left in the title.
    @Test fun aNumericDateAfterAPartOfTheDayIsADate() {
        val quiz = event("Quiz Friday night 9/10")
        assertEquals("Quiz", quiz.title); assertEquals(oct(9), quiz.date); assertNull(quiz.time); assertTrue(quiz.ambiguousTime)
        val dinner = event("Dinner Saturday night 10/10")
        assertEquals("Dinner", dinner.title); assertEquals(oct(10), dinner.date); assertNull(dinner.time)
        // The hour after the part of the day still works.
        assertEquals(LocalTime.of(21, 0), event("Quiz Friday night 9").time)
    }

    // QE-4: "12/10 night" is a date and a part of the day, not a stay of 10 nights.
    @Test fun aNumericDateBeforeNightIsNotACountOfNights() {
        val gig = QuickEntry.parse("Gig 12/10 night", today, now = now.toLocalDateTime(), dayFirst = true, zone = zone)
        assertEquals("Gig", gig.title); assertEquals(oct(12), gig.date); assertNull(gig.endDate)
        assertTrue(gig.ambiguousTime); assertEquals("What time did you mean by ‘night’? Tap Choose time.", gig.error)
        val concert = QuickEntry.parse("Concert 3/4 night", today, now = now.toLocalDateTime(), dayFirst = false, zone = zone)
        assertEquals("Concert", concert.title); assertNull(concert.endDate); assertEquals(LocalDate.of(2027, 3, 4), concert.date)
        // A task is due that day.
        QuickEntry.numericDayFirst = true
        try { task("Gig 12/10 night").let { assertEquals(oct(12), it.date); assertNull(it.quickProblem(true, now)) } }
        finally { QuickEntry.numericDayFirst = null }
        // A count of nights is still a stay, and "Movie night" a title.
        assertEquals(oct(8), event("Cabin 3 nights").endDate)
        assertEquals("Movie night", event("Movie night Friday").title)
    }

    // QE-5: "due next Thursday or Friday" keeps "next" for both.
    @Test fun dueNextWeekdayOrWeekday() {
        for (text in listOf("Report due next Thursday or Friday", "Report due by next Thursday or Friday", "Report by next Thursday or Friday",
            "Report next Thursday or Friday")) {
            val s = task(text)
            assertEquals(text, "Report", s.title); assertEquals(text, listOf(oct(15), oct(16)), s.dateChoices)
        }
    }

    // QE-6: a task with "Friday or Saturday night" asks which date, not to choose Event.
    @Test fun aTaskWithTwoDatesAndAPartOfTheDayAsksForTheDate() {
        val s = task("Bins Friday or Saturday night")
        assertEquals("Which date did you mean?", s.quickProblem(true, now))
        assertEquals(listOf(oct(9), oct(10)), s.dateChoices)
        val picked = QuickInput("Bins Friday or Saturday night", task = true, baseDate = today, dateOverride = oct(10).toString()).suggestion(now)
        assertNull(picked.quickProblem(true, now)); assertEquals(oct(10), picked.date)
    }
}
