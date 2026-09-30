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
class EighthFeaturesUiTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val app get()=context.applicationContext as ItineraryApp
    private fun data()=runBlocking { app.repository.snapshot() }
    private fun nodes():List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo) { result+=n;for(i in 0 until n.childCount)n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit);return result
    }
    private fun find(text:String)=nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString()==text || it.contentDescription?.toString()==text) }
    private fun screenshot(name:String) {
        val dir=File(context.cacheDir,"qa-eighth-evidence").apply { mkdirs() }
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
    // Save keeps the editor open on the saved event; Close then leaves (nothing is unsaved).
    private fun saveAndClose() { click("Save");click("Close") }
    // Close with unsaved changes asks "Save changes?"; Discard there abandons them.
    private fun closeAndDiscard() { click("Close");await { find("Save changes?")!=null };click("Discard") }
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
                EntryShortcuts.SCAN -> nodes().any { it.isVisibleToUser && (it.contentDescription?.toString()=="Shutter" || it.viewIdResourceName?.endsWith(":id/shutter_button")==true) }
                null -> find("AGENDA")!=null || find("Close")!=null
                else -> find("Close")!=null
            }
        }
    }


    @Test fun quickEntryReviewsBeforeSavingAndPersistsParsedFields()=runBlocking {
        EditorDraftStore(context).clear()
        app.settings.setAgendaRange(AgendaRange.ALL)
        val before=data().items.size
        open();click("Quick entry");setText("","QA dentist Tuesday 3 pm")
        await { find("QA dentist")!=null };screenshot("quick-entry-preview")
        click("More options");click("Open in full editor")
        assertEquals(before,data().items.size)
        saveAndClose()
        await { data().items.any { it.title=="QA dentist" } }
        val saved=data().items.single { it.title=="QA dentist" }
        assertEquals(LocalDate.now().with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.TUESDAY)),saved.date)
        assertEquals(java.time.LocalTime.of(15,0),saved.startTime)
        click("Quick entry");setText("","Invalid 2026-02-30")
        await { find("That date isn't valid.")!=null }
        assertEquals(before+1,data().items.size)
        click("Close")
    }

    @Test fun weekFilterUsesRealSavedEvents()=runBlocking {
        EditorDraftStore(context).clear()
        val date=LocalDate.now()
        app.repository.saveItem(ItineraryItem(tripId=0,date=date,startTime=null,title="QA weekly event"))
        app.repository.saveItem(ItineraryItem(tripId=0,date=date,startTime=null,title="QA weekly bill",category="Bills",billAmountMinor=12345))
        app.repository.saveItem(ItineraryItem(tripId=0,date=date,startTime=null,title="QA paid bill",category="Bills",billAmountMinor=6789,billCurrency="USD",paid=true))
        app.repository.saveItem(ItineraryItem(tripId=0,date=date.minusDays(2),startTime=null,title="QA earlier tasks",checklist=listOf(ChecklistEntry(text="QA unfinished task"))))
        app.repository.saveItem(ItineraryItem(tripId=0,date=date.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY)).plusDays(1),startTime=null,title="QA next week"))
        open()
        click("This week")
        await { app.settings.agendaRange.value == AgendaRange.THIS_WEEK && find("QA weekly bill")!=null }
        assertNull(find("QA earlier tasks"));assertNull(find("QA next week"));screenshot("week-filter")
        // A bill opens its editor; an event opens the calendar.
        click("QA weekly event");await { find("CALENDAR")!=null };screenshot("week-event-navigation")
    }

    @Test fun launcherActionsOpenEditorsAndProtectExistingDraft()=runBlocking {
        EditorDraftStore(context).clear()
        val shortcuts=context.getSystemService(android.content.pm.ShortcutManager::class.java).manifestShortcuts
        assertEquals(setOf("add_event","add_bill","scan_document"),shortcuts.map { it.id }.toSet())
        val count=data().items.size
        open(EntryShortcuts.ADD_BILL)
        reveal { find("3 days before")!=null };screenshot("shortcut-bill")
        click("Close");assertEquals(count,data().items.size)
        open(EntryShortcuts.SCAN);screenshot("shortcut-scan")
        ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        await { find("Take page")!=null };click("Cancel");click("Close")
        open(EntryShortcuts.ADD_EVENT);setText("","QA protected draft")
        await { EditorDraftStore(context).read()?.getJSONObject("item")?.getString("title")=="QA protected draft" }
        open(EntryShortcuts.ADD_BILL)
        await { find("QA protected draft")!=null };screenshot("shortcut-protected-draft")
        assertEquals("Other",EditorDraftStore(context).read()!!.getJSONObject("item").getString("category"))
        closeAndDiscard();assertEquals(count,data().items.size)
    }

    @Test fun ocrBillSuggestionsAreReviewedAppliedAndSaved()=runBlocking {
        EditorDraftStore(context).clear()
        // Render a real invoice and run the bundled OCR engine, then review the output in the editor.
        val bitmap=Bitmap.createBitmap(1600,1100,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint().apply { color=Color.BLACK;textSize=48f;isAntiAlias=true;typeface=Typeface.create("sans-serif",Typeface.NORMAL) }
        listOf("Acme Energy","Tax invoice","Amount due: AUD 123.45","Due date: 2026-10-30").forEachIndexed { i,line -> canvas.drawText(line,80f,120f+i*150f,paint) }
        val file=app.attachmentStore.newPhotoFile()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,100,it) };bitmap.recycle()
        val attachment=com.example.itinerary.scanner.DocumentText.index(Attachment(itemId=0,name="QA invoice",fileName=file.name,mimeType="image/jpeg"),app.attachmentStore)
        assertEquals("READY",attachment.textStatus)
        val parsed=BillSuggestions.parse(attachment.recognizedText)
        assertEquals(12345L,parsed.amount);assertEquals(LocalDate.of(2026,10,30),parsed.date)
        val initial=ItineraryItem(tripId=0,date=LocalDate.now(),startTime=null,title="")
        EditorDraftStore(context).write(org.json.JSONObject().put("token",java.util.UUID.randomUUID().toString())
            .put("initial",DraftCodec.item(initial)).put("item",DraftCodec.item(initial)).put("state",org.json.JSONObject().put("repeat","NONE").put("count","12").put("billAmountText","").put("durationText","").put("lastTimedTime","09:00"))
            .put("added",DraftCodec.attachments(listOf(attachment))))
        val before=data().items.size
        open();click("Suggest bill details")
        await { find("Suggested title")!=null || find("Acme Energy")!=null }
        screenshot("bill-suggestions")
        click("Use due date")
        click("Apply selected")
        assertEquals(before,data().items.size)
        saveAndClose()
        await { data().items.any { it.title=="Acme Energy" } }
        val bill=data().items.single { it.title=="Acme Energy" }
        assertEquals("Bills",bill.category);assertEquals(12345L,bill.billAmountMinor)
        assertEquals("AUD",bill.billCurrency);assertEquals(LocalDate.of(2026,10,30),bill.date)
        assertTrue(data().attachments.single { it.itemId==bill.id }.recognizedText.contains("123.45"))
        assertEquals(3,data().reminders.single { it.itemId==bill.id }.amount)
    }
    @Test fun agendaMenuOpensEachActionAndDismissalCreatesNothing()=runBlocking {
        EditorDraftStore(context).clear()
        open()
        val before=data()
        assertNull(find("Add bill"));assertNull(find("Quick entry"));assertNull(find("Bill totals"));assertNull(find("View week"))
        click("Add menu")
        for (label in listOf("Add task","Add event","Quick entry")) assertNotNull(label,find(label))
        assertNull(find("Add bill"));assertNull(find("Scan bill"))
        assertNull(find("Bill totals"))
        screenshot("add-menu")
        ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        await { find("Add menu")!=null };assertNull(find("Add bill"))
        click("Add event");await { find("New event")!=null };click("Close")
        click("Add bill");reveal { find("3 days before")!=null };click("Close")
        click("Quick entry");click("Close")
        assertEquals(before.items,data().items);assertEquals(before.reminders,data().reminders)
        assertNull(EditorDraftStore(context).read())
    }
    @Test fun calendarHasTheAgendaMenuAndAddsOnTheSelectedDay()=runBlocking {
        EditorDraftStore(context).clear()
        // A day other than today, so the check can tell "the selected day" from "today".
        val day=LocalDate.now().plusDays(3)
        app.settings.lastCalendarDate=day
        open()
        click("Switch to Calendar view");await { find("CALENDAR")!=null }
        assertNull(find("Add event"))
        click("Add menu")
        for (label in listOf("Add task","Add event","Quick entry")) assertNotNull(label,find(label))
        screenshot("calendar-add-menu")
        click("Add event");await { find("New event")!=null }
        setText("","QA calendar plus")
        saveAndClose()
        await { data().items.any { it.title=="QA calendar plus" } }
        assertEquals(day,data().items.single { it.title=="QA calendar plus" }.date)
    }

}
