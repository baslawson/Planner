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
        val text = "Subject: Order shipped\nDate: 6 Oct 2026 08:00\nFrom: Shop <shop@example.com>\nTo: Me <me@example.org>\n\nYour parcel is on its way."
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

    // Only a message's opening is read, so a long one can't hold the share up (SH-5): a day after it isn't seen.
    @Test fun onlyTheOpeningOfALongMessageIsRead() {
        val filler = (1..200).joinToString("\n") { "Line $it of the newsletter with nothing in particular to say." }
        assertTrue(filler.length > SharedDates.MAX_READ)
        assertEquals(emptyList<LocalDate>(), SharedDates.find("$filler\nSee you on 20 October 2026.", today).dates)
        assertEquals(LocalDate.of(2026, 10, 20), SharedDates.find("See you on 20 October 2026.\n$filler", today).date)
        val start = System.nanoTime()
        SharedDates.find("a".repeat(20_000), today)
        assertTrue(SharedDates.find(filler.repeat(3), today).dates.isEmpty())
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
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

    // Bug hunt #6: realistic messages, where only a plain appointment day counts.

    @Test fun aContactCardIsNoEmail() {
        val card = "Name: Plumber\nContact: bob@example.com\nAddress: 12 High St\nPrice: \$120\n\nCall him Monday"
        assertEquals(SharedDraft("Name: Plumber", card), SharedText.draft(card))
    }

    @Test fun emailsWithoutASubjectAnEmptyBodyOrFoldedHeaders() {
        // No subject: Thunderbird leaves the line out, so the first line is the sent date, never the title.
        val noSubject = SharedText.draft("Date: 4 Oct 2026 09:14\nFrom: A <a@example.com>\nTo: B <b@example.com>\n\nSee you soon\nA")
        assertEquals("See you soon", noSubject.title)
        // An empty body: still an email, titled from its subject, with only the sender kept.
        val empty = SharedText.draft("Subject: Photos\nDate: 4 Oct 2026 09:14\nFrom: A <a@example.com>\nTo: B <b@example.com>")
        assertEquals("Photos", empty.title); assertEquals("From: A <a@example.com>", empty.notes)
        // A subject with an address in it is still the subject, and From is still the sender.
        val about = SharedText.draft("Subject: Mail from a@shop.example\nDate: 4 Oct 2026 09:14\nFrom: A <a@example.com>\nTo: B <b@example.com>\n\nHi")
        assertEquals("Mail from a@shop.example", about.title); assertTrue(about.notes.startsWith("From: A <a@example.com>"))
        // Headers folded over two lines, with Windows line ends.
        val folded = SharedText.draft("Subject: A very long\r\n subject line\r\nDate: 4 Oct 2026 09:14\r\nFrom: A <a@example.com>\r\nTo: B <b@example.com>,\r\n C <c@example.com>\r\n\r\nHi")
        assertEquals("A very long subject line", folded.title); assertEquals("Hi", folded.body)
    }

    @Test fun boilerplateNamesNoDay() {
        listOf("Open Monday to Friday 9am-5pm", "Call us 24/7.", "This offer is valid until 30 November.", "We spoke on Monday.",
            "Add 1/2 cup of sugar.", "Score 3-1 at half time.", "Open 9-5.", "We meet every Monday.", "Please reply by end of day.",
            "Your delivery is scheduled for tomorrow.", "Our office is open Monday to Friday.").forEach {
            assertEquals(it, emptyList<LocalDate>(), SharedDates.find(it, today).dates)
        }
    }

    @Test fun theAppointmentNotTheSignature() {
        val email = "Subject: Your appointment\nDate: 2 Oct 2026 09:14\nFrom: The Clinic <clinic@example.com>\nTo: Me <me@example.org>\n\n" +
            "Hi Bas,\nYour appointment is confirmed for Tuesday 13 October at 2pm.\nKind regards\nThe Clinic\nOpen Monday to Friday 9am-5pm"
        assertEquals(SharedWhen(LocalDate.of(2026, 10, 13), LocalTime.of(14, 0), listOf(LocalDate.of(2026, 10, 13))),
            SharedDates.find(SharedText.draft(email).body, today))
        // A weekday with a time is a day; the table is on Saturday 10 October.
        assertEquals(LocalDate.of(2026, 10, 10), SharedDates.find("Your table is booked for Saturday at 7pm.", today).date)
    }

    @Test fun aQuotedEarlierMessageAndASignatureAreNotRead() {
        val reply = "Sounds good, see you then.\nOn Sun, 4 Oct 2026 at 08:00, B <b@example.com> wrote:\n> Lunch on 20 October at 1pm?"
        assertEquals(emptyList<LocalDate>(), SharedDates.find(SharedText.draft(reply).body, today).dates)
        val quoted = "Yes please.\nAm 4. Okt. 2026 schrieb B:\n> Dinner on 21 October at 7pm?"
        assertEquals(emptyList<LocalDate>(), SharedDates.find(SharedText.draft(quoted).body, today).dates)
        val signed = "Thanks!\n-- \nJo, open 1 November for bookings"
        assertEquals(emptyList<LocalDate>(), SharedDates.find(SharedText.draft(signed).body, today).dates)
    }

    @Test fun abbreviationsDontEndASentence() {
        assertEquals(SharedWhen(LocalDate.of(2026, 10, 12), LocalTime.of(15, 0), listOf(LocalDate.of(2026, 10, 12))),
            SharedDates.find("Your appointment is on Oct. 12 at 3pm.", today))
        assertEquals(SharedWhen(LocalDate.of(2026, 10, 9), LocalTime.of(10, 0), listOf(LocalDate.of(2026, 10, 9))),
            SharedDates.find("See you at 10 a.m. on Friday 9 October.", today))
    }

    @Test fun aTimeAlreadyGoneTodayIsLeftOut() {
        assertEquals(emptyList<LocalDate>(), SharedDates.find("Call on 4 October 2026 at 8am.", today, LocalTime.of(10, 0)).dates)
        assertEquals(today, SharedDates.find("Call on 4 October 2026 at 4pm.", today, LocalTime.of(10, 0)).date)
    }

    @Test fun billAmountWithACurrencyBeatsABareTotalAndAPastDueDateIsLeft() {
        assertEquals(8420L to "EUR", BillSuggestions.parseMessage("Items in total: 3\nAmount EUR 84.20", null).let { it.amount to it.currency })
        assertNull(BillSuggestions.parseMessage("Due date: 2026-09-01\nEUR 84.20", null, today).date)
        assertEquals(LocalDate.of(2026, 10, 20), BillSuggestions.parseMessage("Due date: 2026-10-20\nEUR 84.20", null, today).date)
    }

    // R18-Q2: a day without a year that has just gone is past, not next year's. Today is Wednesday 7 October 2026.
    @Test fun aDayJustGoneIsNotNextYears() {
        val oct7 = LocalDate.of(2026, 10, 7)
        assertEquals(emptyList<LocalDate>(), SharedDates.find("Your bill of \$80 was due on 3 October. Please pay now.", oct7).dates)
        assertEquals(emptyList<LocalDate>(), SharedDates.find("Payment received on 2 October, thank you.", oct7).dates)
        assertEquals(LocalDate.of(2026, 10, 25), SharedDates.find("Statement date: 1 Oct\nTotal: \$80\nDue: 25 Oct", oct7).date)
        // Longer ago than six months, or with its year written, it is still next year's or that year's.
        assertEquals(LocalDate.of(2027, 3, 1), SharedDates.find("Your lease renews on 1 March.", oct7).date)
        assertEquals(LocalDate.of(2027, 10, 3), SharedDates.find("The conference is on 3 October 2027.", oct7).date)
    }

    // R18-Q5: money coming back is no bill, and a dollar with its country keeps its currency.
    @Test fun refundsCreditsAndCountryDollars() {
        fun bill(text: String) = BillSuggestions.parseMessage(text, null).let { it.amount to it.currency }
        for (text in listOf("Refund of -\$45 processed", "Total: -\$45.00", "Closing balance: \$45.00 CR", "Credit of \$45 applied",
            "Your refund of \$45 is on its way.", "We have credited \$45 to your account.", "Cashback \$45 paid", "Reversal of \$45 done",
            "Balance (\$45.00)", "Balance −\$45.00")) {
            assertEquals(text, null to null, bill(text))
        }
        assertEquals(12000L to "AUD", bill("Amount due AUD\$120 by 15 Oct"))
        assertEquals(8950L to "NZD", bill("NZ\$ 89.50 due Friday"))
        for ((written, code) in listOf("A\$" to "AUD", "AU\$" to "AUD", "AUD\$" to "AUD", "NZ\$" to "NZD", "NZD\$" to "NZD", "US\$" to "USD",
            "C\$" to "CAD", "CA\$" to "CAD", "S\$" to "SGD", "SG\$" to "SGD")) {
            assertEquals(written, 4500L to code, bill("Please pay ${written}45.00 by Friday."))
            assertEquals(written, 4500L to code, BillSuggestions.parse("Amount due: ${written}45.00").let { it.amount to it.currency })
        }
        // A refund in another sentence leaves this one's bill; a credit card is no credit.
        assertEquals(8000L to "AUD", bill("Last month's refund went through. Please pay AUD 80 by Friday."))
        assertEquals(8000L to "AUD", bill("Your credit card bill of AUD 80 is due."))
        assertEquals(123456L to "AUD", bill("Pay AUD 1,234.56 now"))
        assertEquals(123456L to "EUR", bill("Betrag 1.234,56 €"))
        assertEquals(2050L to "AUD", bill("Pay Sam AUD 20.50"))
    }
}
