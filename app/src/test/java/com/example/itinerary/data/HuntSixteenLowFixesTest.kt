package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// Low items from bug hunt 16 (qa/bughunt-16: T16-2, T16-3, N16-3, N16-4), as approved on 6 Oct.
class HuntSixteenLowFixesTest {
    private val monday = LocalDate.of(2026, 10, 5)
    private fun oct(day: Int) = LocalDate.of(2026, 10, day)

    // T16-2: short day names joined by "or" ask which date, as the long forms do, and leave the title alone.
    @Test fun shortWeekdaysJoinedByOrAskWhichDate() {
        QuickEntry.parse("Market Sat or Sun", monday).let {
            assertEquals("Market", it.title); assertEquals(listOf(oct(10), oct(11)), it.dateChoices)
        }
        QuickEntry.parse("Market Sun or Sat", monday).let {
            assertEquals("Market", it.title); assertEquals(listOf(oct(11), oct(10)), it.dateChoices)
        }
        QuickEntry.parse("Mow lawn Wed or Thu", monday).let {
            assertEquals("Mow lawn", it.title); assertEquals(listOf(oct(7), oct(8)), it.dateChoices)
        }
        QuickEntry.parse("Mow lawn Sat or Sun 3pm", monday).let {
            assertEquals("Mow lawn", it.title); assertEquals(listOf(oct(10), oct(11)), it.dateChoices)
            assertEquals(LocalTime.of(15, 0), it.time)
        }
        // As before: the long forms, and "or" before an ordinary word keeps the short one a title word.
        assertEquals(listOf(oct(10), oct(11)), QuickEntry.parse("Mow lawn Saturday or Sunday", monday).dateChoices)
        QuickEntry.parse("Buy sun or rain hat", monday).let {
            assertEquals("Buy sun or rain hat", it.title); assertFalse(it.dateSpecified)
        }
    }

    // T16-3: "every month on the last day" starts on this month's last day, whatever its length, and stays on month ends.
    @Test fun lastDayOfEveryMonthStartsThisMonthAndKeepsToMonthEnds() {
        fun parse(today: LocalDate) = QuickEntry.parse("Rent every month on the last day", today)
        parse(LocalDate.of(2026, 11, 5)).let {
            assertNull(it.error); assertEquals(RepeatRule.MONTHLY, it.repeat); assertEquals(LocalDate.of(2026, 11, 30), it.date)
            assertEquals(31, it.repeatAnchorDay)
        }
        assertEquals(LocalDate.of(2027, 2, 28), parse(LocalDate.of(2027, 2, 5)).date)
        assertEquals(LocalDate.of(2028, 2, 29), parse(LocalDate.of(2028, 2, 5)).date)
        assertEquals(LocalDate.of(2026, 9, 30), parse(LocalDate.of(2026, 9, 5)).date)
        assertEquals(LocalDate.of(2026, 10, 31), parse(monday).date)
        assertEquals(LocalDate.of(2026, 11, 30), QuickEntry.parse("Rent the last day of every month", LocalDate.of(2026, 11, 5)).date)
        // The literal 31st is unchanged: the next month that has one.
        assertEquals(LocalDate.of(2026, 12, 31), QuickEntry.parse("Rent on the 31st of every month", LocalDate.of(2026, 11, 5)).date)

        // An event or bill series: each month's last day, not the 30th from November on.
        val zone = ZoneId.of("Australia/Sydney")
        val now = ZonedDateTime.of(2026, 11, 5, 9, 0, 0, 0, zone)
        val event = QuickInput("Rent every month on the last day", baseDate = now.toLocalDate()).suggestion(now)
        assertNull(event.quickProblem(false, now))
        assertEquals(listOf(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28),
            LocalDate.of(2027, 3, 31)), event.repeat.dates(event.date, 5, event.repeatAnchorDay))
        // Counted to the end date on the same month ends: Nov, Dec, Jan, Feb, Mar.
        assertEquals(5, QuickEntry.parse("Rent every month on the last day until 31 March", LocalDate.of(2026, 11, 5)).repeatCount)
        // A task: due 30 Nov, then 31 Dec.
        val task = QuickInput("Pay rent every month on the last day", task = true, baseDate = now.toLocalDate()).suggestion(now).quickTask()
        assertEquals(LocalDate.of(2026, 11, 30), task.dueDate); assertEquals(31, task.repeatAnchorDay)
        assertEquals(LocalDate.of(2026, 12, 31), task.nextOccurrence(LocalDate.of(2026, 11, 30), zone)!!.dueDate)
        // Without an anchor, a series keeps to its start date as before.
        assertEquals(listOf(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31)),
            RepeatRule.MONTHLY.dates(LocalDate.of(2027, 1, 31), 3))
        assertEquals(listOf(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 30)), RepeatRule.MONTHLY.dates(LocalDate.of(2026, 11, 30), 2))
    }

    // N16-3: an untitled note keeps no title when Nextcloud's is only its first line tidied.
    @Test fun nextcloudTidiedFirstLineIsNotATitle() {
        fun remote(title: String, content: String) = RemoteNote(1, "e", title, content, "", false)
        assertEquals("", NoteMapping.localTitle(remote("[ ] milk", "- [ ] milk\n- [ ] eggs")))
        assertEquals("", NoteMapping.localTitle(remote("milk", "- milk")))
        assertEquals("", NoteMapping.localTitle(remote("Meeting 102", "Meeting: 10/2")))
        // A title of its own stays.
        assertEquals("Shopping", NoteMapping.localTitle(remote("Shopping", "- [ ] milk")))
        assertEquals("🎄", NoteMapping.localTitle(remote("🎄", "🎁")))
    }

    // N16-4: spaces beside slashes go, so "Work /" and "/ Work" are the "Work" notebook; cleaning twice changes nothing.
    @Test fun notebookNamesTrimSpacesBesideSlashes() {
        for (name in listOf("Work /", "/ Work", " Work ", "Work/", " / Work / ")) assertEquals(name, "Work", Notes.cleanNotebook(name))
        assertEquals("Parent/Child", Notes.cleanNotebook("Parent/Child"))
        assertEquals("Parent/Child", Notes.cleanNotebook("Parent / Child /"))
        val long = "A".repeat(Notes.MAX_NOTEBOOK - 1) + " B"
        for (name in listOf("Work /", "/ Work", long, "x/ " + "y".repeat(120)))
            Notes.cleanNotebook(name).let { assertEquals(name, it, Notes.cleanNotebook(it)); assertTrue(it.length <= Notes.MAX_NOTEBOOK) }
        // Sync leaves a category cleaning would rename untouched, so nothing is renamed on the way back.
        fun fits(category: String) = NoteMapping.fits(RemoteNote(1, "e", "t", "c", category, false))
        assertTrue(fits("Parent/Child")); assertTrue(fits("Work"))
        assertFalse(fits("Work /")); assertFalse(fits("Parent / Child"))
    }
}
