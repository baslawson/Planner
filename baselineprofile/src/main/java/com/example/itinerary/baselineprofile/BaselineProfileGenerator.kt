package com.example.itinerary.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A cold start, then the screens most people open first: the Agenda, the calendar view, the ⋮ menu and Notes. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    // Compose redraws the screen often, so a button found a moment ago can be replaced before the tap: find it again.
    private fun MacrobenchmarkScope.tap(selector: BySelector) {
        repeat(5) {
            try { device.wait(Until.findObject(selector), 5_000)?.click() ?: return; device.waitForIdle(); return }
            catch (_: StaleObjectException) { Thread.sleep(300) }
        }
    }

    @Test fun startAndFirstScreens() = rule.collect(packageName = "io.github.baslawson.planner.benchmark", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("AGENDA")), 10_000)
        tap(By.desc("Switch to Calendar view"))
        tap(By.desc("Switch to Agenda view"))
        tap(By.desc("More options"))
        tap(By.text("Notes"))
        device.wait(Until.hasObject(By.text("Search notes")), 5_000)
        device.pressBack(); device.waitForIdle()
    }
}
