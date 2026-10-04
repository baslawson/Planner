package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// Bug hunt #8 (5 Oct 2026): sharing text and emails into Planner, and Quick entry, after the hunt #7 fixes (SQ8-1 … SQ8-10).
class ShareQuickHuntEightTest {
    private val today = LocalDate.of(2026, 10, 4) // a Sunday
    private fun parse(text: String) = QuickEntry.parse(text, today)
    private fun days(text: String) = SharedDates.find(SharedText.draft(text).body, today).dates
    private val oct14 = LocalDate.of(2026, 10, 14)
    private val oct20 = LocalDate.of(2026, 10, 20)

    // SQ8-1: the person's own line ending in ":" with a year and a time is no quote intro.
    @Test fun aLineOfOwnEndingInAColonIsKept() {
        assertEquals(listOf(oct14), days("The meeting is on 14 October 2026 at 10:00. Agenda:\n1. Budget\n2. Hiring"))
        assertEquals(listOf(oct20), days("Hi Jo,\nYour appointment on 20.10.2026 at 10:30 is confirmed. Please bring:\n- your card"))
        assertEquals(listOf(oct20), days("Booking confirmed for 2026-10-20 at 10:30:\nTable for 4"))
        assertEquals(listOf(oct20), days("Hi Jo,\nReminder for Tuesday 20/10/26 10:30, please bring the following:\n- your card"))
        assertTrue(EmailHeaders.message("Order 120345, pickup 10:30:\nAt the front desk").contains("front desk"))
        // The bill amount is read again too.
        val bill = "Your appointment on 20.10.2026 at 10:30 is confirmed. Please bring:\n- EUR 84.20 in cash"
        assertEquals(8420L, BillSuggestions.parseMessage(SharedText.draft(bill).body, null).amount)
        // An attribution in a language not in the list still starts an earlier message.
        assertEquals(listOf(oct14), days("Dinner on 14 October at 7pm.\nDen 30.09.2026 kl. 10:00 ritade Jo:\nWhat about 20 October at 6pm?"))
        assertEquals(listOf(oct14), days("Dinner on 14 October at 7pm.\n2026-09-30 10:00 GMT+02:00 Jo <jo@example.com>:\nWhat about 20 October at 6pm?"))
    }

    // SQ8-2: a long range without a year across New Year reads forwards.
    @Test fun aLongRangeAcrossNewYearIsForwards() {
        fun range(text: String) = parse(text).let { assertNull(text, it.error); it.date to it.endDate }
        assertEquals(LocalDate.of(2026, 9, 1) to LocalDate.of(2027, 6, 30), range("School year 1 Sep to 30 Jun"))
        assertEquals(LocalDate.of(2026, 7, 1) to LocalDate.of(2027, 6, 30), range("Lease 1 Jul to 30 Jun"))
        assertEquals(LocalDate.of(2026, 10, 1) to LocalDate.of(2027, 4, 30), range("Season 1 Oct to 30 Apr"))
        assertEquals(LocalDate.of(2026, 10, 1) to LocalDate.of(2027, 4, 30), range("Winter season Oct 1 - Apr 30"))
        assertEquals(LocalDate.of(2026, 12, 1) to LocalDate.of(2027, 6, 30), range("Ski season 1 Dec to 30 Jun"))
        assertEquals(LocalDate.of(2026, 11, 1) to LocalDate.of(2027, 4, 30), range("Season 1 Nov to 30 Apr"))
        assertEquals(LocalDate.of(2026, 9, 1) to LocalDate.of(2027, 6, 30), range("School year 1 Sep to 30 Jun 2027"))
        // SQ-14 still: written backwards.
        listOf("Trip 20 Oct to 10 Oct", "Trip 9 Oct to 7 Oct", "Trip 20 Nov to 10 Oct", "Trip 5 Jan to 3 Jan", "Trip Oct 20 - Oct 10",
            "Trip 20 Oct 2026 to 10 Oct 2026").forEach { assertEquals(it, "End the date range after it starts.", parse(it).error) }
    }

    // SQ8-3: a forwarded message is read, its header block left out; a web form's notification is read whole.
    @Test fun aForwardedMessageIsRead() {
        val gmail = "FYI, see below.\n\n---------- Forwarded message ---------\nFrom: Clinic <clinic@example.com>\n" +
            "Date: Fri, 2 Oct 2026 at 10:00\nSubject: Appointment confirmation\nTo: Me <me@example.org>\n\n" +
            "Your appointment is on 20 October at 10:30am."
        SharedDates.find(SharedText.draft(gmail).body, today).let { assertEquals(oct20, it.date); assertEquals(LocalTime.of(10, 30), it.time) }
        assertFalse(SharedText.draft(gmail).body.contains("clinic@example.com"))
        val bill = gmail.replace("Your appointment is on 20 October at 10:30am.", "Amount due: EUR 84.20\nDue date: 20 October 2026")
        val body = SharedText.draft(bill).body
        BillSuggestions.parseMessage(body, SharedDates.find(body, today).date).let {
            assertEquals(8420L, it.amount); assertEquals("EUR", it.currency); assertEquals(oct20, it.date)
        }
        // With no words of the person's own, and Thunderbird's and Apple Mail's forwards.
        assertEquals(listOf(oct20), days(gmail.substringAfter("below.\n\n")))
        assertEquals(listOf(oct20), days("-------- Forwarded Message --------\nSubject: Appointment\nDate: Fri, 2 Oct 2026 10:00\n" +
            "From: Clinic <clinic@example.com>\nTo: Me <me@example.org>\n\nYour appointment is on 20 October at 10:30am."))
        assertEquals(listOf(oct20), days("Begin forwarded message:\n\nFrom: Clinic <clinic@example.com>\nSubject: Appointment\n" +
            "Date: 2 October 2026 at 10:00:00 CEST\nTo: Me <me@example.org>\n\nYour appointment is on 20 October at 10:30am."))
        assertEquals(listOf(oct20), days("New enquiry\nFrom: Jane <jane@example.com>\nTo: Sales\nSubject: Quote\n" +
            "Message: Can you come on 20 October at 10am?"))
        // A reply's Outlook block is still left out (SQ-4).
        assertEquals(listOf(oct14), days("Dinner is on 14 October at 7pm.\n\nFrom: Sam <sam@example.com>\nSent: Friday, 2 October 2026 10:00\n" +
            "To: Me <me@example.org>\nSubject: Lunch on 20 October at 1pm\n\nLunch?"))
    }

    // SQ8-4: a sentence after "Thanks" is no signature name.
    @Test fun aSentenceAfterThanksIsKept() {
        assertEquals(listOf(oct14), days("Hi Jo,\n\nThanks\nMonday works for me.\nSee you on 14 October at 2pm."))
        assertEquals(listOf(oct14), days("Hi Jo,\n\nThanks,\nSounds good.\nDinner on 14 October at 7pm then."))
        // SQ-5 still.
        assertEquals(listOf(oct14), days("Hi, dinner is on 14 October at 7pm.\n\nCheers,\nSam\nOffice hours: Saturday at 10am"))
        assertEquals(listOf(oct14), days("Dinner is on 14 October at 7pm.\nRegards,\nJo van Dijk\nClosed 25 December"))
        assertEquals(listOf(oct14), days("Dinner is on 14 October at 7pm.\nBest regards,\nDr. A. Smith\nClosed 25 December"))
    }

    // SQ8-5: "Last, First <address>", a comment after the address, and a subject with a date and time.
    @Test fun moreHeaderShapesAreRead() {
        SharedText.draft("Subject: Lunch\nFrom: Smith, Sam <sam@example.com>\nTo: me@example.org\n\nSee you").let {
            assertEquals("Lunch", it.title); assertEquals("From: Smith, Sam <sam@example.com>\n\nSee you", it.notes)
        }
        SharedText.draft("Subject: Lunch\nFrom: sam@example.com (Sam Smith)\nTo: me@example.org\n\nSee you").let {
            assertEquals("Lunch", it.title); assertEquals("From: sam@example.com (Sam Smith)\n\nSee you", it.notes)
        }
        assertEquals("Meeting 12/10/26 10:30",
            SharedText.draft("Subject: Meeting 12/10/26 10:30\nFrom: Sam <sam@example.com>\nTo: me@example.org\n\nSee you").title)
        // Sent dates are still no subject (SH-2, SQ-12).
        listOf("Date: Fri, 2 Oct 2026 10:00", "Datum: 01.10.26, 14:30", "Datum: Fr., 2. Okt. 2026, 10:00", "Date: 2 Oct 2026 09:14",
            "Date: October 2, 2026 at 10:00 AM").forEach {
            assertEquals(it, "Hallo", SharedText.draft("$it\nFrom: Sam <sam@example.com>\nTo: me@example.org\n\nHallo").title)
        }
        // Still no header: a subject, a contact card, a list of names.
        assertNull(EmailHeaders.read("Subject: Lunch, dinner\nTo: Sales, Support\n\nHi"))
    }

    // SQ8-6: a long To or Cc list is read, quickly.
    @Test fun aLongAddressListIsRead() {
        val start = System.nanoTime()
        for (n in listOf(200, 400, 2_000)) {
            val to = (1..n).joinToString(", ") { "Person Name$it <person.name$it@example.com>" }
            val quoted = (1..n).joinToString(", ") { "\"Name, Person$it\" <person$it@example.com>" }
            for (list in listOf(to, quoted)) {
                val text = "Subject: Party\nFrom: Sam <sam@example.com>\nTo: $list\n\nParty on 14 October at 7pm."
                if (text.length > 20_000) { assertNotNull(EmailHeaders.read(text)); continue }
                SharedText.draft(text).let { assertEquals("Party", it.title); assertEquals(listOf(oct14), SharedDates.find(it.body, today).dates) }
            }
        }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
    }

    // SQ8-7: reading the message takes time in step with its length.
    @Test fun longTextsAreQuick() {
        fun ms(text: String): Long { val s = System.nanoTime(); SharedText.draft(text); return (System.nanoTime() - s) / 1_000_000 }
        listOf("x\n".repeat(9_999), "Thanks\n".repeat(2_700), "Hi\n" + "Thanks\n\n\n".repeat(1_900), "- item\n".repeat(2_400),
            "Hi\n" + "From: a\n".repeat(2_400)).forEach { ms(it) } // warm up
        listOf("x\n".repeat(9_999), "Thanks\n".repeat(2_700), "Hi\n" + "Thanks\n\n\n".repeat(1_900), "- item\n".repeat(2_400),
            "Hi\n" + "From: a\n".repeat(2_400)).forEach { assertTrue(ms(it) < 150) }
    }

    // SQ8-8: an amount due is no count, nor a total of 10 or more.
    @Test fun anAmountDueIsNoCount() {
        assertEquals(8500L to null, BillSuggestions.parseMessage("Amount due: 85\nYour credit limit is AUD 5,000.", null).let { it.amount to it.currency })
        assertEquals(8500L, BillSuggestions.parseMessage("Your electricity bill.\nTotal: 85\nPrevious balance: $120.50", null).amount)
        // SH-6 still.
        assertEquals(8420L to "EUR", BillSuggestions.parseMessage("Items in total: 3\nAmount EUR 84.20", null).let { it.amount to it.currency })
        assertEquals(8420L to "EUR", BillSuggestions.parseMessage("Total: 3\nAmount EUR 84.20", null).let { it.amount to it.currency })
        assertEquals(8420L to "EUR", BillSuggestions.parseMessage("Total items: 12\nAmount EUR 84.20", null).let { it.amount to it.currency })
    }

    // SQ8-9: a bare hour before a new sentence is asked.
    @Test fun aBareHourBeforeANewSentenceIsAsked() {
        for (text in listOf("Gym Friday 6. Bring towel", "Brekkie Sunday 9. Pancakes", "Gym Friday 6...", "Gym Friday 6.")) {
            parse(text).let {
                assertEquals(text, 2, it.timeChoices.size)
                assertFalse(text, Regex("\\b[69]\\b").containsMatchIn(it.title))
            }
        }
        assertEquals("Gym. Bring towel", parse("Gym Friday 6. Bring towel").title)
        // A version number or a decimal is no hour.
        assertNull(parse("Gym Friday 6.5 km").timeChoices.firstOrNull())
    }

    // SQ8-10: Finnish Outlook labels, a wrapped To line, and a signature name with "|".
    @Test fun moreQuotedBlocksAndSignatures() {
        assertEquals(listOf(oct14), days("Dinner is on 14 October at 7pm.\n\nLähettäjä: Sam <sam@example.com>\nLähetetty: perjantai 2. lokakuuta 2026 10.00\n" +
            "Vastaanottaja: Me <me@example.org>\nAihe: Lunch on 20 October at 1pm\n\nLunch?"))
        assertEquals(listOf(oct14), days("Dinner is on 14 October at 7pm.\n\nFrom: Sam <sam@example.com>\nSent: Friday, 2 October 2026 10:00\n" +
            "To: Ann <ann@example.org>; Bo <bo@example.org>; Cy <cy@example.org>;\n Lee <lee@example.org>\nSubject: Lunch on 20 October at 1pm\n\nLunch?"))
        assertEquals(listOf(oct14), days("Dinner is on 14 October at 7pm.\n\nBest regards,\n\nSAM SMITH | Senior Manager\nAcme Ltd\n" +
            "Office hours: Saturday at 10am"))
    }
}
