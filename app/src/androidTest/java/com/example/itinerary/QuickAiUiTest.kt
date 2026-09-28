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
import com.example.itinerary.ui.fullLabel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Real application UI; run only with an external backup/restore harness. */
@Suppress("DEPRECATION")
class QuickAiUiTest {
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
        val dir=File(context.cacheDir,"qa-quick-ai-openai-evidence").apply { mkdirs() }
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
                    hideQuickTestKeyboard(ins)
                    // Gesture targets the foreground surface, never the Agenda or a nested text field's semantics.
                    if (tries % 20 == 0) forward = !forward
                    val metrics = context.resources.displayMetrics
                    val x = metrics.widthPixels * .65f
                    val from = metrics.heightPixels * (if (forward) .72f else .3f)
                    val to = metrics.heightPixels * (if (forward) .3f else .72f)
                    val down = SystemClock.uptimeMillis()
                    for (step in 0..12) {
                        val action = when (step) { 0 -> android.view.MotionEvent.ACTION_DOWN; 12 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
                        val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, from + (to - from) * step / 12, 0)
                        ins.uiAutomation.injectInputEvent(event, true);event.recycle();Thread.sleep(20)
                    }
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
        if (text in setOf("Add event", "Add bill", "Quick entry") && find(text)==null && find("Add menu")!=null) click("Add menu")
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
    private fun open(action:String?=null) {
        ins.startActivitySync(Intent(context,MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await {
            when(action) {
                EntryShortcuts.SCAN -> find("How would you like to save the scan?")!=null
                null -> find("Add menu")!=null || find("Discard")!=null
                else -> find("Discard")!=null
            }
        }
    }


    private fun start() {
        QuickDraftStore(context).clear()
        EditorDraftStore(context).clear()
        TaskDraftStore(context).clear("new")
        app.settings.lastViewCalendar=false
        app.settings.setAgendaRange(AgendaRange.ALL)
        app.settings.setAgendaTypes(AgendaType.entries.toSet())
        open();click("Quick entry")
    }

    private var originalPending=emptySet<String>()
    private var originalItems=emptySet<Long>()
    private var originalTasks=emptySet<String>()
    @org.junit.Before fun rememberOriginalIds() { originalPending=app.repository.pendingDeletions.value.map { it.token }.toSet();originalItems=data().items.map { it.id }.toSet();originalTasks=data().tasks.map { it.id }.toSet() }
    @org.junit.After fun removeTestAlarms()=runBlocking {
        app.repository.deleteEventsWithUndo(data().items.map { it.id }.toSet()-originalItems)
        data().tasks.filter { it.id !in originalTasks }.forEach { app.repository.deleteTask(it.id) }
        app.repository.pendingDeletions.value.filter { it.token !in originalPending }.forEach { app.repository.finishDeletion(it.token) }
    }

    private lateinit var server: okhttp3.mockwebserver.MockWebServer
    private lateinit var oldClient: QuickAiClient
    private lateinit var oldStore: QuickAiConnectionStore
    private var originalAiEnabled = true
    private val alias="planner.quick-ai.ui-qa"
    @org.junit.Before fun connectFakeAi() {
        originalAiEnabled = app.settings.aiFeaturesEnabled.value
        app.settings.setAiFeaturesEnabled(true)
        val cert=okhttp3.tls.HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCert=okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust=okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server=okhttp3.mockwebserver.MockWebServer();server.useHttps(serverCert.sslSocketFactory(),false);server.start()
        oldClient=app.quickAiClient;oldStore=app.quickAiConnectionStore
        app.quickAiClient=QuickAiClient(okhttp3.OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                .url(server.url(chain.request().url.encodedPath)).build()) }.build(), app.settings.aiFeaturesEnabled)
        app.quickAiConnectionStore=QuickAiConnectionStore(context,alias,"qa-quick-ai-ui-connection")
        app.quickAiConnectionStore.save(QuickAiConnection.create("test-key-"+"c".repeat(35),"gemini-test"))
    }
    @org.junit.After fun disconnectFakeAi() {
        app.settings.setAiFeaturesEnabled(originalAiEnabled)
        QuickAiProvider.entries.forEach { app.quickAiConnectionStore.clear(it) }
        File(context.noBackupFilesDir,"qa-quick-ai-ui-connection-provider").delete()
        app.quickAiConnectionStore=oldStore;app.quickAiClient=oldClient
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(alias) }
        server.shutdown()
    }
    private fun entry(raw:String,title:String="QA AI gym",task:Boolean=false)=QuickAiEntry(raw,task,title,"2030-01-02",if(task)null else "15:00",if(task)null else 60,"",null,RepeatRule.NONE,null)
    private fun response(entries:List<QuickAiEntry>,delay:Long=0) {
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(geminiReply(org.json.JSONObject().put("status","ready").put("message","")
            .put("entries",org.json.JSONArray().apply { entries.forEach { put(it.json()) } })).toString()).setBodyDelay(delay,java.util.concurrent.TimeUnit.MILLISECONDS))
    }
    @Test fun aiSinglePreviewEditsPersistAndSaveOnlyOnExplicitAdd() {
        start();val raw="QA exercise after lunch tomorrow";setText("",raw)
        Thread.sleep(500);assertEquals(0,server.requestCount)
        response(listOf(entry(raw)));click("Understand with AI")
        await { find("Entry title")!=null };screenshot("single-ai-preview")
        assertTrue(data().items.none { it.title=="QA AI gym" })
        setText("QA AI gym","QA corrected AI gym");click("Duration, for 60 min");click("45 min");click("Set duration")
        click("Close");click("Quick entry")
        await { find("QA corrected AI gym")!=null };click("Add event")
        if(find("Add anyway")!=null)click("Add anyway")
        await { data().items.any { it.title=="QA corrected AI gym" } }
        val saved=data().items.single { it.title=="QA corrected AI gym" }
        assertEquals(45,saved.durationMinutes);assertEquals(java.time.LocalTime.of(15,0),saved.startTime)
        assertEquals(1,server.requestCount)
    }
    @Test fun editedTextAndCancellationRejectLateResponses() {
        start();val raw="QA old tomorrow after lunch";setText("",raw)
        response(listOf(entry(raw)),1800);click("Understand with AI")
        await { server.requestCount==1 };setText(raw,"QA new tomorrow 4pm")
        Thread.sleep(2300);assertNull(find("Entry title"))
        assertTrue(nodes().any { it.isEditable && it.text?.toString()=="QA new tomorrow 4pm" })
        response(listOf(entry("QA new tomorrow 4pm")),1800);click("Understand with AI")
        await { server.requestCount==2 };click("Cancel AI");Thread.sleep(2300)
        assertNull(find("Entry title"));assertTrue(data().items.none { it.title=="QA AI gym" });screenshot("cancelled-ai")
    }
    @Test fun clarificationIsExplicitAndAnswersDoNotSave() {
        start();val raw="QA meet tomorrow after lunch";setText("",raw)
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(geminiReply(org.json.JSONObject("{\"status\":\"clarify\",\"message\":\"What time after lunch?\",\"entries\":[]}")).toString()))
        click("Understand with AI");await { find("Your clarification")!=null };screenshot("ai-clarification")
        assertEquals(1,server.requestCount);setText("","3pm")
        response(listOf(entry(raw)));click("Send answer");await { find("Entry title")!=null }
        assertTrue(data().items.none { it.title=="QA AI gym" })
        server.takeRequest()
        val request=geminiContext(server.takeRequest().body.readUtf8())
        assertEquals("3pm",request.getJSONArray("answers").getJSONObject(0).getString("answer"))
    }
    @Test fun networkFailureKeepsDraftAndOfflineSaveAvailable() {
        start();val raw="QA offline after AI tomorrow 4pm";setText("",raw)
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(429).setBody("quota"))
        click("Understand with AI")
        await { find("AI usage limit reached. Try later or continue offline.")!=null }
        assertTrue(nodes().any { it.isEditable && it.text?.toString()==raw });screenshot("ai-offline-fallback")
        click("Close");click("Quick entry");click("Add event")
        if(find("Add anyway")!=null)click("Add anyway")
        await { data().items.any { it.title=="QA offline after AI" } }
        assertEquals(1,server.requestCount)
    }
    @Test fun closingCannotApplyPendingAi() {
        start();val raw="QA close tomorrow 4pm";setText("",raw)
        response(listOf(entry(raw)),1500);click("Understand with AI");await { server.requestCount==1 }
        click("Close");click("Quick entry");Thread.sleep(1800)
        assertNull(find("Entry title"));assertTrue(nodes().any { it.isEditable && it.text?.toString()==raw })
    }
    @Test fun personalKeySetupUsesOnlyMasterSwitchAndCanReplaceKeyWithoutSending() {
        start();setText("","QA private text tomorrow");click("AI provider: Gemini")
        await { find("More below")!=null };assertNull(find("More above"));screenshot("ai-settings-scroll-top")
        click("More below");await { find("More above")!=null }
        screenshot("ai-settings-scroll-down")
        click("More above");await { find("More below")!=null };click("Done")
        app.quickAiConnectionStore.clear();start();setText("","QA private text tomorrow")
        click("Set up AI assistance");setText("","test-key-"+"c".repeat(35))
        click("Save AI settings");await { app.quickAiConnectionStore.load()!=null }
        assertNull(find("Allow AI assistance"));assertEquals(0,server.requestCount)
        click("Remove API key");await { app.quickAiConnectionStore.load()==null }
        setText("","test-key-"+"d".repeat(35));click("Save AI settings")
        await { app.quickAiConnectionStore.load()!=null };assertNull(find("Allow AI assistance"))
        screenshot("ai-settings")
        click("Done");await { find("Understand with AI")!=null }
        assertEquals(0,server.requestCount)
        assertNull(find("Pair phone"));assertNull(find("AI server HTTPS address"))
    }
    @Test fun openAiSwitchUsesExistingPreviewAndKeepsGeminiSettings() {
        app.quickAiConnectionStore.save(QuickAiConnection.create("sk-test-"+"o".repeat(35), "gpt-4.1-mini", QuickAiProvider.OPENAI))
        start();val raw="QA exercise tomorrow at 3pm";setText("",raw)
        click("AI provider: Gemini");click("OpenAI")
        await { find("Use your own OpenAI key") != null }
        screenshot("openai-settings");click("Done")
        await { find("AI provider: OpenAI") != null }
        assertEquals(0,server.requestCount)
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(openAiReply(org.json.JSONObject().put("status","ready").put("message","")
            .put("entries",org.json.JSONArray().put(entry(raw).json()))).toString()))
        click("Understand with AI");await { find("Entry title") != null }
        screenshot("openai-preview")
        assertTrue(data().items.none { it.title == "QA AI gym" })
        setText("QA AI gym","QA OpenAI corrected");click("Add event")
        if(find("Add anyway")!=null)click("Add anyway")
        await { data().items.any { it.title == "QA OpenAI corrected" } }
        assertEquals("/v1/responses",server.takeRequest().path)
        assertEquals("gemini-test",app.quickAiConnectionStore.load(QuickAiProvider.GEMINI)!!.model)
        assertNotNull(app.quickAiConnectionStore.load(QuickAiProvider.GEMINI))
    }

    @Test fun masterSwitchInSettingsHidesAiAndKeepsKeys() {
        start();click("Close");click("More options");click("Settings")
        click("Enable AI features")
        await { !app.settings.aiFeaturesEnabled.value }
        assertFalse(SettingsRepository(context).aiFeaturesEnabled.value)
        assertNull(find("AI assistance"));screenshot("master-off-settings")
        click("Save");click("Quick entry")
        setText("","QA offline only tomorrow 3pm")
        assertNull(find("Understand with AI"));assertNull(find("Set up AI assistance"));assertNull(find("AI provider: Gemini"))
        screenshot("master-off-single")
        click("Add event")
        if(find("Add anyway")!=null)click("Add anyway")
        await { data().items.any { it.title == "QA offline only" } }
        assertNotNull(app.quickAiConnectionStore.load())
        assertEquals(0,server.requestCount)
        app.settings.setAiFeaturesEnabled(true);click("Quick entry")
        await { find("Understand with AI") != null }
    }
    @Test fun masterOffCancelsPendingRequestAndRemovesClarification() {
        start();val raw="QA master tomorrow 3pm";setText("",raw)
        response(listOf(entry(raw)),1500);click("Understand with AI");await { server.requestCount==1 }
        ins.runOnMainSync { app.settings.setAiFeaturesEnabled(false) }
        Thread.sleep(1800)
        assertNull(find("Entry title"));assertNull(find("Understand with AI"));assertNull(find("Cancel AI"))
        assertNull(QuickDraftStore(context).read()!!.single.ai)
        ins.runOnMainSync { app.settings.setAiFeaturesEnabled(true) }
        await { find("Understand with AI") != null }
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(geminiReply(org.json.JSONObject().put("status","clarify")
            .put("message","Which time?").put("entries",org.json.JSONArray())).toString()))
        click("Understand with AI");await { find("Which time?") != null }
        ins.runOnMainSync { app.settings.setAiFeaturesEnabled(false) }
        await { find("Which time?") == null }
        assertNull(find("Send answer"));assertEquals(2,server.requestCount)
    }
    @Test fun masterOffKeepsExistingPreviewCorrectionsWithoutAiPrompts() {
        start();val raw="QA preserved tomorrow 3pm";setText("",raw)
        response(listOf(entry(raw)));click("Understand with AI");await { find("Entry title") != null }
        setText("QA AI gym","QA retained correction")
        ins.runOnMainSync { app.settings.setAiFeaturesEnabled(false) }
        await { find("Use offline interpretation") == null }
        assertNotNull(find("QA retained correction"))
        click("Close");click("Quick entry")
        await { find("QA retained correction") != null }
        assertNull(find("Use offline interpretation"));assertNull(find("Understand with AI"))
        click("Add event");if(find("Add anyway")!=null)click("Add anyway")
        await { data().items.any { it.title=="QA retained correction" } }
        assertEquals(1,server.requestCount)
    }

}
