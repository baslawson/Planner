package com.example.itinerary.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Trip::class, ItineraryItem::class, Attachment::class, Reminder::class, EventTemplate::class, DeletedEntry::class, PlannerTask::class],
    version = 24,
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

// Every upgrade step, oldest first: the app opens its database with these, and the migration tests use the same list.
val ALL_MIGRATIONS = arrayOf(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24,
)
