package com.example.itinerary

import android.content.Intent
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

/**
 * Save keeps the event and task editors open on what was saved (Save greyed until the next change); Close leaves at
 * once with nothing unsaved and otherwise asks "Save changes?" (Save / Discard / Keep editing). Back is Close.
 * Run only with an external backup/restore harness for the shared emulator.
 */
@Suppress("DEPRECATION")
class EditorSaveCloseUiTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val app get()=context.applicationContext as ItineraryApp
    private fun data()=runBlocking { app.repository.snapshot() }
    private fun nodes():List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo) { result+=n;for(i in 0 until n.childCount)n.getChild(i)?.let(::visit) }
        ins.uiAutomation.freshRoot?.let(::visit);return result
    }
    private fun find(text:String)=nodes().firstOrNull { it.isVisibleToUser && !it.isEditable && (it.text?.toString()==text || it.contentDescription?.toString()==text) }
    private fun screenshot(name:String) {
        val dir=File(context.cacheDir,"qa-editor-save-close").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
    }
    private fun await(timeout:Long=45000,condition:()->Boolean) {
        val end=SystemClock.uptimeMillis()+timeout
        while(SystemClock.uptimeMillis()<end) { if(condition())return;Thread.sleep(150) }
        screenshot("failure");fail("Timed out: "+nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    // The button holding [text]: Compose puts the label in a child of the clickable node.
    private fun button(text:String):AccessibilityNodeInfo? {
        var node=find(text)
        while(node!=null && !node.isClickable)node=node.parent
        return node
    }
    private fun enabled(text:String)=button(text)?.isEnabled==true
    private fun click(text:String) {
        await { button(text)?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true };Thread.sleep(350)
    }
    private fun setText(old:String,value:String) {
        await { pickEditable(nodes(),old)!=null }
        assertTrue(pickEditable(nodes(),old)!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
        }));Thread.sleep(350)
    }
    private fun back() {
        ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(350)
    }
    private fun open() {
        app.settings.lastViewCalendar=false
        app.settings.setAgendaRange(AgendaRange.ALL)
        ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA")!=null }
    }
    private fun seed(title:String)=runBlocking {
        app.repository.saveItemId(ItineraryItem(tripId=0,date=LocalDate.now(),startTime=LocalTime.of(10,0),title=title))
    }
    // From the agenda a tap opens the calendar day and a second tap the editor; on the calendar one tap is enough.
    private fun openEvent(title:String) {
        click(title)
        if (find("Edit event")==null) { Thread.sleep(700); if (find("Edit event")==null) click(title) }
        await { find("Edit event")!=null && pickEditable(nodes(),title)!=null }
    }
    private fun editorOpen()=find("Edit event")!=null || find("New event")!=null

    @Test fun newEventSaveKeepsEditingAndBecomesExisting() {
        open();click("Add menu");click("Add event")
        await { find("New event")!=null }
        assertNull(find("Delete"))
        setText("","QA keep editing")
        click("Save")
        await { data().items.any { it.title=="QA keep editing" } }
        val id=data().items.single { it.title=="QA keep editing" }.id
        // Still open, on the saved event: an existing one now, with Delete, and nothing left to save.
        await { find("Edit event")!=null && find("Delete")!=null }
        assertNotNull(pickEditable(nodes(),"QA keep editing"))
        assertTrue(enabled("Close"));assertFalse(enabled("Save"))
        screenshot("new-event-saved-still-open")
        setText("QA keep editing","QA keep editing 2")
        await { enabled("Save") }
        click("Save")
        await { data().items.single { it.id==id }.title=="QA keep editing 2" }
        assertEquals(1,data().items.count { it.title.startsWith("QA keep editing") })
        await { !enabled("Save") && enabled("Close") }
        assertNotNull(pickEditable(nodes(),"QA keep editing 2"))
        // Nothing unsaved: Close leaves at once.
        click("Close")
        await { !editorOpen() && find("Save changes?")==null }
        assertNull(runCatching { EditorDraftStore(context).read() }.getOrNull())
    }

    @Test fun closeWithUnsavedChangesAsksAndEachChoiceWorks() {
        val id=seed("QA close ask")
        open();openEvent("QA close ask")
        assertFalse(enabled("Save"))
        setText("QA close ask","QA close ask edited")
        await { enabled("Save") }
        // Keep editing: the dialog goes, the edit stays.
        click("Close");await { find("Save changes?")!=null }
        screenshot("save-changes-dialog")
        click("Keep editing")
        await { find("Save changes?")==null && editorOpen() }
        assertNotNull(pickEditable(nodes(),"QA close ask edited"))
        // Discard: closes without saving, and leaves no draft behind.
        click("Close");await { find("Save changes?")!=null }
        click("Discard")
        await { !editorOpen() && find("Save changes?")==null }
        assertEquals("QA close ask",data().items.single { it.id==id }.title)
        assertNull(runCatching { EditorDraftStore(context).read() }.getOrNull())
        // Save (the dialog's, the only Save in the active window): saves, then closes.
        openEvent("QA close ask")
        setText("QA close ask","QA close ask saved")
        click("Close");await { find("Save changes?")!=null }
        click("Save")
        await { !editorOpen() && find("Save changes?")==null }
        assertEquals("QA close ask saved",data().items.single { it.id==id }.title)
        assertNull(runCatching { EditorDraftStore(context).read() }.getOrNull())
    }

    @Test fun backAndCloseWithNothingUnsavedCloseAtOnce() {
        seed("QA close at once")
        open();openEvent("QA close at once")
        assertFalse(enabled("Save"))
        // An edit undone again is nothing unsaved.
        setText("QA close at once","QA close at once x");await { enabled("Save") }
        setText("QA close at once x","QA close at once");await { !enabled("Save") }
        back()
        await { !editorOpen() };assertNull(find("Save changes?"))
        openEvent("QA close at once")
        click("Close")
        await { !editorOpen() };assertNull(find("Save changes?"))
        // Back with an edit asks too.
        openEvent("QA close at once")
        setText("QA close at once","QA close at once y")
        back();await { find("Save changes?")!=null }
        click("Discard");await { !editorOpen() }
        assertTrue(data().items.any { it.title=="QA close at once" })
    }

    @Test fun taskSaveKeepsEditingAndBecomesExisting() {
        open();click("Add menu");click("Add task");click("To-do task")
        await { find("Add task")!=null && find("Task title")!=null }
        assertNull(find("Duplicate task"))
        setText("","QA task keep editing")
        click("Save")
        await { data().tasks.any { it.title=="QA task keep editing" } }
        val id=data().tasks.single { it.title=="QA task keep editing" }.id
        await { find("Edit task")!=null && find("Duplicate task")!=null && find("Schedule time")!=null }
        assertNotNull(pickEditable(nodes(),"QA task keep editing"))
        assertFalse(enabled("Save"))
        screenshot("new-task-saved-still-open")
        setText("QA task keep editing","QA task keep editing 2")
        await { enabled("Save") }
        click("Save")
        await { data().tasks.single { it.id==id }.title=="QA task keep editing 2" }
        assertEquals(1,data().tasks.count { it.title.startsWith("QA task keep editing") })
        await { !enabled("Save") && find("Edit task")!=null }
        // Unsaved edit: Close asks; Discard keeps the saved title.
        setText("QA task keep editing 2","QA task discarded")
        click("Close");await { find("Save changes?")!=null }
        click("Discard")
        await { find("Edit task")==null && find("Save changes?")==null }
        assertEquals("QA task keep editing 2",data().tasks.single { it.id==id }.title)
    }

    // E10: the open editor notices a sync pull's change of its event (written here as the pull writes it, through
    // PlannerStore.update): a banner with Reload, and Save asks before writing its older copy back. Its own Save never
    // shows the banner.
    @Test fun anEventChangedUnderneathTheEditorIsNoticed() {
        val id=seed("QA underneath")
        open();openEvent("QA underneath")
        // Its own save: no banner.
        setText("QA underneath","QA underneath mine")
        click("Save")
        await { data().items.single { it.id==id }.title=="QA underneath mine" && !enabled("Save") }
        Thread.sleep(1000)
        assertNull(find("This event was changed elsewhere"))
        // Changed underneath while nothing is unsaved: the banner, and Reload shows it at once.
        fun pull(title:String,location:String)=runBlocking {
            app.repository.asPlannerStore().update(data().items.single { it.id==id }.copy(title=title,location=location))
        }
        pull("QA underneath web","Web room")
        await { find("This event was changed elsewhere")!=null }
        screenshot("changed-underneath-banner")
        click("Reload")
        await { pickEditable(nodes(),"QA underneath web")!=null && find("This event was changed elsewhere")==null }
        assertFalse(enabled("Save"))
        // Changed again while edited here: Save asks; Reload (after asking) drops the edit for Nextcloud's version.
        setText("QA underneath web","QA underneath edit 1")
        pull("QA underneath web 2","Web room 2")
        await { find("This event was changed elsewhere")!=null }
        click("Save")
        await { find("Changed elsewhere")!=null }
        screenshot("changed-underneath-save-asks")
        assertEquals("QA underneath web 2",data().items.single { it.id==id }.title) // nothing written yet
        click("Reload")
        await { pickEditable(nodes(),"QA underneath web 2")!=null && find("Changed elsewhere")==null }
        assertEquals("Web room 2",data().items.single { it.id==id }.location)
        // Banner Reload with unsaved changes asks first.
        setText("QA underneath web 2","QA underneath edit 2")
        pull("QA underneath web 3","Web room 3")
        await { find("This event was changed elsewhere")!=null }
        click("Reload")
        await { find("Reload?")!=null }
        click("Keep editing")
        await { find("Reload?")==null && pickEditable(nodes(),"QA underneath edit 2")!=null }
        // Save anyway: this version is written over the stored one.
        click("Save")
        await { find("Changed elsewhere")!=null }
        click("Save anyway")
        await { data().items.single { it.id==id }.title=="QA underneath edit 2" && !enabled("Save") }
        Thread.sleep(1000)
        assertNull(find("This event was changed elsewhere"))
        click("Close")
        await { !editorOpen() }
    }
}
