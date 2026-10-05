package com.example.itinerary

import com.example.itinerary.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PaymentSettlementCodecTest {
    private fun bill() = ItineraryItem(tripId = 1, date = LocalDate.of(2026, 9, 26), startTime = null,
        title = "Power", category = "Bills", billAmountMinor = 10000)

    @Test fun explicitSettlementAndManualIdentitySurviveSharedStorageCodec() {
        val manual = BillPayment(id = "manual", amount = 4000, note = "Marked paid")
        val paid = Payments.setPaid(bill().copy(payments = listOf(manual)), true)
        val restored = paid.copy(payments = Payments.decode(Payments.encode(paid.payments)))
        assertEquals(paid.payments, restored.payments)
        assertEquals(false, restored.payments.first().automaticSettlement)
        assertEquals(true, restored.payments.last().automaticSettlement)
        val unpaid = Payments.setPaid(restored, false)
        assertEquals(manual, unpaid.payments.first())
        assertTrue(unpaid.payments.last().reversed)
        assertEquals(6000L, Payments.remaining(10000, false, unpaid.payments))
    }

    @Test fun olderMetadataRetainsHistoricalBehaviorAcrossCodecRoundTrip() {
        val old = Payments.decode("""[{"id":"cash","amount":4000,"date":"2026-09-26","note":"Cash","reversed":false},
            {"id":"old-settlement","amount":6000,"date":"2026-09-26","note":"Marked paid","reversed":false}]""")
        assertTrue(old.all { it.automaticSettlement == null })
        val restored = Payments.decode(Payments.encode(old))
        assertEquals(old, restored)
        val unpaid = Payments.setPaid(bill().copy(paid = true, payments = restored), false)
        assertEquals(listOf(false, true), unpaid.payments.map { it.reversed })
        assertEquals(4000L, Payments.total(unpaid.payments))
        assertEquals(6000L, Payments.remaining(10000, false, unpaid.payments))
        // Old freeform "Marked paid" entries are indistinguishable from old settlements.
        // The existing rule is retained only for those unclassified histories.
        val ambiguous = old.first().copy(note = "Marked paid")
        assertTrue(Payments.setPaid(bill().copy(paid = true, payments = listOf(ambiguous)), false)
            .payments.single().reversed)
    }
}
