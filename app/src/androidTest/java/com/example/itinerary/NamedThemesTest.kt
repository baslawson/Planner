package com.example.itinerary

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class NamedThemesTest {
    @Test fun persistenceAndBackupCompatibility() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "named-theme-test").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("named_theme_test_$name", mode)
        }
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            for (theme in listOf(AppTheme.HIGH_CONTRAST, AppTheme.COLOUR_BLIND)) {
                val settings = SettingsRepository(context)
                val originalHeading = settings.headingColor.value
                val originalFont = settings.appFont.value
                assertEquals(AppTheme.MATRIX, settings.appTheme.value)
                settings.setAppTheme(theme)
                settings.setThemeMode(ThemeMode.LIGHT)
                assertEquals(theme, SettingsRepository(context).appTheme.value)
                val store = AttachmentStore(context)
                val repo = Repository(db, store, ReminderScheduler(context))
                val backup = BackupManager(context, repo, store, settings)
                val file = File(directory, "theme.zip")
                backup.export(Uri.fromFile(file), trackStatus = false)
                val staged = backup.stage(Uri.fromFile(file))
                assertEquals(theme, staged.settings.appTheme)
                assertEquals(ThemeMode.LIGHT, staged.settings.themeMode)
                settings.setAppTheme(AppTheme.MATRIX)
                settings.applySnapshot(staged.settings)
                assertEquals(theme, settings.appTheme.value)
                backup.discard(staged)
                val json = ZipFile(file).use { zip -> JSONObject(zip.getInputStream(zip.getEntry("data.json")).bufferedReader().readText()) }
                for (value in listOf<String?>(null, "future-theme")) {
                    if (value == null) json.getJSONObject("settings").remove("appTheme")
                    else json.getJSONObject("settings").put("appTheme", value)
                    val legacy = File(directory, "legacy.zip")
                    ZipOutputStream(legacy.outputStream()).use { zip ->
                        zip.putNextEntry(ZipEntry("data.json")); zip.write(json.toString().toByteArray()); zip.closeEntry()
                    }
                    val old = backup.stage(Uri.fromFile(legacy))
                    assertEquals(AppTheme.MATRIX, old.settings.appTheme)
                    backup.discard(old)
                }
                settings.setAppTheme(AppTheme.MATRIX)
                assertEquals(originalHeading, settings.headingColor.value)
                assertEquals(originalFont, settings.appFont.value)
                assertEquals(ThemeMode.LIGHT, settings.themeMode.value)
            }
        } finally {
            db.close()
            base.deleteSharedPreferences("named_theme_test_settings")
            base.deleteSharedPreferences("named_theme_test_backup_status")
            directory.deleteRecursively()
        }
    }
}
