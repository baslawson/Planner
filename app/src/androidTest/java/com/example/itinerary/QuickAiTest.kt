package com.example.itinerary

import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.*
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.security.KeyStore
import java.io.File

internal fun geminiReply(value: JSONObject): JSONObject = JSONObject().put("candidates", org.json.JSONArray().put(
    JSONObject().put("finishReason", "STOP").put("content", JSONObject().put("parts",
        org.json.JSONArray().put(JSONObject().put("text", value.toString()))))))
internal fun geminiContext(body: String): JSONObject = JSONObject(JSONObject(body).getJSONArray("contents")
    .getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))

internal fun openAiReply(value: JSONObject): JSONObject = JSONObject().put("status", "completed")
    .put("output", org.json.JSONArray().put(JSONObject().put("type", "message").put("role", "assistant")
        .put("status", "completed").put("content", org.json.JSONArray().put(JSONObject().put("type", "output_text").put("text", value.toString())))))

class QuickAiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var client: QuickAiClient
    private val master = kotlinx.coroutines.flow.MutableStateFlow(true)
    private val source = "QA gym tomorrow at 3pm"
    private val entry get() = QuickAiEntry(source, false, "QA gym", "2030-01-02", "15:00", 60, "Park", 30, RepeatRule.WEEKLY, 3)
    private fun result() = JSONObject().put("status", "ready").put("message", "").put("entries", org.json.JSONArray().put(entry.json()))
    private fun connection() = QuickAiConnection.create("test-key-" + "c".repeat(35), "gemini-test")
    private val input get() = QuickInput(source, baseDate = LocalDate.of(2030,1,1))
    private fun enqueueResult(j: JSONObject = result()) {
        server.enqueue(MockResponse().setBody(geminiReply(j).toString()))
    }
    @Before fun start() {
        val cert=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCert=HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server=MockWebServer();server.useHttps(serverCert.sslSocketFactory(),false);server.start()
        client=QuickAiClient(OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager)
            .addInterceptor { chain ->
                assertEquals(if (chain.request().header("Authorization") != null) "api.openai.com" else "generativelanguage.googleapis.com", chain.request().url.host)
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build(), master)
    }
    @After fun stop() { server.shutdown() }
    @Test fun explicitRequestSendsOnlyAllowedContextAndPersonalKey()=runBlocking {
        enqueueResult()
        val value=client.understand(connection(),input.copy(dateOverride="2031-02-03"),emptyList())
        assertEquals("QA gym",value.entries.single().title)
        val request=server.takeRequest();assertEquals(connection().apiKey,request.getHeader("x-goog-api-key"));assertNull(request.getHeader("Authorization"))
        assertEquals("/v1beta/models/gemini-test:generateContent",request.path)
        val raw=request.body.readUtf8();assertFalse(raw.contains(connection().apiKey));val body=geminiContext(raw)
        assertEquals(setOf("text","reference_date","timezone","single_kind","answers"),body.keys().asSequence().toSet())
        assertEquals("2030-01-01",body.getString("reference_date"))
        assertEquals(source,body.getString("text"))
    }
    @Test fun masterOffBlocksSavedKeyAndInvalidKeysOrModelsAreRejected()=runBlocking {
        for (model in listOf("https://example.com", "gemini-test?key=x", "gemini-test/other", ""))
            assertTrue(runCatching { QuickAiConnection.create(connection().apiKey,model) }.isFailure)
        for (key in listOf("", "short", "line\nbreak".repeat(5)))
            assertTrue(runCatching { QuickAiConnection.create(key) }.isFailure)
        val saved=QuickAiConnection.create(connection().apiKey,"gemini-test")
        master.value = false
        assertTrue(runCatching { client.understand(saved,input,emptyList()) }.isFailure)
        assertEquals(0,server.requestCount)
    }
    @Test fun redirectDoesNotForwardCredentialAndQuotaErrorsAreUseful()=runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",server.url("/stolen")))
        assertTrue(runCatching { client.understand(connection(),input,emptyList()) }.isFailure)
        assertEquals(1,server.requestCount)
        server.enqueue(MockResponse().setResponseCode(429).setBody("secret internal error"))
        val error=runCatching { client.understand(connection(),input,emptyList()) }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("limit"));assertFalse(error.message!!.contains("secret"))
    }
    @Test fun cancelledRequestCannotReturnAnInterpretation()=runBlocking {
        server.enqueue(MockResponse().setBody(geminiReply(result()).toString()).setBodyDelay(2,TimeUnit.SECONDS))
        var applied=false
        val job=launch { client.understand(connection(),input,emptyList());applied=true }
        withContext(Dispatchers.IO) { server.takeRequest(3,TimeUnit.SECONDS) }
        job.cancelAndJoin();assertFalse(applied)
    }
    @Test fun malformedModelOutputNeverBecomesAPreview() {
        for ((key,value) in listOf("duration" to true,"duration" to 0,"date" to "0000-01-01","time" to "25:00","repeat" to "EVERY_3_WEEKS","reminder" to -1,"count" to 999,"source" to "invented")) {
            val bad=result();bad.getJSONArray("entries").getJSONObject(0).put(key,value)
            assertTrue(key,runCatching { QuickAiResult.decode(bad,source,false) }.isFailure)
        }
        val duplicate=result();duplicate.getJSONArray("entries").put(entry.json())
        assertTrue(runCatching { QuickAiResult.decode(duplicate,source,false) }.isFailure)
    }
    @Test fun clarificationPreservesContextAndNeverIncludesEntries()=runBlocking {
        val question=JSONObject().put("status","clarify").put("message","What time after lunch?").put("entries",org.json.JSONArray())
        enqueueResult(question)
        assertEquals("clarify",client.understand(connection(),input,listOf("Which day?" to "Tomorrow")).status)
        val body=geminiContext(server.takeRequest().body.readUtf8())
        assertEquals("Tomorrow",body.getJSONArray("answers").getJSONObject(0).getString("answer"))
        question.getJSONArray("entries").put(entry.json())
        assertTrue(runCatching { QuickAiResult.decode(question,source,false) }.isFailure)
    }
    @Test fun blockedTruncatedOrToolResponsesAreRejected() {
        val blocked=JSONObject().put("promptFeedback",JSONObject().put("blockReason","SAFETY"))
        assertTrue(runCatching { QuickGeminiProtocol.result(blocked, input) }.isFailure)
        val truncated=geminiReply(result());truncated.getJSONArray("candidates").getJSONObject(0).put("finishReason","MAX_TOKENS")
        assertTrue(runCatching { QuickGeminiProtocol.result(truncated, input) }.isFailure)
        val tool=geminiReply(result());tool.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .put("parts",org.json.JSONArray().put(JSONObject().put("functionCall",JSONObject())))
        assertTrue(runCatching { QuickGeminiProtocol.result(tool, input) }.isFailure)
    }
    @Test fun secureConnectionRoundTripsWithoutPlaintextAndCanBeRemoved() {
        val alias="planner.quick-ai.qa";val name="qa-quick-ai-connection"
        val store=QuickAiConnectionStore(context,alias,name)
        try {
            assertNull(store.load());store.save(connection())
            assertNotNull(store.load());assertEquals(connection().apiKey,store.load()!!.apiKey)
            val bytes=File(context.noBackupFilesDir,name).readBytes()
            assertFalse(String(bytes).contains(connection().apiKey))
            File(context.noBackupFilesDir,name).writeBytes(byteArrayOf(1,2,3))
            assertTrue(runCatching { store.load() }.isFailure)
            store.clear();assertNull(store.load())
        } finally { store.clear();KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(alias) } }
    }
    @Test fun obsoleteAccessIsDeletedWithoutChangingPersonalKey() {
        val old = File(context.noBackupFilesDir,"quick-ai-connection")
        val current = File(context.noBackupFilesDir,"gemini-key")
        val original = current.takeIf { it.exists() }?.readBytes()
        assertFalse(old.exists())
        try {
            old.writeText("obsolete encrypted connection fixture")
            val store = QuickAiConnectionStore(context)
            store.retireObsoleteAccess()
            assertFalse(old.exists())
            if (original == null) assertFalse(current.exists()) else assertArrayEquals(original, current.readBytes())
        } finally { old.delete() }
    }
    @Test fun aiDraftRetainsCorrectionsAndEditableFieldsAcrossRestart() {
        val store=QuickDraftStore(context)
        val original=runCatching { store.read() }.getOrNull()
        try {
            val edited=input.copy(dateOverride="2031-02-03",timeOverride="16:00",durationText="45",countText="2",removeReminder=true).copy(ai=entry.copy(title="Corrected title"),literals=emptyList())
            val draft=QuickDraft(edited)
            store.write(draft);assertEquals(draft,QuickDraftStore(context).read())
            val s=edited.suggestion();assertEquals(LocalDate.of(2031,2,3),s.date);assertEquals("16:00",s.time.toString())
            assertEquals(45,s.durationMinutes);assertEquals(2,s.repeatCount);assertNull(s.reminderMinutes)
            assertEquals("Corrected title",s.title)
            assertNull(edited.edited(source.replace("QA gym","QA swimming")).ai)
            store.write(draft.copy(single=edited.copy(ai=entry.copy(title=""))))
            assertEquals("Add a title.",store.read()!!.single.suggestion().quickProblem(false,java.time.ZonedDateTime.now()))
        } finally { if(original==null)store.clear() else store.write(original) }
    }
    private fun openAiConnection() = QuickAiConnection.create("sk-test-" + "o".repeat(35), "gpt-4.1-mini", QuickAiProvider.OPENAI)
    @Test fun openAiUsesFixedEndpointSeparateAuthorizationAndSharedContract() = runBlocking {
        server.enqueue(MockResponse().setBody(openAiReply(result()).toString()))
        assertEquals("QA gym", client.understand(openAiConnection(), input, emptyList()).entries.single().title)
        val request = server.takeRequest()
        assertEquals("/v1/responses", request.path)
        assertEquals("Bearer " + openAiConnection().apiKey, request.getHeader("Authorization"))
        assertNull(request.getHeader("x-goog-api-key"))
        val body = JSONObject(request.body.readUtf8())
        assertFalse(body.getBoolean("store")); assertEquals("gpt-4.1-mini", body.getString("model"))
        assertEquals(QuickAiContract.context(input, emptyList()).toString(), body.getString("input"))
        assertTrue(body.getJSONObject("text").getJSONObject("format").getBoolean("strict"))
        assertEquals(QuickAiContract.schema.let { JSONObject(it).toString() }, body.getJSONObject("text").getJSONObject("format").getJSONObject("schema").toString())
        assertFalse(body.toString().contains(openAiConnection().apiKey))
    }
    @Test fun openAiRefusalIncompleteAndToolsNeverBecomePreview() {
        val incomplete = openAiReply(result()).put("status", "incomplete")
        val refusal = openAiReply(result()).apply { getJSONArray("output").getJSONObject(0)
            .put("content", org.json.JSONArray().put(JSONObject().put("type", "refusal").put("refusal", "private provider text"))) }
        val tool = openAiReply(result()).apply { getJSONArray("output").put(JSONObject().put("type", "function_call")) }
        for (body in listOf(incomplete, refusal, tool, openAiReply(result()).put("error", JSONObject())))
            assertTrue(runCatching { QuickOpenAiProtocol.result(body, input) }.isFailure)
    }
    @Test fun providerProfilesSurviveSwitchesAndRemovalWithoutFallback() {
        val name = "qa-openai-profiles"; val alias = "planner.openai.qa"
        val store = QuickAiConnectionStore(context, alias, name)
        try {
            store.save(connection()) // Existing Gemini encrypted format remains readable.
            store.select(QuickAiProvider.OPENAI); assertNull(store.load())
            store.save(openAiConnection()); assertNotNull(store.load())
            store.select(QuickAiProvider.GEMINI); assertEquals(connection().apiKey, store.load()!!.apiKey)
            store.select(QuickAiProvider.OPENAI)
            val reopened = QuickAiConnectionStore(context, alias, name)
            assertEquals(QuickAiProvider.OPENAI, reopened.selected())
            assertEquals(openAiConnection().apiKey, reopened.load()!!.apiKey)
            assertFalse(String(File(context.noBackupFilesDir, "$name-openai").readBytes()).contains(openAiConnection().apiKey))
            reopened.clear(); assertNull(reopened.load())
            assertNotNull(reopened.load(QuickAiProvider.GEMINI))
        } finally {
            QuickAiProvider.entries.forEach { store.clear(it) }
            File(context.noBackupFilesDir, "$name-provider").delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
    @Test fun openAiRedirectAndUnauthorizedErrorsNeverExposeKey() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/stolen")))
        assertTrue(runCatching { client.understand(openAiConnection(), input, emptyList()) }.isFailure)
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(401).setBody(openAiConnection().apiKey))
        val error = runCatching { client.understand(openAiConnection(), input, emptyList()) }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("OpenAI")); assertFalse(error.message!!.contains(openAiConnection().apiKey))
    }

    @Test fun masterGateBlocksBothProvidersAndCancelsAnActiveRequest() = runBlocking {
        master.value = false
        for (value in listOf(connection(), openAiConnection()))
            assertTrue(runCatching { client.understand(value,input,emptyList()) }.exceptionOrNull() is QuickAiException)
        assertEquals(0,server.requestCount)
        master.value = true
        server.enqueue(MockResponse().setBody(geminiReply(result()).toString()).setBodyDelay(2,TimeUnit.SECONDS))
        var applied = false
        val job = launch { client.understand(connection(),input,emptyList()); applied = true }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3,TimeUnit.SECONDS)) }
        master.value = false;job.join()
        assertTrue(job.isCancelled);assertFalse(applied)
    }

    @Test fun legacyProviderOptInsDoNotOverrideMasterSwitch() = runBlocking {
        val name = "qa-legacy-ai-profile"; val alias = "planner.legacy-ai.qa"
        val store = QuickAiConnectionStore(context,alias,name)
        try {
            store.save(connection())
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for (oldEnabled in listOf(false,true)) {
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,keyStore.getKey(alias,null))
                val legacy = JSONObject().put("apiKey",connection().apiKey).put("model","gemini-test").put("enabled",oldEnabled)
                File(context.noBackupFilesDir,name).writeBytes(cipher.iv + cipher.doFinal(legacy.toString().toByteArray()))
                val loaded = store.load()!!
                master.value = true;enqueueResult()
                assertEquals("ready",client.understand(loaded,input,emptyList()).status)
                master.value = false
                assertTrue(runCatching { client.understand(loaded,input,emptyList()) }.isFailure)
            }
            assertEquals(2,server.requestCount)
        } finally { store.clear();KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(alias) } }
    }

}
