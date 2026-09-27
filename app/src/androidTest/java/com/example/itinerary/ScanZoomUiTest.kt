package com.example.itinerary

import android.content.Intent
import androidx.activity.compose.setContent
import com.example.itinerary.ui.ManualDocumentScanner
import com.example.itinerary.ui.theme.ItineraryTheme
import com.example.itinerary.scanner.*
import android.view.MotionEvent
import android.graphics.Rect
import androidx.compose.runtime.CompositionLocalProvider
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Normal application flow. Run only after backing up user data; the external harness restores it. */
@Suppress("DEPRECATION")
class ScanZoomUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val repo get() = (instrumentation.targetContext.applicationContext as ItineraryApp).repository
    private fun snapshot() = runBlocking { repo.snapshot() }

    @Test fun zoomPanFitAndZoomedCornerDragging() = runBlocking {
        val context = instrumentation.targetContext
        val app = context.applicationContext as ItineraryApp
        EditorDraftStore(context).clear()
        val directory = File(context.filesDir, "draft-scan").apply { mkdirs() }
        val imageFile = File(directory, "qa-crop-grid.jpg")
        val bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.DKGRAY)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE }
        canvas.drawRect(90f, 120f, 810f, 1080f, paint)
        paint.color = android.graphics.Color.BLUE; paint.strokeWidth = 2f
        for (x in 90..810 step 30) canvas.drawLine(x.toFloat(),120f,x.toFloat(),1080f,paint)
        for (y in 120..1080 step 30) canvas.drawLine(90f,y.toFloat(),810f,y.toFloat(),paint)
        paint.color = android.graphics.Color.BLACK; paint.textSize = 48f
        canvas.drawText("CROP TEST", 200f, 550f, paint)
        imageFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) }; bitmap.recycle()
        val session = ScanSession(directory)
        val original = listOf(ScanPoint(.1f,.1f),ScanPoint(.9f,.1f),ScanPoint(.9f,.9f),ScanPoint(.1f,.9f))
        session.save(listOf(ScanImages.Page(imageFile, original)),0,null,false)
        val output = java.util.concurrent.atomic.AtomicReference<List<Attachment>>()
        val activity = instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync { activity.setContent {
                ItineraryTheme(darkTheme = true) { ManualDocumentScanner(true,app.attachmentStore,{}, { output.set(it) }, durable = true) }
            } }
            fun crop() = nodes().firstOrNull { it.contentDescription?.toString() == "Document crop. Adjust the four page corners." }
            await { crop() != null }
            val bounds = Rect().also(crop()!!::getBoundsInScreen)
            val display = instrumentation.uiAutomation.takeScreenshot()!!
            try { assertTrue("Crop workspace must use most of the screen: $bounds",bounds.height() > display.height * .65f) }
            finally { display.recycle() }
            screenshot("expanded-workspace")
            fun dragCorner(index: Int, name: String, zoom: Float = 1f, edgeAligned: Boolean = false) {
                val before = session.pages(session.read()).single()
                val rect = Rect().also(crop()!!::getBoundsInScreen)
                val density = context.resources.displayMetrics.density
                val width = if (before.turns % 2 == 0) 900f else 1200f
                val height = if (before.turns % 2 == 0) 1200f else 900f
                val scale = minOf((rect.width()-48*density)/width,(rect.height()-48*density)/height) * zoom
                val point = before.corners[index]
                val x = rect.left+(if(edgeAligned)24*density else (rect.width()-width*scale)/2)+point.x*width*scale
                val y = rect.top+(if(edgeAligned)24*density else (rect.height()-height*scale)/2)+point.y*height*scale
                val dx = if(point.x < .5f) 1f else -1f
                val dy = if(point.y < .5f) 1f else -1f
                val down = SystemClock.uptimeMillis()
                fun event(action: Int, amount: Float) {
                    val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x+dx*amount,y+dy*amount,0)
                    e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(e,true)) } finally { e.recycle() }
                    Thread.sleep(100)
                }
                event(MotionEvent.ACTION_DOWN,0f)
                try {
                    event(MotionEvent.ACTION_MOVE,20f)
                    event(MotionEvent.ACTION_MOVE,42f)
                    await { crop()?.stateDescription?.toString()?.startsWith("Magnifying") == true }
                    screenshot("magnifier-$name")
                } finally { event(MotionEvent.ACTION_UP,42f) }
                await { crop()?.stateDescription?.toString()?.startsWith("No corner selected") == true }
                val after = session.pages(session.read()).single()
                assertTrue(validScanCorners(after.corners))
                assertNotEquals(before.corners[index],after.corners[index])
                before.corners.indices.filter { it != index }.forEach { assertEquals(before.corners[it],after.corners[it]) }
            }
            (0..3).forEach { dragCorner(it,"corner-$it") }
            fun zoomPercent() = Regex("Zoom (\\d+)%").find(crop()?.stateDescription?.toString().orEmpty())?.groupValues?.get(1)?.toInt() ?: 100
            fun pinch() {
                val r=Rect().also(crop()!!::getBoundsInScreen)
                val x=r.exactCenterX();val y=r.exactCenterY()
                val down=SystemClock.uptimeMillis()
                fun send(action:Int, distance:Float, count:Int) {
                    val properties=Array(count) { i -> MotionEvent.PointerProperties().apply { id=i;toolType=MotionEvent.TOOL_TYPE_FINGER } }
                    val coords=Array(count) { i -> MotionEvent.PointerCoords().apply { this.x=x+if(i==0)-distance else distance;this.y=y;pressure=1f;size=1f } }
                    val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,properties,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event,true)) } finally { event.recycle() }
                    Thread.sleep(100)
                }
                send(MotionEvent.ACTION_DOWN,100f,1)
                send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),100f,2)
                for(step in 1..5)send(MotionEvent.ACTION_MOVE,100f+step*10,2)
                send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),150f,2)
                send(MotionEvent.ACTION_UP,150f,1)
            }
            val beforeZoom=session.pages(session.read()).single().corners
            pinch()
            await { zoomPercent() in 149..151 }
            assertEquals(beforeZoom,session.pages(session.read()).single().corners)
            screenshot("pinch-zoom")
            val r=Rect().also(crop()!!::getBoundsInScreen)
            val down=SystemClock.uptimeMillis()
            fun pan(action:Int,delta:Float) {
                val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,r.exactCenterX()+delta,r.exactCenterY()+delta,0)
                e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(e,true)) } finally { e.recycle() }
                Thread.sleep(120)
            }
            pan(MotionEvent.ACTION_DOWN,0f)
            for(step in 1..6)pan(MotionEvent.ACTION_MOVE,step*50f)
            pan(MotionEvent.ACTION_UP,300f)
            assertEquals(beforeZoom,session.pages(session.read()).single().corners)
            screenshot("panned-image")
            dragCorner(0,"zoomed",zoomPercent()/100f,true)
            val adjusted=session.pages(session.read()).single().corners
            click("Page tools");click("Fit image")
            await { zoomPercent() == 100 }
            assertEquals(adjusted,session.pages(session.read()).single().corners)
            screenshot("fit-image")
            pinch()
            click("Page tools");click("Rotate")
            await { session.pages(session.read()).single().turns == 1 && crop() != null }
            Thread.sleep(700)
            assertEquals(100,zoomPercent())
            dragCorner(0,"rotated")
            click("Preview scan")
            await { find("Cropped page preview") != null }
            screenshot("cropped-preview")
            click("Adjust corners")
            await { crop() != null }
            click("Page tools");click("Reset crop")
            await { session.pages(session.read()).single().corners == fullPageCorners }
            dragCorner(0,"image-boundary")
            click("Preview scan");click("Attach scan")
            await { output.get() != null }
            val pdf = output.get().single()
            assertEquals("application/pdf",pdf.mimeType)
            android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(app.attachmentStore.fileFor(pdf.fileName),android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use {
                assertEquals(1,it.pageCount)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() }; directory.deleteRecursively() }
    }

    private fun capturePage() {
        // AOSP camera: prove it waits for a shutter tap instead of capturing automatically.
        fun shutter() = nodes().firstOrNull { it.isVisibleToUser &&
            (it.viewIdResourceName?.endsWith(":id/shutter_button") == true || it.contentDescription?.toString() == "Shutter") }
        await { shutter() != null }
        Thread.sleep(1500)
        assertNotNull("Camera must still be waiting for a manual shutter tap",shutter())
        screenshot("manual-camera")
        assertTrue(shutter()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await {
            if (nodes().any { it.contentDescription?.toString() == "Document crop. Adjust the four page corners." }) true
            else {
                // The camera exposes Done before JPEG processing finishes; a successful accessibility
                // action alone does not mean it accepted the photo. Wait for the actual return.
                nodes().firstOrNull { it.isVisibleToUser && it.isEnabled &&
                    (it.viewIdResourceName?.endsWith(":id/done_button") == true || it.contentDescription?.toString() == "Done") }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(350)
                false
            }
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser &&
        (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(150)
        }
        screenshot("failure")
        throw AssertionError("Timed out. Visible text: " + nodes().mapNotNull { it.text?.toString() }.joinToString(" | "))
    }
    private fun click(text: String) {
        var attempts = 0
        var forward = true
        await {
            var target = find(text)
            while (target != null && !target.isClickable) target = target.parent
            if (target != null) target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            else {
                // Give navigation/recomposition time before moving the new screen. Search both
                // directions so a transient missing node cannot strand the test below the target.
                if (++attempts > 5) {
                    val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    val scrolled = nodes().lastOrNull { it.isScrollable && !it.isEditable }?.performAction(action) == true
                    if (!scrolled) forward = !forward
                    Thread.sleep(600)
                    screenshot("scroll-$attempts")
                }
                false
            }
        }
        Thread.sleep(350)
    }
    private fun setText(node: AccessibilityNodeInfo, value: String) {
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        Thread.sleep(250)
    }
    private fun screenshot(name: String) {
        val folder = File(instrumentation.targetContext.cacheDir, "qa-scan-zoom").apply { mkdirs() }
        File(folder, "$name.txt").writeText(nodes().joinToString("\n") { "${android.graphics.Rect().also(it::getBoundsInScreen)} ${it.className} text=${it.text} desc=${it.contentDescription} id=${it.viewIdResourceName} editable=${it.isEditable} focus=${it.isFocused} actions=${it.actionList}" })
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
