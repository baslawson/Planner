package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

// Bug hunt #10 (5 Oct 2026): sharing and Quick entry after the hunt #9 fixes (SQX-1 … SQX-11). The range and bare-hour
// rules (SQX-4, SQX-5, SQX-6) are in ShareQuickHuntNineTest beside the rules they change; every example is also pinned in
// ShareQuickCasesTest.
class ShareQuickHuntTenTest {
    private val today = LocalDate.of(2026, 10, 5) // a Monday
    private fun found(text: String, subject: String? = null) = SharedDates.find(SharedText.draft(text, subject).body, today)
    private fun days(text: String, subject: String? = null) = found(text, subject).dates
    private val oct14 = LocalDate.of(2026, 10, 14)
    private val oct20 = LocalDate.of(2026, 10, 20)
    private val oct21 = LocalDate.of(2026, 10, 21)
    private fun outlook(subject: String) = "________________________________\nFrom: Clinic <clinic@example.com>\nSent: Friday, 2 October 2026 10:00\n" +
        "To: Me <me@example.org>\nSubject: $subject\n\nYour appointment is on 20 October at 10:30am."
    private val samsung = "-------- Original message --------\nFrom: Clinic <clinic@example.com>\nDate: 02/10/2026 10:00 (GMT+01:00)\n" +
        "To: Me <me@example.org>\nSubject: Appointment\n\nYour appointment is on 20 October at 10:30am."

    // SQX-1: a reply to a forward cuts the quoted message; only the share's own subject makes a forward.
    @Test fun aReplyToAForwardIsAReply() {
        val reply = "See you 14 October at 7pm.\n\n" + outlook("FW: Hotel booking")
        assertEquals(listOf(oct14), days(reply))
        assertEquals(listOf(oct14), days(reply, "RE: FW: Hotel booking"))
        assertEquals(listOf(oct14), days(reply.replace("Subject: FW:", "Subject: TR:")))
        assertEquals(listOf(oct20), days("FYI\n\n" + outlook("Appointment"), "FW: Appointment"))
    }

    // SQX-2: a forward's block below the phone's signature or a sign-off is read, the earlier ones under it not.
    @Test fun aForwardBelowAPhoneSignatureIsRead() {
        for (text in listOf("FYI\n\nSent from my Galaxy\n\n$samsung", "FYI\n\nThanks,\nSam\n\n$samsung", "FYI\n\nGet Outlook for Android\n" + outlook("Appointment"))) {
            found(text, "Fwd: Appointment").let { assertEquals(text, oct20, it.date); assertEquals(LocalTime.of(10, 30), it.time) }
        }
        assertEquals(listOf(oct20), days("FYI\n\nSent from my Galaxy\n\n$samsung\n\n" + samsung.replace("20 October at 10:30am", "21 October at 9am"), "Fwd: Appointment"))
        // A reply's is still cut.
        assertEquals(emptyList<LocalDate>(), days("Thanks, see you then.\n\nSent from my Galaxy\n\n$samsung", "Re: Appointment"))
    }

    // SQX-3: the person's own agenda line, a date and time and a name before ":", is kept; a header has an address.
    @Test fun anAgendaLineIsKept() {
        for (line in listOf("Friday 16 October 2026 at 12:30 Lunch with Jo:", "20/10/2026 10:30 Team Meeting:", "Mon 19.10.2026 09:00 Standup:",
            "19 October 2026 9:00 – 10:00 Workshop:", "On 20/10/2026 at 10:30 with Dr Smith:")) {
            assertEquals(line, "Hi,\n$line\n- bring this", EmailHeaders.message("Hi,\n$line\n- bring this"))
        }
        assertEquals("Hi,", EmailHeaders.message("Hi,\n2026-10-20 10:30 Jo <jo@example.org>:\nDinner on 14 October at 7pm?"))
    }

    // SQX-7: Gmail's German and Apple Mail's Dutch reply lines start an earlier message.
    @Test fun moreReplyLines() {
        for (line in listOf("Sam Smith <sam@example.com> schrieb am Fr., 2. Okt. 2026, 10:00:",
            "Op 2 okt 2026 om 10:00 heeft Sam Smith <sam@example.com> het volgende geschreven:")) {
            assertEquals(line, listOf(oct21), days("Works for me, 21 October at 7pm.\n\n$line\n\nDinner on 14 October at 7pm?"))
        }
    }

    // SQX-8: an amount with its sign after it, and thousands set apart by a space.
    @Test fun anAmountWithTheSignAfterIt() {
        fun bill(text: String) = BillSuggestions.parseMessage(text, null).let { it.amount to it.currency }
        assertEquals(8420L to "EUR", bill("Gesamtbetrag: 84,20 €\nFällig am 20.10.2026"))
        assertEquals(123456L to "EUR", bill("Rechnungsbetrag: 1.234,56 €"))
        assertEquals(245000L to "EUR", bill("Total amount due 2 450,00 € before 20/10/2026"))
        assertEquals(245000L to "EUR", bill("Montant dû : 2 450,00 €"))
        assertEquals(8420L to "EUR", bill("Te betalen: € 84,20"))
        assertEquals(8420L to "EUR", BillSuggestions.parse("Total: 84.20 €").let { it.amount to it.currency })
        // A sign before a number is that number's: "for 2 $40" is $40.
        assertEquals(4000L, BillSuggestions.parseMessage("Table for 2 $40 deposit", null).amount)
    }

    // SQX-9: a sign-off name with a job title and a company.
    @Test fun aNameWithTitleAndCompany() {
        for (name in listOf("Jo - Sales Manager, Acme", "Jo Bloggs - Sales Manager, Acme Pty Ltd", "Jo, Head of Sales, Acme")) {
            assertEquals(name, listOf(LocalDate.of(2026, 10, 16)), days("Hi,\n\nLunch Friday 16 October at 12:30?\n\nThanks,\n$name\nOpen Saturday at 10am"))
        }
    }

    // SQX-10: a forward shared with no subject is titled by the forwarded message, not the phone's signature over it.
    @Test fun aSignatureIsNoTitle() {
        val iphone = "\n\nSent from my iPhone\n\nBegin forwarded message:\n\nFrom: Clinic <clinic@example.com>\nSubject: Appointment\n" +
            "Date: 2 October 2026 at 10:00:00 CEST\nTo: Me <me@example.org>\n\nYour appointment is on 20 October at 10:30am."
        assertEquals("Appointment", SharedText.draft(iphone).title)
        SharedText.draft("Sent from my Galaxy\n\n$samsung").let { assertEquals("Appointment", it.title); assertEquals("Your appointment is on 20 October at 10:30am.", it.body) }
    }

    // The new reads stay in step with the text's length, at the share's 20,000 characters (as SQ8-7, SQ9-10).
    @Test fun longInputsAreQuick() {
        val inputs = listOf("Hi\n" + "1 234 ".repeat(3_300), "Hi\n" + "84,20 € ".repeat(2_400), "Hi\n" + "1 234 567 890 €".repeat(1_300),
            "FYI\n\nSent from my Galaxy\n" + ("From: a\n" + " y\n".repeat(300) + "To: b\n").repeat(30),
            "FYI\n" + "________\nFrom: a <a@b.c>\nSent: x\nTo: c <c@d.e>\nSubject: FW: d\n\nThanks,\nJo, Head Of Sales, Acme Pty Ltd\n".repeat(150),
            "Hi\n" + "Sam <s@x.com> schrieb am Fr., 2. Okt. 2026, 10:00 ".repeat(350) + ":",
            "Hi\n" + "Mon 19.10.2026 09:00 Standup Meeting With Sam Smith And Jo:\n".repeat(330))
        fun ms(text: String): Long {
            val start = System.nanoTime()
            val body = SharedDates.opening(SharedText.draft(text.take(19_990), "Fwd: x").body)
            BillSuggestions.parseMessage(body, null, today)
            return (System.nanoTime() - start) / 1_000_000
        }
        inputs.forEach { ms(it) } // warm up
        inputs.forEach { assertTrue(it.take(30), ms(it) < 150) }
    }

    // SQX-11: a web form's "Your message:" is no mail header block.
    @Test fun aFormsMessageLabelIsNoHeader() {
        for (label in listOf("Your message", "Message body", "Additional comments")) {
            assertEquals(label, listOf(oct20), days("New enquiry\nFrom: Jane <jane@example.com>\nTo: Sales\nSubject: Quote\n$label: Can you come on 20 October at 10am?"))
        }
    }
}
