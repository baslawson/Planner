package com.example.itinerary

import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import com.example.itinerary.reminders.*
import com.example.itinerary.ui.ItemEditorSheet
import com.example.itinerary.ui.theme.ItineraryTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Temporary QA fixtures only; run under the external app-data backup/restore harness. */
class ReviewFindingsUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val app get() = context.applicationContext as ItineraryApp
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun data() = runBlocking { app.repository.snapshot() }
    private fun launch() = ins.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) { result += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::walk) }
        ins.uiAutomation.freshRoot?.let(::walk)
        return result
    }
    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis()+20000
        while (SystemClock.uptimeMillis()<until) { if (condition()) return; Thread.sleep(100) }
        screenshot("failure")
        fail("$message: " + nodes().mapNotNull { it.text }.joinToString(" | "))
    }
    private fun click(text: String) {
        await("Click $text") {
            var node = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()==text }
            while (node!=null && !node.isClickable) node=node.parent
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
        }
        ins.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        val dir = File(context.cacheDir,"qa-review-findings").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot()?.let { image ->
            File(dir,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
        }
    }
    private fun editorPayment(recover: Boolean) = runBlocking {
        assertNull(EditorDraftStore(context).read())
        val title = "QA review fixes ${if (recover) "recovered" else "open"} bill"
        app.repository.saveItem(ItineraryItem(tripId=0,date=LocalDate.now().plusDays(15),startTime=null,
            title=title,category="Bills",billAmountMinor=12345678),
            addedReminders=listOf(Reminder(itemId=0,amount=0,unit=ReminderUnit.MINUTES)))
        val original = data().items.single { it.title==title }
        val reminder = data().reminders.single { it.itemId==original.id }
        val activity = launch()
        ins.runOnMainSync { activity.setContent { ItineraryTheme {
            ItemEditorSheet(original, emptyList(), listOf(reminder), emptyMap(), emptySet(), {}, {}, {},
                { item, added, removed, alarms, deleted, options -> app.repository.saveItem(item,added,removed,alarms,deleted,options) },
                { _, _ -> })
        } } }
        await("Editor title") { nodes().any { it.isEditable && it.text?.toString()==title } }
        val changedTitle = "$title edited"
        val field = nodes().single { it.isEditable && it.text?.toString()==title }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, changedTitle)
        }))
        await("Draft title") { EditorDraftStore(context).read()?.getJSONObject("item")?.getString("title")==changedTitle }
        BillPaymentReceiver.action(context, reminder.id, billReminderToken(original,reminder)).send()
        await("Notification payment") { data().items.single { it.id==original.id }.paid }
        val ledger = data().items.single { it.id==original.id }.payments
        if (recover) {
            ins.runOnMainSync { activity.finish() }
            ins.waitForIdleSync()
            launch() // AppNav restores the original baseline and edited fields from the real draft file.
            await("Recovered title") { nodes().any { it.isEditable && it.text?.toString()==changedTitle } }
        }
        screenshot(if (recover) "recovered-editor" else "open-editor")
        click("Save")
        await("Saved editor") { data().items.single { it.id==original.id }.title==changedTitle && EditorDraftStore(context).read()==null }
        val saved = data().items.single { it.id==original.id }
        assertTrue(saved.paid); assertEquals(ledger,saved.payments)
        manager.cancel(reminder.id.toInt())
        if (!recover) ins.runOnMainSync { activity.finish() }
    }
    @Test fun openEditorPreservesRealNotificationPayment() = editorPayment(false)
    @Test fun recoveredEditorPreservesRealNotificationPayment() = editorPayment(true)

    @Test fun editedAlarmStopsAndOpenStaleSnoozeChooserCannotOverrideNewSchedule() = runBlocking {
        launch()
        app.repository.saveItem(ItineraryItem(tripId=0,date=LocalDate.now().minusDays(1),startTime=LocalTime.of(9,0),
            title="QA review fixes ringing alarm"),
            addedReminders=listOf(Reminder(itemId=0,amount=0,unit=ReminderUnit.MINUTES,ringUntilDismissed=true)))
        val item = data().items.single { it.title=="QA review fixes ringing alarm" }
        val r = data().reminders.single { it.itemId==item.id }
        try {
            context.sendBroadcast(reminderIntent(context,item,r))
            await("Ringing notification") { manager.activeNotifications.any { it.id==1_000_000_001 } }
            val oldAction = manager.activeNotifications.single { it.id==1_000_000_001 }.notification.actions.single { it.title=="Snooze" }.actionIntent
            app.repository.rescheduleAllReminders()
            assertTrue(manager.activeNotifications.any { it.id==1_000_000_001 })
            oldAction.send()
            await("Snooze chooser") { nodes().any { it.text?.toString()=="10 minutes" } }
            app.repository.saveItem(item.copy(date=LocalDate.now().plusDays(1)))
            await("Old alarm stopped") { manager.activeNotifications.none { it.id==1_000_000_001 || it.id==r.id.toInt() } }
            screenshot("stale-snooze-chooser")
            click("10 minutes")
            await("Chooser closed") { nodes().none { it.text?.toString()=="Snooze reminder" } }
            assertNull(data().reminders.single { it.id==r.id }.snoozedUntil)
            val edited = data().items.single { it.id==item.id }
            assertEquals(LocalDate.now().plusDays(1),edited.date)
            assertTrue(postReminderNotification(context,r.id.toInt(),edited.title,"Future event","Test",r.id,
                snoozeToken=eventReminderToken(edited,r)))
            await("Normal notification") { manager.activeNotifications.any { it.id==r.id.toInt() } }
            app.repository.rescheduleAllReminders()
            assertTrue(manager.activeNotifications.any { it.id==r.id.toInt() })
            app.repository.saveItem(edited.copy(startTime=LocalTime.NOON))
            await("Normal notification cleared") { manager.activeNotifications.none { it.id==r.id.toInt() } }
        } finally { app.reminderScheduler.cancel(r.id) }
    }
}
