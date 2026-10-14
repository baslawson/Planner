package com.example.itinerary

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.AddButtonPlace
import com.example.itinerary.data.AddMenuOrder
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.time.LocalDate

/**
 * The big + button can be dragged anywhere and stays there (also after a restart); a tap still opens its menu, which
 * opens downwards from the top half. A long press and a drag rearranges the menu, and Reset puts both back.
 */
@Suppress("DEPRECATION")
class AddButtonMoveUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as ItineraryApp

    // Reading every window changes how the tests after this one see the screen (a dialog under the keyboard reads as
    // hidden), so the flag is put back afterwards.
    private var setFlag = false
    @After fun putTheWindowsFlagBack() {
        if (!setFlag) return
        val info = ins.uiAutomation.serviceInfo
        info.flags = info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS.inv()
        ins.uiAutomation.serviceInfo = info
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            out += n
            repeat(n.childCount) { walk(n.getChild(it)) }
        }
        // The menu is a popup, a window of its own: read every window, from a fresh cache.
        val info = ins.uiAutomation.serviceInfo
        if (info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
            setFlag = true
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            ins.uiAutomation.serviceInfo = info
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) ins.uiAutomation.clearCache()
        ins.uiAutomation.windows.forEach { walk(it.root) }
        return out
    }
    private fun find(label: String) = nodes().firstOrNull {
        (!it.isEditable && it.text?.toString() == label) || it.contentDescription?.toString() == label
    }
    private fun await(what: String, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(50)
        }
        fail("Timed out: $what; visible: " + nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "))
    }
    private fun bounds(label: String) = Rect().also { find(label)!!.getBoundsInScreen(it) }
    private fun shell(command: String) = ins.uiAutomation.executeShellCommand(command).close()
    private fun open() {
        ins.targetContext.startActivity(Intent(ins.targetContext, MainActivity::class.java).apply {
            action = "com.example.itinerary.widget.OPEN_DATE"
            putExtra("widget_date", LocalDate.of(2036, 4, 12).toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        await("Calendar with the + button") { find("CALENDAR") != null && find("Add menu") != null }
        Thread.sleep(500)
    }
    private fun shot(name: String) {
        val dir = java.io.File(ins.targetContext.cacheDir, "qa-add-button").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> java.io.File(dir, "$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    }

    @Test fun theButtonMovesStaysAndItsMenuCanBeRearranged() {
        open()
        val start = bounds("Add menu")
        // Dragged up and left: it follows and is kept.
        shell("input swipe ${start.centerX()} ${start.centerY()} ${start.centerX() - 300} 700 800")
        await("moved from $start") { app.settings.addButtonPlace.value.y < 0.5f }
        val moved = bounds("Add menu")
        assertTrue("moved up ($start -> $moved)", moved.centerY() < start.centerY() - 500)
        assertTrue("moved left ($start -> $moved)", moved.centerX() < start.centerX() - 200)
        shot("moved")

        // A tap still opens the menu; from the top half it opens downwards, mirrored: ✕ on top, Quick entry next to it.
        shell("input tap ${moved.centerX()} ${moved.centerY()}")
        await("menu") { find("Quick entry") != null }
        fun top(label: String) = bounds(label).top
        assertTrue("✕ on top, Quick entry next to it", top("Close") < top("Quick entry"))
        assertTrue("mirrored order", top("Quick entry") < top("Add note") && top("Add note") < top("Add event") && top("Add event") < top("Add task"))
        shot("menu-down")

        // Long press on Add task (farthest from the button) and drag it to the top, next to the ✕: kept as the row nearest
        // the button, so the last of the order.
        val task = bounds("Add task"); val quick = bounds("Quick entry")
        shell("input draganddrop ${task.centerX()} ${task.centerY()} ${quick.centerX()} ${quick.top} 2000")
        await("rearranged") { app.settings.addMenuOrder.value.last() == AddMenuOrder.TASK }
        assertEquals(listOf("event", "note", "quick", "task"), app.settings.addMenuOrder.value)
        assertNotNull("the menu stays open after a rearrange (the drag didn't also run Add task)", find("Quick entry"))
        assertTrue(top("Add task") < top("Quick entry"))
        shot("rearranged")
        shell("input keyevent KEYCODE_BACK")
        await("menu closed") { find("Quick entry") == null }

        // After a restart it is where it was left.
        open()
        val again = bounds("Add menu")
        assertTrue("same place after a restart ($moved / $again)", kotlin.math.abs(again.centerX() - moved.centerX()) <= 3 && kotlin.math.abs(again.centerY() - moved.centerY()) <= 3)

        // Reset: back to bottom centre, first order.
        ins.runOnMainSync { app.settings.resetAddButton() }
        await("back at the bottom") { bounds("Add menu").centerY() > again.centerY() + 500 }
        assertEquals(AddMenuOrder.DEFAULT, app.settings.addMenuOrder.value)
        // At the bottom the menu opens upwards in the order as kept: Quick entry last, just above the ✕.
        val home = bounds("Add menu")
        shell("input tap ${home.centerX()} ${home.centerY()}")
        await("menu up") { find("Quick entry") != null }
        assertTrue("Quick entry just above the ✕", bounds("Add task").top < bounds("Quick entry").top && bounds("Quick entry").top < bounds("Close").top)
        shot("menu-up")
        shell("input keyevent KEYCODE_BACK")
    }

    // Near an edge the menu is pushed inside the screen, but its ✕ still sits exactly where the + was.
    @Test fun theCloseButtonStaysWhereThePlusWasAtTheEdges() {
        open()
        for ((place, name) in listOf(AddButtonPlace(0f, 1f) to "left-bottom", AddButtonPlace(1f, 0f) to "right-top")) {
            ins.runOnMainSync { app.settings.setAddButtonPlace(place) }
            await("button at $name") { find("Add menu") != null && Rect().also { r -> find("Add menu")!!.getBoundsInScreen(r) }.let { r -> if (place.x == 0f) r.left < 150 else r.right > 930 } }
            Thread.sleep(300)
            val plus = bounds("Add menu")
            shell("input tap ${plus.centerX()} ${plus.centerY()}")
            await("menu at $name") { find("Quick entry") != null }
            Thread.sleep(300)
            val close = bounds("Close")
            shot("edge-$name")
            assertTrue("✕ where the + was at $name ($plus / $close)",
                kotlin.math.abs(close.centerX() - plus.centerX()) <= 4 && kotlin.math.abs(close.centerY() - plus.centerY()) <= 4)
            shell("input keyevent KEYCODE_BACK")
            await("menu closed") { find("Quick entry") == null }
        }
        ins.runOnMainSync { app.settings.resetAddButton() }
    }
}
