package com.example.itinerary

import com.example.itinerary.reminders.AlarmLedger
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// E12: the alarms this phone has set (AlarmLedger) stay out of Android's backup and device transfer, on every Android
// version Planner runs on; so do the support pop-up's "already shown" and the update download (A5-2, 4 Oct). Nothing
// else is left out (only exclusions: everything else is backed up as before).
class BackupRulesTest {
    private val res = File("src/main/res/xml")
    private fun xml(name: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, name)).documentElement
    private fun org.w3c.dom.Element.children() = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>()
    private fun org.w3c.dom.Element.rules() = children().map { Triple(it.tagName, it.getAttribute("domain"), it.getAttribute("path")) }
    private val ledger = Triple("exclude", "sharedpref", "${AlarmLedger.PREFS}.xml")
    // A6-9: also how far ahead this phone set its alarms, and App lock (this phone's choice). Which reminders already
    // rang (delivered_alarms) and their zone (reminder_zone) go with the restored data on purpose.
    private val leftOut = listOf(ledger, Triple("exclude", "sharedpref", "alarm_window.xml"),
        Triple("exclude", "sharedpref", "${com.example.itinerary.ui.AppLock.PREFS}.xml"),
        Triple("exclude", "sharedpref", "${com.example.itinerary.ui.SupportPrompt.PREFS}.xml"),
        Triple("exclude", "sharedpref", "updates.xml"))

    @Test fun theManifestUsesBothRuleFiles() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:allowBackup=\"true\""))
    }

    @Test fun pendingAlarmsAreLeftOutOfBackupsBeforeAndroid12() {
        val rules = xml("backup_rules.xml")
        assertEquals("full-backup-content", rules.tagName)
        assertEquals(leftOut, rules.rules())
    }

    @Test fun pendingAlarmsAreLeftOutOfCloudBackupAndDeviceTransferFromAndroid12() {
        val rules = xml("data_extraction_rules.xml")
        assertEquals("data-extraction-rules", rules.tagName)
        assertEquals(listOf("cloud-backup", "device-transfer"), rules.children().map { it.tagName })
        rules.children().forEach { assertEquals(it.tagName, leftOut, it.rules()) }
    }
}
