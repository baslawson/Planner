package com.example.itinerary

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Shared emulator: external private-data backup is required. */
@Suppress("DEPRECATION")
class PlannerConveniencesUiTest {
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
        val dir=File(context.cacheDir,"qa-conveniences-evidence").apply { mkdirs() }
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
                    val before=nodes().map { it.text?.toString() }
                    val bounds=android.graphics.Rect()
                    nodes().firstOrNull { it.isScrollable && !it.isEditable }?.getBoundsInScreen(bounds)
                    if(!bounds.isEmpty) {
                        val delta=bounds.height()/6
                        val from=bounds.centerY()+if(forward)delta else -delta
                        val to=bounds.centerY()-if(forward)delta else -delta
                        ins.uiAutomation.executeShellCommand("input swipe ${bounds.left+12} $from ${bounds.left+12} $to 200").use {
                            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
                        }
                    }
                    Thread.sleep(350)
                    if(nodes().map { it.text?.toString() }==before)forward=!forward
                };false
            }
        }
    }
    private fun click(text:String) {
        if (text == "Settings" && find(text) == null && find("More options") != null) click("More options")
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
        app.settings.lastViewCalendar=false // open() expects the agenda
        ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("AGENDA")!=null }
    }


    @Test fun notificationChooserSavesAllDurationsAndCancelsWithoutChangingReminder()=runBlocking {
        open()
        app.repository.saveItem(ItineraryItem(tripId=0,date=LocalDate.now().plusDays(8),startTime=null,
            title="QA snooze choices",category="Bills"),addedReminders=listOf(Reminder(itemId=0,amount=0,unit=ReminderUnit.MINUTES)))
        val item=data().items.single { it.title=="QA snooze choices" }
        val r=data().reminders.single { it.itemId==item.id }
        val manager=context.getSystemService(android.app.NotificationManager::class.java)
        for (choice in SnoozeChoice.entries) {
            assertTrue(postReminderNotification(context,r.id.toInt(),item.title,"Due","Reminder",r.id,snoozeToken=eventReminderToken(item,data().reminders.single { it.id==r.id })))
            await { manager.activeNotifications.any { it.id==r.id.toInt() && it.notification.actions?.any { a -> a.title=="Snooze" }==true } }
            val action=manager.activeNotifications.single { it.id==r.id.toInt() }.notification.actions.single { it.title=="Snooze" }
            action.actionIntent.send()
            await { find("Snooze reminder")!=null && find("10 minutes")!=null && find("Tomorrow at 9 am")!=null }
            screenshot("snooze-choices")
            val low=choice.until();click(choice.label);val high=choice.until()
            await { data().reminders.single { it.id==r.id }.snoozedUntil?.let { it in low..high }==true }
            await { manager.activeNotifications.none { it.id==r.id.toInt() } }
        }
        val before=data().reminders.single { it.id==r.id }
        SnoozeActivity.action(context,r.id,eventReminderToken(item,before)).send();await { find("Snooze reminder")!=null };click("Cancel")
        assertEquals(before,data().reminders.single { it.id==r.id })
        // Ringing bills also expose the chooser and keep Stop and Mark paid.
        app.repository.saveItem(item, removedReminders=listOf(before), addedReminders=listOf(before.copy(id=0,ringUntilDismissed=true,snoozedUntil=null)))
        val ring=data().reminders.single { it.itemId==item.id }
        context.sendBroadcast(reminderIntent(context,item,ring))
        await { manager.activeNotifications.any { it.id==1_000_000_001 } }
        val notification=manager.activeNotifications.single { it.id==1_000_000_001 }.notification
        assertEquals(listOf("Mark paid","Snooze","Stop"),notification.actions.map { it.title.toString() })
        notification.actions.single { it.title=="Snooze" }.actionIntent.send();await { find("10 minutes")!=null };click("10 minutes")
        await { data().reminders.single { it.id==ring.id }.snoozedUntil!=null && manager.activeNotifications.none { it.id==1_000_000_001 } }
    }

    @Test fun monthlyForecastShowsRemainingBalancesAndCalendarFileCanBeSaved()=runBlocking {
        app.settings.setAgendaRange(AgendaRange.ALL)
        app.settings.setBillsExpanded(true)
        app.settings.setShowBillsSummary(true)
        val day=LocalDate.now()
        app.repository.saveItem(ItineraryItem(tripId=0,date=day,startTime=java.time.LocalTime.of(23,30),durationMinutes=120,
            title="QA forecast export",category="Bills",billAmountMinor=12345,location="Perth, WA",notes="Export note; line\nTwo",
            payments=listOf(BillPayment(amount=2345))))
        val item=data().items.single { it.title=="QA forecast export" }
        open();click("View monthly bill forecast")
        await { find("QA forecast export")!=null && find("AUD 100.00")!=null };screenshot("bill-forecast");click("Done")
        click("Actions for QA forecast export");click("Export to calendar (.ics)")
        await { nodes().any { it.isEditable && it.text?.toString()?.endsWith(".ics")==true } }
        // A deterministic test filename; only this file is removed by the external harness.
        val field=nodes().first { it.isEditable && it.text?.toString()?.endsWith(".ics")==true }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Planner-QA-conveniences.ics")
        }))
        screenshot("calendar-save-picker")
        val save=nodes().first { it.isVisibleToUser && it.text?.toString().equals("Save",true) }
        assertTrue(save.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await { find("AGENDA")!=null }
        var text=""
        await {
            val fd=ins.uiAutomation.executeShellCommand("cat /sdcard/Download/Planner-QA-conveniences.ics")
            text=android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
            text.contains("END:VCALENDAR")
        }
        assertTrue(text.contains("SUMMARY:QA forecast export"));assertTrue(text.contains("LOCATION:Perth\\, WA"))
        assertTrue(text.contains("DESCRIPTION:Export note\\; line\\nTwo"));assertFalse(text.contains("RRULE"))
        assertTrue(text.contains("DTEND:"));assertTrue(text.contains("DTSTART:"))
        val before=data().items.single { it.id==item.id }
        click("Actions for QA forecast export");click("Export to calendar (.ics)")
        await { nodes().any { it.isEditable && it.text?.toString()?.endsWith(".ics")==true } }
        ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        await { find("AGENDA")!=null };assertEquals(before,data().items.single { it.id==item.id })
    }

    @Test fun backupReminderPreferenceAndDeferralSurviveStoreReloadAndSuccessHidesPrompt()=runBlocking {
        val isolated=object:android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name:String, mode:Int)=super.getSharedPreferences("qa_conveniences_"+name,mode)
        }
        isolated.getSharedPreferences("backup_status",0).edit().clear().commit()
        val store=BackupStatusStore(isolated);val today=LocalDate.now()
        assertTrue(store.reminder.value.due(store.state.value.lastSuccess,today))
        store.remindNextWeek(today)
        assertFalse(BackupStatusStore(isolated).reminder.value.due(null,today.plusDays(6)))
        assertTrue(BackupStatusStore(isolated).reminder.value.due(null,today.plusDays(7)))
        store.setRemindersEnabled(false)
        assertFalse(BackupStatusStore(isolated).reminder.value.enabled)
        store.setRemindersEnabled(true)
        store.track("QA") { Unit }
        assertFalse(store.reminder.value.due(store.state.value.lastSuccess,today.plusDays(6)))
        assertEquals(store.state.value,BackupStatusStore(isolated).state.value)
        // Real Settings includes an operable preference.
        open();click("Settings");reveal { find("Weekly backup reminders")!=null };screenshot("backup-reminders")
        isolated.getSharedPreferences("backup_status",0).edit().clear().commit()
        Unit
    }
}
