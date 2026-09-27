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
import java.time.YearMonth

/** Real application UI; run only with an external backup/restore harness. */
@Suppress("DEPRECATION")
class BillConveniencesUiTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val app get()=context.applicationContext as ItineraryApp
    private fun data()=runBlocking { app.repository.snapshot() }
    private fun nodes():List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo) { result+=n;for(i in 0 until n.childCount)n.getChild(i)?.let(::visit) }
        ins.uiAutomation.rootInActiveWindow?.let(::visit);return result
    }
    private fun find(text:String)=nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString()==text || it.contentDescription?.toString()==text) }
    private fun screenshot(name:String) {
        val dir=File(context.cacheDir,"qa-bills-evidence").apply { mkdirs() }
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
                    val action=if(forward)AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    if(nodes().lastOrNull { it.isScrollable && !it.isEditable }?.performAction(action)!=true)forward=!forward
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
        reveal {
            var node=find(text)
            while(node!=null && !node.isClickable)node=node.parent
            node?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
        };Thread.sleep(350)
    }
    private fun setText(old:String,value:String) {
        reveal { nodes().any { it.isVisibleToUser && it.isEditable && it.text?.toString()==old } }
        val node=nodes().first { it.isVisibleToUser && it.isEditable && it.text?.toString()==old }
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
        }));Thread.sleep(350)
    }
    private fun open() {
        ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await { find("Agenda")!=null }
    }

    @Test fun quickBillUsesThreeDayReminderAndPaymentOffersUndo()=runBlocking {
        app.settings.setAgendaRange(AgendaRange.ALL)
        open();click("Add bill")
        setText("","QA quick bill")
        reveal { find("3 days before")!=null };screenshot("quick-bill-reminder")
        click("Save")
        await { data().items.any { it.title=="QA quick bill" } }
        val bill=data().items.single { it.title=="QA quick bill" }
        assertEquals("Bills",bill.category)
        val reminder=data().reminders.single { it.itemId==bill.id }
        assertEquals(3,reminder.amount);assertEquals(ReminderUnit.DAYS,reminder.unit)
        click("Actions for QA quick bill");click("Mark paid")
        await { data().items.single { it.id==bill.id }.paid }
        click("Undo");await { !data().items.single { it.id==bill.id }.paid }
        screenshot("payment-undone")
        // A second quick entry can be discarded without leaving a saved bill or reminder.
        val before=data();click("Add bill");click("Discard")
        assertEquals(before.items,data().items);assertEquals(before.reminders,data().reminders)
    }

    @Test fun overdueBillsAndHistoryShowOnlyTheirSeries()=runBlocking {
        app.settings.setAgendaRange(AgendaRange.ALL)
        app.repository.saveItem(ItineraryItem(tripId=0,date=LocalDate.now().minusMonths(3),startTime=null,
            title="QA history bill",category="Bills",billAmountMinor=12345),options=EventSaveOptions(RepeatRule.MONTHLY,3))
        val members=data().items.filter { it.title=="QA history bill" }.sortedBy { it.date }
        app.repository.setPaid(members[0].id,true)
        app.repository.pendingPayments.value.toList().forEach { app.repository.finishPayment(it.token) }
        app.repository.setSkipped(members[1].id,true)
        open();reveal { find("Overdue")!=null };screenshot("overdue-agenda")
        click("Actions for QA history bill");click("Bill history")
        await { find("1 paid · 1 unpaid · 1 skipped")!=null && find("Overdue")!=null }
        screenshot("bill-history");click("Close")
        click("Search");setText("","QA history bill")
        await { find("Overdue")!=null };screenshot("overdue-search")
        click("Back")
        context.startActivity(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setAction("com.example.itinerary.widget.OPEN_DATE").putExtra("widget_date",members.last().date.toString()))
        await { find("Calendar")!=null && find("Overdue")!=null };screenshot("overdue-calendar")
    }

    @Test fun realNotificationAndRingingAlarmCanPayOneBillAndUndo()=runBlocking {
        open()
        app.repository.saveItem(ItineraryItem(tripId=0,date=LocalDate.now().plusDays(7),startTime=null,
            title="QA notification bill",category="Bills"),addedReminders=listOf(Reminder(itemId=0,amount=3,unit=ReminderUnit.DAYS)),
            options=EventSaveOptions(RepeatRule.MONTHLY,2))
        val item=data().items.first { it.title=="QA notification bill" }
        val reminder=data().reminders.single { it.itemId==item.id }
        val manager=context.getSystemService(android.app.NotificationManager::class.java)
        fun notification()=manager.activeNotifications.firstOrNull { it.id==reminder.id.toInt() }?.notification
        context.sendBroadcast(com.example.itinerary.reminders.reminderIntent(context,item,reminder))
        await { notification()?.actions?.any { it.title=="Mark paid" }==true }
        assertEquals(listOf("Mark paid","Snooze"),notification()!!.actions.map { it.title.toString() })
        notification()!!.actions.first { it.title=="Mark paid" }.actionIntent.send()
        await { data().items.single { it.id==item.id }.paid && notification()?.actions?.any { it.title=="Undo" }==true }
        assertEquals(1,data().items.count { it.title=="QA notification bill" && it.paid })
        // Simulate the app's snackbar finishing before the independent notification Undo action.
        app.repository.pendingPayments.value.toList().forEach { app.repository.finishPayment(it.token) }
        notification()!!.actions.first { it.title=="Undo" }.actionIntent.send()
        await { !data().items.single { it.id==item.id }.paid }
        app.repository.saveItem(data().items.single { it.id==item.id },
            removedReminders=listOf(reminder),addedReminders=listOf(reminder.copy(id=0,ringUntilDismissed=true)))
        val ringing=data().reminders.single { it.itemId==item.id }
        context.sendBroadcast(com.example.itinerary.reminders.reminderIntent(context,data().items.single { it.id==item.id },ringing))
        await { manager.activeNotifications.any { it.id==1_000_000_001 } }
        val alarm=manager.activeNotifications.first { it.id==1_000_000_001 }.notification
        assertEquals(listOf("Mark paid","Snooze","Stop"),alarm.actions.map { it.title.toString() })
        alarm.actions.first { it.title=="Mark paid" }.actionIntent.send()
        await { data().items.single { it.id==item.id }.paid && manager.activeNotifications.none { it.id==1_000_000_001 } }
        assertEquals(1,data().items.count { it.title=="QA notification bill" && it.paid })
        manager.cancel(ringing.id.toInt())
        app.repository.pendingPayments.value.toList().forEach { app.repository.finishPayment(it.token) }
        screenshot("notification-payment-complete")
    }
}
