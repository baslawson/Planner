package com.example.itinerary

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.itinerary.data.AgendaRange
import com.example.itinerary.data.AgendaType
import com.example.itinerary.data.DataSnapshot
import com.example.itinerary.data.EditorDraftStore
import com.example.itinerary.data.QuickDraftStore
import com.example.itinerary.data.TaskDraftStore
import kotlinx.coroutines.runBlocking
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/**
 * Runs the instrumented tests. The test app is installed fresh, so before it starts it gets the settings the tests were
 * written against: 24-hour times ("Until 01:30"). A format a test saved itself is left alone.
 */
class PlannerTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        val listeners = listOfNotNull(arguments.getString("listener"), CleanStart::class.java.name)
        arguments.putString("listener", listeners.joinToString(","))
        super.onCreate(arguments)
    }

    override fun callApplicationOnCreate(app: Application) {
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.contains("time_format")) prefs.edit().putString("time_format", "HOUR_24").commit()
        super.callApplicationOnCreate(app)
    }
}

/**
 * Each test starts with no screen open, no unsaved draft and an empty database, so one test cannot leave an editor
 * that the next opens into ("Unfinished draft recovered") or entries that crowd its screens. The database is emptied
 * the way a backup restore replaces it, which also cancels reminder alarms and removes unused attachment files.
 * What the agenda and calendar show goes back to a new install's defaults too; appearance settings (theme, font, date
 * and time format) are left to the tests that change them.
 * Harness steps are left alone: their drafts and data must survive between steps.
 */
class CleanStart : RunListener() {
    override fun testStarted(description: Description) {
        if (description.getAnnotation(HarnessStage::class.java) != null ||
            description.testClass?.getAnnotation(HarnessStage::class.java) != null) return
        val ins = InstrumentationRegistry.getInstrumentation()
        ins.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.STARTED, Stage.CREATED)
                .flatMap { monitor.getActivitiesInStage(it) }.forEach { it.finish() }
        }
        ins.waitForIdleSync()
        val context = ins.targetContext
        EditorDraftStore(context).clear()
        QuickDraftStore(context).clear()
        TaskDraftStore(context).clear("new")
        com.example.itinerary.data.NoteDraftStore(context).clearAll()
        // Every test opens Planner as if for the first time in the process: its alarms are set again (RescheduleOnOpen).
        com.example.itinerary.data.RescheduleOnOpen.forget()
        val app = context.applicationContext as ItineraryApp
        runBlocking {
            app.repository.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList()))
            app.calendarSync.clearAll() // Nextcloud calendars and their downloaded events
        }
        with(app.settings) {
            setAgendaTypes(AgendaType.entries.toSet()); setAgendaRange(AgendaRange.UPCOMING)
            setShowBillsSummary(true); setBillsExpanded(true); setCalendarCollapsed(false)
            setHiddenCategories(emptySet()); setSavedSearches(emptyList())
            lastViewCalendar = false; lastCalendarDate = null; lastCalendarMonth = null
        }
    }
}
