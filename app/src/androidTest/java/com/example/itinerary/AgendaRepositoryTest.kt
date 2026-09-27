package com.example.itinerary

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Isolated in-memory database; never opens the user's database or schedules test alarms. */
class AgendaRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository
    private val day = LocalDate.of(2026, 9, 25)

    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = Repository(db, AttachmentStore(context), ReminderScheduler(context))
    }
    @After fun close() { db.close() }

    private fun event(title: String, owner: Long = 0, time: LocalTime? = null) =
        ItineraryItem(tripId = owner, date = day, startTime = time, title = title)

    @Test fun firstEventCreatesInternalOwnerAndSubsequentEventsReuseIt() = runBlocking {
        repo.saveItem(event("First"))
        repo.saveItem(event("Second").copy(date = day.plusYears(1)))
        val snapshot = repo.snapshot()
        assertEquals(1, snapshot.trips.size)
        assertEquals(2, snapshot.items.size)
        assertTrue(snapshot.items.all { it.tripId == snapshot.trips.single().id })
        val first = snapshot.items.first { it.title == "First" }
        repo.saveItem(first.copy(title = "Edited", date = day.minusYears(1)))
        assertEquals("Edited", repo.allItems.first().first().title)
        repo.deleteItem(first)
        assertEquals(listOf("Second"), repo.allItems.first().map { it.title })
    }

    @Test fun calendarIncludesAllLegacyOwnersWithAttachmentsAndReminders() = runBlocking {
        val a = db.tripDao().upsert(Trip(name = "Old A", destination = "", startDate = day, endDate = day))
        val b = db.tripDao().upsert(Trip(name = "Old B", destination = "", startDate = day, endDate = day))
        val timed = db.itemDao().upsert(event("Timed", a, LocalTime.NOON))
        val allDay = db.itemDao().upsert(event("All day", b))
        db.attachmentDao().insert(Attachment(itemId = timed, name = "Link", fileName = "", mimeType = "text/uri-list", url = "https://example.com"))
        db.reminderDao().insert(Reminder(itemId = allDay, amount = 0, unit = ReminderUnit.MINUTES))
        assertEquals(listOf(allDay, timed), repo.allItems.first().map { it.id })
        assertEquals(timed, repo.allAttachments.first().single().itemId)
        assertEquals(allDay, repo.allReminders.first().single().itemId)
        val before = repo.snapshot()
        repo.saveItem(event("New"))
        val after = repo.snapshot()
        assertEquals(before.trips, after.trips)
        assertTrue(after.items.containsAll(before.items))
        assertEquals(before.attachments, after.attachments)
        assertEquals(before.reminders, after.reminders)
    }

    @Test fun failedFirstSaveRollsBackInternalOwnerToo() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_event BEFORE INSERT ON items BEGIN SELECT RAISE(ABORT, 'test failure'); END",
        )
        try {
            repo.saveItem(event("Rejected"))
            fail("Expected the database trigger to reject the save")
        } catch (_: android.database.sqlite.SQLiteException) {
            assertTrue(repo.snapshot().items.isEmpty())
            assertTrue(repo.snapshot().trips.isEmpty())
        }
    }
}
