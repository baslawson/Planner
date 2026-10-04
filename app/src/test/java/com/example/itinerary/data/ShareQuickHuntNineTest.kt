package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// Bug hunt #9 (5 Oct 2026): sharing text and emails into Planner, and Quick entry, after the hunt #8 fixes (SQ9-1 … SQ9-10).
// The reviewers' other examples, from all three hunts, are pinned in ShareQuickCasesTest.
class ShareQuickHuntNineTest {
    private val today = LocalDate.of(2026, 10, 5) // a Monday
    private fun parse(text: String) = QuickEntry.parse(text, today)
    private fun found(text: String, subject: String? = null) = SharedDates.find(SharedText.draft(text, subject).body, today)
    private fun days(text: String, subject: String? = null) = found(text, subject).dates
    private val oct14 = LocalDate.of(2026, 10, 14)
    private val oct20 = LocalDate.of(2026, 10, 20)
    private val gmailForward = "---------- Forwarded message ---------\nFrom: Clinic <clinic@example.com>\nDate: Fri, 2 Oct 2026 at 10:00\n" +
        "Subject: Appointment confirmation\nTo: Me <me@example.org>\n\nYour appointment is on 20 October at 10:30am."

    // SQ9-1: a forward below a signature or a sign-off is read.
    @Test fun aForwardBelowASignatureIsRead() {
        val iphone = "Sent from my iPhone\n\nBegin forwarded message:\n\nFrom: Clinic <clinic@example.com>\nSubject: Appointment\n" +
            "Date: 2 October 2026 at 10:00:00 CEST\nTo: Me <me@example.org>\n\nYour appointment is on 20 October at 10:30am."
        for (text in listOf(iphone, "Hi Jo, see below.\n\nThanks,\nSam\n\n$gmailForward", "FYI\n\n-- \nSam Smith\nAcme Ltd\n\n$gmailForward")) {
            found(text).let { assertEquals(text, oct20, it.date); assertEquals(LocalTime.of(10, 30), it.time) }
        }
        // The signature itself stays out, and so does a quoted earlier message's forward.
        assertEquals(listOf(oct20), days("FYI\n\n-- \nSam Smith\nOpen Saturday at 10am\n\n$gmailForward"))
        assertEquals(listOf(oct14), days("Dinner on 14 October at 7pm.\n\n-- \nSam\n\nOn Fri, 2 Oct 2026 at 10:00, Jo <jo@example.org> wrote:\n" +
            "> $gmailForward".replace("\n", "\n> ")))
        // A signature with nothing below it still ends the message (SQ-5).
        assertEquals(listOf(oct14), days("Dinner on 14 October at 7pm.\n\nCheers,\nSam\nOffice hours: Saturday at 10am"))
    }

    // SQ9-2: the person's own line ending in "AM:", "Uhr:" or a name, with a date, is no quote intro.
    @Test fun ownLinesWithADateAndAColonAreKept() {
        found("Details for your visit on 20.10.2026 at 10:30 AM:\n- bring your card").let { assertEquals(oct20, it.date); assertEquals(LocalTime.of(10, 30), it.time) }
        assertEquals(listOf(LocalTime.of(10, 30), LocalTime.of(22, 30)), found("Ihr Termin am 20.10.2026 um 10:30 Uhr:\n- Karte mitbringen").timeChoices)
        found("Your booking on 2026-10-20 at 10:30 PM:\nTable for 4").let { assertEquals(oct20, it.date); assertEquals(LocalTime.of(22, 30), it.time) }
        for (text in listOf("Ihr Termin am 20.10.2026 um 10:30 Uhr:\n- Versichertenkarte mitbringen",
            "Your appointment on 20.10.2026 with Dr Smith:\n- bring your card", "Afspraak op 20.10.2026 bij Tandarts Jansen:\n- neem je pas mee",
            "Flight to London on 20/10/2026 10:30 from Sydney:\nSeat 14A")) assertEquals(text, oct20, found(text).date)
        val bill = SharedText.draft("Rechnung vom 20.10.2026 für Max Mustermann:\nBetrag: EUR 84.20").body
        assertEquals(8420L to "EUR", BillSuggestions.parseMessage(bill, null).let { it.amount to it.currency })
        // Attributions in a language not listed still start an earlier message (SQ8-1).
        assertEquals(listOf(oct14), days("Dinner on 14 October at 7pm.\nDen 30.09.2026 kl. 10:00 ritade Jo:\nWhat about 20 October at 6pm?"))
        assertEquals(listOf(oct14), days("Dinner on 14 October at 7pm.\nDen 30.09.2026 kl. 10.00 ritade Jo:\nWhat about 20 October at 6pm?"))
        assertEquals(listOf(oct14), days("Dinner on 14 October at 7pm.\n2026-09-30 10:00 GMT+02:00 Jo <jo@example.com>:\nWhat about 20 October at 6pm?"))
    }

    // SQ9-3: Gmail's reply lines with the name after the verb, and in Polish, Czech, Turkish and Hungarian.
    @Test fun moreGmailReplyLinesAreQuoteIntros() {
        listOf("Am Fr., 2. Okt. 2026 um 10:00 Uhr schrieb Sam Smith <sam@example.com>:",
            "pt., 2 paź 2026 o 10:00 Sam Smith <sam@example.com> napisał(a):",
            "Dne pá 2. 10. 2026 10:00 uživatel Sam Smith <sam@example.com> napsal:",
            "2 Eki 2026 Cum, 10:00 tarihinde Sam Smith <sam@example.com> şunu yazdı:",
            "Sam Smith <sam@example.com> ezt írta (időpont: 2026. okt. 2., P, 10:00):",
            "Am 02.10.2026 um 10:00 schrieb Sam Smith:").forEach {
            assertEquals(it, listOf(oct14), days("Sure, 14 October at 7pm works.\n\n$it\nHow about 20 October at 6pm?"))
        }
        // Without a date the person may be quoting a line into their own message.
        assertTrue(EmailHeaders.message("Sam wrote:\nDinner on 14 October at 7pm.").contains("14 October"))
    }

    // SQ9-4: one rule for a range whose end comes before its start in the calendar, and both sides of it.
    @Test fun aRangeAcrossNewYearOrBackwards() {
        fun range(text: String) = parse(text).let { assertNull(text, it.error); it.date to it.endDate }
        fun refused(text: String) = assertEquals(text, "End the date range after it starts.", parse(text).error)
        // The end in the start's month or the month before: written backwards, whatever the days.
        listOf("Trip 20 Oct to 10 Oct", "Trip 4 Oct to 3 Oct", "Trip 6 Oct to 5 Oct", "Trip 20 Nov to 10 Oct", "Trip 10 Nov to 20 Oct",
            "Trip 20 Oct to 25 Sep", "Trip Oct 20 - Sep 25", "Lease 15 Jul to 14 Jul", "Lease 15 Feb to 14 Jan", "Trip 5 Jan to 3 Jan").forEach(::refused)
        // Earlier: across New Year, long ones too, and a year from the 1st.
        assertEquals(LocalDate.of(2026, 9, 1) to LocalDate.of(2027, 6, 30), range("School year 1 Sep to 30 Jun"))
        assertEquals(LocalDate.of(2026, 12, 28) to LocalDate.of(2027, 1, 3), range("Trip 28 Dec to 3 Jan"))
        assertEquals(LocalDate.of(2026, 8, 1) to LocalDate.of(2027, 7, 31), range("Lease 1 Aug to 31 Jul")) // under way
        assertEquals(LocalDate.of(2027, 2, 1) to LocalDate.of(2028, 1, 31), range("Lease 1 Feb to 31 Jan"))
        assertEquals(LocalDate.of(2027, 3, 1) to LocalDate.of(2028, 2, 28), range("Lease 1 Mar to 28 Feb"))
        // Under way, when it began at most half a year ago; else next time round.
        assertEquals(LocalDate.of(2026, 9, 30) to LocalDate.of(2026, 10, 10), range("Trip 30 Sep to 10 Oct"))
        assertEquals(LocalDate.of(2026, 11, 1) to LocalDate.of(2027, 10, 31), range("Lease 1 Nov to 31 Oct"))
        assertEquals(LocalDate.of(2027, 1, 1) to LocalDate.of(2027, 10, 31), range("Trip 1 Jan to 31 Oct"))
        // A year on the end only: the same rule, the start the year before when the range crosses New Year.
        assertEquals(LocalDate.of(2026, 8, 1) to LocalDate.of(2027, 7, 31), range("Lease 1 Aug to 31 Jul 2027"))
        assertEquals(LocalDate.of(2026, 12, 28) to LocalDate.of(2027, 1, 3), range("Trip 28 Dec to 3 Jan 2027"))
        listOf("Lease 15 Jul to 14 Jul 2027", "Trip 20 Oct to 10 Oct 2027", "Trip 7 Oct to 3 Oct 2026", "Trip 20 Oct 2026 to 10 Oct 2026").forEach(::refused)
    }

    // SQ9-5: an ordinal before a month or a numbered list is no bare hour; a new sentence still is (SQ8-9).
    @Test fun anOrdinalOrAListIsNoBareHour() {
        parse("Party Friday 9. October").let { assertTrue(it.timeChoices.isEmpty()); assertEquals("Party 9. October", it.title) }
        parse("Meeting Friday 1. Budget 2. Hiring").let { assertTrue(it.timeChoices.isEmpty()); assertEquals("Meeting 1. Budget 2. Hiring", it.title) }
        for (text in listOf("Gym Friday 6. Bring towel", "Dinner Sat 7. Sam's place", "Gym every Monday 6. Bring towel", "Gym Friday 6...",
            "Lunch Friday 12. Café Rio", "Gym Friday 6. Bring towel 2.5 kg")) assertEquals(text, 2, parse(text).timeChoices.size)
        assertTrue(parse("Run Friday 6.5 km").timeChoices.isEmpty())
    }

    // SQ9-6: more signature names, and a date line after "Thanks," is no name.
    @Test fun moreSignatureNames() {
        for (name in listOf("Sam x", "Sam - Acme", "Sam Smith, Ph.D.", "Sam Smith (she/her)", "SAM SMITH | Senior Manager", "Jo van Dijk")) {
            assertEquals(name, listOf(oct14), days("See you then, dinner on 14 October at 7pm.\n\nThanks,\n$name\n\nOffice hours: Saturday at 10am"))
        }
        assertEquals(listOf(oct14), days("Hi Jo,\n\nThanks,\nWednesday, October 14 at 7pm then"))
        assertEquals(listOf(LocalDate.of(2026, 10, 20)), days("Hi,\n\nThanks,\nTuesday, October 20 at 10am works for me."))
        // Sentences after "Thanks" stay (SQ8-4).
        assertEquals(listOf(oct14), days("Hi Jo,\n\nThanks,\nSounds good.\nDinner on 14 October at 7pm then."))
    }

    // SQ9-7: forwards with no known marker: Outlook's, Samsung's and Apple Mail's in other languages.
    @Test fun moreForwardsAreRead() {
        val outlook = "From: Clinic <clinic@example.com>\nSent: Friday, 2 October 2026 10:00\nTo: Me <me@example.org>\n" +
            "Subject: Appointment confirmation\n\nYour appointment is on 20 October at 10:30am."
        // The block's own subject, or the share's, says it's a forward.
        assertEquals(listOf(oct20), days("FYI\n\n________________________________\n" + outlook.replace("Subject: ", "Subject: FW: ")))
        assertEquals(listOf(oct20), days("FYI\n\n________________________________\n$outlook", subject = "FW: Appointment confirmation"))
        assertEquals(listOf(oct20), days("FYI\n\n$outlook", subject = "Fwd: Appointment confirmation"))
        // Nothing of the person's own above it: what was shared.
        assertEquals(listOf(oct20), days("-------- Original message --------\nFrom: Clinic <clinic@example.com>\nDate: 02/10/2026 10:00 (GMT+01:00)\n" +
            "To: Me <me@example.org>\nSubject: Appointment confirmation\n\nYour appointment is on 20 October at 10:30am."))
        for (marker in listOf("Begin doorgestuurd bericht:", "Inicio del mensaje reenviado:", "Inizio messaggio inoltrato:")) {
            assertEquals(marker, listOf(oct20), days("$marker\n\nFrom: Clinic <clinic@example.com>\nSubject: Afspraak\nTo: Me <me@example.org>\n\n" +
                "Your appointment is on 20 October at 10:30am."))
        }
        // A reply's block under the person's words is still an earlier message (SQ-4), whatever "RE:" the share has.
        val reply = "Dinner is on 14 October at 7pm.\n\nFrom: Sam <sam@example.com>\nSent: Friday, 2 October 2026 10:00\n" +
            "To: Me <me@example.org>\nSubject: Lunch on 20 October at 1pm\n\nLunch?"
        assertEquals(listOf(oct14), days(reply))
        assertEquals(listOf(oct14), days(reply, subject = "RE: Lunch"))
        assertEquals(listOf(oct14), days(reply.replace("\n\nFrom:", "\n\n-----Original Message-----\nFrom:")))
    }

    // SQ9-8: a forward shared with no subject is titled by the forwarded message.
    @Test fun aForwardWithoutASubjectHasTheForwardedTitle() {
        assertEquals("Appointment confirmation", SharedText.draft(gmailForward).title)
        assertEquals("Your appointment is on 20 October at 10:30am.",
            SharedText.draft("---------- Forwarded message ---------\n\nYour appointment is on 20 October at 10:30am.").title)
        assertEquals("Mine", SharedText.draft(gmailForward, "Mine").title)
        assertEquals("Hi", SharedText.draft("Hi\n$gmailForward").title)
    }

    // SQ9-9: a P.S. under the signature is read.
    @Test fun aPostscriptIsRead() {
        assertEquals(listOf(oct14), days("See you then.\n\nCheers,\nSam\n\nPS: dinner is on 14 October at 7pm."))
        assertEquals(listOf(oct14), days("See you then.\n\nCheers,\nSam\nOpen Saturday at 10am\n\nP.S. Dinner is on 14 October at 7pm."))
    }

    // SQ9-10: long runs of numbers and of ". " that don't end a sentence are read in time in step with their length.
    @Test fun longRunsAreQuick() {
        val inputs = listOf("Readings\n" + "12.50,13.20,".repeat(660), "Data\n" + "2026.10.05,".repeat(720), "Hi\n" + "1".repeat(7_990),
            "Hi\n" + "1.1.".repeat(1_990), "Hi\n" + "1,".repeat(3_990), "Notes\n" + "Meet at 10 a.m. on site, ".repeat(320),
            "Hi\n" + "Mon. 1 ".repeat(1_140), "Hi\n" + "a. b ".repeat(1_600), "Hi\n" + "Dr. x ".repeat(1_330))
        // The two reads that were quadratic; find's Quick entry reading of up to 200 sentences is in step with them.
        fun ms(text: String): Long {
            val start = System.nanoTime()
            val body = SharedDates.opening(SharedText.draft(text).body)
            SharedDates.sentences(body)
            BillSuggestions.parseMessage(body, null, today)
            return (System.nanoTime() - start) / 1_000_000
        }
        inputs.forEach { ms(it) } // warm up
        inputs.forEach { assertTrue(it.take(20), ms(it) < 150) }
        // The amounts and sentences read the same.
        assertEquals(8420L to "EUR", BillSuggestions.parseMessage("Paid 12.50,13.20 so far. Your bill is EUR 84.20.", null).let { it.amount to it.currency })
        assertEquals(listOf("Meet at 10 a.m. on site.", "Mon. 12 Oct works."), SharedDates.sentences("Meet at 10 a.m. on site. Mon. 12 Oct works."))
    }
}
