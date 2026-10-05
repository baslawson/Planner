package com.example.itinerary

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.AlarmWindow
import com.example.itinerary.reminders.MissedReminders
import com.example.itinerary.reminders.ReminderAlarms
import com.example.itinerary.reminders.eventReminderAt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

// Android 12+ refuses an app's 501st alarm: the repository keeps only the nearest AlarmWindow.LIMIT armed (no real alarms).
class ReminderAlarmWindowDataTest {
    // Arms as ReminderScheduler does: only a future time, and only up to the horizon.
    private class Alarms : ReminderAlarms {
        val armed = mutableMapOf<String, Long>()
        var horizon: Long? = null
        private var selection = AlarmWindow.Selection(null, emptySet())
        private val waiting = mutableMapOf<String, Long>()
        private val delivered = mutableMapOf<String, Long>()
        private fun arm(key: String, at: Long?) {
            if (at != null && at > System.currentTimeMillis() && selection.arms(key, at) && !wasDelivered(key, at)) {
                armed[key] = at; waiting.remove(key)
            } else {
                armed.remove(key)
                if (at != null && at > System.currentTimeMillis() && !wasDelivered(key, at)) waiting[key] = at
            }
        }
        override fun schedule(item: ItineraryItem, reminder: Reminder) = arm(MissedReminders.eventKey(reminder.id), eventReminderAt(item, reminder))
        override fun scheduleTask(task: PlannerTask) = arm(MissedReminders.taskKey(task.id), task.activeReminderAt?.takeIf { !task.done })
        override fun cancelTask(id: String) {
            val key = MissedReminders.taskKey(id); armed.remove(key); waiting.remove(key); delivered.remove(key)
        }
        override fun cancel(reminderId: Long) {
            val key = MissedReminders.eventKey(reminderId); armed.remove(key); waiting.remove(key); delivered.remove(key)
        }
        override fun armHorizon() = horizon
        override fun setArmHorizon(horizon: Long?) { this.horizon = horizon }
        override fun setArmWindow(triggers: Map<String, Long>, now: Long) {
            val candidates = triggers.filter { (key, trigger) -> !wasDelivered(key, trigger) }
            selection = AlarmWindow.select(candidates, now)
            horizon = selection.horizon
            val pending = AlarmWindow.deferred(waiting, candidates, selection, now)
            waiting.clear(); waiting.putAll(pending)
        }
        override fun markDelivered(key: String, trigger: Long) { delivered[key] = trigger; forgetDeferred(mapOf(key to trigger)) }
        override fun wasDelivered(key: String, trigger: Long) = delivered[key] == trigger
        override fun deferredReminders() = waiting.toMap()
        override fun forgetDeferred(reminders: Map<String, Long>) {
            reminders.forEach { (key, trigger) -> if (waiting[key] == trigger) waiting.remove(key) }
        }
        override fun armedCount() = armed.size
    }

    @Test fun onlyTheNearestRemindersAreArmed() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "alarm-window").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        val alarms = Alarms()
        try {
            val repo = Repository(db, AttachmentStore(object : ContextWrapper(base) { override fun getFilesDir() = dir }), alarms)
            val start = LocalDate.now().plusDays(1)
            fun reminder() = listOf(Reminder(itemId = 0, amount = 1, unit = ReminderUnit.HOURS))
            // A year of a daily event: 365 alarms, all of them armed.
            repo.saveItem(ItineraryItem(tripId = 0, date = start, startTime = LocalTime.of(9, 0), title = "Daily"),
                addedReminders = reminder(), options = EventSaveOptions(RepeatRule.DAILY, 365))
            assertEquals(365, alarms.armed.size); assertNull(alarms.horizon)
            // A second series goes over the limit: the nearest LIMIT of both are armed, the rest wait.
            repo.saveItem(ItineraryItem(tripId = 0, date = start, startTime = LocalTime.of(18, 0), title = "Weekly"),
                addedReminders = reminder(), options = EventSaveOptions(RepeatRule.WEEKLY, 100))
            val all = repo.snapshot().let { s -> s.reminders.map { r -> eventReminderAt(s.items.single { it.id == r.itemId }, r) } }
            assertEquals(465, all.size)
            assertEquals(AlarmWindow.LIMIT, alarms.armed.size)
            assertEquals(all.sorted().take(AlarmWindow.LIMIT).toSet(), alarms.armed.values.toSet())
            // A task reminder sooner than all of them is armed at once; the latest armed one waits again.
            val task = PlannerTask(title = "Call", reminderAt = System.currentTimeMillis() + 3_600_000)
            repo.saveTask(task)
            assertEquals(task.reminderAt, alarms.armed[MissedReminders.taskKey(task.id)])
            assertEquals(AlarmWindow.LIMIT, alarms.armed.size)
            // Opening the app chooses the same ones.
            repo.rescheduleAllReminders()
            assertEquals(AlarmWindow.LIMIT, alarms.armed.size)
            assertEquals((all + task.reminderAt!!).sorted().take(AlarmWindow.LIMIT).toSet(), alarms.armed.values.toSet())
            // The weekly series gone: the next rescheduling (app open, or the next alarm going off) arms all again.
            repo.deleteEventsWithUndo(repo.snapshot().items.filter { it.title == "Weekly" }.mapTo(hashSetOf()) { it.id })
            assertTrue(alarms.armed.size < AlarmWindow.LIMIT)
            repo.refillReminders()
            assertEquals(366, alarms.armed.size); assertNull(alarms.horizon)
        } finally { db.close(); dir.deleteRecursively() }
    }
    @Test fun importingSimultaneousRemindersKeepsOnlyTheBoundedIdentityWindow() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "alarm-window-ties").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        val alarms = Alarms()
        try {
            val repo = Repository(db, AttachmentStore(object : ContextWrapper(base) { override fun getFilesDir() = dir }), alarms)
            val at = System.currentTimeMillis() + 3_600_000
            val tasks = (1..601).map { PlannerTask(id = "tie-$it", title = "Task $it", reminderAt = at) }
            repo.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), tasks = tasks))
            assertEquals(AlarmWindow.LIMIT, alarms.armed.size)
            assertEquals(tasks.map { MissedReminders.taskKey(it.id) }.sorted().take(AlarmWindow.LIMIT).toSet(), alarms.armed.keys)
            val first = tasks.single { MissedReminders.taskKey(it.id) == alarms.armed.keys.first() }
            repo.setTaskDone(first.id, true)
            repo.refillReminders()
            assertEquals(AlarmWindow.LIMIT, alarms.armed.size)
            assertFalse(alarms.armed.containsKey(MissedReminders.taskKey(first.id)))
        } finally { db.close(); dir.deleteRecursively() }
    }

    @Test fun simultaneousOverflowIsDeliveredOnceWhenItsTimeArrives() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "alarm-window-due").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        val alarms = Alarms()
        try {
            val repo = Repository(db, AttachmentStore(object : ContextWrapper(base) { override fun getFilesDir() = dir }), alarms)
            val at = System.currentTimeMillis() + 3_600_000
            val tasks = (1..601).map { PlannerTask(id = "due-$it", title = "Task $it", reminderAt = at) }
            val triggers = tasks.associate { MissedReminders.taskKey(it.id) to at }
            repo.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), tasks = tasks))
            assertEquals(400, alarms.armed.size)
            assertEquals(201, alarms.deferredReminders().size)
            val shown = mutableListOf<String>()
            fun collect(missed: List<MissedReminders.Missed>) {
                shown += missed.map { MissedReminders.taskKey((it as MissedReminders.Task).task.id) }
            }
            // Simulate the 400 platform alarms ringing at their due instant. The repository records delivery.
            repo.deliverMissedReminders(alarms.armed.toMap(), at) { collect(it) }
            alarms.armed.clear()
            // Rebuilding a window at that instant must retain the 201 already-waiting due identities.
            alarms.setArmWindow(triggers, at)
            assertEquals(201, alarms.deferredReminders().size)
            com.example.itinerary.reminders.drainDeferredReminders(repo, alarms, at) { collect(it) }
            assertEquals(601, shown.size)
            assertEquals(triggers.keys, shown.toSet())
            assertTrue(alarms.deferredReminders().isEmpty())
            com.example.itinerary.reminders.drainDeferredReminders(repo, alarms, at) { collect(it) }
            repo.deliverMissedReminders(triggers, at) { collect(it) }
            assertEquals("Repeated drains and stale pending copies must not show any of them twice", 601, shown.size)
            // A changed trigger must survive forgetting the captured older deferred version.
            val newTrigger = at + 60_000
            alarms.setArmWindow((1..601).associate { "t:next-$it" to newTrigger }, at)
            val deferred = alarms.deferredReminders()
            assertEquals(201, deferred.size)
            alarms.forgetDeferred(deferred.mapValues { it.value - 1 })
            assertEquals(deferred, alarms.deferredReminders())
            repo.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList()))
            assertTrue("Restoring empty data clears obsolete deferred identities", alarms.deferredReminders().isEmpty())
        } finally { db.close(); dir.deleteRecursively() }
    }

    @Test fun actualSchedulerBoundsPlatformAlarmsAndPersistsOverflow() = runBlocking {
        // The runner's isolated uitest app owns these alarms; the shared debug/release apps are never touched.
        val app = baseApp()
        val at = System.currentTimeMillis() + 24 * 3_600_000L
        val tasks = (1..601).map { PlannerTask(id = "platform-tie-$it", title = "Platform task $it", reminderAt = at) }
        val keys = tasks.mapTo(hashSetOf()) { MissedReminders.taskKey(it.id) }
        try {
            app.repository.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), tasks = tasks))
            val armed = app.reminderScheduler.ledger.all()
            val deferred = app.reminderScheduler.deferredReminders()
            assertEquals(400, armed.size)
            assertEquals(201, deferred.size)
            assertEquals(keys, armed.keys + deferred.keys)
            assertTrue(armed.keys.intersect(deferred.keys).isEmpty())
            assertTrue((armed.values + deferred.values).all { it == at })
            // A fresh scheduler reads the same persisted overflow rather than relying on process-only state.
            assertEquals(deferred, com.example.itinerary.reminders.ReminderScheduler(
                InstrumentationRegistry.getInstrumentation().targetContext).deferredReminders())
        } finally {
            app.repository.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList()))
        }
        assertTrue(app.reminderScheduler.ledger.all().isEmpty())
        assertTrue(app.reminderScheduler.deferredReminders().isEmpty())
    }

    private fun baseApp() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ItineraryApp

    @Test fun delayedPlatformAlarmsReleaseASlotEvenWithoutAFutureHorizon() = runBlocking {
        val app = baseApp()
        val scheduler = app.reminderScheduler
        val now = System.currentTimeMillis()
        val oldKeys = (1..400).map { MissedReminders.taskKey("delayed-platform-$it") }
        val future = PlannerTask(id = "future-after-delayed", title = "Future after delayed alarms", reminderAt = now + 24 * 3_600_000L)
        val futureKey = MissedReminders.taskKey(future.id)
        try {
            // Represent 400 past-due platform alarms still queued by Android. No alarms are actually fired by this test.
            oldKeys.forEach { scheduler.ledger.set(it, now - 60_000) }
            app.repository.saveTask(future)
            assertNull("The single future candidate fits the time window", scheduler.armHorizon())
            assertEquals(future.reminderAt, scheduler.deferredReminders()[futureKey])
            assertFalse(scheduler.ledger.all().containsKey(futureKey))
            assertTrue(scheduler.needsArmRefill())
            // The same ledger removal that a real receiver performs frees a slot. Refill must honour the rebalance flag.
            scheduler.ledger.fired(oldKeys.first(), now)
            app.repository.refillReminders()
            assertEquals(future.reminderAt, scheduler.ledger.all()[futureKey])
            assertFalse(scheduler.deferredReminders().containsKey(futureKey))
            assertFalse(scheduler.needsArmRefill())
        } finally {
            oldKeys.forEach(scheduler.ledger::remove)
            app.repository.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList()))
        }
        assertTrue(scheduler.ledger.all().isEmpty())
        assertTrue(scheduler.deferredReminders().isEmpty())
    }

}
