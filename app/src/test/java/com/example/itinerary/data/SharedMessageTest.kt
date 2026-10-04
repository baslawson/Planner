package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// Sharing an email to Planner: Thunderbird's header block, the day the message names, and a bill from its wording.
class SharedMessageTest {
    private val today = LocalDate.of(2026, 10, 4) // a Sunday

    // As Thunderbird's ShareIntentBuilder sends it: no EXTRA_SUBJECT, the headers on top of the text.
    private val thunderbird = """
        Subject: Dentist appointment confirmation
        Date: 2 Oct 2026 09:14
        From: Example Dental <info@example.com>
        To: Me <me@example.org>
        Cc: Other <other@example.org>

        Hello, this confirms your appointment on Wednesday 14 October at 10:30.
        Please arrive 10 minutes early.
    """.trimIndent()

    @Test fun thunderbirdShareTakesTheSubjectAndKeepsOnlyTheSender() {
        val draft = SharedText.draft(thunderbird)
        assertEquals("Dentist appointment confirmation", draft.title)
        assertEquals("From: Example Dental <info@example.com>\n\nHello, this confirms your appointment on Wednesday 14 October at 10:30.\n" +
            "Please arrive 10 minutes early.", draft.notes)
        assertFalse(draft.body.contains("2 Oct 2026"))
        assertFalse(draft.notes.contains("me@example.org"))
    }

    @Test fun headersAreReadByPositionInAnyLanguage() {
        val german = "Betreff: Re: Rechnung\nDatum: 2. Okt. 2026\nVon: Stadtwerke <rechnung@stadtwerke.example>\nAn: ich@example.org\n\nHallo"
        val draft = SharedText.draft(german)
        assertEquals("Re: Rechnung", draft.title)
        assertEquals("Von: Stadtwerke <rechnung@stadtwerke.example>\n\nHallo", draft.notes)
    }

    @Test fun messageWithoutSubjectOrDateStillDropsTheHeaders() {
        val text = "From: A <a@example.com>\nTo: B <b@example.com>\nCc: C <c@example.com>\n\nLunch on Friday?"
        val draft = SharedText.draft(text)
        assertEquals("Lunch on Friday?", draft.title)
        assertEquals("From: A <a@example.com>\n\nLunch on Friday?", draft.notes)
    }

    @Test fun anExplicitSubjectStillWins() {
        assertEquals("From the app", SharedText.draft(thunderbird, "From the app").title)
    }

    @Test fun ordinaryTextIsNotTakenForAnEmail() {
        val notes = "Note: buy milk\nTip: the shop shuts at 6\nAlso: bread\n\nThanks"
        assertEquals(SharedDraft("Note: buy milk", notes), SharedText.draft(notes))
        // Headers but no address, too few, or no blank line after them.
        listOf("A: 1\nB: 2\nC: 3\n\nbody", "Subject: x\nFrom: a@b.com\n\nbody", "Subject: x\nDate: y\nFrom: a@b.com\nbody",
            "Subject: x\nDate: y\nFrom: a@b.com\nnot a header\n\nbody").forEach {
            assertEquals(it, SharedText.draft(it).notes)
        }
    }

    @Test fun theOneDayAnEmailNamesWithItsUnclearTimeToPick() {
        // "10:30" could be morning or evening: Quick entry asks, so the share offers both rather than guessing.
        val found = SharedDates.find(SharedText.draft(thunderbird).body, today)
        assertEquals(SharedWhen(LocalDate.of(2026, 10, 14), null, listOf(LocalDate.of(2026, 10, 14)),
            listOf(LocalTime.of(10, 30), LocalTime.of(22, 30))), found)
    }

    @Test fun aClearTimeIsTaken() {
        val day = LocalDate.of(2026, 10, 14)
        assertEquals(SharedWhen(day, LocalTime.of(10, 30), listOf(day)),
            SharedDates.find("Your appointment is on Wednesday 14 October at 10:30am.", today))
        assertEquals(SharedWhen(day, LocalTime.of(14, 15), listOf(day)),
            SharedDates.find("Your appointment is on 14 October at 14:15. See you then.", today))
        // Two different times on the one day: the day only.
        assertEquals(SharedWhen(day, null, listOf(day)),
            SharedDates.find("Doors open 14 October at 6pm. The show starts 14 October at 7pm.", today))
    }

    @Test fun theSentDateIsNeverTaken() {
        val text = "Subject: Order shipped\nDate: 6 Oct 2026 08:00\nFrom: Shop <shop@example.com>\n\nYour parcel is on its way."
        assertEquals(SharedWhen(null, null, emptyList()), SharedDates.find(SharedText.draft(text).body, today))
    }

    @Test fun severalDaysAreListedNotGuessed() {
        val found = SharedDates.find("The course starts on 12 October. The exam is on 30 October at 9am.", today)
        assertNull(found.date); assertNull(found.time)
        assertEquals(listOf(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 30)), found.dates)
    }

    @Test fun pastDaysAndPlainTextAreLeftOut() {
        assertEquals(emptyList<LocalDate>(), SharedDates.find("We met on 1 October 2026. Thanks for coming!", today).dates)
        assertEquals(emptyList<LocalDate>(), SharedDates.find("Hello, how are you? Talk soon.", today).dates)
        // The same day twice is still one day; a time only once.
        assertEquals(SharedWhen(LocalDate.of(2026, 10, 20), null, listOf(LocalDate.of(2026, 10, 20))),
            SharedDates.find("Due 20 October 2026.\nReminder: pay by 20 October 2026.", today))
    }

    @Test fun aLongEmailIsReadQuickly() {
        val long = (1..400).joinToString("\n") { "Line $it of the newsletter with nothing in particular to say about anything." }
        val start = System.nanoTime()
        SharedDates.find(long.take(20_000), today)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 1500)
    }

    @Test fun billFromEmailWording() {
        val text = "Your electricity bill of EUR 84.20 is due on 20 October 2026."
        val bill = BillSuggestions.parseMessage(text, SharedDates.find(text, today).date)
        assertEquals(8420L, bill.amount); assertEquals("EUR", bill.currency); assertEquals(LocalDate.of(2026, 10, 20), bill.date)
    }

    @Test fun billAmountsInOtherForms() {
        assertEquals(8420L to "EUR", BillSuggestions.parseMessage("Total €84,20 please", null).let { it.amount to it.currency })
        assertEquals(123450L to "GBP", BillSuggestions.parseMessage("You owe 1,234.50 GBP.", null).let { it.amount to it.currency })
        assertEquals(123450L to "EUR", BillSuggestions.parseMessage("Betrag: 1.234,50 EUR", null).let { it.amount to it.currency })
        // A $ alone doesn't say which dollar.
        assertEquals(5000L to null, BillSuggestions.parseMessage("Please pay $50.00 soon", null).let { it.amount to it.currency })
        // The same amount written twice is one amount.
        assertEquals(8420L, BillSuggestions.parseMessage("EUR 84.20 due. Pay €84.20 by card.", null).amount)
    }

    @Test fun severalAmountsAreLeftForThePerson() {
        val bill = BillSuggestions.parseMessage("Last month EUR 70.00, this month EUR 84.20.", null)
        assertNull(bill.amount); assertNull(bill.currency)
        assertEquals("Several amounts found", bill.warnings["amount"])
    }

    @Test fun labelledTotalsStillWin() {
        val bill = BillSuggestions.parseMessage("Previous balance EUR 10.00\nAmount due: EUR 84.20\nDue date: 2026-10-20", null)
        assertEquals(8420L, bill.amount); assertEquals("EUR", bill.currency); assertEquals(LocalDate.of(2026, 10, 20), bill.date)
    }
}
