package com.example.itinerary

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.MissedReminders
import com.example.itinerary.reminders.showMissedReminders
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class MissedAlarmRefillRegressionTest {
    @Test fun appOpenRefillsFreedSlotsWithoutFullRescheduleOrRepeatedDelivery() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ItineraryApp
        val scheduler = app.reminderScheduler
        val now = System.currentTimeMillis()
        val past = (1..400).map {
            PlannerTask(id = "dropped-alarm-$it", title = "Dropped alarm $it", reminderAt = now - 2 * 3_600_000L)
        }
        val future = PlannerTask(id = "waiting-after-drain", title = "Waiting after drain", reminderAt = now + 24 * 3_600_000L)
        val key = MissedReminders.taskKey(future.id)
        val state = RescheduleOnOpen.state(scheduler.canScheduleExact(), ZoneId.systemDefault())
        try {
            app.repository.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), tasks = past))
            // Model Android dropping queued alarms; the persisted ledger still owns all 400 slots.
            past.forEach { scheduler.ledger.set(MissedReminders.taskKey(it.id), it.reminderAt!!) }
            app.repository.saveTask(future)
            assertEquals(400, scheduler.ledger.all().size)
            assertNull(scheduler.armHorizon())
            assertEquals(future.reminderAt, scheduler.deferredReminders()[key])
            assertFalse(scheduler.ledger.all().containsKey(key))
            RescheduleOnOpen.done(state)

            showMissedReminders(app, afterBoot = false)
            assertFalse("The full reschedule gate stayed closed", RescheduleOnOpen.due(state))
            assertEquals("Freed slots arm the waiting alarm", mapOf(key to future.reminderAt), scheduler.ledger.all())
            assertFalse(scheduler.deferredReminders().containsKey(key))
            assertFalse(scheduler.needsArmRefill())
            assertTrue(past.all { scheduler.wasDelivered(MissedReminders.taskKey(it.id), it.reminderAt!!) })

            showMissedReminders(app, afterBoot = false)
            assertEquals("A second open keeps just the one future alarm", mapOf(key to future.reminderAt), scheduler.ledger.all())
            assertTrue(scheduler.deferredReminders().isEmpty())
        } finally {
            past.forEach { scheduler.ledger.remove(MissedReminders.taskKey(it.id)) }
            app.repository.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList()))
            context.getSystemService(NotificationManager::class.java).cancelAll()
            RescheduleOnOpen.forget()
        }
    }
}
