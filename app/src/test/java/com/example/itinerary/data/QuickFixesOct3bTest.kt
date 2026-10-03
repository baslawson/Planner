package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// Bug hunt #5 (3 Oct 2026, b), Quick entry: Q5-1, Q5-2, Q5-3, Q5-5, Q5-6 and the titles seen in passing.
class QuickFixesOct3bTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val now = ZonedDateTime.of(2026, 10, 5, 14, 0, 0, 0, zone) // a Monday
    private val today = now.toLocalDate()
    private fun task(text: String) = QuickInput(text, task = true, baseDate = today).suggestion(now)
    private fun event(text: String) = QuickInput(text, baseDate = today).suggestion(now)
    private fun oct(day: Int) = LocalDate.of(2026, 10, day)

    // Q5-1: written in Title Case, a part of the day before a name or after Her/My is still the when.
    @Test fun titleCaseKeepsTheWhen() {
        val meet = event("Meet Her Friday Night")
        assertEquals("Meet Her", meet.title); assertEquals(oct(9), meet.date); assertTrue(meet.dateSpecified); assertTrue(meet.ambiguousTime)
        assertEquals("What time did you mean by ‘Friday Night’? Tap Choose time.", meet.quickProblem(false, now))
        for ((text, title, day) in listOf(Triple("Dinner Tonight Sam", "Dinner Sam", today), Triple("Call Mum Tonight Please", "Call Mum Please", today),
            Triple("Drinks This Evening Sam", "Drinks Sam", today), Triple("Call Mum Tonight Re Bills", "Call Mum Re Bills", today),
            Triple("Dinner Tonight Mum And Dad", "Dinner Mum And Dad", today), Triple("Movie Friday Night Sam", "Movie Sam", oct(9)),
            Triple("Call Her Tonight", "Call Her", today), Triple("Dinner With My Sister Saturday Night", "Dinner With My Sister", oct(10)))) {
            val s = event(text)
            assertEquals(text, title, s.title); assertEquals(text, day, s.date); assertTrue(text, s.dateSpecified); assertTrue(text, s.ambiguousTime)
        }
        for ((text, place, day) in listOf(Triple("Drinks Friday Night at Luigi's", "Luigi's", oct(9)), Triple("Party Saturday Night at Jo's", "Jo's", oct(10)),
            Triple("Drinks Tonight at The Pub", "The Pub", today))) {
            val s = event(text)
            assertEquals(text, place, s.location); assertEquals(text, day, s.date); assertTrue(text, s.dateSpecified)
            assertFalse(text, s.title.contains("Night", ignoreCase = true))
        }
    }

    @Test fun titleCaseTasksAreDueThatDay() {
        for ((text, title, day) in listOf(Triple("Meet Her Friday Night", "Meet Her", oct(9)), Triple("Call Her Tonight", "Call Her", today),
            Triple("Bins Tonight Please", "Bins Please", today), Triple("Call My Mum Tomorrow Morning", "Call My Mum", oct(6)),
            Triple("Text Him Saturday Morning", "Text Him", oct(10)))) {
            val s = task(text)
            assertEquals(text, title, s.title); assertEquals(text, day, s.date); assertTrue(text, s.dateSpecified)
            assertNull(text, s.quickProblem(true, now))
        }
    }

    @Test fun namesWithAPartOfTheDayStillStayInTheTitle() {
        for (text in listOf("Watch The Tonight Show", "Book tickets for Tonight Show", "Watch Monday Night Football",
            "Buy Saturday Night Live tickets", "Read Last Night in Soho review", "Watch Friday Night Lights", "Watch Saturday Night Fever",
            "Book tickets for the Tonight Show", "Join Friday Night Club")) {
            for (isTask in listOf(true, false)) {
                val s = if (isTask) task(text) else event(text)
                assertEquals(text, text, s.title); assertFalse(text, s.ambiguousTime); assertFalse(text, s.dateSpecified)
                assertNull(text, s.quickProblem(isTask, now))
            }
        }
        // The name, then a when.
        val show = task("Book tickets for Tonight Show Friday")
        assertEquals("Book tickets for Tonight Show", show.title); assertEquals(oct(9), show.date)
        val watch = event("Watch The Tonight Show tonight")
        assertEquals("Watch The Tonight Show", watch.title); assertEquals(today, watch.date); assertTrue(watch.dateSpecified)
    }

    // Q5-2: on a task, ordinary words after the part of the day don't stop it being the due day.
    @Test fun ordinaryTasksAreDueThatDayWithoutAQuestion() {
        for ((text, title, day) in listOf(Triple("Pay bills tonight online", "Pay bills online", today), Triple("Call Mum tonight too", "Call Mum too", today),
            Triple("Finish report tonight ASAP", "Finish report ASAP", today), Triple("Gym tomorrow morning early", "Gym early", oct(6)),
            Triple("Water plants this evening again", "Water plants again", today), Triple("Call Sam Friday night maybe", "Call Sam maybe", oct(9)),
            Triple("Take bins out tonight latest", "Take bins out latest", today), Triple("Buy milk tomorrow morning first", "Buy milk first", oct(6)),
            Triple("Email boss tonight urgently", "Email boss urgently", today))) {
            val s = task(text)
            assertEquals(text, title, s.title); assertEquals(text, day, s.date); assertTrue(text, s.dateSpecified)
            assertNull(text, s.quickProblem(true, now))
        }
        // Before a word that makes it a name, it still asks.
        for ((text, said) in listOf("Book 2night club" to "2night", "Get tickets Friday night football" to "Friday night", "Watch tonight show" to "tonight")) {
            val problem = task(text).quickProblem(true, now)
            assertNotNull(text, problem); assertTrue(text, problem!!.contains("‘$said’"))
        }
    }

    // Q5-3: "2-3 nights" is a stay (the longer one), not a date.
    @Test fun aRangeOfNightsIsAStay() {
        for (text in listOf("Hotel 2-3 nights", "Hotel 2–3 nights", "Hotel 2 - 3 nights")) {
            val s = event(text)
            assertEquals(text, "Hotel", s.title); assertTrue(text, s.dateChoices.isEmpty()); assertNull(text, s.time)
            assertEquals(text, oct(8), s.endDate); assertNull(text, s.quickProblem(false, now))
        }
        val friday = event("Hotel 2-3 nights from Friday")
        assertEquals("Hotel", friday.title); assertEquals(oct(9), friday.date); assertEquals(oct(12), friday.endDate)
        assertNull(friday.time); assertFalse(friday.ambiguousTime); assertNull(friday.durationMinutes)
        assertTrue(task("Book hotel for 2-3 nights").dateChoices.isEmpty())
        assertEquals(oct(8), event("Book hotel for 2-3 nights").endDate)
        // A range before a single "night" is not a stay.
        for (text in listOf("Party 12-10 night", "Concert 3-4 night")) assertNull(text, event(text).endDate)
    }

    // Q5-5: "at" before a repeat goes with it.
    @Test fun atBeforeARepeatIsNotAPlace() {
        val meet = event("Meet at every Monday")
        assertEquals("Meet", meet.title); assertEquals("", meet.location); assertEquals(RepeatRule.WEEKLY, meet.repeat); assertEquals(today, meet.date)
        val coffee = event("Coffee at every Friday")
        assertEquals("Coffee", coffee.title); assertEquals("", coffee.location); assertEquals(oct(9), coffee.date)
        // A place's name is still a place.
        assertEquals("Every Day Cafe", event("Lunch at Every Day Cafe").location)
        assertEquals("the gym", event("Meet at the gym every Monday").location)
    }

    // Q5-6: a huge paste is refused at once, with the dialog's message, rather than parsed in full.
    @Test fun aHugePasteIsNotParsed() {
        val huge = "Tonight Show ".repeat(2000)
        event(huge) // warm up
        val started = System.nanoTime()
        val s = event(huge)
        val ms = (System.nanoTime() - started) / 1e6
        assertEquals("Use at most 500 characters per entry.", s.quickProblem(false, now))
        assertEquals("Use at most 500 characters per entry.", task("Friday or ".repeat(2000)).quickProblem(true, now))
        assertTrue("took $ms ms", ms < 300)
        // Up to the limit it is read as usual; with a title in its own box the two count together.
        val full = "Dentist " + "a".repeat(500 - "Dentist  tomorrow".length) + " tomorrow"
        assertEquals(500, full.length)
        assertEquals(oct(6), event(full).date); assertNull(event(full).quickProblem(false, now))
        assertEquals("Use at most 500 characters per entry.", event(full + "x").quickProblem(false, now))
        val boxed = QuickInput("tomorrow", baseDate = today, title = "a".repeat(495)).suggestion(now)
        assertEquals("Use at most 500 characters per entry.", boxed.quickProblem(false, now))
    }

    // Seen in passing: a possessive part of the day is title text; punctuation left by a removed phrase goes; "9-5 night".
    @Test fun titlesAndHoursSeenInPassing() {
        val paper = event("Read tonight's paper")
        assertEquals("Read tonight's paper", paper.title); assertFalse(paper.ambiguousTime); assertNull(paper.quickProblem(false, now))
        assertEquals("Plan tomorrow night’s dinner", event("Plan tomorrow night’s dinner").title)
        assertEquals("Bins", task("Bins tonight!").title)
        assertNull(task("Bins tonight!").quickProblem(true, now)); assertEquals(today, task("Bins tonight!").date)
        assertEquals("Party", event("Party tomorrow!").title)
        assertEquals("Call Mum. Ask about Xmas", task("Call Mum tomorrow. Ask about Xmas").title)
        assertEquals("Really?", event("Really?").title)
        for ((text, start, minutes) in listOf(Triple("Shift 9-5 night", LocalTime.of(21, 0), 480), Triple("Shift 10-6 night", LocalTime.of(22, 0), 480),
            Triple("Party 8-1 night", LocalTime.of(20, 0), 300), Triple("Shift 6-10 evening", LocalTime.of(18, 0), 240))) {
            val s = event(text)
            assertEquals(text, text.substringBefore(' '), s.title); assertTrue(text, s.dateChoices.isEmpty()); assertEquals(text, start, s.time)
            assertEquals(text, minutes, s.durationMinutes); assertNull(text, s.quickProblem(false, now))
        }
    }
}
