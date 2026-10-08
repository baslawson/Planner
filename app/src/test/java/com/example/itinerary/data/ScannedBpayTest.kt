package com.example.itinerary.data

import org.junit.Assert.*
import org.junit.Test

// A scanned bill with a BPAY box: an Australian bill, so a plain "$" is AUD, and its biller code and reference are read.
class ScannedBpayTest {
    private val bill = """
        Acme Energy
        Tax invoice
        Amount due: $142.80
        Due date: 30 September 2026
        BPAY®
        Biller Code: 23796
        Ref: 1234 5678 9012
    """.trimIndent()

    @Test fun aBpayBoxMakesAPlainDollarAudAndGivesItsNumbers() {
        val s = BillSuggestions.parse(bill)
        assertEquals(14280L, s.amount)
        assertEquals("AUD", s.currency)
        assertTrue(s.warnings.getValue("currency").startsWith("BPAY found"))
        assertEquals("23796", s.bpayBiller)
        assertEquals("123456789012", s.bpayReference)
    }

    @Test fun withoutBpayAPlainDollarStaysUncertain() {
        val s = BillSuggestions.parse(bill.lineSequence().takeWhile { !it.startsWith("BPAY") }.joinToString("\n"))
        assertNull(s.currency)
        assertNull(s.bpayBiller); assertNull(s.bpayReference)
        assertNull(s.warnings["bpay"])
    }

    @Test fun anotherCurrencyWrittenOnTheBillIsKept() {
        assertEquals("NZD", BillSuggestions.parse(bill.replace("$142.80", "NZ$142.80")).currency)
        assertEquals("USD", BillSuggestions.parse(bill.replace("$142.80", "USD 142.80")).currency)
    }

    @Test fun commonLayoutsOfTheBpayBox() {
        val oneLine = BillSuggestions.parse("Amount due: $50.00\nBPAY Biller Code 4321 Ref 99887766")
        assertEquals("4321", oneLine.bpayBiller); assertEquals("99887766", oneLine.bpayReference)
        val crn = BillSuggestions.parse("Total due: $50.00\nBiller code\n654321\nCustomer Reference Number (CRN): 1111 2222")
        assertEquals("654321", crn.bpayBiller); assertEquals("11112222", crn.bpayReference)
    }

    @Test fun anInvoiceReferenceFarFromTheBpayBoxIsNotTaken() {
        val s = BillSuggestions.parse("Ref: 555\nAcme\nline\nline\nline\nline\nTotal: $10.00\nBiller Code: 1234")
        assertEquals("1234", s.bpayBiller)
        assertNull(s.bpayReference)
        assertTrue(s.warnings.getValue("bpay").contains("couldn't be read clearly"))
    }

    @Test fun twoDifferentBillerCodesAreLeftForTheUser() {
        val s = BillSuggestions.parse("Total: $10.00\nBPAY\nBiller Code: 1111\nBiller Code: 2222")
        assertNull(s.bpayBiller)
    }
}
