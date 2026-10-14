package com.example.itinerary

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.zip.ZipFile

/** Selected events saved as one template (TemplateSet): saved, added again from another day, and kept in backups. */
class TemplateSetDataTest {
    private fun fixture(test: suspend (Repository, BackupManager, File) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "template-set-tests").apply { deleteRecursively(); mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences("template_set_$name", mode)
        }
        val alarms = object : ReminderAlarms {
            override fun schedule(item: ItineraryItem, reminder: Reminder) {}
            override fun cancel(reminderId: Long) {}
        }
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        val store = AttachmentStore(context); val repo = Repository(db, store, alarms)
        try { test(repo, BackupManager(context, repo, store, SettingsRepository(context)), dir) }
        finally { db.close(); dir.deleteRecursively(); context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit() }
    }
    private val fri = LocalDate.of(2026, 11, 6)

    @Test fun aSetIsSavedAddedFromAnotherDayAndKeptInBackups() = fixture { repo, backup, dir ->
        repo.saveItem(ItineraryItem(tripId = 0, date = fri, startTime = LocalTime.of(18, 0), durationMinutes = 120, title = "Drive up"),
            addedReminders = listOf(Reminder(itemId = 0, amount = 1, unit = ReminderUnit.HOURS)))
        repo.saveItem(ItineraryItem(tripId = 0, date = fri.plusDays(1), endDate = fri.plusDays(2), startTime = null, title = "Cabin",
            checklist = listOf(ChecklistEntry(text = "Firewood", done = true))))
        repo.saveItem(ItineraryItem(tripId = 0, date = fri.plusDays(2), startTime = LocalTime.of(15, 0), title = "Drive home"))
        val ids = repo.snapshot().items.map { it.id }
        repo.saveTemplateSet(" Weekend away ", ids)
        val saved = repo.snapshot().templates.single()
        assertEquals("Weekend away", saved.name)
        val set = TemplateSet.decode(saved.payload)
        assertEquals(listOf(0, 1, 2), set.events.map { it.first })

        // Added again three weeks on: the same days apart, a fresh checklist, the reminder kept.
        val start = fri.plusWeeks(3)
        assertEquals(3, repo.addTemplateSet(set, start))
        val added = repo.snapshot().items.filter { it.date >= start }.sortedBy { it.date }
        assertEquals(listOf("Drive up", "Cabin", "Drive home"), added.map { it.title })
        assertEquals(listOf(start, start.plusDays(1), start.plusDays(2)), added.map { it.date })
        assertEquals(start.plusDays(2), added[1].endDate)
        assertFalse(added[1].checklist.single().done)
        assertEquals(LocalTime.of(18, 0), added[0].startTime); assertEquals(120, added[0].durationMinutes)
        assertEquals(1, repo.snapshot().reminders.count { it.itemId == added[0].id })

        // A backup with a set is format 18 and restores it; a plain one stays 17 for older Planners.
        val file = File(dir, "set.zip"); backup.export(Uri.fromFile(file))
        ZipFile(file).use { zip -> assertEquals(18, JSONObject(zip.getInputStream(zip.getEntry("data.json")).bufferedReader().readText()).getInt("formatVersion")) }
        val before = repo.snapshot()
        repo.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList()))
        backup.restore(backup.stage(Uri.fromFile(file)))
        assertEquals(before.templates, repo.snapshot().templates)
        repo.deleteTemplate(repo.snapshot().templates.single())
        val plain = File(dir, "plain.zip"); backup.export(Uri.fromFile(plain))
        ZipFile(plain).use { zip -> assertEquals(17, JSONObject(zip.getInputStream(zip.getEntry("data.json")).bufferedReader().readText()).getInt("formatVersion")) }
    }

    @Test fun aSetNeverKeepsARepeatAndIsRefusedWhenTooLong() {
        val item = ItineraryItem(tripId = 0, date = fri, startTime = null, title = "Gym", repeatRule = "WEEKLY", seriesId = "s1")
        val set = TemplateSet.of(listOf(item to emptyList()))
        assertEquals(RepeatRule.NONE, set.events.single().second.repeat)
        assertNull(set.from(fri.plusDays(7)).single().first.seriesId)
        assertThrows(IllegalArgumentException::class.java) {
            TemplateSet.of(listOf(item to emptyList(), item.copy(date = fri.plusDays(400)) to emptyList())).encode()
        }
    }
}
