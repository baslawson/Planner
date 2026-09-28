package com.example.itinerary

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EventMigrationTest {
    @Test fun version12UpgradesWithoutChangingExistingRecords() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "enhancements-v12.db")
        file.delete()
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        old.execSQL("CREATE TABLE trips (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, destination TEXT NOT NULL, startDate TEXT NOT NULL, endDate TEXT NOT NULL, sortOrder INTEGER NOT NULL DEFAULT 0, colorIndex INTEGER NOT NULL DEFAULT 0, customColor INTEGER)")
        old.execSQL("CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, tripId INTEGER NOT NULL, date TEXT NOT NULL, startTime TEXT, title TEXT NOT NULL, location TEXT NOT NULL, notes TEXT NOT NULL, category TEXT NOT NULL, colorIndex INTEGER NOT NULL DEFAULT 0, customColor INTEGER, FOREIGN KEY(tripId) REFERENCES trips(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        old.execSQL("CREATE INDEX index_items_tripId ON items(tripId)")
        old.execSQL("CREATE INDEX index_items_date ON items(date)")
        old.execSQL("CREATE TABLE attachments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, itemId INTEGER NOT NULL, name TEXT NOT NULL, fileName TEXT NOT NULL, mimeType TEXT NOT NULL, url TEXT, FOREIGN KEY(itemId) REFERENCES items(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        old.execSQL("CREATE INDEX index_attachments_itemId ON attachments(itemId)")
        old.execSQL("CREATE TABLE reminders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, itemId INTEGER NOT NULL, amount INTEGER NOT NULL, unit TEXT NOT NULL, ringUntilDismissed INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(itemId) REFERENCES items(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        old.execSQL("CREATE INDEX index_reminders_itemId ON reminders(itemId)")
        old.execSQL("INSERT INTO trips VALUES (7, 'Legacy', '', '2026-09-25', '2026-09-25', 0, 2, NULL)")
        old.execSQL("INSERT INTO items VALUES (11, 7, '2026-09-25', '14:30', 'Existing event', 'Home', 'Keep my notes', 'Other', 3, NULL)")
        old.execSQL("INSERT INTO attachments VALUES (13, 11, 'Link', '', 'text/uri-list', 'https://example.com')")
        old.execSQL("INSERT INTO reminders VALUES (17, 11, 10, 'MINUTES', 0)")
        old.version = 12
        old.close()
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .addMigrations(*ALL_MIGRATIONS).build()
        try {
            val event = upgraded.itemDao().all().single()
            assertEquals(11L, event.id)
            assertEquals("Existing event", event.title)
            assertEquals("Keep my notes", event.notes)
            assertEquals("NONE", event.repeatRule)
            assertNull(event.seriesId)
            assertEquals(13L, upgraded.attachmentDao().all().single().id)
            assertEquals(17L, upgraded.reminderDao().all().single().id)
            assertNull(event.durationMinutes)
            assertTrue(event.checklist.isEmpty())
            assertFalse(event.paid)
            assertNull(upgraded.reminderDao().all().single().snoozedUntil)
            assertNull(event.billAmountMinor)
            assertEquals("AUD",event.billCurrency)
            assertFalse(event.skipped)
            assertTrue(upgraded.templateDao().all().isEmpty())
            assertEquals("",upgraded.attachmentDao().all().single().recognizedText)
            assertTrue(event.payments.isEmpty())
            assertTrue(upgraded.deletedDao().all().isEmpty())
            assertTrue(upgraded.taskDao().all().isEmpty())
            assertEquals(ALL_MIGRATIONS.last().endVersion, upgraded.openHelper.readableDatabase.version)
        } finally {
            upgraded.close()
            SQLiteDatabase.deleteDatabase(file)
        }
    }
}
