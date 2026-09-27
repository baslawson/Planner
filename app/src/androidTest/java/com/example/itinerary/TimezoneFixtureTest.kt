package com.example.itinerary

import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Opt-in fixture for an external system-timezone test. Caller backs up/restores app data. */
class TimezoneFixtureTest {
    @Test fun fixture(): Unit = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("timezoneFixture")
        assumeTrue(phase == "prepare" || phase == "cleanup")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = (context.applicationContext as ItineraryApp).repository
        val title = "QA timezone regression"
        val existing = repo.snapshot().items.filter { it.title == title }
        if (phase == "prepare") {
            assertTrue(existing.isEmpty())
            val date = LocalDate.now().plusDays(5)
            repo.saveItem(ItineraryItem(tripId = 0, date = date, startTime = LocalTime.of(10, 0), title = title),
                addedReminders = listOf(Reminder(itemId = 0, amount = 0, unit = ReminderUnit.MINUTES)))
            val snapshot = repo.snapshot()
            val item = snapshot.items.single { it.title == title }
            val reminder = snapshot.reminders.single { it.itemId == item.id }
            File(context.cacheDir, "qa-bug-fix-timezone.json").writeText(JSONObject()
                .put("date", date.toString()).put("reminderId", reminder.id).toString())
        } else {
            existing.forEach { repo.deleteItem(it) }
            File(context.cacheDir, "qa-bug-fix-timezone.json").delete()
        }
    }
}
