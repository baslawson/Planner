package com.example.itinerary.reminders

import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.Reminder
import com.example.itinerary.data.ReminderUnit
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

// RB-3: the snapshot of alarms set again before the first unlock after a reboot.
class LockedAlarmsTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val event = LockedAlarm.Event(7, now + hour, "Dentist\twith Sam", "Main St\n2nd floor", "2026-10-05", "09:30",
        "30 minutes before", bill = false, ring = true, snoozeToken = "abc", billToken = null)

    @Test fun roundTripKeepsEveryFieldIncludingTabsLineBreaksBackslashesAndNulls() {
        val alarms = listOf(event, event.copy(reminderId = 8, bill = true, ring = false, billToken = "def", title = "C:\\bills\\0", location = ""),
            LockedAlarm.Task("task-1", now + 2 * hour, "Call \\0 back\r\n"), LockedAlarm.Note("note-1", now + 3 * hour))
        val snapshot = LockedSnapshot("HOUR_24", alarms)
        assertEquals(snapshot, LockedAlarmCodec.decode(LockedAlarmCodec.encode(snapshot)))
        assertEquals(LockedSnapshot(null, emptyList()), LockedAlarmCodec.decode(LockedAlarmCodec.encode(LockedSnapshot(null, emptyList()))))
    }

    @Test fun aNoteKeepsNoWordsOnlyItsIdTimeAndRingChoice() {
        val text = LockedAlarmCodec.encode(LockedSnapshot(null, listOf(LockedAlarm.Note("n-9", now))))
        assertEquals(listOf("n", "n-9", now.toString(), "0"), text.lines()[1].split('\t'))
    }

    @Test fun anotherFormatOrVersionIsNotReadAndADamagedLineCostsOnlyItself() {
        assertNull(LockedAlarmCodec.decode(""))
        assertNull(LockedAlarmCodec.decode("something else\n"))
        assertNull(LockedAlarmCodec.decode("planner-locked-alarms\t2\t\\0\nn\tx\t1\n"))
        val text = LockedAlarmCodec.encode(LockedSnapshot(null, listOf(LockedAlarm.Task("t", now, "Keep"))))
        val damaged = text + "e\tnot-a-number\t1\n" + "t\t\t5\tno id\n" + "x\twhat\n" + "n\tonly-id\n"
        assertEquals(listOf(LockedAlarm.Task("t", now, "Keep")), LockedAlarmCodec.decode(damaged)!!.alarms)
    }

    @Test fun firedListRoundTrips() {
        val fired = listOf(LockedFired("e:7", now, now + 5), LockedFired("t:a\tb", now + 1, now + 6))
        assertEquals(fired, LockedAlarmCodec.decodeFired(LockedAlarmCodec.encodeFired(fired)))
        assertEquals(emptyList<LockedFired>(), LockedAlarmCodec.decodeFired(""))
        assertEquals(listOf(fired[0]), LockedAlarmCodec.decodeFired(LockedAlarmCodec.encodeFired(fired.take(1)) + "broken\n"))
    }

    @Test fun selectionIsTheNearestAheadWithinTwoWeeksAtMostMax() {
        val past = LockedAlarm.Note("past", now)
        val far = LockedAlarm.Note("far", now + LockedAlarmSelection.HORIZON_MS + 1)
        val edge = LockedAlarm.Note("edge", now + LockedAlarmSelection.HORIZON_MS)
        val soon = (1..LockedAlarmSelection.MIN_KEPT).map { LockedAlarm.Note("soon$it", now + it) }
        assertEquals(soon + edge, LockedAlarmSelection.select(listOf(far, edge, past) + soon.reversed(), now))
        val many = (1..LockedAlarmSelection.MAX + 10).map { LockedAlarm.Note("n$it", now + it * 1000L) }.shuffled()
        val chosen = LockedAlarmSelection.select(many, now)
        assertEquals(LockedAlarmSelection.MAX, chosen.size)
        assertEquals((1..LockedAlarmSelection.MAX).map { "n$it" }, chosen.map { (it as LockedAlarm.Note).id })
    }

    // R6-3: a monthly bill reminder, a month off, is kept although it's past the two weeks: the nearest few always are.
    @Test fun theNearestFewAreKeptHoweverFar() {
        val day = 24 * hour
        val monthly = LockedAlarm.Note("monthly", now + 31 * day)
        assertEquals(listOf(monthly), LockedAlarmSelection.select(listOf(monthly), now))
        assertNull("nothing left out: no rewrite needed", LockedAlarmSelection.rewriteAt(listOf(monthly), now))
        // With the nearest few close by, one further off than two weeks is left out, and the snapshot is due to be
        // written again before its two weeks run out.
        val near = (1..LockedAlarmSelection.MIN_KEPT).map { LockedAlarm.Note("near$it", now + it * hour) }
        assertEquals(near, LockedAlarmSelection.select(near + monthly, now))
        val again = LockedAlarmSelection.rewriteAt(near + monthly, now)!!
        assertTrue(again > now + LockedAlarmSelection.REFRESH_MS && again < now + LockedAlarmSelection.HORIZON_MS)
        // Written again then (the near ones have rung), the far one is kept.
        assertEquals(listOf(monthly), LockedAlarmSelection.select(near + monthly, again))
    }

    // AS-5: cut short by MAX, the snapshot is written again by the last alarm it kept, not two weeks on.
    @Test fun aSnapshotCutByItsCapIsDueByItsLastAlarm() {
        val many = (1..LockedAlarmSelection.MAX + 10).map { LockedAlarm.Note("n$it", now + it * 60_000L) }
        val kept = LockedAlarmSelection.select(many, now)
        assertEquals(LockedAlarmSelection.MAX, kept.size)
        assertEquals(kept.last().trigger, LockedAlarmSelection.rewriteAt(many, now))
        // Exactly MAX ahead: all kept, no rewrite.
        assertNull(LockedAlarmSelection.rewriteAt(many.take(LockedAlarmSelection.MAX), now))
    }

    @Test fun mirrorSaysWhenTheSnapshotIsDueAgain() {
        val near = (1..LockedAlarmSelection.MIN_KEPT).map { LockedAlarm.Note("near$it", now + it * hour) }
        val mirror = LockedAlarmMirror(read = { null }, write = {}, timeFormat = { null })
        near.forEach(mirror::put)
        assertEquals("all kept: no refresh", Long.MAX_VALUE, mirror.save(now))
        assertNull("nothing changed: not written", mirror.save(now))
        mirror.put(LockedAlarm.Note("far", now + 40 * 24 * hour))
        assertEquals(now + LockedAlarmSelection.REWRITE_AFTER_MS, mirror.save(now))
    }

    // T16-1: a fresh process (a Done from a notification) knows only what the last snapshot kept. Its save must not cancel
    // the refresh that snapshot still needs, nor count as a full write for the 12-hour check.
    @Test fun aPartialMirrorKeepsTheRefreshAndTheLastFullWrite() {
        var stored: LockedSnapshot? = null
        val many = (1..84).map { LockedAlarm.Note("n$it", now + it * 4 * hour) }
        val first = LockedAlarmMirror(read = { stored }, write = { stored = LockedAlarmCodec.decode(LockedAlarmCodec.encode(it)) }, timeFormat = { null })
        many.forEach(first::put)
        first.markComplete(now)
        val refresh = first.save(now)!!
        assertTrue(refresh < Long.MAX_VALUE)
        assertEquals(refresh, stored!!.rewriteAt)
        assertEquals(now, stored!!.fullAt)
        val later = now + hour
        val fresh = LockedAlarmMirror(read = { stored }, write = { stored = LockedAlarmCodec.decode(LockedAlarmCodec.encode(it)) }, timeFormat = { null })
        fresh.remove("n:n1")
        assertEquals("a partial save cancelled the refresh", refresh, fresh.save(later))
        assertEquals(refresh, stored!!.rewriteAt)
        assertEquals("a partial save counted as a full write", now, stored!!.fullAt)
        // Every alarm set again in that process: it knows them all, and writes as a full set.
        many.drop(1).forEach(fresh::put)
        fresh.markComplete(later + LockedAlarmSelection.REFRESH_MS)
        fresh.save(later + LockedAlarmSelection.REFRESH_MS)
        assertEquals(later + LockedAlarmSelection.REFRESH_MS, stored!!.fullAt)
    }

    @Test fun eventCarriesWhatTheNotificationShowsAndABillTokenOnlyForAnUnpaidBill() {
        val item = ItineraryItem(id = 3, tripId = 1, date = LocalDate.of(2026, 10, 5), startTime = LocalTime.of(9, 30), title = "Rent",
            location = "Bank", category = "Bills")
        val reminder = Reminder(id = 11, itemId = 3, amount = 1, unit = ReminderUnit.values().first(), ringUntilDismissed = true)
        val e = LockedAlarm.Event.of(item, reminder, now)
        assertEquals("e:11", e.key)
        assertEquals(listOf("Rent", "Bank", "2026-10-05", "09:30", reminder.label), listOf(e.title, e.location, e.date, e.time, e.offsetLabel))
        assertTrue(e.bill && e.ring && e.billToken != null && e.snoozeToken != null)
        assertNull(LockedAlarm.Event.of(item.copy(paid = true), reminder, now).billToken)
        assertNull(LockedAlarm.Event.of(item.copy(category = "Other"), reminder, now).billToken)
        assertEquals("", LockedAlarm.Event.of(item.copy(startTime = null), reminder, now).time)
        assertEquals("t:x", LockedAlarm.Task("x", now, "T").key)
        assertEquals("n:y", LockedAlarm.Note("y", now).key)
    }

    @Test fun mirrorWritesOnlyAfterAChangeAndStartsFromTheLastSnapshot() {
        val writes = mutableListOf<LockedSnapshot>()
        var format: String? = "HOUR_12"
        val earlier = LockedAlarm.Task("old", now + hour, "From before")
        val mirror = LockedAlarmMirror(read = { LockedSnapshot("HOUR_12", listOf(earlier)) }, write = { writes += it }, timeFormat = { format })
        mirror.save(now)
        assertEquals("nothing changed: nothing written", 0, writes.size)
        mirror.put(event)
        mirror.put(event)
        mirror.save(now)
        assertEquals(listOf(LockedSnapshot("HOUR_12", listOf(earlier, event))), writes)
        // The same alarm set again: no write.
        mirror.put(event); mirror.save(now)
        assertEquals(1, writes.size)
        // Moved, then cleared.
        mirror.put(event.copy(trigger = now + 5 * hour)); mirror.remove("t:old"); mirror.remove("t:never-set")
        mirror.save(now)
        assertEquals(LockedSnapshot("HOUR_12", listOf(event.copy(trigger = now + 5 * hour))), writes.last())
        // A new time format is written although no alarm changed.
        format = "HOUR_24"; mirror.save(now)
        assertEquals("HOUR_24", writes.last().timeFormat)
        assertEquals(3, writes.size)
        // Alarms that have rung by now aren't written.
        mirror.put(LockedAlarm.Note("n", now + hour)); mirror.save(now + 6 * hour)
        assertEquals(emptyList<LockedAlarm>(), writes.last().alarms)
    }

    @Test fun mirrorStartsEmptyWhenTheSnapshotCantBeRead() {
        val writes = mutableListOf<LockedSnapshot>()
        val mirror = LockedAlarmMirror(read = { throw IllegalStateException("locked") }, write = { writes += it }, timeFormat = { null })
        mirror.put(event); mirror.save(now)
        assertEquals(listOf(LockedSnapshot(null, listOf(event))), writes)
    }

    // D6-4: a snapshot written some time ago is refreshed when a reminder rings, so a long stretch without opening
    // Planner still leaves the next two weeks in it.
    @Test fun aSnapshotIsRefreshedOnceItIsHalfADayOldOrMissingOrFromTheFuture() {
        assertTrue(LockedAlarmSelection.stale(null, now))
        assertFalse(LockedAlarmSelection.stale(now - LockedAlarmSelection.REFRESH_MS + 1, now))
        assertTrue(LockedAlarmSelection.stale(now - LockedAlarmSelection.REFRESH_MS, now))
        // The clock was set back since it was written.
        assertTrue(LockedAlarmSelection.stale(now + hour, now))
    }

    @Test fun aForcedSaveWritesTheSelectionAsItIsNowEvenWithNothingChanged() {
        val writes = mutableListOf<LockedSnapshot>()
        val gone = LockedAlarm.Task("gone", now + hour, "Rung already")
        val later = event.copy(trigger = now + 5 * hour)
        val mirror = LockedAlarmMirror(read = { LockedSnapshot("HOUR_12", listOf(gone, later)) }, write = { writes += it }, timeFormat = { "HOUR_12" })
        mirror.save(now + 2 * hour)
        assertEquals(0, writes.size)
        mirror.save(now + 2 * hour, force = true)
        assertEquals(listOf(LockedSnapshot("HOUR_12", listOf(later))), writes)
    }
}
