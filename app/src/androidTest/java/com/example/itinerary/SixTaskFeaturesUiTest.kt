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

/** Run only with an external backup/restore harness for the shared emulator. */
@Suppress("DEPRECATION")
class SixTaskFeaturesUiTest {
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
        val dir=File(context.cacheDir,"qa-six-task-evidence").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { b -> File(dir,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
    }
    private fun await(timeout:Long=45000,condition:()->Boolean) {
        val end=SystemClock.uptimeMillis()+timeout
        while(SystemClock.uptimeMillis()<end) { if(condition())return;Thread.sleep(150) }
        screenshot("failure");fail("Timed out: "+nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun reveal(test:()->Boolean) {
        var tries=0;var forward=true;var stationary=0
        await {
            if(test())true else {
                if(++tries>5) {
                    hideQuickTestKeyboard(ins) // a swipe across the keyboard would type words
                    fun positions()=nodes().map { n ->
                        val r=android.graphics.Rect();n.getBoundsInScreen(r);"${n.text}:$r"
                    }
                    val before=positions()
                    val bounds=android.graphics.Rect()
                    nodes().filter { it.isScrollable && !it.isEditable }.maxByOrNull {
                        val r=android.graphics.Rect();it.getBoundsInScreen(r);r.height()
                    }?.getBoundsInScreen(bounds)
                    if(!bounds.isEmpty) {
                        val delta=bounds.height()/6
                        val from=bounds.centerY()+if(forward)delta else -delta
                        val to=bounds.centerY()-if(forward)delta else -delta
                        ins.uiAutomation.executeShellCommand("input swipe ${bounds.left+12} $from ${bounds.left+12} $to 200").use {
                            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
                        }
                    }
                    Thread.sleep(350)
                    if(positions()==before)stationary++ else stationary=0
                    if(stationary>=3) { forward=!forward;stationary=0 }
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
    private fun open() {
        ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("Add menu")!=null }
    }


    private fun back() {
        ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(350)
    }
    private fun cleanFixture(prefix:String)=runBlocking {
        data().tasks.filter { it.title.startsWith(prefix) }.forEach { app.repository.deleteTask(it.id) }
        data().deleted.filter { it.label.startsWith(prefix) }.forEach { app.repository.permanentlyDelete(it.id) }
    }
    private fun field(label: String, value: String) {
        reveal {
            val node=nodes().firstOrNull { it.isVisibleToUser && it.isEditable &&
                (it.hintText?.toString()==label || it.text?.toString()==label || descendants(it).any { n -> n.text?.toString()==label }) }
            node?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
            })==true
        }
        await { nodes().any { it.isEditable && it.text?.toString()==value } }
        Thread.sleep(350)
    }
    private fun descendants(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(n) +
        (0 until n.childCount).flatMap { n.getChild(it)?.let(::descendants).orEmpty() }
    private fun fresh() {
        val info=ins.uiAutomation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        ins.uiAutomation.serviceInfo=info
        TaskDraftStore(context).clear("new")
        EditorDraftStore(context).clear()
        app.settings.setAgendaRange(AgendaRange.ALL); app.settings.setAgendaTypes(AgendaType.entries.toSet())
        open()
    }
    @Test fun createRepeatingTaskWithChecklistRecoverDraftAndComplete() = runBlocking {
        cleanFixture("QA six repeat")
        fresh(); click("Add menu"); click("Add task"); click("To-do task")
        field("Task title", "QA six repeat")
        click("Never"); click("Weekly")
        click("Add task"); field("Task 1", "Pack documents")
        screenshot("repeat-checklist-editor")
        back(); await { find("AGENDA")!=null }
        click("Add menu"); click("Add task"); click("To-do task")
        click("Resume draft"); reveal { find("Pack documents")!=null }
        click("Save")
        await { data().tasks.any { it.title=="QA six repeat" } }
        val task=data().tasks.single { it.title=="QA six repeat" }
        assertEquals("WEEKLY", task.repeat); assertEquals("Pack documents",task.checklist.single().text)
        click("Mark QA six repeat done")
        await { data().tasks.count { it.title==task.title }==2 }
        assertTrue(data().tasks.single { it.id==task.id }.done)
        assertEquals(LocalDate.now().plusWeeks(1), data().tasks.single { it.id!=task.id && it.title==task.title }.dueDate)
        screenshot("repeat-created")
    }
    @Test fun agendaFiltersSeparateTasksBillsAndEvents() = runBlocking {
        val prefix="QA six filter"
        cleanFixture(prefix)
        data().trips.filter { it.name==prefix }.forEach { app.repository.deleteTrip(it) }
        app.repository.saveTask(PlannerTask(title="$prefix task"))
        app.repository.saveTrip(Trip(name=prefix,destination="",startDate=LocalDate.now(),endDate=LocalDate.now()))
        app.repository.saveItem(ItineraryItem(tripId=data().trips.single { it.name==prefix }.id,date=LocalDate.now(),startTime=null,title="$prefix bill",category="Bills"),emptyList(),emptyList(),emptyList(),emptyList())
        app.repository.saveItem(ItineraryItem(tripId=data().trips.single { it.name==prefix }.id,date=LocalDate.now(),startTime=null,title="$prefix event"),emptyList(),emptyList(),emptyList(),emptyList())
        fresh()
        click("Show bills"); click("Show events"); await { app.settings.agendaTypes.value==setOf(AgendaType.TASKS) }; reveal { find("$prefix task")!=null }
        await { find("$prefix bill")==null && find("$prefix event")==null }; screenshot("filter-tasks")
        click("Show bills"); click("Show tasks"); await { app.settings.agendaTypes.value==setOf(AgendaType.BILLS) }; reveal { find("$prefix bill")!=null }
        await { find("$prefix task")==null && find("$prefix event")==null }; screenshot("filter-bills")
        click("Show events"); click("Show bills"); await { app.settings.agendaTypes.value==setOf(AgendaType.EVENTS) }; reveal { find("$prefix event")!=null }
        await { find("$prefix task")==null && find("$prefix bill")==null }; screenshot("filter-events")
        assertEquals(setOf(AgendaType.EVENTS),SettingsRepository(context).agendaTypes.value)
        click("Show tasks"); click("Show bills"); reveal { find("$prefix task")!=null }
        // Real touch holds verify release does not also fire the ordinary toggle.
        for (type in AgendaType.entries) {
            val label="Show ${type.label.lowercase()}"
            reveal { find(label)!=null }
            var target=find(label)
            while(target!=null && !target.isLongClickable) target=target.parent
            val node=requireNotNull(target) { "No long-click target for $label" }
            assertTrue(node.actionList.toString(),node.actionList.any { it.id==AccessibilityNodeInfo.ACTION_LONG_CLICK && it.label?.toString()=="Show only ${type.label.lowercase()}" })
            val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds)
            ins.uiAutomation.executeShellCommand("input swipe ${bounds.centerX()} ${bounds.centerY()} ${bounds.centerX()} ${bounds.centerY()} 900").use {
                java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
            }
            await { app.settings.agendaTypes.value==setOf(type) }
            Thread.sleep(400)
            assertEquals(setOf(type),app.settings.agendaTypes.value)
            var checkbox=find(label)
            while(checkbox!=null && !checkbox.isCheckable) checkbox=checkbox.parent
            assertTrue("Checked accessibility state for $label",checkbox?.isChecked==true)
            assertEquals(setOf(type),SettingsRepository(context).agendaTypes.value)
        }
        screenshot("long-press-single-type")
        open();await { app.settings.agendaTypes.value==setOf(AgendaType.entries.last()) }
        click("Show ${AgendaType.entries.last().label.lowercase()}")
        await { app.settings.agendaTypes.value.isEmpty() }
        click("Show tasks");await { app.settings.agendaTypes.value==setOf(AgendaType.TASKS) }

    }
    @Test fun billPaymentDetailsSaveAndReopen() = runBlocking {
        fresh();click("Add menu");click("Add bill")
        field("Bill title","QA six payment")
        field("Payment link","https://example.com/pay")
        field("Reference","REF 00123")
        field("BPAY biller code","001234")
        field("BPAY reference","00987654321")
        screenshot("bill-payment-details");click("Save")
        await { data().items.any { it.title=="QA six payment" } }
        val bill=data().items.single { it.title=="QA six payment" }
        assertEquals("https://example.com/pay",bill.paymentLink);assertEquals("REF 00123",bill.paymentReference)
        assertEquals("001234",bill.bpayBillerCode);assertEquals("00987654321",bill.bpayReference)
        click("QA six payment");reveal { find("00987654321")!=null }
        val clipboard=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        var previousClip: android.content.ClipData? = null
        ins.runOnMainSync { previousClip=clipboard.primaryClip }
        try {
            click("Copy BPAY reference")
            ins.runOnMainSync { assertEquals("00987654321",clipboard.primaryClip!!.getItemAt(0).text.toString()) }
        } finally {
            ins.runOnMainSync {
                val previous=previousClip
                if(previous!=null) clipboard.setPrimaryClip(previous)
                else if(android.os.Build.VERSION.SDK_INT>=28) clipboard.clearPrimaryClip()
                else clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
            }
        }
        click("Discard")
    }
    @Test fun taskFilePickerAndPhotoCapture() = runBlocking {
        fresh();click("Add menu");click("Add task");click("To-do task")
        field("Task title","QA six attachments")
        click("Attach file")
        await { nodes().any { it.packageName?.toString()=="com.google.android.documentsui" } }
        screenshot("task-file-picker"); back()
        click("Take photo")
        fun cameraButton(id: String) = nodes().firstOrNull { it.isVisibleToUser && it.viewIdResourceName?.endsWith(":id/$id")==true }
        await { find("Shutter")!=null || cameraButton("shutter_button")!=null }
        screenshot("task-camera")
        (find("Shutter") ?: cameraButton("shutter_button"))!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        await { find("Done")!=null || cameraButton("done_button")!=null }
        (find("Done") ?: cameraButton("done_button"))!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        reveal { find("Task photo.jpg")!=null };screenshot("task-photo-attached");click("Save")
        await { data().tasks.any { it.title=="QA six attachments" } }
        val file=data().tasks.single { it.title=="QA six attachments" }.attachments.single()
        assertTrue(app.attachmentStore.fileFor(file.fileName).length()>0)
    }
    @HarnessStage @Test fun prepareProcessDeathDraft() = runBlocking {
        fresh();click("Add menu");click("Add task");click("To-do task")
        field("Task title","QA six cold draft")
        click("Never");click("Days after completion")
        field("Days after completion (1–3650)","12")
        field("Notes (optional)","Recover after process death")
        click("Add task");field("Task 1","Keep this step")
        await { TaskDraftStore(context).read("new")?.optString("title")=="QA six cold draft" }
        screenshot("draft-before-kill")
    }
    @HarnessStage @Test fun resumeProcessDeathDraft() = runBlocking {
        open();click("Add menu");click("Add task");click("To-do task");click("Resume draft")
        reveal { find("QA six cold draft")!=null };reveal { find("Keep this step")!=null }
        screenshot("draft-after-kill");click("Save")
        await { data().tasks.any { it.title=="QA six cold draft" } }
        val task=data().tasks.single { it.title=="QA six cold draft" }
        assertEquals("AFTER_COMPLETION",task.repeat);assertEquals(12,task.repeatDays)
        assertEquals("Recover after process death",task.notes);assertEquals("Keep this step",task.checklist.single().text)
        assertNull(TaskDraftStore(context).read("new"))
    }
    @Test fun notificationDoneAndSnoozeActionsWork() = runBlocking {
        val manager=context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        assertTrue(com.example.itinerary.reminders.notificationsEnabled(context))
        val task=PlannerTask(title="QA six notification",repeat="DAILY",reminderAt=System.currentTimeMillis()-1000)
        app.repository.saveTask(task)
        context.sendBroadcast(com.example.itinerary.reminders.TaskReminderReceiver.intent(context,task.id).putExtra("trigger",task.reminderAt))
        await { manager.activeNotifications.any { it.tag=="task:${task.id}" } }
        var notification=manager.activeNotifications.single { it.tag=="task:${task.id}" }.notification
        assertEquals(listOf("Done","Snooze"),notification.actions.map { it.title.toString() })
        notification.actions.single { it.title.toString()=="Snooze" }.actionIntent.send()
        await { find("Snooze reminder")!=null };screenshot("task-snooze-choices");click("1 hour")
        await { data().tasks.single { it.id==task.id }.reminderAt!! > System.currentTimeMillis()+3500000 }
        assertTrue(manager.activeNotifications.none { it.tag=="task:${task.id}" })
        val stale=notification.actions.single { it.title.toString()=="Done" }.actionIntent
        stale.send();Thread.sleep(400);assertFalse(data().tasks.single { it.id==task.id }.done)
        val elapsed=System.currentTimeMillis()-1000
        app.repository.saveTask(task.copy(reminderAt=elapsed),create=false)
        context.sendBroadcast(com.example.itinerary.reminders.TaskReminderReceiver.intent(context,task.id).putExtra("trigger",elapsed))
        await { manager.activeNotifications.any { it.tag=="task:${task.id}" } }
        notification=manager.activeNotifications.single { it.tag=="task:${task.id}" }.notification
        notification.actions.single { it.title.toString()=="Done" }.actionIntent.send()
        await { data().tasks.single { it.id==task.id }.done }
        assertEquals(2,data().tasks.count { it.title==task.title })
        assertTrue(manager.activeNotifications.none { it.tag=="task:${task.id}" })
    }
    @Test fun taskDocumentImportCopiesBytes() = runBlocking {
        fresh();click("Add menu");click("Add task");click("To-do task")
        field("Task title","QA six document")
        click("Attach file");click("QA-six-document.txt")
        reveal { find("QA-six-document.txt")!=null && find("Save")!=null };screenshot("task-document-attached");click("Save")
        await { data().tasks.any { it.title=="QA six document" } }
        val attachment=data().tasks.single { it.title=="QA six document" }.attachments.single()
        assertEquals("QA document bytes for task attachment\n",app.attachmentStore.fileFor(attachment.fileName).readText())
    }
    @HarnessStage @Test fun prepareExistingTaskDraft() = runBlocking {
        cleanFixture("QA six edit")
        val task=PlannerTask(title="QA six edit original",notes="Saved notes")
        app.repository.saveTask(task)
        fresh();click("QA six edit original")
        field("Task title","QA six edit recovered")
        field("Notes (optional)","Recovered edited notes")
        back();await { find("AGENDA")!=null }
        assertEquals(task,data().tasks.single { it.id==task.id })
        assertNotNull(TaskDraftStore(context).read(task.id))
    }
    @HarnessStage @Test fun resumeExistingTaskDraft() = runBlocking {
        open();click("QA six edit original");click("Resume draft")
        reveal { find("QA six edit recovered")!=null };reveal { find("Recovered edited notes")!=null }
        click("Save")
        await { data().tasks.any { it.title=="QA six edit recovered" } }
        assertEquals("Recovered edited notes",data().tasks.single { it.title=="QA six edit recovered" }.notes)
    }
    @Test fun cleanupTestTaskAlarms() = runBlocking {
        data().tasks.filter { it.title.startsWith("QA six ") || it.title=="QA task editor" }.forEach { app.repository.deleteTask(it.id) }
        assertTrue(data().tasks.none { it.title.startsWith("QA six ") || it.title=="QA task editor" })
    }
    @Test fun clearSyntheticClipboard() {
        open()
        ins.runOnMainSync {
            val clipboard=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            if(clipboard.primaryClip?.getItemAt(0)?.text?.toString()=="00987654321") {
                if(android.os.Build.VERSION.SDK_INT>=28) clipboard.clearPrimaryClip()
                else clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
            }
        }
    }
}
