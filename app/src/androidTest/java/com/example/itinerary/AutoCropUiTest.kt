package com.example.itinerary

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.Attachment
import com.example.itinerary.data.AttachmentStore
import com.example.itinerary.ui.ManualDocumentScanner
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Real crop UI, with a deterministic paper image supplied at the camera-result boundary. */
@Suppress("DEPRECATION")
class AutoCropUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result+=n;for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        instrumentation.uiAutomation.freshRoot?.let(::visit);return result
    }
    private fun await(test: ()->Boolean) {
        val end=SystemClock.uptimeMillis()+15000
        while (SystemClock.uptimeMillis()<end) { if(test()) return;Thread.sleep(150) }
        fail("Timed out: "+nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    @Test fun detectedCornersCanBeAdjustedAndOnlyFinalCropIsExported() {
        val context=instrumentation.targetContext
        val dir=File(context.cacheDir,"auto-crop-ui-test").apply { mkdirs() }
        val store=AttachmentStore(object:ContextWrapper(context) { override fun getFilesDir()=dir })
        val output=AtomicReference<List<Attachment>?>(null)
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val monitor=object:Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent?): Instrumentation.ActivityResult? {
                if(intent?.action!=MediaStore.ACTION_IMAGE_CAPTURE) return null
                val uri=requireNotNull(intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT))
                val bitmap=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888)
                Canvas(bitmap).apply { drawColor(Color.DKGRAY);drawRect(120f,100f,700f,900f,Paint().apply { color=Color.WHITE }) }
                context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) };bitmap.recycle()
                return Instrumentation.ActivityResult(Activity.RESULT_OK,Intent())
            }
        }
        try {
            instrumentation.addMonitor(monitor)
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                ManualDocumentScanner(false,store,onDismiss={},onComplete={ output.set(it) })
            } } }
            await { nodes().any { it.text?.toString()?.contains("Edges detected")==true } } // "Page 1 of 1 · Edges detected"
            await { nodes().any { it.contentDescription?.toString()=="Document crop. Adjust the four page corners." } }
            val crop=nodes().first { it.contentDescription?.toString()=="Document crop. Adjust the four page corners." }
            val action=crop.actionList.first { it.label?.toString()=="Move top left corner right" }
            assertTrue(crop.performAction(action.id))
            Thread.sleep(250)
            val evidence=File(context.cacheDir,"qa-auto-crop-evidence").apply { mkdirs() }
            instrumentation.uiAutomation.takeScreenshot()!!.let { b -> File(evidence,"detected-and-adjusted.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
            fun click(label: String) {
                await { nodes().any { it.text?.toString() == label } }
                var node: AccessibilityNodeInfo?=nodes().first { it.text?.toString()==label }
                while(node!=null && !node.isClickable) node=node.parent
                assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
            }
            click("Preview scan")
            await { nodes().any { it.contentDescription?.toString() == "Cropped page preview" } }
            assertNull(output.get())
            click("Adjust corners")
            await { nodes().any { it.contentDescription?.toString() == "Document crop. Adjust the four page corners." } }
            click("Preview scan")
            await { nodes().any { it.contentDescription?.toString() == "Cropped page preview" } }
            click("Attach scan")
            await { output.get()!=null }
            val bitmap=BitmapFactory.decodeFile(store.fileFor(output.get()!!.single().fileName).path)
            assertTrue("Automatic crop removes background width",bitmap.width in 550..610)
            assertTrue("Automatic crop removes background height",bitmap.height in 770..830)
            assertTrue("Edited top edge is inside the white page",Color.red(bitmap.getPixel(bitmap.width/2,20))>240)
            bitmap.recycle()
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { activity.finish() }
            dir.deleteRecursively()
        }
    }
}
