package com.example.itinerary

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
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
class AutoReorderTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var sandbox: File
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository
    private lateinit var settings: SettingsRepository
    private val models = ViewModelStore()
    private var activity: MainActivity? = null
    @Volatile private var opens = 0
    private val names = listOf("QA Soon Alpha", "QA Soon Beta", "QA Past Gamma")
    private val plans = names.mapIndexed { i, name ->
        val day = LocalDate.now().plusDays(listOf(0L, 1L, -3L)[i])
        Trip(id = 930001L + i, name = name, destination = "", startDate = day, endDate = day, sortOrder = i)
    }

    @Before fun setup() = runBlocking {
        val base = instrumentation.targetContext
        sandbox = File(base.cacheDir, "auto-reorder-test").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(sandbox, "files").apply { mkdirs() }
            override fun getCacheDir() = File(sandbox, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(sandbox, "no_backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("auto_reorder_test_$name", mode)
        }
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        settings = SettingsRepository(context)
        repo = Repository(db, AttachmentStore(context), ReminderScheduler(context))
        repo.replaceAll(DataSnapshot(plans, emptyList(), emptyList(), emptyList()))
    }

    @After fun teardown() {
        instrumentation.runOnMainSync { models.clear(); activity?.finish() }
        db.close()
        instrumentation.targetContext.deleteSharedPreferences("auto_reorder_test_settings")
        sandbox.deleteRecursively()
    }

    private fun show(enabled: Boolean = true) {
        settings.setUpcomingOnTop(enabled)
        val screen = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        activity = screen
        instrumentation.runOnMainSync {
            val backup = BackupManager(context, repo, AttachmentStore(context), settings)
            val vm = TripsViewModel(repo, settings, backup, NextcloudBackups(context, backup))
            models.put("auto-reorder", vm)
            screen.setContent { ItineraryTheme { TripsScreen(vm, { _, _ -> opens++ }, {}) } }
        }
        await { node(names[2]) != null }
    }

    @Test fun enabledAllowsSoonPlanDragThenRestoresAutomaticDisplay() {
        show()
        drag(0, 2)
        await { saved() == listOf(1, 2, 0) }
        await { warning() && displayed() == listOf(1, 2, 0) }
        screenshot("manual-order-warning")
        Thread.sleep(1000)
        assertEquals(listOf(1, 2, 0), displayed())
        await { !warning() && displayed() == listOf(0, 1, 2) }
        assertEquals("Auto ordering must not overwrite the saved manual order", listOf(1, 2, 0), saved())
        assertEquals(0, opens)
        screenshot("automatic-order-resumed")
    }

    @Test fun disabledKeepsManualOrderWithoutWarning() {
        show(false)
        drag(0, 2)
        await { saved() == listOf(1, 2, 0) && displayed() == listOf(1, 2, 0) }
        assertFalse(warning())
        Thread.sleep(5000)
        assertEquals(listOf(1, 2, 0), displayed())
        assertFalse(warning())
        assertEquals(0, opens)
    }

    @Test fun stationaryLongPressDoesNotOpenButTapDoes() {
        show()
        press(0, 800)
        Thread.sleep(400)
        assertEquals("Releasing a long press must not open a plan", 0, opens)
        assertEquals(listOf(0, 1, 2), saved())
        click(names[0]) // Accessibility activation must still work after a held touch.
        await { opens == 1 }
        press(0, 80)
        await { opens == 2 }
    }

    @Test fun filteredLongPressDoesNotOpenButTapDoes() {
        show()
        click("Past")
        await { node(names[0]) == null && node(names[2]) != null }
        Thread.sleep(600) // Let the remaining row finish moving to its filtered position.
        press(2, 800)
        Thread.sleep(400)
        assertEquals("Long presses must not open a plan when dragging is disabled", 0, opens)
        press(2, 80)
        await { opens == 1 }
    }

    @Test fun selectionLongPressDoesNotToggleButTapDoes() {
        show()
        openOptions(0)
        click("Delete plan")
        await { node("1 selected") != null }
        press(1, 800)
        Thread.sleep(400)
        assertNotNull("Long presses must not act as selection taps", node("1 selected"))
        assertEquals(0, opens)
        press(1, 80)
        screenshot("selection-after-tap")
        await { node("2 selected") != null }
        assertEquals(0, opens)
    }

    @Test fun anotherDragPausesAndRestartsCountdown() {
        show()
        drag(0, 2)
        await { saved() == listOf(1, 2, 0) && warning() }
        // Hold the second drag beyond the first countdown. Its order must not jump under the finger.
        drag(1, 2, hold = 5000, duringHold = { assertEquals(listOf(1, 2, 0), displayed()) })
        await { saved() == listOf(2, 1, 0) && displayed() == listOf(2, 1, 0) }
        Thread.sleep(1000)
        assertTrue(warning())
        assertEquals(listOf(2, 1, 0), displayed())
        await { !warning() && displayed() == listOf(0, 1, 2) }
    }

    @Test fun turningOptionOffDuringDelayKeepsManualOrder() {
        show()
        drag(0, 2)
        await { saved() == listOf(1, 2, 0) && warning() }
        instrumentation.runOnMainSync { settings.setUpcomingOnTop(false) }
        await { !warning() }
        Thread.sleep(5000)
        assertEquals(listOf(1, 2, 0), displayed())
    }

    @Test fun cancelledDragDoesNotSave() {
        show()
        drag(0, 2, cancel = true)
        await { !warning() && displayed() == listOf(0, 1, 2) }
        assertEquals(listOf(0, 1, 2), saved())
        assertEquals(0, opens)
    }

    @Test fun filterDuringDelayUpdatesImmediately() {
        show()
        drag(0, 2)
        await { saved() == listOf(1, 2, 0) && warning() }
        click("Past")
        await { node(names[0]) == null && node(names[1]) == null && node(names[2]) != null && !warning() }
        click("All")
        await { displayed() == listOf(0, 1, 2) }
        assertEquals(listOf(1, 2, 0), saved())
    }

    private fun openOptions(index: Int) {
        var row = node(names[index])!!
        while (row.parent != null && row.childCount < 2) row = row.parent
        fun option(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.contentDescription?.toString() == "Plan options") return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { option(it) }?.let { return it }
            return null
        }
        while (option(row) == null && row.parent != null) row = row.parent
        var button = option(row)!!
        while (!button.isClickable && button.parent != null) button = button.parent
        check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun selectionPausesAutomaticOrderAndCancelRestoresDragging() {
        show()
        drag(0, 2)
        await { saved() == listOf(1, 2, 0) && warning() }
        openOptions(0)
        click("Delete plan")
        await { node("1 selected") != null }
        Thread.sleep(5000)
        assertEquals(listOf(1, 2, 0), displayed())
        assertFalse(warning())
        drag(1, 2)
        assertEquals(listOf(1, 2, 0), saved())
        assertEquals(listOf(1, 2, 0), displayed())
        click("Cancel")
        await { node("Select all visible plans") == null }
        drag(1, 2)
        await { saved() == listOf(2, 1, 0) }
        await { !warning() && displayed() == listOf(0, 1, 2) }
    }

    @Test fun moveMenuAlsoAllowsUpcomingPlansAndWarns() {
        show()
        openOptions(0)
        click("Move to bottom")
        await { saved() == listOf(1, 2, 0) && displayed() == listOf(1, 2, 0) && warning() }
        await { !warning() && displayed() == listOf(0, 1, 2) }
    }

    private fun click(text: String) {
        await {
            var n = node(text)
            while (n != null && !n.isClickable && n.parent != null) n = n.parent
            n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }

    private fun saved() = runBlocking { repo.snapshot().trips.map { (it.id - 930001).toInt() } }
    private fun displayed(): List<Int> {
        val positions = names.indices.mapNotNull { index ->
            node(names[index])?.let { n -> index to Rect().also { n.getBoundsInScreen(it) }.top }
        }
        // Accessibility can briefly omit a moving node; poll again instead of crashing the test.
        return if (positions.size == names.size) positions.sortedBy { it.second }.map { it.first } else emptyList()
    }
    private fun warning() = find { it.text?.contains("Plans will rearrange automatically") == true } != null
    private fun bounds(index: Int) = Rect().also { node(names[index])!!.getBoundsInScreen(it) }
    private fun press(index: Int, duration: Long) {
        val target = bounds(index)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                target.exactCenterX(), target.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
            if (action == MotionEvent.ACTION_DOWN) Thread.sleep(duration)
        }
    }
    private fun drag(from: Int, to: Int, hold: Long = 800, cancel: Boolean = false, duringHold: () -> Unit = {}) {
        val start = bounds(from)
        val end = bounds(to)
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, start.exactCenterX(), y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, start.exactCenterY())
        Thread.sleep(hold)
        duringHold()
        for (i in 1..20) {
            send(MotionEvent.ACTION_MOVE, start.exactCenterY() + (end.exactCenterY() - start.exactCenterY()) * i / 20f)
            Thread.sleep(25)
        }
        send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, end.exactCenterY())
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        // Selection counts and row bounds can otherwise remain cached after an update.
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(n) && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it) }?.let { return it }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let { visit(it) }
    }
    private fun node(text: String) = find { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10000
        while (!condition()) {
            if (SystemClock.uptimeMillis() > end) throw AssertionError("Timed out waiting for UI/order")
            Thread.sleep(50)
        }
    }
    private fun screenshot(name: String) {
        val folder = File(instrumentation.targetContext.cacheDir, "qa-auto-reorder-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

