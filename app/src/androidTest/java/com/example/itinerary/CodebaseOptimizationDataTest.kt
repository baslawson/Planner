package com.example.itinerary

import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.Collections
import java.util.concurrent.Executor

class CodebaseOptimizationDataTest {
    private val day = LocalDate.of(2026, 9, 27)
    private fun event(id: Long) = ItineraryItem(id=id, tripId=1, date=day, startTime=null, title="Fixture $id")
    private fun fixture(test: suspend (AppDatabase, Repository, List<String>, MutableList<Long>) -> Unit) = runBlocking<Unit> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir,"codebase-data").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = dir }
        val queries = Collections.synchronizedList(mutableListOf<String>())
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java)
            .setQueryCallback({ sql, _ -> queries.add(sql) }, Executor { it.run() }).build()
        val reconciled = mutableListOf<Long>()
        val repo = Repository(db, AttachmentStore(context), object : ReminderAlarms {
            override fun schedule(item: ItineraryItem, reminder: Reminder) {}
            override fun cancel(reminderId: Long) {}
            override fun reconcile(item: ItineraryItem, reminder: Reminder) { reconciled += item.id }
        })
        try {
            db.tripDao().upsert(Trip(id=1,name="Fixture",destination="",startDate=day,endDate=day))
            test(db,repo,queries,reconciled)
        } finally { db.close(); dir.deleteRecursively() }
    }
    private fun assertNoWholeEventRead(queries: List<String>) {
        // Copy under the list's lock: background follow-up work can still be adding queries.
        val seen = synchronized(queries) { queries.toList() }
        assertFalse(seen.any { it.trim().matches(Regex("SELECT \\* FROM items(?: ORDER BY.*)?",RegexOption.IGNORE_CASE)) })
    }

    @Test fun saveDeleteAndReconcileAvoidWholeEventReadsAndDraftRetryIsIdempotent() = fixture { db,repo,queries,reconciled ->
        db.itemDao().insertAll((1L..500L).map(::event))
        db.reminderDao().insert(Reminder(itemId=7,amount=0,unit=ReminderUnit.MINUTES))
        (queries as MutableList).clear()
        repo.saveItem(event(7).copy(title="Updated"),options=EventSaveOptions(draftToken="saved-draft"))
        repo.saveItem(event(0),options=EventSaveOptions(draftToken="saved-draft"))
        repo.rescheduleAllReminders()
        repo.deleteWithUndo(event(8))
        assertNoWholeEventRead(queries)
        assertEquals(listOf(7L),reconciled)
        assertEquals("Updated",db.itemDao().byId(7)!!.title)
        assertEquals(499,db.itemDao().all().size)
        assertNull(db.itemDao().byId(8))
    }

    @Test fun moreThanSqliteParameterLimitDeletesOnlySelectedChildrenAndUndoRestoresThem() = fixture { db,repo,_,_ ->
        db.withTransaction {
            db.itemDao().insertAll((1L..1105L).map(::event))
            db.attachmentDao().insertAll((1L..1105L).map { Attachment(id=it,itemId=it,name="Shared",fileName="shared",mimeType="text/plain") })
            db.reminderDao().insertAll((1L..1105L).map { Reminder(id=it,itemId=it,amount=0,unit=ReminderUnit.MINUTES) })
        }
        val selected=(1L..1100L).reversed().toSet()
        repo.deleteEventsWithUndo(selected + 99999L)
        assertEquals((1101L..1105L).toList(),db.itemDao().all().map { it.id })
        assertEquals(5,db.attachmentDao().all().size)
        assertEquals(5,db.reminderDao().all().size)
        val bundle=repo.pendingDeletions.value.single()
        assertEquals((1L..1100L).toList(),bundle.items.map { it.id })
        assertEquals(selected,bundle.attachments.map { it.itemId }.toSet())
        repo.undoDeletion(bundle.token)
        assertEquals(1105,db.itemDao().all().size)
        assertEquals(1105,db.attachmentDao().all().size)
        assertEquals(1105,db.reminderDao().all().size)
    }

    @Test fun widgetQueryMatchesFullHistoryForOvernightBillsSkippedAndMidnightBoundaries() = fixture { db,repo,_,_ ->
        val events=listOf(
            event(1),
            event(2).copy(date=day.minusDays(1),startTime=LocalTime.of(23,30),durationMinutes=120),
            event(3).copy(date=day.minusDays(1),startTime=LocalTime.of(23,0),durationMinutes=60),
            event(4).copy(date=day.minusDays(1),startTime=LocalTime.of(23,30),durationMinutes=120,category="Bills"),
            event(5).copy(skipped=true),event(6).copy(date=day.plusDays(1)),
            event(7).copy(date=day.minusDays(2),startTime=LocalTime.of(23,59),durationMinutes=1440),
            event(8).copy(date=day.minusDays(1),startTime=LocalTime.NOON,durationMinutes=1440),
            event(9).copy(category="Bills",paid=true),event(10).copy(date=day.minusDays(1)),
        )
        db.itemDao().insertAll(events)
        for (date in listOf(day.minusDays(1),day,day.plusDays(1))) {
            assertEquals(eventsOnDay(events.filterNot { it.skipped },date),repo.widgetEvents(date))
        }
        assertEquals(setOf(1L,2L,8L,9L),repo.widgetEvents(day).map { it.id }.toSet())
    }

    @Test fun convertingExistingEventToSeriesCopiesChildrenAndKeepsUnrelatedEvents() = fixture { db,repo,_,_ ->
        db.itemDao().insertAll(listOf(event(1),event(2)))
        db.attachmentDao().insert(Attachment(itemId=1,name="Link",fileName="",mimeType="text/plain",url="https://example.com"))
        db.reminderDao().insert(Reminder(itemId=1,amount=1,unit=ReminderUnit.DAYS))
        repo.saveItem(event(1).copy(title="Series"),options=EventSaveOptions(RepeatRule.WEEKLY,3))
        val members=db.itemDao().all().filter { it.seriesId!=null }
        assertEquals(3,members.size)
        for (member in members) {
            assertEquals(1,db.attachmentDao().forItem(member.id).size)
            assertEquals(1,db.reminderDao().forItem(member.id).size)
        }
        assertEquals(event(2),db.itemDao().byId(2))
        repo.deleteWithUndo(members.first(),entireSeries=true)
        assertEquals(listOf(event(2)),db.itemDao().all())
        repo.undoDeletion(repo.pendingDeletions.value.single().token)
        assertEquals(4,db.itemDao().all().size)
    }
    @Test fun seriesKeepsChronologicalOrderAcrossExtendedIsoYears() = fixture { db,repo,_,_ ->
        val early=event(21).copy(date=LocalDate.of(9999,12,31),seriesId="extended",repeatRule="DAILY")
        val late=event(22).copy(date=early.date.plusDays(1),seriesId="extended",repeatRule="DAILY")
        db.itemDao().insertAll(listOf(early,late))
        repo.saveItem(early.copy(title="Updated"),options=EventSaveOptions(
            repeat=RepeatRule.DAILY,entireSeries=true,changeRepeat=true))
        assertEquals(early.date,db.itemDao().byId(21)!!.date)
        assertEquals(late.date,db.itemDao().byId(22)!!.date)
    }

}
