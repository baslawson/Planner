package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
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

/** Real application UI; run only with an external backup/restore harness. */
@Suppress("DEPRECATION")
class ScanBillUiTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val app get()=context.applicationContext as ItineraryApp
    private fun data()=runBlocking { app.repository.snapshot() }
    private fun nodes():List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo) { result+=n;for(i in 0 until n.childCount)n.getChild(i)?.let(::visit) }
        ins.uiAutomation.rootInActiveWindow?.let(::visit);return result
    }
    private fun find(text:String)=nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString()==text || it.contentDescription?.toString()==text) }
    private fun screenshot(name:String) {
        val dir=File(context.cacheDir,"qa-scanbill-evidence").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
    }
    private fun await(timeout:Long=45000,condition:()->Boolean) {
        val end=SystemClock.uptimeMillis()+timeout
        while(SystemClock.uptimeMillis()<end) { if(condition())return;Thread.sleep(150) }
        screenshot("failure");fail("Timed out: "+nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun reveal(test:()->Boolean) {
        var tries=0;var forward=true
        await {
            if(test())true else {
                if(++tries>5) {
                    hideQuickTestKeyboard(ins) // a swipe across the keyboard would type words
                    val action=if(forward)AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    if(nodes().firstOrNull { it.isVisibleToUser && it.isScrollable && !it.isEditable && it.actionList.any { a -> a.id == action } }?.performAction(action)!=true)forward=!forward
                    Thread.sleep(350)
                };false
            }
        }
    }
    private fun click(text:String) {
        if (text == "Add bill") {
            if (find("Add task") == null && find("Bill payment") == null) click("Add menu")
            if (find("Bill payment") == null) click("Add task")
            click("Bill payment")
            return
        }
        if (text in setOf("Add event", "Add bill", "Quick entry") && find(text)==null && find("Add menu")!=null) click("Add menu")
        reveal {
            var node=find(text)
            while(node!=null && !node.isClickable)node=node.parent
            node?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
        };Thread.sleep(350)
    }
    private fun setText(old:String,value:String) {
        reveal { pickEditable(nodes(),old)!=null }
        val node=pickEditable(nodes(),old)!!
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
        }));Thread.sleep(350)
    }
    private fun open(action:String?=null) {
        app.settings.lastViewCalendar=false // open() expects the agenda
        ins.startActivitySync(Intent(context,MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await {
            when(action) {
                EntryShortcuts.SCAN -> nodes().any { it.contentDescription?.toString() == "Shutter" || it.viewIdResourceName?.endsWith(":id/shutter_button") == true }
                null -> find("AGENDA")!=null || find("Discard")!=null
                else -> find("Discard")!=null
            }
        }
    }


    private fun launch(): android.app.Activity {
        app.settings.lastViewCalendar = false
        return ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    private fun scanFixture(pdf: Boolean, pages: List<List<String>>) {
        EditorDraftStore(context).clear()
        val folder=File(context.filesDir,"draft-scan").apply { deleteRecursively();mkdirs() }
        val scanPages=pages.mapIndexed { i,lines ->
            val bitmap=Bitmap.createBitmap(1600,1100,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
            val paint=Paint().apply { color=Color.BLACK;textSize=48f;isAntiAlias=true;typeface=Typeface.create("sans-serif",Typeface.NORMAL) }
            lines.forEachIndexed { line,text -> canvas.drawText(text,80f,120f+line*150f,paint) }
            val file=File(folder,"page-$i.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,100,it) };bitmap.recycle()
            com.example.itinerary.scanner.ScanImages.Page(file, com.example.itinerary.scanner.fullPageCorners, 0, false)
        }
        com.example.itinerary.scanner.ScanSession(folder).save(scanPages,0,null,false)
        val initial=ItineraryItem(tripId=0,date=LocalDate.now(),startTime=null,title="",category="Bills")
        EditorDraftStore(context).write(org.json.JSONObject().put("token",java.util.UUID.randomUUID().toString())
            .put("initial",DraftCodec.item(initial)).put("item",DraftCodec.item(initial))
            .put("state",org.json.JSONObject().put("repeat","NONE").put("count","12").put("billAmountText","")
                .put("durationText","").put("lastTimedTime","09:00").put("scanningPdf",pdf)))
    }

    @Test fun taskMenuCameraCaptureCropReviewAndSaveBillPdf() = runBlocking {
        EditorDraftStore(context).clear()
        val before = data()
        val info = ins.uiAutomation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        ins.uiAutomation.serviceInfo = info
        launch(); await { find("Add menu") != null }
        click("Add menu"); click("Add task"); click("Scan bill")
        fun shutter() = nodes().firstOrNull { it.isVisibleToUser &&
            (it.contentDescription?.toString() == "Shutter" || it.viewIdResourceName?.endsWith(":id/shutter_button") == true) }
        await { shutter() != null }
        Thread.sleep(1500)
        assertNotNull("Camera must wait for manual capture", shutter())
        screenshot("bill-camera-before-shutter")
        assertTrue(shutter()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await {
            if (find("Document crop. Adjust the four page corners.") != null) true
            else {
                nodes().firstOrNull { it.isVisibleToUser && it.isEnabled &&
                    (it.contentDescription?.toString() == "Done" || it.viewIdResourceName?.endsWith(":id/done_button") == true) }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                false
            }
        }
        val crop = requireNotNull(find("Document crop. Adjust the four page corners."))
        assertTrue(crop.performAction(crop.actionList.first { it.label?.toString() == "Move top left corner right" }.id))
        click("Page tools"); click("Rotate"); screenshot("bill-camera-crop")
        click("Preview scan"); screenshot("bill-camera-preview"); click("Attach scan")
        click("Read details")
        await { find("Review bill details") != null }
        screenshot("bill-camera-details-review")
        assertEquals(before, data())
        // The emulator camera sees a virtual room, not an invoice: enter the bill title manually.
        click("Cancel")
        setText("", "QA camera bill retest")
        await { nodes().any { it.isEditable && it.text?.toString() == "QA camera bill retest" } }
        click("Save")
        await { data().items.any { it.title == "QA camera bill retest" } }
        val saved = data().items.single { it.title == "QA camera bill retest" }
        assertEquals("Bills", saved.category)
        val attachment = data().attachments.single { it.itemId == saved.id }
        assertEquals("application/pdf", attachment.mimeType)
        android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(
            app.attachmentStore.fileFor(attachment.fileName), android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            assertEquals(1, renderer.pageCount)
            renderer.openPage(0).use { page ->
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val dir = File(context.cacheDir, "qa-scanbill-evidence").apply { mkdirs() }
                File(dir, "saved-camera-pdf.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        assertEquals(before.items.size + 1, data().items.size)
    }

    @Test fun scanBillOpensCameraDirectlyAndCancelSavesNothing()=runBlocking {
        EditorDraftStore(context).clear()
        val before=data()
        launch();await { find("Add menu")!=null };click("Add menu");click("Add task")
        await { find("Scan bill")!=null };screenshot("scan-bill-menu")
        click("Scan bill")
        await { nodes().any { it.isVisibleToUser && (it.contentDescription?.toString()=="Shutter" || it.viewIdResourceName?.endsWith(":id/shutter_button")==true) } }
        assertNull(find("How would you like to save the scan?"));screenshot("direct-camera")
        val draft=EditorDraftStore(context).read()!!
        assertEquals("Bills",draft.getJSONObject("item").getString("category"))
        assertTrue(draft.getJSONObject("state").getBoolean("scanningPdf"))
        assertEquals(before,data())
        ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        await { find("Take page")!=null };click("Cancel");click("Discard")
        assertEquals(before,data());assertNull(EditorDraftStore(context).read())
    }

    @Test fun pdfScanPromptsRecoversAndSavesOnlyApprovedDetails()=runBlocking {
        val before=data().items.size
        scanFixture(true,listOf(listOf("Acme Energy","Tax invoice","Amount due: AUD 123.45","Due date: 2026-10-30")))
        launch();click("Preview scan");click("Attach scan")
        await { find("Read details from this scan?")!=null }
        assertNull(find("Review bill details"));screenshot("read-details-prompt")
        launch();await { find("Read details from this scan?")!=null }
        click("Read details")
        await { find("Review bill details")!=null };screenshot("requested-review")
        assertEquals(before,data().items.size)
        await { EditorDraftStore(context).read()?.getJSONObject("state")?.optJSONArray("billReviewFiles")?.length()==1 }
        val files=DraftCodec.attachments(EditorDraftStore(context).read()!!.optJSONArray("added"))
        assertEquals(1,files.size);assertEquals("application/pdf",files.single().mimeType)
        assertTrue(files.single().recognizedText.contains("123.45"))
        // A recreated activity recovers the pending review without duplicating the attachment.
        launch();await { find("Review bill details")!=null }
        assertEquals(files,DraftCodec.attachments(EditorDraftStore(context).read()!!.optJSONArray("added")))
        click("Use due date");click("Apply selected")
        assertEquals(before,data().items.size)
        click("Save");await { data().items.any { it.title=="Acme Energy" } }
        val saved=data().items.single { it.title=="Acme Energy" }
        assertEquals("Bills",saved.category);assertEquals(12345L,saved.billAmountMinor)
        assertEquals("AUD",saved.billCurrency);assertEquals(LocalDate.of(2026,10,30),saved.date)
        assertEquals(1,data().attachments.count { it.itemId==saved.id })
        assertTrue(app.attachmentStore.fileFor(data().attachments.single { it.itemId==saved.id }.fileName).isFile)
    }

    @Test fun imagePagesCombineForReviewAndCancelDoesNotApplyOrSave()=runBlocking {
        val before=data()
        scanFixture(false,listOf(listOf("Acme Water","Tax invoice"),listOf("Amount due: AUD 76.54","Due date: 2026-11-20")))
        launch();click("Preview scan");click("Attach scan")
        click("Read details")
        await { find("Review bill details")!=null }
        assertTrue(nodes().any { it.text?.toString()=="76.54" })
        assertTrue(nodes().any { it.text?.toString()=="2026-11-20" })
        screenshot("image-pages-review");click("Cancel")
        assertEquals(before,data())
        await { EditorDraftStore(context).read()?.getJSONObject("state")?.optJSONArray("billReviewFiles")?.length()==0 }
        val draft=EditorDraftStore(context).read()!!
        assertEquals("",draft.getJSONObject("item").getString("title"))
        assertEquals(2,DraftCodec.attachments(draft.optJSONArray("added")).size)
        launch();await { find("Discard")!=null };assertNull(find("Review bill details"));click("Discard")
        assertEquals(before,data())
    }

    @Test fun unreadableScanStillOffersReviewWithoutInventedValues()=runBlocking {
        val before=data()
        scanFixture(true,listOf(emptyList()))
        launch();click("Preview scan");click("Attach scan")
        click("Read details")
        await { find("Review bill details")!=null }
        assertNotNull(find("No readable text was found. You can enter the details here, or cancel and scan again."))
        screenshot("empty-scan-review");click("Cancel");click("Discard")
        assertEquals(before,data())
    }
    @Test fun skippingReviewKeepsScanAndAllowsManualReviewLater()=runBlocking {
        val before=data()
        scanFixture(true,listOf(listOf("Acme Water","Amount due: AUD 76.54")))
        launch();click("Preview scan");click("Attach scan")
        await { find("Read details from this scan?")!=null }
        assertNull(find("Review bill details"));click("Not now")
        await { EditorDraftStore(context).read()?.getJSONObject("state")?.optJSONArray("billReviewFiles")?.length()==0 }
        val draft=EditorDraftStore(context).read()!!
        val files=DraftCodec.attachments(draft.optJSONArray("added"))
        assertEquals(1,files.size)
        assertTrue(app.attachmentStore.fileFor(files.single().fileName).isFile)
        assertEquals("",draft.getJSONObject("item").getString("title"))
        assertEquals(before,data())
        launch();await { find("Discard")!=null }
        assertNull(find("Read details from this scan?"));assertNull(find("Review bill details"))
        assertEquals(files,DraftCodec.attachments(EditorDraftStore(context).read()!!.optJSONArray("added")))
        click("Suggest bill details");await { find("Review bill details")!=null }
        assertNull(find("Read details from this scan?"))
        click("Cancel");click("Discard");assertEquals(before,data())
    }

}
