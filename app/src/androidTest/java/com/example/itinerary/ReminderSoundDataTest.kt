package com.example.itinerary

import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderAlarms
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Reminder sound (bugnotes 7 Oct): each reminder's length (ringSeconds, beside "Until I stop it") survives the database
 * upgrade, a backup and a draft; what was stored before any of them knew it reads as Default.
 */
class ReminderSoundDataTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val alarms = object : ReminderAlarms {
        override fun schedule(item: ItineraryItem, reminder: Reminder) {}
        override fun cancel(reminderId: Long) {}
    }
    private val later = System.currentTimeMillis() + 86_400_000L

    // Version 35 upgrades to 36 (the length column on reminders, tasks and notes) keeping everything, "Until I stop it"
    // included; every reminder from before reads as Default.
    @Test fun version35UpgradesWithEveryReminderAtDefault() = runBlocking {
        val name = "reminder-sound-upgrade.db"
        base.deleteDatabase(name)
        try {
            var db = Room.databaseBuilder(base, AppDatabase::class.java, name).build()
            Repository(db, AttachmentStore(base), alarms).saveItem(
                ItineraryItem(tripId = 0, date = LocalDate.now().plusDays(2), startTime = LocalTime.NOON, title = "Kept"),
                addedReminders = listOf(Reminder(itemId = 0, amount = 1, unit = ReminderUnit.HOURS, ringUntilDismissed = true),
                    Reminder(itemId = 0, amount = 2, unit = ReminderUnit.HOURS)))
            val task = PlannerTask(title = "Kept task", reminderAt = later, ringUntilDismissed = true)
            val note = PlannerNote(title = "Kept note", reminderAt = later)
            db.taskDao().insert(task); db.noteDao().insert(note); db.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(base.getDatabasePath(name).path, null, 0).use { raw ->
                listOf("reminders", "tasks", "notes").forEach { raw.execSQL("ALTER TABLE $it DROP COLUMN ringSeconds") }
                raw.execSQL("DELETE FROM room_master_table"); raw.version = 35
            }
            db = Room.databaseBuilder(base, AppDatabase::class.java, name).addMigrations(*ALL_MIGRATIONS).build()
            assertEquals(36, db.openHelper.readableDatabase.version)
            val reminders = db.reminderDao().all()
            assertEquals(listOf(ReminderSound.UNTIL_STOPPED, ReminderSound.DEFAULT), reminders.sortedBy { it.amount }.map { it.sound })
            assertEquals(task, db.taskDao().all().single()); assertEquals(note, db.noteDao().all().single())
            db.taskDao().update(task.copy(ringUntilDismissed = false, ringSeconds = 30))
            db.close()
            // Reopened as an ordinary version 36 database (Room checks the tables match what it expects).
            db = Room.databaseBuilder(base, AppDatabase::class.java, name).build()
            assertEquals(30, db.taskDao().all().single().ringSeconds); db.close()
        } finally { base.deleteDatabase(name) }
    }

    private fun <T> withBackup(name: String, block: suspend (Repository, BackupManager, File) -> T) = runBlocking {
        val root = File(base.cacheDir, "reminder-sound-$name").apply { deleteRecursively(); mkdirs() }
        val prefix = "reminder_sound_${name}_"
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            val store = AttachmentStore(context)
            val repo = Repository(db, store, alarms)
            block(repo, BackupManager(context, repo, store, SettingsRepository(context)), root)
        } finally {
            db.close(); root.deleteRecursively()
            listOf("settings", "backup_status").forEach { base.deleteSharedPreferences(prefix + it) }
        }
    }

    // A backup keeps each reminder's sound; one made before the length existed reads as Default, keeping "Until I stop it".
    @Test fun aBackupKeepsEachSoundAndAnOlderOneReadsAsDefault() = withBackup("backup") { repo, backup, root ->
        repo.saveItem(ItineraryItem(tripId = 0, date = LocalDate.now().plusDays(2), startTime = LocalTime.NOON, title = "Dentist"),
            addedReminders = listOf(Reminder(itemId = 0, amount = 1, unit = ReminderUnit.HOURS, ringSeconds = 30),
                Reminder(itemId = 0, amount = 2, unit = ReminderUnit.HOURS, ringUntilDismissed = true),
                Reminder(itemId = 0, amount = 3, unit = ReminderUnit.HOURS, ringSeconds = -1)))
        repo.saveTask(PlannerTask(title = "Call", reminderAt = later, ringSeconds = 60))
        repo.saveNote(PlannerNote(title = "Read", reminderAt = later, ringSeconds = -1), create = true)
        val zip = File(root, "backup.zip")
        backup.export(Uri.fromFile(zip))
        val staged = backup.stage(Uri.fromFile(zip))
        assertEquals(listOf(ReminderSound.SECONDS_30, ReminderSound.UNTIL_STOPPED, ReminderSound.NOTIFICATION),
            staged.data.reminders.sortedBy { it.amount }.map { it.sound })
        assertEquals(60, staged.data.tasks.single().ringSeconds); assertEquals(-1, staged.data.notes.single().ringSeconds)
        backup.discard(staged)

        // The same backup as a build before the length wrote it.
        val json = java.util.zip.ZipFile(zip).use { z -> JSONObject(z.getInputStream(z.getEntry("data.json")).readBytes().toString(Charsets.UTF_8)) }
        listOf("reminders", "tasks", "notes").forEach { key -> json.getJSONArray(key).let { a -> for (i in 0 until a.length()) a.getJSONObject(i).remove("ringSeconds") } }
        val older = File(root, "older.zip")
        java.util.zip.ZipOutputStream(older.outputStream()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("data.json")); out.write(json.toString().toByteArray(Charsets.UTF_8)); out.closeEntry()
        }
        val old = backup.stage(Uri.fromFile(older))
        assertEquals(listOf(ReminderSound.DEFAULT, ReminderSound.UNTIL_STOPPED, ReminderSound.DEFAULT),
            old.data.reminders.sortedBy { it.amount }.map { it.sound })
        assertEquals(0, old.data.tasks.single().ringSeconds); assertEquals(0, old.data.notes.single().ringSeconds)
        backup.discard(old)
    }

    // An editor's draft keeps each reminder's sound; a draft written before reads as Default.
    @Test fun draftsKeepTheSoundAndOlderOnesReadAsDefault() {
        val reminders = listOf(Reminder(id = 4, itemId = 1, amount = 1, unit = ReminderUnit.HOURS, ringSeconds = 60),
            Reminder(id = 5, itemId = 1, amount = 2, unit = ReminderUnit.HOURS, ringUntilDismissed = true, snoozedUntil = later))
        val encoded = DraftCodec.reminders(reminders)
        assertEquals(reminders, DraftCodec.reminders(JSONArray(encoded.toString())))
        for (i in 0 until encoded.length()) encoded.getJSONObject(i).remove("ringSeconds")
        assertEquals(reminders.map { it.copy(ringSeconds = 0) }, DraftCodec.reminders(encoded))
        // Tasks and notes (their drafts and backups use these codecs).
        val task = PlannerTask(id = "t", title = "Call", reminderAt = later, ringSeconds = 10)
        assertEquals(task, TaskCodec.decode(TaskCodec.encode(listOf(task))).single())
        val oldTask = TaskCodec.encode(listOf(task)).apply { getJSONObject(0).remove("ringSeconds") }
        assertEquals(0, TaskCodec.decode(oldTask).single().ringSeconds)
        val note = PlannerNote(id = "n", title = "Read", reminderAt = later, ringSeconds = 30)
        assertEquals(note, NoteCodec.decode(NoteCodec.encode(listOf(note))).single())
        val oldNote = NoteCodec.encode(listOf(note)).apply { getJSONObject(0).remove("ringSeconds") }
        assertEquals(0, NoteCodec.decode(oldNote).single().ringSeconds)
        assertEquals("a length no build offers reads as Default", 0,
            TaskCodec.decode(TaskCodec.encode(listOf(task)).apply { getJSONObject(0).put("ringSeconds", 45) }).single().ringSeconds)
    }

    // Settings' choice: the old switch's value becomes it on an upgrade (off → notification only), and a new install has 10 s.
    @Test fun theSettingComesFromTheOldSwitch() {
        val prefs = base.getSharedPreferences("reminder_sound_setting_test", android.content.Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit()
            assertEquals(ReminderSound.SECONDS_10, com.example.itinerary.reminders.ReminderSoundSetting.read(prefs))
            prefs.edit().putBoolean(com.example.itinerary.reminders.ReminderSoundSetting.OLD_PREF, false).commit()
            assertEquals(ReminderSound.NOTIFICATION, com.example.itinerary.reminders.ReminderSoundSetting.read(prefs))
            prefs.edit().putBoolean(com.example.itinerary.reminders.ReminderSoundSetting.OLD_PREF, true).commit()
            assertEquals(ReminderSound.SECONDS_10, com.example.itinerary.reminders.ReminderSoundSetting.read(prefs))
            prefs.edit().putString(com.example.itinerary.reminders.ReminderSoundSetting.PREF, "MINUTE").commit()
            assertEquals(ReminderSound.MINUTE, com.example.itinerary.reminders.ReminderSoundSetting.read(prefs))
        } finally { base.deleteSharedPreferences("reminder_sound_setting_test") }
    }
}
