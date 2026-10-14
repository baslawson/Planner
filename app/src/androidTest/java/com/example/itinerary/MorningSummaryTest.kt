package com.example.itinerary

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.ItineraryItem
import com.example.itinerary.data.PlannerTask
import com.example.itinerary.reminders.MorningSummary
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

// The morning summary on a phone: shown once a day when on, with the day's entries, and not at all when off.
class MorningSummaryTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as ItineraryApp
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private fun summary() = manager.activeNotifications.firstOrNull { it.tag == "morning-summary" }

    @After fun tearDown() {
        app.settings.setMorningSummary(false, LocalTime.of(7, 30))
        app.settings.morningSummaryShownOn = null
        manager.cancel("morning-summary", 0)
    }

    @Test fun showsTheDayOnceWhenOnAndNothingWhenOff() = runBlocking {
        // Notifications must be allowed for the check (the uitest app is installed with -g).
        ins.uiAutomation.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS").close()
        val today = LocalDate.now()
        app.repository.saveItem(ItineraryItem(tripId = 0, date = today, startTime = LocalTime.of(23, 0), title = "QA late call"))
        app.repository.saveTask(PlannerTask(title = "QA task", dueDate = today))
        app.settings.morningSummaryShownOn = null

        app.settings.setMorningSummary(false)
        MorningSummary.show(app, MorningSummary.Kind.MORNING)
        assertNull("off: nothing", summary())

        app.settings.setMorningSummary(true, LocalTime.of(7, 30))
        MorningSummary.show(app, MorningSummary.Kind.MORNING)
        val shown = summary()
        assertNotNull("on: shown", shown)
        val extras = shown!!.notification.extras
        assertEquals("Today: 1 event, 1 task", extras.getString("android.title"))
        val lines = extras.getCharSequenceArray("android.textLines")!!.map { it.toString() }
        assertTrue(lines.toString(), lines.any { it.endsWith("QA late call") } && "☐ QA task" in lines)
        assertEquals(today.toString(), app.settings.morningSummaryShownOn)

        // Once a day: dismissed and shown again the same day, it doesn't come back.
        manager.cancel("morning-summary", 0)
        MorningSummary.show(app, MorningSummary.Kind.MORNING)
        assertNull(summary())
    }
}
