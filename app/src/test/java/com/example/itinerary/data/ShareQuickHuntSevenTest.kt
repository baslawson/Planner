package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// Bug hunt #7 (5 Oct 2026): sharing text and emails into Planner, and Quick entry (SQ-1 … SQ-15).
class ShareQuickHuntSevenTest {
    private val today = LocalDate.of(2026, 10, 4) // a Sunday
    private fun parse(text: String) = QuickEntry.parse(text, today)
    private fun days(text: String) = SharedDates.find(SharedText.draft(text).body, today).dates

    // SQ-1: a total without a currency stays; only a small whole count gives way to an amount with one.
    @Test fun aTotalWithoutACurrencyStays() {
        assertEquals(12000L to null, BillSuggestions.parseMessage("Total: 120.00\nA late fee of $5 applies after the due date.", null)
            .let { it.amount to it.currency })
        assertEquals(8420L to null, BillSuggestions.parseMessage("Total amount due: 84.20\nPaid last month: EUR 79.10", null)
            .let { it.amount to it.currency })
        assertEquals(12000L, BillSuggestions.parseMessage("Total: 120.00\nDue date: 20 October 2026\nLate fee AUD 15", null).amount)
        assertEquals(12000L, BillSuggestions.parseMessage("Total: 120\nLate fee $5", null).amount)
        // SH-6 still: a count beside the real amount.
        assertEquals(8420L to "EUR", BillSuggestions.parseMessage("Items in total:\n3\nAmount EUR 84.20", null).let { it.amount to it.currency })
    }

    // SQ-2: a short name before a capital ends its sentence; before a number it is an abbreviation.
    @Test fun shortNamesEndASentenceBeforeACapital() {
        assertEquals(emptyList<LocalDate>(), SharedDates.find("Thanks for coming on Sat. See you at 7pm.", today).dates)
        assertEquals(emptyList<LocalDate>(), SharedDates.find("We had fun on Fri. Next time 7pm works.", today).dates)
        assertEquals(LocalDate.of(2026, 10, 14), SharedDates.find("Great to see you on Mon. The party is 14 October at 7pm.", today).date)
        assertEquals(LocalDate.of(2026, 10, 10), SharedDates.find("Thanks for Fri. See you on Saturday at 8pm.", today).date)
        assertEquals(LocalDate.of(2026, 10, 14), SharedDates.find("I was at the shop on Sat. The party is 14 October at 7pm.", today).date)
        assertEquals(LocalDate.of(2026, 10, 14), SharedDates.find("We used Plan A. The party is 14 October at 7pm.", today).date)
        // Still abbreviations.
        assertEquals(LocalDate.of(2026, 10, 12), SharedDates.find("Meeting on Mon. 12 Oct at 2pm.", today).date)
        assertEquals(LocalDate.of(2026, 10, 12), SharedDates.find("Your appointment is on Oct. 12 at 3pm.", today).date)
        assertEquals(LocalDate.of(2026, 10, 9), SharedDates.find("See you at 10 a.m. on Friday 9 October.", today).date)
    }

    // SQ-3: the person's own lines after a quoted line are kept.
    @Test fun ownWordsAfterAQuoteAreRead() {
        assertEquals("Dinner is on 14 October at 7pm.", EmailHeaders.message("Agenda:\n> item one\nDinner is on 14 October at 7pm."))
        assertEquals(listOf(LocalDate.of(2026, 10, 14)), days("> Are you free?\nYes, dinner is on 14 October at 7pm."))
    }

    // SQ-4: an earlier message quoted Outlook's way or in another language isn't read.
    @Test fun outlookAndOtherLanguageQuotesAreLeftOut() {
        val outlook = "Dinner is on 14 October at 7pm.\n\nFrom: Sam <sam@example.com>\nSent: Friday, 2 October 2026 10:00\n" +
            "To: Me <me@example.org>\nSubject: Lunch on 20 October at 1pm\n\nLunch?"
        assertEquals(listOf(LocalDate.of(2026, 10, 14)), days(outlook))
        val dutch = "Prima.\n\nVan: Sam <sam@example.com>\nVerzonden: vrijdag 2 oktober 2026\nAan: Me <me@example.org>\n" +
            "Onderwerp: Lunch on 20 October at 1pm"
        assertEquals(emptyList<LocalDate>(), days(dutch))
        assertEquals(listOf(LocalDate.of(2026, 10, 14)),
            days("Dinner on 14 October at 7pm.\nAm 30.09.2026 um 10:00 schrieb Jo:\nWie wäre es am 20 October at 6pm?"))
        // A travel plan's From/To isn't a quoted message, nor a block at the very top.
        assertEquals(listOf(LocalDate.of(2026, 10, 14)), days("Flight on 14 October at 7pm.\nFrom: Perth\nTo: Sydney"))
        assertTrue(EmailHeaders.message("From: Sam\nTo: Jo\nSubject: Dinner\nDinner on 14 October at 7pm.").contains("14 October"))
        // "Sam wrote:" with no date is the person quoting a line into their own message.
        assertTrue(EmailHeaders.message("Sam wrote:\nDinner on 14 October at 7pm.").contains("14 October"))
    }

    // SQ-5: a signature under a sign-off is left out; a sentence after "Thanks," isn't.
    @Test fun aSignatureUnderASignOffIsLeftOut() {
        assertEquals(listOf(LocalDate.of(2026, 10, 14)),
            days("Hi, dinner is on 14 October at 7pm.\n\nCheers,\nSam\nOffice hours: Saturday at 10am"))
        assertEquals(listOf(LocalDate.of(2026, 10, 14)), days("Dinner is on 14 October at 7pm.\nRegards,\nJo van Dijk\nClosed 25 December"))
        assertEquals(listOf(LocalDate.of(2026, 10, 14)), days("Got it.\nThanks,\nSee you on 14 October at 7pm."))
    }

    // SQ-7: a number ending a sentence is no unfinished length.
    @Test fun aNumberBeforeAFullStopIsFinished() {
        assertNull(parse("Book a table for 4.").error)
        assertNull(parse("Dinner for 2.").error)
        assertNotNull(parse("Study for 30").error)
    }

    // SQ-8: "Friday 6." asks for the hour as "Friday 6" does.
    @Test fun aBareHourBeforeAFullStopIsAsked() {
        parse("Gym Friday 6.").let {
            assertEquals(listOf(LocalTime.of(6, 0), LocalTime.of(18, 0)), it.timeChoices); assertEquals("Gym", it.title.trimEnd('.'))
        }
    }

    // SQ-9: no stray full stop where the time was.
    @Test fun noStrayFullStopInTheTitle() {
        assertEquals("Meet. Bring wine", parse("Meet Friday at 5. Bring wine").title)
        assertEquals("Coffee", parse("Coffee at 5. Friday").title)
        assertEquals("Pick up. Bring keys", parse("Pick up at 5. Bring keys").title)
    }

    // SQ-10: equal numbers are that many nights.
    @Test fun equalNightsAreThatMany() {
        parse("Hotel 3-3 nights").let { assertNull(it.error); assertEquals(today.plusDays(3), it.endDate) }
        assertEquals("Write the shorter stay first, for example 2-3 nights.", parse("Hotel 3-2 nights").error)
    }

    // SQ-11: the opening read ends at a sentence, not inside a word.
    @Test fun theOpeningEndsAtASentence() {
        val filler = "Some words here. ".repeat(SharedDates.MAX_READ / 17 - 1)
        val text = filler + "Your appointment is on 20 October at 10:30am and costs EUR 84.20 in total."
        assertTrue(text.length > SharedDates.MAX_READ)
        val read = SharedDates.opening(text)
        assertTrue(read.length <= SharedDates.MAX_READ); assertTrue(read.trimEnd().endsWith("."))
        assertEquals(emptyList<LocalDate>(), SharedDates.find(text, today).dates)
        assertEquals("short", SharedDates.opening("short"))
    }

    // SQ-12: a sent date with a two-digit year is no subject.
    @Test fun aTwoDigitYearSentDateIsNoTitle() {
        val email = "Datum: 01.10.26, 14:30\nVon: Sam <sam@example.com>\nAn: Me <me@example.org>\n\nHallo"
        assertEquals("Hallo", SharedText.draft(email).title)
    }

    // SQ-13: an address in the subject, and a contact card with two addresses.
    @Test fun aSubjectWithAnAddressAndAContactCard() {
        val email = "Subject: Meet sam@example.com\nFrom: Sam <sam@example.com>\nTo: me@example.org\n\nHi"
        SharedText.draft(email).let { assertEquals("Meet sam@example.com", it.title); assertEquals("From: Sam <sam@example.com>\n\nHi", it.notes) }
        val card = "Name: Sam\nEmail: sam@example.com\nWork email: s@example.com\n\nMet at the conference"
        assertNull(EmailHeaders.read(card))
        assertEquals(card, SharedText.draft(card).notes)
        // A display name with a comma, as Outlook writes it, is still a header.
        assertNotNull(EmailHeaders.read("Subject: Hi\nFrom: \"Doe, Jane\" <jane@example.com>\nTo: me@example.org\n\nHello"))
    }

    // SQ-14: a range written backwards is refused; across New Year it is short.
    @Test fun aBackwardsRangeIsRefused() {
        assertEquals("End the date range after it starts.", parse("Trip 20 Oct to 10 Oct").error)
        assertEquals("End the date range after it starts.", parse("Trip 9 Oct to 7 Oct").error)
        assertEquals("End the date range after it starts.", parse("Trip 20 Nov to 10 Oct").error)
        parse("Trip 28 Dec to 3 Jan").let { assertNull(it.error); assertEquals(LocalDate.of(2026, 12, 28), it.date); assertEquals(LocalDate.of(2027, 1, 3), it.endDate) }
    }

    // SQ-15: "of" does count: "8 Of us" is how many, not 20:00 (without it in the set, this reads 20:00).
    @Test fun ofIsACountWordAfterAll() {
        parse("Dinner tonight 8 Of us").let { assertNull(it.time) }
    }
}
