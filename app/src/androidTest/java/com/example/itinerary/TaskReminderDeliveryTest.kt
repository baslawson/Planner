package com.example.itinerary

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.PlannerTask
import com.example.itinerary.reminders.notificationsEnabled
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Requires permission to use the shared emulator and the external data backup/restore harness. */
class TaskReminderDeliveryTest {
    @Test fun realAlarmsDeliverDistinctNotificationsAndCompletionCancelsThem() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ItineraryApp
        assertTrue("Enable notifications before running this delivery test", notificationsEnabled(context))
        assertTrue("Exact alarm permission is required for this bounded test", app.reminderScheduler.canScheduleExact())
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val prefix = "qa-task-reminder-${System.currentTimeMillis()}-"
        // These suffixes have the same String hash; independent URIs/tags must still deliver both.
        val one = PlannerTask(id = prefix + "Aa", title = "First task reminder", reminderAt = System.currentTimeMillis() + 3000)
        val two = one.copy(id = prefix + "BB", title = "Second task reminder")
        assertEquals(one.id.hashCode(), two.id.hashCode())
        fun present(id: String) = manager.activeNotifications.any { it.tag == "task:$id" }
        fun waitFor(check: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 20000
            while (!check() && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
            assertTrue("Task notification expectation timed out", check())
        }
        try {
            assertFalse(present(one.id)); assertFalse(present(two.id))
            app.repository.saveTask(one); app.repository.saveTask(two)
            waitFor { present(one.id) && present(two.id) }
            app.repository.setTaskDone(one.id, true)
            waitFor { !present(one.id) }
            assertTrue(present(two.id))
            app.repository.deleteTask(two.id)
            waitFor { !present(two.id) }
        } finally {
            app.repository.deleteTask(one.id)
            app.repository.deleteTask(two.id)
        }
    }
}
