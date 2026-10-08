package com.example.itinerary.data

import com.example.itinerary.reminders.RescheduleRemindersWorker
import com.example.itinerary.ui.BudgetLinkOwner
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

// Bug hunt 25 (9 Oct 2026): MyBudget ids after a restore, bills leaving Bills, a copy of a deleted paid bill, a weekday
// repeat over a range, the window waiting for MyBudget, and the reminder upkeep that runs on every full reschedule.
class BugHunt25Test {
    private val today = LocalDate.of(2026, 10, 9) // a Friday
    private fun parse(text: String) = QuickEntry.parse(text, today)
    private fun bill(id: Long = 7, amount: Long? = null) = ItineraryItem(id = id, tripId = 1, date = LocalDate.of(2026, 10, 9), startTime = null,
        title = "Water", category = "Bills", billAmountMinor = amount)

    @After fun reset() { BudgetLink.useIds("", emptyList()) }

    // E1: ids up to the backup's highest keep the install id MyBudget knows them by; ones above it get a suffix of their own,
    // so a new bill never takes an id the old phone went on to use.
    @Test fun idsAboveARestoredBackupGetTheirOwnSuffix() {
        BudgetLink.useIds("-old11111", BudgetLink.restoredLaterIds(emptyList(), 100, fresh = "-new22222"))
        assertEquals("planner-paid-100-old11111", BudgetLink.unpricedPaymentId(bill(100)))
        assertEquals("planner-paid-101-new22222", BudgetLink.unpricedPaymentId(bill(101)))
        assertEquals("planner-bill-101-new22222", BudgetLink.upcomingId(bill(101)))
        assertEquals("planner-bill-101-new22222", BudgetLink.billKey(bill(101)))
        // A backup made after that restore carries the range, and restored again keeps it below its own highest.
        val carried = BudgetLink.decodeLaterIds(BudgetLink.encodeLaterIds(BudgetLink.laterIds))
        assertEquals(listOf(100L to "-new22222"), carried)
        BudgetLink.useIds("-old11111", BudgetLink.restoredLaterIds(carried, 150, fresh = "-third333"))
        assertEquals("planner-paid-90-old11111", BudgetLink.unpricedPaymentId(bill(90)))
        assertEquals("planner-paid-150-new22222", BudgetLink.unpricedPaymentId(bill(150)))
        assertEquals("planner-paid-151-third333", BudgetLink.unpricedPaymentId(bill(151)))
        // A range from the backup's highest up holds none of its ids: replaced by the fresh one.
        assertEquals(listOf(100L to "-fresh444"), BudgetLink.restoredLaterIds(listOf(100L to "-x", 120L to "-y"), 100, fresh = "-fresh444"))
    }

    // E1: an older backup has none; anything that doesn't read is dropped whole.
    @Test fun laterIdsReadBackSafely() {
        assertEquals(emptyList<Pair<Long, String>>(), BudgetLink.decodeLaterIds(null))
        assertEquals(emptyList<Pair<Long, String>>(), BudgetLink.decodeLaterIds(""))
        assertEquals(listOf(5L to "-a1", 9L to "-b2"), BudgetLink.decodeLaterIds("5:-a1,9:-b2"))
        for (bad in listOf("5:a1", "x:-a1", "-1:-a1", "9:-b2,5:-a1", "5:-a/1", "5"))
            assertEquals(bad, emptyList<Pair<Long, String>>(), BudgetLink.decodeLaterIds(bad))
        val data = DataSnapshot(emptyList(), listOf(bill(3), bill(42), bill(8)), emptyList(), emptyList())
        assertEquals(42L, BackupManager.highestItemId(data))
        assertEquals(0L, BackupManager.highestItemId(data.copy(items = emptyList())))
    }

    // E2: moved out of Bills (Repository saves it unpaid), what counted in MyBudget is undone; moved back, it goes again once.
    @Test fun aBillLeavingBillsIsUndoneInMyBudget() {
        val paid = Payments.setPaid(bill(), true)
        val moved = paid.copy(category = "Home", paid = false)
        assertEquals(listOf(BudgetLink.Message.Undone(BudgetLink.unpricedPaymentId(paid))), BudgetLink.changes(paid, moved))
        // Unpaid in the same save: the same.
        assertEquals(listOf(BudgetLink.Message.Undone(BudgetLink.unpricedPaymentId(paid))), BudgetLink.changes(paid, moved.copy(paid = false)))
        val back = moved.copy(category = "Bills", paid = true)
        assertEquals(listOf(BudgetLink.unpricedPaymentId(paid)), BudgetLink.changes(moved, back).map { (it as BudgetLink.Message.Add).paymentId })
        // A priced one: its live payments are undone (they stay on it), and come back as they were.
        val priced = bill(amount = 5_000).copy(paid = true, payments = listOf(BillPayment("p1", 5_000, today), BillPayment("gone", 100, today, reversed = true)))
        val out = priced.copy(category = "Home", paid = false)
        assertEquals(listOf(BudgetLink.Message.Undone("p1")), BudgetLink.changes(priced, out))
        assertEquals(listOf("p1"), BudgetLink.changes(out, priced).map { (it as BudgetLink.Message.Add).paymentId })
        // Never a bill: nothing, as before.
        assertTrue(BudgetLink.changes(out, out.copy(title = "Rates")).isEmpty())
    }

    // E4: a copy of an unpriced paid bill deleted elsewhere adds no second expense; a new paid bill still goes, and so do a
    // copy's payments (same ids, which MyBudget recognises).
    @Test fun aCopyOfADeletedPaidBillAddsNoSecondExpense() {
        val copy = Payments.setPaid(bill(id = 12), true)
        assertTrue(BudgetLink.changes(BudgetLink.newBillBefore(copy, copiesPaid = true), copy).isEmpty())
        assertEquals(1, BudgetLink.changes(BudgetLink.newBillBefore(copy, copiesPaid = false), copy).size)
        val pricedCopy = bill(id = 12, amount = 5_000).copy(paid = true, payments = listOf(BillPayment("p1", 5_000, today)))
        assertEquals(listOf<BudgetLink.Message>(BudgetLink.Message.Add("p1", BudgetLink.billKey(pricedCopy), "Water", 5_000, today, BudgetLink.upcomingId(pricedCopy))),
            BudgetLink.changes(BudgetLink.newBillBefore(pricedCopy, copiesPaid = true), pricedCopy))
    }

    // E3: a weekday repeat over a range that starts on another day starts on its first matching day.
    @Test fun aWeekdayRepeatOverARangeStartsOnItsFirstDay() {
        parse("Swim every Monday 1-30 Nov").let { // 1 Nov 2026 is a Sunday
            assertNull(it.error)
            assertEquals(LocalDate.of(2026, 11, 2), it.date)
            assertNull(it.endDate)
            assertEquals(5, it.repeatCount)
        }
        parse("Gym Mon/Wed/Fri 1-30 Nov").let { assertNull(it.error); assertEquals(LocalDate.of(2026, 11, 2), it.date); assertEquals(13, it.repeatCount) }
        // Under way: from the next one on or after today (Hunt 24 E6).
        parse("Swim every Monday 1-31 Oct").let { assertNull(it.error); assertEquals(LocalDate.of(2026, 10, 12), it.date); assertEquals(3, it.repeatCount) }
        // No matching day in it at all.
        assertTrue(parse("Swim every Monday 3-4 Nov").error.orEmpty().startsWith("No day in that date range matches"))
        // One that starts on a matching day is as before.
        parse("Swim every Monday 2-30 Nov").let { assertNull(it.error); assertEquals(LocalDate.of(2026, 11, 2), it.date); assertEquals(5, it.repeatCount) }
    }

    // E5: a second window doesn't take what another waits for; a fresh process (none waiting) does.
    @Test fun onlyTheWindowWaitingForMyBudgetOwnsTheMessage() {
        val first = Any(); val second = Any()
        assertFalse(BudgetLinkOwner.othersWaiting(second))
        BudgetLinkOwner.claim(first)
        assertTrue(BudgetLinkOwner.othersWaiting(second))
        assertFalse(BudgetLinkOwner.othersWaiting(first))
        BudgetLinkOwner.release(second) // not its own: nothing changes
        assertTrue(BudgetLinkOwner.othersWaiting(second))
        BudgetLinkOwner.release(first)
        assertFalse(BudgetLinkOwner.othersWaiting(second))
    }

    // D2: a paid or skipped event's (or done task's) reminder is cancelled again only within two days of it.
    @Test fun oldPaidRemindersAreLeftAlone() {
        val now = 1_800_000_000_000L
        assertTrue(stillCancelled(now + 60_000, now))
        assertTrue(stillCancelled(now - 86_400_000L, now))
        assertFalse(stillCancelled(now - 3 * 86_400_000L, now))
    }

    // D3: what the receiver hands on keeps what it was to do, each in its own unique work, so a plain reschedule doesn't
    // replace a boot's missed reminders.
    @Test fun handedOnWorkKeepsItsJob() {
        assertEquals(RescheduleRemindersWorker.Job.AFTER_BOOT, RescheduleRemindersWorker.jobOf("AFTER_BOOT"))
        assertEquals(RescheduleRemindersWorker.Job.RESCHEDULE, RescheduleRemindersWorker.jobOf(null))
        assertEquals(RescheduleRemindersWorker.Job.RESCHEDULE, RescheduleRemindersWorker.jobOf("something else"))
        assertEquals("reschedule-reminders", RescheduleRemindersWorker.workName(RescheduleRemindersWorker.Job.RESCHEDULE))
        assertEquals(3, RescheduleRemindersWorker.Job.entries.map(RescheduleRemindersWorker::workName).distinct().size)
    }
}
