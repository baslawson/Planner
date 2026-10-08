package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

// Bug hunt 23 (8 Oct 2026): quick entry ranges with a repeat, until a weekday, checklist line ends, BPAY numbers, and
// what MyBudget's messages carry.
class BugHunt23Test {
    private val today = LocalDate.of(2026, 10, 8) // a Thursday
    private fun parse(text: String) = QuickEntry.parse(text, today)

    // P2: a date range with a repeat is when the repeat runs, each occurrence one day.
    @Test fun aRangeWithARepeatIsWhenItRuns() {
        val s = parse("Physio daily 12-16 Oct")
        assertNull(s.error)
        assertEquals("Physio", s.title)
        assertEquals(LocalDate.of(2026, 10, 12), s.date)
        assertNull(s.endDate)
        assertEquals(RepeatRule.DAILY, s.repeat)
        assertEquals(5, s.repeatCount)
        assertTrue(s.repeatCountSpecified)
        // Ended two ways: asked.
        assertNotNull(parse("Physio daily 12-16 Oct for 3 times").error)
        // Hunt 24 E6: one occurrence in the range keeps the older meaning, an entry over the days that repeats.
        parse("Physio weekly 12-14 Oct").let { assertNull(it.error); assertEquals(LocalDate.of(2026, 10, 14), it.endDate); assertEquals(RepeatRule.WEEKLY, it.repeat) }
        // Without a repeat it is still one entry over the days.
        parse("Trip 12-16 Oct").let { assertNull(it.error); assertEquals(LocalDate.of(2026, 10, 16), it.endDate) }
    }

    // A repeat "until Friday" that starts next week ends on the Friday after it starts.
    @Test fun untilAWeekdayCountsFromTheStart() {
        val s = parse("Run every day starting next Monday until Friday")
        assertNull(s.error)
        assertEquals(LocalDate.of(2026, 10, 12), s.date)
        assertEquals(5, s.repeatCount)
        // From today, as before.
        assertEquals(2, parse("Run every day until Friday").repeatCount)
    }

    @Test fun checklistTicksKeepWindowsAndOldMacLineEnds() {
        val crlf = "Shopping\r\n- [ ] milk\r\n- [ ] bread"
        assertEquals("Shopping\r\n- [x] milk\r\n- [ ] bread", Markdown.toggle(crlf, 1))
        assertEquals("Shopping\r\n- [ ] milk\r\n- [x] bread", Markdown.toggle(crlf, 2))
        val cr = "Shopping\r- [ ] milk\r- [ ] bread"
        assertEquals("Shopping\r- [ ] milk\r- [x] bread", Markdown.toggle(cr, 2))
        assertEquals(cr, Markdown.toggle(cr, 3))
        assertEquals(cr, Markdown.toggle(cr, -1))
    }

    @Test fun bpayNumbersThatArePartOfSomethingElseAreLeftOut() {
        fun ref(line: String) = BillSuggestions.parse("Amount due: $50.00\nBPAY Biller Code: 4321\n$line").bpayReference
        assertNull(ref("Ref 2026/123"))
        assertNull(ref("Ref 01/10/2026"))
        assertEquals("99887766", ref("Ref 99887766."))
        assertEquals("99887766", ref("Ref: 9988 7766"))
    }

    @Test fun undoneCarriesItsCurrencyAndOldLinesReadAsAud() {
        val undone = BudgetLink.Message.Undone("pay-1", "USD")
        assertEquals(undone, BudgetLink.decode(BudgetLink.encode(undone)))
        assertEquals(BudgetLink.Message.Undone("pay-1", "AUD"), BudgetLink.decode("U\tpay-1"))
    }
}
