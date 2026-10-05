package com.example.itinerary

import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import com.example.itinerary.reminders.snoozeTime
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime

class LatestFeaturesTest {
    private fun fixture(test: suspend (Repository, AttachmentStore, android.content.Context) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "latest-features-test").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("latest_test_$name", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        val store = AttachmentStore(context)
        val repo = Repository(db, store, object : ReminderAlarms {
            override fun schedule(item: ItineraryItem, reminder: Reminder) { assertFalse(item.paid) }
            override fun cancel(reminderId: Long) {}
        })
        try { test(repo, store, context) } finally { db.close(); dir.deleteRecursively(); context.getSharedPreferences("settings", 0).edit().clear().commit() }
    }
    private fun bill() = ItineraryItem(tripId = 0, date = LocalDate.of(2030, 1, 31), startTime = null, title = "Electricity", category = "Bills")

    @Test fun paymentCancelsDeliveryAndSnoozeOnlyForThatOccurrence() = fixture { repo, _, _ ->
        repo.saveItem(bill(), addedReminders = listOf(Reminder(itemId = 0, amount = 3, unit = ReminderUnit.DAYS)), options = EventSaveOptions(RepeatRule.MONTHLY, 2))
        val data = repo.snapshot(); val id = data.reminders.first().id
        val until = System.currentTimeMillis() + 3_600_000
        assertTrue(repo.snoozeReminder(id, until))
        var delivered = 0
        repo.deliverReminder(id, 0) { _, _ -> delivered++ }
        assertEquals(0, delivered)
        repo.deliverReminder(id, until) { _, _ -> delivered++ }
        assertEquals(1, delivered)
        repo.setPaid(data.items.first().id, true)
        repo.rescheduleAllReminders()
        repo.deliverReminder(id, until) { _, _ -> delivered++ }
        assertEquals(1, delivered)
        assertFalse(repo.snoozeReminder(id, until))
        assertTrue(repo.snapshot().items.first().paid)
        assertFalse(repo.snapshot().items.last().paid)
        assertNull(repo.snapshot().reminders.first().snoozedUntil)
        repo.setPaid(data.items.first().id, false)
        repo.deliverReminder(id, 0) { _, _ -> delivered++ }
        assertEquals(2, delivered)
        // A paid bill moved to another category is no longer paid: its reminder comes again. Hunt 16: the second occurrence,
        // as one reminder already delivered is never delivered twice (hunts 11-12).
        val second = repo.snapshot().items.last()
        val secondReminder = repo.snapshot().reminders.single { it.itemId == second.id }.id
        repo.setPaid(second.id, true)
        repo.saveItem(repo.snapshot().items.last().copy(category = "Other"))
        assertFalse(repo.snapshot().items.last().paid)
        repo.deliverReminder(secondReminder, 0) { _, _ -> delivered++ }
        assertEquals(3, delivered)
        repo.deliverReminder(id, 0) { _, _ -> delivered++ }
        assertEquals(3, delivered)
    }

    @Test fun existingEventBecomesSeriesWithChildrenAndChangesFrequencyWithoutLosingPaidFlags() = fixture { repo, store, _ ->
        store.writableFileFor("bill.txt").writeText("receipt")
        repo.saveItem(bill(), added = listOf(Attachment(itemId=0,name="Receipt",fileName="bill.txt",mimeType="text/plain")),
            addedReminders = listOf(Reminder(itemId=0,amount=3,unit=ReminderUnit.DAYS)))
        val original = repo.snapshot().items.single()
        repo.saveItem(original, options=EventSaveOptions(RepeatRule.MONTHLY,3))
        val repeated = repo.snapshot()
        assertEquals(3, repeated.items.size); assertEquals(original.id, repeated.items.first().id)
        assertEquals(3, repeated.attachments.size); assertEquals(3,repeated.reminders.size)
        assertEquals(LocalDate.of(2030,2,28), repeated.items[1].date)
        repo.setPaid(repeated.items[1].id,true)
        repo.saveItem(repeated.items.first(),options=EventSaveOptions(RepeatRule.FORTNIGHTLY,entireSeries=true,changeRepeat=true))
        val changed=repo.snapshot()
        assertEquals(listOf(original.date,original.date.plusWeeks(2),original.date.plusWeeks(4)),changed.items.map { it.date })
        assertTrue(changed.items[1].paid)
        repo.saveItem(changed.items.first(),options=EventSaveOptions(RepeatRule.NONE,entireSeries=true,changeRepeat=true))
        val detached=repo.snapshot()
        assertEquals(changed.items.map { it.id to it.date },detached.items.map { it.id to it.date })
        assertTrue(detached.items.all { it.seriesId == null && it.repeatRule == "NONE" })
        assertEquals("receipt",store.fileFor("bill.txt").readText())
    }

    @Test fun paidAndSnoozedValuesSurviveBackupAndRestore() = fixture { repo, store, context ->
        repo.saveItem(bill(),addedReminders=listOf(Reminder(itemId=0,amount=3,unit=ReminderUnit.DAYS)),options=EventSaveOptions(RepeatRule.YEARLY,2,draftToken="backup-receipt"))
        val data=repo.snapshot()
        repo.setPaid(data.items.first().id,true)
        repo.snoozeReminder(data.reminders.last().id,System.currentTimeMillis()+3_600_000)
        val expected=repo.snapshot()
        val backup=BackupManager(context,repo,store,SettingsRepository(context))
        val uri=Uri.fromFile(File(context.cacheDir,"backup.zip"))
        backup.export(uri)
        repo.deleteItem(data.items.first())
        backup.restore(backup.stage(uri))
        assertEquals(expected,repo.snapshot())
    }

    @Test fun draftRoundTripKeepsUnfinishedTextAndChildren() = fixture { _, _, context ->
        val event=bill().copy(notes="line one\nline two",checklist=listOf(ChecklistEntry(text="Receipt",done=true), ChecklistEntry(text="")),paid=true,draftToken="draft-receipt")
        val attachments=listOf(Attachment(itemId=0,name="Scan",fileName="scan.pdf",mimeType="application/pdf"))
        val reminders=listOf(Reminder(itemId=0,amount=3,unit=ReminderUnit.DAYS,snoozedUntil=1234))
        EditorDraftStore(context).write(JSONObject().put("initial",DraftCodec.item(event)).put("added",DraftCodec.attachments(attachments))
            .put("reminders",DraftCodec.reminders(reminders)).put("unfinishedDuration","9999"))
        val read=EditorDraftStore(context).read()!!
        assertEquals(event,DraftCodec.item(read.getJSONObject("initial")))
        assertEquals(attachments,DraftCodec.attachments(read.getJSONArray("added")))
        assertEquals(reminders,DraftCodec.reminders(read.getJSONArray("reminders")))
        assertEquals("9999",read.getString("unfinishedDuration"))
        EditorDraftStore(context).clear();assertNull(EditorDraftStore(context).read())
    }

    @Test fun retryAfterCommittedDraftDoesNotDuplicateSeriesOrChildren() = fixture { repo, _, _ ->
        val options=EventSaveOptions(RepeatRule.WEEKLY,3,draftToken="interrupted-save")
        val reminder=Reminder(itemId=0,amount=3,unit=ReminderUnit.DAYS)
        repo.saveItem(bill(),addedReminders=listOf(reminder),options=options)
        val committed=repo.snapshot()
        repo.saveItem(bill(),addedReminders=listOf(reminder),options=options)
        assertEquals(committed,repo.snapshot())
        assertEquals(3,repo.snapshot().items.size)
        assertTrue(repo.snapshot().items.all { it.draftToken=="interrupted-save" })
    }

    @Test fun tomorrowSnoozeUsesLocalNineAcrossDaylightSaving() {
        val before=ZonedDateTime.parse("2026-10-03T23:30:00+10:00[Australia/Sydney]")
        assertEquals(ZonedDateTime.parse("2026-10-04T09:00:00+11:00[Australia/Sydney]").toInstant().toEpochMilli(),snoozeTime(true,before))
        assertEquals(before.toInstant().toEpochMilli()+3_600_000,snoozeTime(false,before))
    }
}
