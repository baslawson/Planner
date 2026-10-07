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
    // Save keeps the editor open on the saved record; Close then leaves at once, as nothing is unsaved.
    private fun saveAndClose() { click("Save");click("Close") }
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
        // Leaving without Close keeps the draft (Close or Back would ask "Save changes?"): restart the app.
        open(); await { find("AGENDA")!=null }
        click("Add menu"); click("Add task"); click("To-do task")
        click("Resume draft"); reveal { find("Pack documents")!=null }
        saveAndClose()
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
        screenshot("bill-payment-details");saveAndClose()
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
        click("Close") // only copied, nothing changed: Close leaves at once
    }
    @Test fun taskFilePickerAndPhotoCapture() = runBlocking {
        fresh();click("Add menu");click("Add task");click("To-do task")
        field("Task title","QA six attachments")
        click("Attach file")
        await { nodes().any { it.packageName?.toString()=="com.google.android.documentsui" } }
        screenshot("task-file-picker"); back()
        // A second tap while the camera opens makes no second photo file (as in the note editor).
        val attachmentsDir = java.io.File(app.filesDir, "attachments")
        val filesBefore = attachmentsDir.list().orEmpty().toSet()
        await { var n = find("Take photo"); while (n != null && !n.isClickable) n = n.parent
            n?.let { it.performAction(AccessibilityNodeInfo.ACTION_CLICK); it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } == true }
        fun cameraButton(id: String) = nodes().firstOrNull { it.isVisibleToUser && it.viewIdResourceName?.endsWith(":id/$id")==true }
        await { find("Shutter")!=null || cameraButton("shutter_button")!=null }
        screenshot("task-camera")
        // The camera shows its shutter while still starting and ignores a press then, so press until it has taken one.
        await {
            if (find("Done")!=null || cameraButton("done_button")!=null) true
            else {
                // A real tap, as a person would press it: the camera app does not always act on an accessibility click.
                (find("Shutter") ?: cameraButton("shutter_button"))?.takeIf { it.isEnabled }?.let { shutter ->
                    val r=android.graphics.Rect();shutter.getBoundsInScreen(r)
                    ins.uiAutomation.executeShellCommand("input tap ${r.centerX()} ${r.centerY()}").use { java.io.FileInputStream(it.fileDescriptor).use { s -> s.readBytes() } }
                }
                Thread.sleep(1500);false
            }
        }
        (find("Done") ?: cameraButton("done_button"))!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        reveal { find("Task photo.jpg")!=null };screenshot("task-photo-attached");saveAndClose()
        await { data().tasks.any { it.title=="QA six attachments" } }
        val file=data().tasks.single { it.title=="QA six attachments" }.attachments.single()
        assertEquals("one photo file for two taps", 1, (attachmentsDir.list().orEmpty().toSet() - filesBefore).size)
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
        screenshot("draft-after-kill");saveAndClose()
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
        await { data().tasks.single { it.id==task.id }.activeReminderAt!! > System.currentTimeMillis()+3500000 }
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
    @Test fun snoozedReminderShowsOnCardAndEditor() = runBlocking {
        val due=System.currentTimeMillis()-1000
        val task=PlannerTask(title="QA six snoozed",dueDate=LocalDate.now().plusDays(1),reminderAt=due)
        app.repository.saveTask(task)
        val until=LocalDate.now().plusDays(1).atTime(10,0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertTrue(app.repository.actOnTaskReminder(task.id,due,snoozeUntil=until))
        fun reminderLines()=nodes().mapNotNull { it.text?.toString() }.filter { it.startsWith("Reminder: ") }
        fresh();reveal { find("QA six snoozed")!=null && reminderLines().isNotEmpty() };screenshot("task-snoozed-card")
        val line=reminderLines().single()
        assertTrue(line,line.startsWith("Reminder: snoozed until ") && line.contains("10"))
        click("QA six snoozed")
        reveal { nodes().any { it.text?.toString()?.startsWith("Snoozed until ")==true } };screenshot("task-snoozed-editor")
        val note=nodes().mapNotNull { it.text?.toString() }.single { it.startsWith("Snoozed until ") }
        assertTrue(note,note.endsWith(". Changing the reminder ends the snooze.") && note.removePrefix("Snoozed until ").startsWith(line.removePrefix("Reminder: snoozed until ")))
        val removeLabel=nodes().mapNotNull { it.contentDescription?.toString() }.single { it.startsWith("Remove reminder: ") }
        click(removeLabel);await { nodes().none { it.text?.toString()?.startsWith("Snoozed until ")==true } }
        // The reminder removal is unsaved, so Back asks first; Discard leaves the task as it was.
        back();await { find("Save changes?")!=null };click("Discard");Unit
    }
    @Test fun taskReminderSectionFollowsTheEventLayout() = runBlocking {
        cleanFixture("QA six chips")
        fresh(); click("Add menu"); click("Add task"); click("To-do task")
        field("Task title", "QA six chips")
        reveal { find("Reminders")!=null && find("Add reminder")!=null };click("Add reminder");await { find("Tomorrow 09:00")!=null && find("Pick date and time…")!=null };screenshot("task-reminder-chips")
        assertNull(find("Reminder (optional)"))
        click("Tomorrow 09:00")
        val expected=java.time.LocalDate.now().plusDays(1).atTime(9,0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        reveal { nodes().any { it.contentDescription?.toString()?.startsWith("Remove reminder: ")==true } };screenshot("task-reminder-set")
        assertNull("choices hidden while a reminder is set",find("Add reminder"))
        saveAndClose()
        await { data().tasks.any { it.title=="QA six chips" } }
        assertEquals(expected,data().tasks.single { it.title=="QA six chips" }.reminderAt)
        cleanFixture("QA six chips");Unit
    }
    // Text boxes grow to show all their text instead of scrolling sideways.
    @Test fun longTitleAndChecklistTextWrapAndGrow() = longTextGrows(100)
    @Test fun longTitleAndChecklistTextWrapAndGrowAtLargestTextSize() = longTextGrows(TextSize.MAX_PERCENT)
    private fun longTextGrows(textSize: Int) = runBlocking {
        cleanFixture("QA six grow")
        app.settings.setTextSizePercent(textSize)
        fresh(); click("Add menu"); click("Add task"); click("To-do task")
        fun heightOf(text:String):Int { val n=nodes().first { it.isEditable && it.text?.toString()==text }; val r=android.graphics.Rect(); n.getBoundsInScreen(r); return r.height() }
        field("Task title", "QA six grow")
        val short=heightOf("QA six grow")
        val long="QA six grow — pick up the dry cleaning, then the parcel from the post office, then groceries for the weekend"
        setText("QA six grow", long)
        await { nodes().any { it.isEditable && it.text?.toString()==long } }
        assertTrue("title box grew (${heightOf(long)} > $short)", heightOf(long) > short*1.5)
        click("Add task"); field("Task 1", "Short")
        val shortItem=heightOf("Short")
        val longItem="Bring the signed lease, two forms of ID, the bond receipt and the spare key for the letterbox"
        setText("Short", longItem)
        await { nodes().any { it.isEditable && it.text?.toString()==longItem } }
        reveal { nodes().any { it.isEditable && it.text?.toString()==longItem && it.isVisibleToUser } }
        assertTrue("checklist box grew (${heightOf(longItem)} > $shortItem)", heightOf(longItem) > shortItem*1.5)
        screenshot("long-text-grows-$textSize")
        saveAndClose()
        await { data().tasks.any { it.title==long } }
        assertEquals(longItem, data().tasks.single { it.title==long }.checklist.single().text)
        cleanFixture("QA six grow"); app.settings.setTextSizePercent(TextSize.DEFAULT_PERCENT)
    }
    @Test fun taskDocumentImportCopiesBytes() = runBlocking {
        // The document the system picker offers, put in Downloads for this test and removed afterwards.
        val resolver=context.contentResolver
        val document=resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"QA-six-document.txt")
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"text/plain")
        })!!
        try {
            resolver.openOutputStream(document)!!.use { it.write("QA document bytes for task attachment\n".toByteArray()) }
            fresh();click("Add menu");click("Add task");click("To-do task")
            field("Task title","QA six document")
            click("Attach file");click("QA-six-document.txt")
            reveal { find("QA-six-document.txt")!=null && find("Save")!=null };screenshot("task-document-attached");saveAndClose()
            await { data().tasks.any { it.title=="QA six document" } }
            val attachment=data().tasks.single { it.title=="QA six document" }.attachments.single()
            assertEquals("QA document bytes for task attachment\n",app.attachmentStore.fileFor(attachment.fileName).readText())
        } finally { resolver.delete(document,null,null) }
    }
    @HarnessStage @Test fun prepareExistingTaskDraft() = runBlocking {
        cleanFixture("QA six edit")
        val task=PlannerTask(title="QA six edit original",notes="Saved notes")
        app.repository.saveTask(task)
        fresh();click("QA six edit original")
        field("Task title","QA six edit recovered")
        field("Notes (optional)","Recovered edited notes")
        // Leaving without Close keeps the draft (Close or Back would ask "Save changes?"): restart the app.
        open();await { find("AGENDA")!=null }
        assertEquals(task,data().tasks.single { it.id==task.id })
        assertNotNull(TaskDraftStore(context).read(task.id))
    }
    @HarnessStage @Test fun resumeExistingTaskDraft() = runBlocking {
        open();click("QA six edit original");click("Resume draft")
        reveal { find("QA six edit recovered")!=null };reveal { find("Recovered edited notes")!=null }
        saveAndClose()
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
