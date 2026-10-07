package com.example.itinerary.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Trip::class, ItineraryItem::class, Attachment::class, Reminder::class, EventTemplate::class, DeletedEntry::class, PlannerTask::class,
        CalendarSource::class, OutsideEvent::class, SentEvent::class, ReminderDelivery::class, SentTask::class, PlannerNote::class, SentNote::class],
    version = 36,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun deletedDao(): DeletedDao
    abstract fun templateDao(): TemplateDao
    abstract fun tripDao(): TripDao
    abstract fun itemDao(): ItemDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun reminderDao(): ReminderDao
    abstract fun outsideDao(): OutsideDao
    abstract fun sentDao(): SentDao
    abstract fun sentTaskDao(): SentTaskDao
    abstract fun noteDao(): NoteDao
    abstract fun sentNoteDao(): SentNoteDao
}

val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN payments TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("CREATE TABLE IF NOT EXISTS recently_deleted (id TEXT NOT NULL PRIMARY KEY, deletedAt INTEGER NOT NULL, label TEXT NOT NULL, payload TEXT NOT NULL)")
    }
}

// Adds attachments without touching existing trips or activities.
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `attachments` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`itemId` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`fileName` TEXT NOT NULL, " +
                "`mimeType` TEXT NOT NULL, " +
                "FOREIGN KEY(`itemId`) REFERENCES `items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachments_itemId` ON `attachments` (`itemId`)")
    }
}

// Adds reminders without touching existing trips, activities or attachments.
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reminders` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`itemId` INTEGER NOT NULL, " +
                "`amount` INTEGER NOT NULL, " +
                "`unit` TEXT NOT NULL, " +
                "FOREIGN KEY(`itemId`) REFERENCES `items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_itemId` ON `reminders` (`itemId`)")
    }
}

// Existing reminders keep their current behaviour (a single notification).
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `reminders` ADD COLUMN `ringUntilDismissed` INTEGER NOT NULL DEFAULT 0")
    }
}

// Plans can have a freely picked colour too. The new column starts empty, so every plan keeps its palette colour.
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `trips` ADD COLUMN `customColor` INTEGER")
    }
}

// Attachments can be web links. The new column starts empty, so every existing attachment stays a file.
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `attachments` ADD COLUMN `url` TEXT")
    }
}

// The event editor no longer offers the eighth palette colour (Slate). Events that used it keep exactly that colour, now as
// a custom colour, so nothing changes on screen.
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE `items` SET `customColor` = ${PlanColors.SLATE_ARGB}, `colorIndex` = 0 " +
                "WHERE `colorIndex` = 7 AND `customColor` IS NULL",
        )
        // Events that already had a colour of their own don't need the palette index any more.
        db.execSQL("UPDATE `items` SET `colorIndex` = 0 WHERE `colorIndex` = 7")
    }
}

// Events can have a freely picked colour. The new column starts empty, so every event keeps its palette colour.
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `items` ADD COLUMN `customColor` INTEGER")
    }
}

// Events get a colour too. Existing events on the same day (in the same plan) are given different ones in turn.
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `items` ADD COLUMN `colorIndex` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "UPDATE `items` SET `colorIndex` = (SELECT COUNT(*) FROM `items` AS other " +
                "WHERE other.`tripId` = `items`.`tripId` AND other.`date` = `items`.`date` " +
                "AND other.`id` < `items`.`id`) % ${PlanColors.COUNT}",
        )
    }
}

// Plans get a colour. Existing plans are given different ones, in the order they are listed.
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `trips` ADD COLUMN `colorIndex` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "UPDATE `trips` SET `colorIndex` = (SELECT COUNT(*) FROM `trips` AS other " +
                "WHERE other.`sortOrder` < `trips`.`sortOrder` " +
                "OR (other.`sortOrder` = `trips`.`sortOrder` AND other.`id` < `trips`.`id`)) % ${PlanColors.COUNT}",
        )
    }
}

// Categories stop being a fixed list and become plain text (same TEXT column, so only the values change).
// Sightseeing is no longer built in, so activities that had it keep the word as a category of their own.
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE `items` SET `category` = CASE `category` " +
                "WHEN 'FLIGHT' THEN 'Flight' WHEN 'STAY' THEN 'Stay' WHEN 'FOOD' THEN 'Food' " +
                "WHEN 'SIGHT' THEN 'Sightseeing' WHEN 'TRANSPORT' THEN 'Transport' ELSE 'Other' END",
        )
    }
}

// Plans become freely orderable. Existing plans start in the order they were already shown (by start date).
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `trips` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "UPDATE `trips` SET `sortOrder` = (SELECT COUNT(*) FROM `trips` AS other " +
                "WHERE other.`startDate` < `trips`.`startDate` " +
                "OR (other.`startDate` = `trips`.`startDate` AND other.`id` < `trips`.`id`))",
        )
    }
}

// Existing events remain standalone. Series occurrences are real rows, so search and alarms use the same dates.
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN seriesId TEXT")
        db.execSQL("ALTER TABLE items ADD COLUMN repeatRule TEXT NOT NULL DEFAULT 'NONE'")
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN durationMinutes INTEGER")
    }
}

// Existing events start with an empty checklist.
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `items` ADD COLUMN `checklist` TEXT NOT NULL DEFAULT '[]'")
    }
}

val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN paid INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE items ADD COLUMN draftToken TEXT")
        db.execSQL("ALTER TABLE reminders ADD COLUMN snoozedUntil INTEGER")
    }
}

val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN billAmountMinor INTEGER")
        db.execSQL("ALTER TABLE items ADD COLUMN billCurrency TEXT NOT NULL DEFAULT 'AUD'")
        db.execSQL("ALTER TABLE items ADD COLUMN skipped INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE attachments ADD COLUMN recognizedText TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE attachments ADD COLUMN textStatus TEXT NOT NULL DEFAULT 'NOT_INDEXED'")
        db.execSQL("CREATE TABLE IF NOT EXISTS templates (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, payload TEXT NOT NULL)")
    }
}

val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS tasks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, dueDate TEXT, priority TEXT NOT NULL, notes TEXT NOT NULL, done INTEGER NOT NULL)")
    }
}

val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN reminderAt INTEGER")
    }
}

val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN repeat TEXT NOT NULL DEFAULT 'NONE'")
        db.execSQL("ALTER TABLE tasks ADD COLUMN repeatDays INTEGER NOT NULL DEFAULT 7")
        db.execSQL("ALTER TABLE tasks ADD COLUMN repeatAnchorDay INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN nextTaskId TEXT")
        db.execSQL("ALTER TABLE tasks ADD COLUMN checklist TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE tasks ADD COLUMN attachments TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE items ADD COLUMN paymentLink TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE items ADD COLUMN paymentReference TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE items ADD COLUMN bpayBillerCode TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE items ADD COLUMN bpayReference TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN linkedTaskId TEXT")
    }
}

val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN bufferBeforeMinutes INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE items ADD COLUMN bufferAfterMinutes INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN prerequisiteIds TEXT NOT NULL DEFAULT '[]'")
    }
}

// Multi-day all-day events: the last day they cover (inclusive); null for every existing event.
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN endDate TEXT")
    }
}

// Calendar sync: calendars kept on another service and their downloaded events, in two new tables. Nothing existing
// changes. The statements are the ones Room generates for CalendarSource and OutsideEvent.
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `calendar_sources` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account` TEXT NOT NULL, `href` TEXT NOT NULL, `name` TEXT NOT NULL, `color` INTEGER, `enabled` INTEGER NOT NULL, `ctag` TEXT, `fetchedFor` TEXT, `lastSynced` INTEGER, `lastError` TEXT)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_calendar_sources_account_href` ON `calendar_sources` (`account`, `href`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `outside_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceId` INTEGER NOT NULL, `date` TEXT NOT NULL, `startTime` TEXT, `durationMinutes` INTEGER, `endDate` TEXT, `timedStart` TEXT, `timedEnd` TEXT, `title` TEXT NOT NULL, `location` TEXT NOT NULL, `notes` TEXT NOT NULL, FOREIGN KEY(`sourceId`) REFERENCES `calendar_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_outside_events_sourceId` ON `outside_events` (`sourceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_outside_events_date` ON `outside_events` (`date`)")
    }
}

// Calendar sync step 3: each calendar says where it lives (Nextcloud or this phone) and may carry a second line. Existing
// calendars are all Nextcloud ones.
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `calendar_sources` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'NEXTCLOUD'")
        db.execSQL("ALTER TABLE `calendar_sources` ADD COLUMN `detail` TEXT")
    }
}

// Calendar sync step 5 (Planner → Nextcloud): what was sent for each event, and which calendar may be written to and is
// the one Planner sends to. Nothing existing changes; existing calendars count as writable until the next sync says.
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `calendar_sources` ADD COLUMN `writable` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `calendar_sources` ADD COLUMN `sendHere` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS `sent_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `itemId` INTEGER NOT NULL, `account` TEXT NOT NULL, `calendar` TEXT NOT NULL, `uid` TEXT, `etag` TEXT, `fingerprint` TEXT NOT NULL, `problem` TEXT)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sent_events_itemId` ON `sent_events` (`itemId`)")
    }
}

// Calendar sync step 6 (two-way): which server file each synced event is, its last synced text, and Nextcloud's version
// while a conflict waits. Nothing existing changes.
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `sent_events` ADD COLUMN `href` TEXT")
        db.execSQL("ALTER TABLE `sent_events` ADD COLUMN `ics` TEXT")
        db.execSQL("ALTER TABLE `sent_events` ADD COLUMN `conflict` TEXT")
    }
}

// A task's snooze moves only this reminder, not the series' reminder time (null for every existing task). Event reminders
// record their last on-time delivery; the ones already due count as delivered, so a later westward time-zone change
// doesn't show them again.
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `snoozedUntil` INTEGER")
        db.execSQL("CREATE TABLE IF NOT EXISTS `reminder_deliveries` (`reminderId` INTEGER NOT NULL, `key` TEXT NOT NULL, PRIMARY KEY(`reminderId`), FOREIGN KEY(`reminderId`) REFERENCES `reminders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        val now = System.currentTimeMillis()
        db.query("SELECT r.id, r.amount, r.unit, i.date, i.startTime FROM reminders r JOIN items i ON r.itemId = i.id WHERE r.snoozedUntil IS NULL").use { rows ->
            while (rows.moveToNext()) {
                // A row that can't be read is simply not recorded; the upgrade must not fail over it.
                val seed = runCatching {
                    val date = java.time.LocalDate.parse(rows.getString(3))
                    val time = if (rows.isNull(4)) null else java.time.LocalTime.parse(rows.getString(4))
                    val offset = rows.getLong(1) * ReminderUnit.valueOf(rows.getString(2)).minutes
                    if (reminderTrigger(date, time, offset).toInstant().toEpochMilli() <= now) ReminderDeliveries.key(date, time, offset) else null
                }.getOrNull() ?: continue
                db.execSQL("INSERT OR REPLACE INTO reminder_deliveries (reminderId, `key`) VALUES (?, ?)", arrayOf<Any>(rows.getLong(0), seed))
            }
        }
    }
}

// Task sync: what each calendar can hold (every calendar so far could hold events; whether it holds tasks is learned at
// the next sync), the chosen task list, and the record of synced tasks.
val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `calendar_sources` ADD COLUMN `events` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `calendar_sources` ADD COLUMN `tasks` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `calendar_sources` ADD COLUMN `tasksHere` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `calendar_sources` ADD COLUMN `taskCtag` TEXT")
        db.execSQL("ALTER TABLE `calendar_sources` ADD COLUMN `taskError` TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS `sent_tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `taskId` TEXT NOT NULL, `account` TEXT NOT NULL, `list` TEXT NOT NULL, `uid` TEXT, `etag` TEXT, `fingerprint` TEXT NOT NULL, `problem` TEXT, `href` TEXT, `ics` TEXT, `conflict` TEXT)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sent_tasks_taskId` ON `sent_tasks` (`taskId`)")
    }
}

// Notes (Quillpad style). Nothing existing changes.
val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(NOTES_TABLE)
    }
}
// Exactly as Room creates it (checked against the generated AppDatabase_Impl), so a migrated database validates.
private const val NOTES_TABLE = "CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `content` TEXT NOT NULL, `notebook` TEXT NOT NULL, `color` INTEGER, `pinned` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `created` INTEGER NOT NULL, `modified` INTEGER NOT NULL, `tags` TEXT NOT NULL, `attachments` TEXT NOT NULL, `reminderAt` INTEGER, `snoozedUntil` INTEGER, PRIMARY KEY(`id`))"

// Nextcloud Notes sync: what was last synced per note. Nothing existing changes.
val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(SENT_NOTES_TABLE)
    }
}
// Notes get a place of their own for dragging (existing ones start in the order the page showed: newest change first)
// and an importance (Normal).
val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Safe to run again, as the earlier steps are: a column already there is kept, with what it holds.
        val columns = db.query("PRAGMA table_info(`notes`)").use { c ->
            generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("name")) else null }.toSet() }
        if ("position" !in columns) {
            db.execSQL("ALTER TABLE `notes` ADD COLUMN `position` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `notes` SET `position` = -`modified`")
        }
        if ("priority" !in columns) db.execSQL("ALTER TABLE `notes` ADD COLUMN `priority` TEXT NOT NULL DEFAULT 'NORMAL'")
    }
}
// As Room creates it (checked against the generated AppDatabase_Impl).
private const val SENT_NOTES_TABLE = "CREATE TABLE IF NOT EXISTS `sent_notes` (`noteId` TEXT NOT NULL, `account` TEXT NOT NULL, `remoteId` INTEGER NOT NULL, `etag` TEXT, `title` TEXT NOT NULL, `content` TEXT NOT NULL, `notebook` TEXT NOT NULL, `pinned` INTEGER NOT NULL, PRIMARY KEY(`noteId`))"

// Existing reminders remain notifications unless their owner explicitly enables ringing.
val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `tasks` ADD COLUMN `ringUntilDismissed` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `ringUntilDismissed` INTEGER NOT NULL DEFAULT 0")
    }
}

// R18-S1: a calendar's note about its last download (repeating events Planner can't show).
val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `calendar_sources` ADD COLUMN `note` TEXT")
    }
}

// Reminder sound (bugnotes 7 Oct): how long each reminder rings, beside "Until I stop it" (ringUntilDismissed, kept as it
// was). 0 is "Default", the Settings choice, so every existing reminder follows that. Safe to run again, as 32→33 is: a
// column already there is kept, with what it holds.
val MIGRATION_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("reminders", "tasks", "notes").forEach { table ->
            val columns = db.query("PRAGMA table_info(`$table`)").use { c ->
                generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("name")) else null }.toSet() }
            if ("ringSeconds" !in columns) db.execSQL("ALTER TABLE `$table` ADD COLUMN `ringSeconds` INTEGER NOT NULL DEFAULT 0")
        }
    }
}

// Every upgrade step, oldest first: the app opens its database with these, and the migration tests use the same list.
val ALL_MIGRATIONS = arrayOf(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36,
)
