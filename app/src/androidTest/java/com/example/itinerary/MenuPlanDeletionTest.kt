package com.example.itinerary

import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderReceiver
import com.example.itinerary.reminders.ReminderScheduler
import com.example.itinerary.ui.TripsScreen
import com.example.itinerary.ui.TripsViewModel
import com.example.itinerary.ui.theme.ItineraryTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

@Suppress("DEPRECATION")
class MenuPlanDeletionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var sandbox: File
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository
    private lateinit var settings: SettingsRepository
    private lateinit var backup: BackupManager
    private val models = ViewModelStore()
    private var activity: MainActivity? = null
    private var opens = 0
    // Keep unscheduled test tokens alive; unlike real alarms, no AlarmManager entry owns them.
    private val pendingTokens = mutableListOf<PendingIntent>()
    private val day = LocalDate.of(2000, 1, 2)
    private val plans = listOf("Delete Alpha", "Delete Beta", "Keep Gamma").mapIndexed { i, name ->
        Trip(id = 920001L + i, name = name, destination = "", startDate = day, endDate = day, sortOrder = i)
    }
    private val reminderIds = listOf(920021L, 920022L, 920023L)

    @Before fun setup() = runBlocking {
        val base = instrumentation.targetContext
        sandbox = File(base.cacheDir, "menu-selection-instrumentation").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(sandbox, "files").apply { mkdirs() }
            override fun getCacheDir() = File(sandbox, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(sandbox, "no_backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("menu_selection_test_$name", mode)
        }
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        settings = SettingsRepository(context)
        val store = AttachmentStore(context)
        repo = Repository(db, store, ReminderScheduler(context))
        backup = BackupManager(context, repo, store, settings)
        val events = plans.mapIndexed { i, plan -> ItineraryItem(id = 920011L + i, tripId = plan.id,
            date = day, startTime = null, title = plan.name, location = "", notes = "", category = "Other") }
        val reminders = events.mapIndexed { i, event -> Reminder(reminderIds[i], event.id, 5, ReminderUnit.MINUTES, false) }
        val attachments = listOf(
            Attachment(920031, events[0].id, "Alpha", "alpha.txt", "text/plain"),
            Attachment(920032, events[1].id, "Beta", "beta.txt", "text/plain"),
            Attachment(920033, events[0].id, "Shared", "shared.txt", "text/plain"),
            Attachment(920034, events[2].id, "Shared", "shared.txt", "text/plain"),
        )
        File(context.filesDir, "attachments").mkdirs()
        for (name in listOf("alpha.txt", "beta.txt", "shared.txt")) File(context.filesDir, "attachments/$name").writeText(name)
        repo.replaceAll(DataSnapshot(plans, events, reminders, attachments))
        // Real PendingIntents, but no scheduled alarms can fire. Both reminder and snooze must be cancelled.
        for (id in reminderIds) for (code in listOf(id, -id)) pending(code, create = true)?.let { pendingTokens += it }
    }

    @After fun teardown() {
        instrumentation.runOnMainSync { models.clear(); activity?.finish() }
        reminderIds.forEach { ReminderScheduler(context).cancel(it) }
        pendingTokens.clear()
        db.close()
        instrumentation.targetContext.deleteSharedPreferences("menu_selection_test_settings")
        sandbox.deleteRecursively()
    }

    private fun showList() {
        val screen = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        activity = screen
        instrumentation.runOnMainSync {
            val vm = TripsViewModel(repo, settings, backup, NextcloudBackups(context, backup))
            models.put("multi-delete", vm)
            screen.setContent { ItineraryTheme { TripsScreen(vm, { _, _ -> opens++ }, {}) } }
        }
        await { node("Delete Alpha") != null }
    }

    @Test fun testDeleteMenuStartsSelectionAndNeverDeletesWithoutConfirmation() = runBlocking {
        showList()
        val before = repo.snapshot()
        val heading = Rect().also { node("Manage plans")!!.getBoundsInScreen(it) }
        selectFromMenu("Delete Alpha")
        await { node("1 selected") != null }
        assertEquals(before, repo.snapshot())
        assertEquals(heading, Rect().also { node("Manage plans")!!.getBoundsInScreen(it) })
        for (name in listOf("Delete Alpha", "Delete Beta", "Keep Gamma")) {
            assertNotNull(node("Select $name"))
        }
        assertTrue(node("Select Delete Alpha")!!.isChecked)
        assertFalse(node("Select Delete Beta")!!.isChecked)
        val main = Rect().also { node("Select all visible plans")!!.getBoundsInScreen(it) }
        val last = Rect().also { node("Keep Gamma")!!.getBoundsInScreen(it) }
        assertTrue("Main checkbox must be bottom right", main.left > 700 && main.top > last.bottom)
        val row = Rect().also { node("Select Delete Alpha")!!.getBoundsInScreen(it) }
        val options = Rect().also { planOptions("Delete Alpha").getBoundsInScreen(it) }
        assertTrue("Checkbox must be beside options", row.right <= options.left && kotlin.math.abs(row.centerY() - options.centerY()) < 30)
        screenshot("selection")
        click("Select all visible plans")
        await { node("3 selected") != null }
        click("Select all visible plans")
        await { node("0 selected") != null }
        assertFalse(clickable(node("Delete (0)")!!).isEnabled)
        click("Select Delete Beta")
        click("Delete (1)")
        await { node("Delete 1 plan?") != null }
        assertNotNull(node("Delete Beta"))
        assertEquals(before, repo.snapshot())
        click("Keep plans")
        await { node("1 selected") != null }
        click("Cancel")
        await { node("Select all visible plans") == null }
        selectFromMenu("Delete Alpha")
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        await { node("Select all visible plans") == null }
        assertEquals(before, repo.snapshot())
        assertEquals(0, opens)
    }

    @Test fun testFilteredBulkAndSingleDeletionConfirmAndCleanUp() = runBlocking {
        showList()
        val field = find { it.className?.toString() == "android.widget.EditText" }!!
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Delete")
        }))
        await { node("Keep Gamma") == null }
        selectFromMenu("Delete Alpha")
        click("Select all visible plans")
        await { node("2 selected") != null }
        val before = repo.snapshot()
        click("Delete (2)")
        await { node("Delete 2 plans?") != null }
        assertNotNull(node("Delete Alpha"))
        assertNotNull(node("Delete Beta"))
        assertNull(node("Keep Gamma"))
        assertEquals(before, repo.snapshot())
        screenshot("confirmation")
        click("Delete 2 plans")
        await { runBlocking { repo.snapshot().trips.size == 1 } && node("Show all plans") != null }
        val after = repo.snapshot()
        assertEquals(listOf(plans[2]), after.trips)
        assertEquals(1, after.items.size)
        assertEquals(listOf(reminderIds[2]), after.reminders.map { it.id })
        assertEquals(listOf("shared.txt"), after.attachments.map { it.fileName })
        assertFalse(File(context.filesDir, "attachments/alpha.txt").exists())
        assertFalse(File(context.filesDir, "attachments/beta.txt").exists())
        assertEquals("shared.txt", File(context.filesDir, "attachments/shared.txt").readText())
        for (id in reminderIds.take(2)) { assertNull(pending(id)); assertNull(pending(-id)) }
        assertNotNull(pending(reminderIds[2]))
        click("Show all plans")
        await { node("Keep Gamma") != null }
        selectFromMenu("Keep Gamma")
        click("Delete (1)")
        await { node("Delete 1 plan?") != null }
        assertEquals(after, repo.snapshot())
        click("Delete 1 plan")
        await { node("No plans yet") != null }
        assertTrue(repo.snapshot().trips.isEmpty())
        assertTrue(repo.snapshot().items.isEmpty())
        assertTrue(repo.snapshot().reminders.isEmpty())
        assertTrue(repo.snapshot().attachments.isEmpty())
        assertFalse(File(context.filesDir, "attachments/shared.txt").exists())
        assertNull(pending(reminderIds[2]))
        assertNull(node("Select all visible plans"))
    }

    @Test fun testDatabaseFailureRollsBackAndLeavesConfirmationOpen() = runBlocking {
        showList()
        val before = repo.snapshot()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_second_plan BEFORE DELETE ON trips " +
            "WHEN OLD.id = 920002 BEGIN SELECT RAISE(ABORT, 'Deliberate test failure'); END")
        selectFromMenu("Delete Alpha")
        click("Select Delete Beta")
        click("Delete (2)")
        click("Delete 2 plans")
        await { find { it.text?.contains("Couldn't finish deleting") == true } != null }
        assertNotNull(node("Delete 2 plans?"))
        assertEquals(before, repo.snapshot())
        assertEquals(3, File(context.filesDir, "attachments").listFiles()!!.size)
        reminderIds.forEach { assertNotNull(pending(it)); assertNotNull(pending(-it)) }
        click("Keep plans")
        click("Cancel")
        assertEquals(before, repo.snapshot())
    }

    @Test fun testSelectAllThenFilterChangeExitsSelection() = runBlocking {
        showList()
        selectFromMenu("Delete Alpha")
        click("Select all visible plans")
        click("Past")
        await { node("Select all visible plans") == null }
        assertEquals(3, repo.snapshot().trips.size)
        selectFromMenu("Delete Alpha")
        click("Select all visible plans")
        click("Delete (3)")
        await { node("Delete 3 plans?") != null }
        for (name in listOf("Delete Alpha", "Delete Beta", "Keep Gamma")) assertNotNull(node(name))
        click("Delete 3 plans")
        await { node("No plans yet") != null }
        assertTrue(repo.snapshot().trips.isEmpty())
    }

    @Test fun testLongConfirmationListsEverySelectedPlan() = runBlocking {
        for (i in 1..15) repo.saveTrip(plans[0].copy(id = 940000L + i, name = "Additional plan $i", sortOrder = 2 + i))
        showList()
        val before = repo.snapshot()
        selectFromMenu("Delete Alpha")
        click("Select all visible plans")
        screenshot("long-selected")
        val debug = StringBuilder()
        fun dump(n: AccessibilityNodeInfo) {
            val b = Rect().also { n.getBoundsInScreen(it) }
            debug.append("${n.text} | ${n.contentDescription} | visible=${n.isVisibleToUser} clickable=${n.isClickable} checked=${n.isChecked} $b\n")
            for (i in 0 until n.childCount) n.getChild(i)?.let { dump(it) }
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let { dump(it) }
        File(instrumentation.targetContext.cacheDir, "qa-menu-selection-evidence/long-ui.txt").writeText(debug.toString())
        click("Delete (18)")
        await { node("Delete 18 plans?") != null }
        assertNotNull(node("Delete Alpha"))
        repeat(20) {
            if (node("Additional plan 15") == null) {
                find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Thread.sleep(120)
            }
        }
        assertNotNull("The final selected plan must be reachable in the warning", node("Additional plan 15"))
        assertEquals(before, repo.snapshot())
        click("Keep plans")
        click("Cancel")
    }

    private fun planOptions(name: String): AccessibilityNodeInfo {
        fun option(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.contentDescription?.toString() == "Plan options") return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { option(it) }?.let { return it }
            return null
        }
        var row = node(name)!!
        while (option(row) == null && row.parent != null) row = row.parent
        return option(row)!!
    }
    private fun selectFromMenu(name: String) {
        assertTrue(clickable(planOptions(name)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        click("Delete plan")
    }
    private fun pending(id: Long, create: Boolean = false): PendingIntent? = PendingIntent.getBroadcast(context,
        id.toInt(), Intent(context, ReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        // The emulator can retain old node text after a large selection update.
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(n) && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it) }?.let { return it }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let { visit(it) }
    }
    private fun node(text: String) = find { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && current.parent != null) current = current.parent
        return current
    }
    private fun click(text: String) { await { node(text)?.let { clickable(it).performAction(AccessibilityNodeInfo.ACTION_CLICK) } == true } }
    private fun longPress(text: String, duration: Long = 800) {
        val bounds = Rect()
        node(text)!!.getBoundsInScreen(bounds)
        val down = SystemClock.uptimeMillis()
        fun event(action: Int) = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
            bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        event(MotionEvent.ACTION_DOWN).let { instrumentation.uiAutomation.injectInputEvent(it, true); it.recycle() }
        Thread.sleep(duration)
        event(MotionEvent.ACTION_UP).let { instrumentation.uiAutomation.injectInputEvent(it, true); it.recycle() }
        instrumentation.waitForIdleSync()
    }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10000
        while (!condition()) { if (SystemClock.uptimeMillis() > end) throw AssertionError("Timed out waiting for UI"); Thread.sleep(50) }
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        Thread.sleep(300)
        val folder = File(instrumentation.targetContext.cacheDir, "qa-menu-selection-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
