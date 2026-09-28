package com.example.itinerary

import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Fills the test app with made-up entries that stress layouts (long titles and places, amounts, overdue bills,
 * priorities, checklists, repeats, an overnight event, a link) for checking screens by hand at large text, in
 * landscape and in the light themes. Run it on its own; it leaves the data in place:
 * `am instrument -w -e class com.example.itinerary.SweepSeed#seedLayoutSample …`
 */
class SweepSeed {
    @HarnessStage @Test fun seedLayoutSample() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ItineraryApp
        val repo = app.repository
        repo.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList()))
        val today = LocalDate.now()
        repo.saveItem(ItineraryItem(tripId = 0, date = today, startTime = LocalTime.of(9, 30), durationMinutes = 90,
            title = "Quarterly planning meeting with the regional operations and finance teams",
            location = "Level 12 conference room, 1 Long Street Name Boulevard, Northbridge WA 6003",
            notes = "Bring the printed agenda and last quarter's figures.",
            checklist = listOf(ChecklistEntry(text = "Print agenda", done = true), ChecklistEntry(text = "Book projector"))),
            addedReminders = listOf(Reminder(itemId = 0, amount = 15, unit = ReminderUnit.MINUTES)),
            added = listOf(Attachment(itemId = 0, name = "Meeting notes", fileName = "", mimeType = "text/uri-list",
                url = "https://example.com/a/very/long/path/to/the/meeting/notes/for/the/quarterly/planning/session")))
        repo.saveItem(ItineraryItem(tripId = 0, date = today, startTime = LocalTime.of(12, 0), title = "Lunch", location = "Cafe"))
        repo.saveItem(ItineraryItem(tripId = 0, date = today.minusDays(1), startTime = LocalTime.of(23, 30), durationMinutes = 150,
            title = "Overnight flight to Singapore"))
        repo.saveItem(ItineraryItem(tripId = 0, date = today.plusDays(1), startTime = LocalTime.of(18, 0),
            title = "Gym class", category = "Other"), options = EventSaveOptions(RepeatRule.WEEKLY, 6))
        repo.saveItem(ItineraryItem(tripId = 0, date = today.minusDays(4), startTime = null,
            title = "Electricity and gas combined account", category = "Bills", billAmountMinor = 123456))
        repo.saveItem(ItineraryItem(tripId = 0, date = today.plusDays(3), startTime = null,
            title = "Water rates", category = "Bills", billAmountMinor = 8950),
            addedReminders = listOf(Reminder(itemId = 0, amount = 3, unit = ReminderUnit.DAYS)),
            options = EventSaveOptions(RepeatRule.MONTHLY, 4))
        repo.saveItem(ItineraryItem(tripId = 0, date = today.plusDays(5), startTime = null, title = "Car registration", category = "Bills"))
        repo.saveTask(PlannerTask(title = "Renew passport before the overseas trip in December", dueDate = today,
            priority = TaskPriority.HIGH, notes = "Photos, old passport, form",
            checklist = listOf(ChecklistEntry(text = "Get photos"), ChecklistEntry(text = "Fill in the form"))))
        repo.saveTask(PlannerTask(title = "Call the plumber", dueDate = today.minusDays(2)))
        repo.saveTask(PlannerTask(title = "Sort out the garage", priority = TaskPriority.LOW))
        repo.saveTask(PlannerTask(title = "Water the plants", dueDate = today.plusDays(1), repeat = "DAILY"))
    }

    /** Sets the app's own text size and theme for a sweep pass: `-e textSize 150 -e theme HIGH_CONTRAST`. */
    @HarnessStage @Test fun applyLayoutSettings() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val settings = (ins.targetContext.applicationContext as ItineraryApp).settings
        val args = InstrumentationRegistry.getArguments()
        args.getString("textSize")?.let { settings.setTextSizePercent(it.toInt()) }
        args.getString("theme")?.let { settings.setAppTheme(AppTheme.valueOf(it)) }
        // The settings are written with apply(); a commit writes them before this process ends.
        ins.targetContext.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().commit()
    }
}
