package com.example.itinerary

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Run prepare, then externally force-stop and prove the PID disappeared, then recover. */
@Suppress("DEPRECATION")
class DraftRecoveryUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private fun snapshot() = runBlocking { app.repository.snapshot() }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result+=n; for(i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit); return result
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString()==text || it.contentDescription?.toString()==text) }
    private fun await(condition: ()->Boolean) {
        val end=SystemClock.uptimeMillis()+20000
        while(SystemClock.uptimeMillis()<end) { if(condition()) return; Thread.sleep(150) }
        screenshot("failure")
        File(context.cacheDir,"qa-draft-evidence/failure-nodes.txt").writeText(nodes().joinToString("\n") { "${it.className} ${it.text} scroll=${it.isScrollable} visible=${it.isVisibleToUser}" })
        fail("Timed out: "+nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun click(text: String) {
        var tries=0; var forward=true
        await {
            var node=find(text)
            while(node!=null && !node.isClickable) node=node.parent
            if(node!=null && node.isEnabled) node.performAction(AccessibilityNodeInfo.ACTION_CLICK) else {
                if(++tries>5) {
                    val action=if(forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    if(nodes().lastOrNull { it.isScrollable && !it.isEditable }?.performAction(action)!=true) forward=!forward
                    Thread.sleep(350)
                };false
            }
        };Thread.sleep(350)
    }
    private fun screenshot(name: String) {
        val dir=File(context.cacheDir,"qa-draft-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()!!.let { b -> File(dir,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
    }
    private fun prepareUnfinishedEditorAndScan() = runBlocking {
        assertNull(EditorDraftStore(context).read())
        app.settings.setAgendaRange(AgendaRange.ALL)
        app.repository.saveItem(ItineraryItem(tripId=0,date=LocalDate.now().plusDays(10),startTime=null,title="QA recovery source", category="Bills"),
            addedReminders=listOf(Reminder(itemId=0,amount=3,unit=ReminderUnit.DAYS)))
        instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        click("QA recovery source");click("QA recovery source")
        await { nodes().any { it.isEditable && it.text?.toString()=="QA recovery source" } }
        val title=nodes().first { it.isEditable && it.text?.toString()=="QA recovery source" }
        assertTrue(title.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"QA recovered bill")
        }))
        click("Does not repeat");click("Fortnightly")
        click("Scan document")
    }
    // Deterministic camera boundary for the prepare stage, while exercising the real editor and scanner.
    @Test fun prepareWithCapturedPage() {
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
        instrumentation.addMonitor(monitor)
        try {
            prepareUnfinishedEditorAndScan()
            await { find("Auto crop ready. Adjust the corners if needed.")!=null }
            click("Preview scan")
            await { find("Cropped page preview")!=null }
            val draft=EditorDraftStore(context).read()!!
            assertEquals("QA recovered bill",DraftCodec.item(draft.getJSONObject("item")).title)
            assertEquals("FORTNIGHTLY",draft.getJSONObject("state").getString("repeat"))
            assertTrue(File(context.filesDir,"draft-scan/session.json").exists())
            assertEquals("QA recovery source",snapshot().items.single { it.title.startsWith("QA recovery") }.title)
            screenshot("before-process-death")
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun recoverScanSaveRepeatAndMarkBillPaid() = runBlocking {
        assertNotNull(EditorDraftStore(context).read())
        instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await { find("Cropped page preview")!=null }
        screenshot("recovered-preview")
        click("Adjust corners")
        await { find("Document crop. Adjust the four page corners.")!=null }
        click("Preview scan")
        await { find("Cropped page preview")!=null }
        click("Attach scan")
        await { find("Save")!=null }
        click("Save")
        await { snapshot().items.count { it.title=="QA recovered bill" }==12 }
        val data=snapshot();val bills=data.items.filter { it.title=="QA recovered bill" }.sortedBy { it.date }
        assertEquals(12,data.attachments.count { a -> bills.any { it.id==a.itemId } })
        assertTrue(bills.all { it.repeatRule=="FORTNIGHTLY" })
        assertEquals(bills.first().date.plusWeeks(22),bills.last().date)
        assertNull(EditorDraftStore(context).read())
        assertFalse(File(context.filesDir,"draft-scan").exists())
        click("Actions for QA recovered bill");click("Mark paid")
        await { snapshot().items.count { it.title=="QA recovered bill" && it.paid }==1 }
        assertNotNull(find("Paid"))
        screenshot("paid-bill")
        // Prove a real notification action schedules a durable override on an unpaid occurrence.
        val unpaid=snapshot().items.first { it.title=="QA recovered bill" && !it.paid }
        val reminder=snapshot().reminders.first { it.itemId==unpaid.id }
        val before=System.currentTimeMillis()
        com.example.itinerary.reminders.SnoozeReceiver.action(context,reminder.id,false,eventReminderToken(unpaid,reminder)).send()
        await { snapshot().reminders.first { it.id==reminder.id }.snoozedUntil!=null }
        val until=snapshot().reminders.first { it.id==reminder.id }.snoozedUntil!!
        assertTrue(until in before+3_600_000..System.currentTimeMillis()+3_600_000)
    }
    @Test fun notificationActionsAndPaymentCancelPostedReminder() = runBlocking {
        val bill=snapshot().items.first { it.title=="QA recovered bill" && it.paid }
        app.repository.setPaid(bill.id,false)
        val reminder=snapshot().reminders.first { it.itemId==bill.id }
        assertTrue(com.example.itinerary.reminders.postReminderNotification(context,reminder.id.toInt(),bill.title,
            "Bill reminder", "3 days before", reminder.id, snoozeToken=eventReminderToken(
                snapshot().items.first { it.id==bill.id }, snapshot().reminders.first { it.id==reminder.id })))
        val manager=context.getSystemService(android.app.NotificationManager::class.java)
        await { manager.activeNotifications.any { it.id==reminder.id.toInt() } }
        val notification=manager.activeNotifications.first { it.id==reminder.id.toInt() }.notification
        assertEquals(listOf("Snooze"),notification.actions.map { it.title.toString() })
        notification.actions[0].actionIntent.send()
        click("Tomorrow at 9 am")
        val tomorrow=com.example.itinerary.reminders.snoozeTime(true)
        await { snapshot().reminders.first { it.id==reminder.id }.snoozedUntil==tomorrow }
        await { manager.activeNotifications.none { it.id==reminder.id.toInt() } }
        // A paid bill also dismisses an already-delivered notification and clears the pending snooze.
        assertTrue(com.example.itinerary.reminders.postReminderNotification(context,reminder.id.toInt(),bill.title,
            "Bill reminder", "3 days before", reminder.id, snoozeToken=eventReminderToken(
                snapshot().items.first { it.id==bill.id }, snapshot().reminders.first { it.id==reminder.id })))
        app.repository.setPaid(bill.id,true)
        assertNull(snapshot().reminders.first { it.id==reminder.id }.snoozedUntil)
        assertTrue(manager.activeNotifications.none { it.id==reminder.id.toInt() })
    }

}
