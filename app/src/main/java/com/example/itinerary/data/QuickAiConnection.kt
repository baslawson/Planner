package com.example.itinerary.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class QuickAiException(message: String) : IOException(message)

enum class QuickAiProvider(val label: String, val defaultModel: String, val keyUrl: String) {
    GEMINI("Gemini", "gemini-3.5-flash-lite", "https://aistudio.google.com/apikey"),
    OPENAI("OpenAI", "gpt-4.1-mini", "https://platform.openai.com/api-keys")
}

// No generated toString: the personal API key must never appear in logs.
class QuickAiConnection private constructor(internal val apiKey: String, val model: String, val provider: QuickAiProvider) {
    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        fun create(apiKey: String, model: String = DEFAULT_MODEL, provider: QuickAiProvider = QuickAiProvider.GEMINI): QuickAiConnection {
            val key = apiKey.trim(); val name = model.trim()
            if (key.length !in 20..2048 || key.any { it.code !in 33..126 })
                throw QuickAiException("Enter a valid ${provider.label} API key.")
            if (!name.matches(Regex(if (provider == QuickAiProvider.GEMINI) "gemini-[A-Za-z0-9._-]{1,100}" else "[A-Za-z0-9][A-Za-z0-9._-]{0,109}")))
                throw QuickAiException("Enter a ${provider.label} model ID, such as ${provider.defaultModel}.")
            return QuickAiConnection(key, name, provider)
        }
    }
}

/** Provider keys/models are device-local, encrypted and excluded from backups. IO dispatcher only. */
class QuickAiConnectionStore(context: Context, private val alias: String = "planner.gemini.v1", fileName: String = "gemini-key") {
    private val files = QuickAiProvider.entries.associateWith { provider ->
        AtomicFile(File(context.noBackupFilesDir, fileName + if (provider == QuickAiProvider.GEMINI) "" else "-openai"))
    }
    private val selection = AtomicFile(File(context.noBackupFilesDir, "$fileName-provider"))
    fun selected(): QuickAiProvider = synchronized(lock) {
        if (!selection.baseFile.exists()) QuickAiProvider.GEMINI
        else try { QuickAiProvider.valueOf(selection.openRead().bufferedReader().use { it.readText() }) }
        catch (_: Exception) { throw QuickAiException("Couldn't read the AI provider. Select a provider in AI settings.") }
    }
    fun select(provider: QuickAiProvider) = synchronized(lock) {
        val out = selection.startWrite()
        try { out.write(provider.name.toByteArray()); selection.finishWrite(out) }
        catch (e: Exception) { selection.failWrite(out); throw e }
    }
    private val oldFile = if (fileName == "gemini-key" && alias == "planner.gemini.v1")
        AtomicFile(File(context.noBackupFilesDir, "quick-ai-connection")) else null
    private var retired = false
    private fun retireOldAccess() {
        if (oldFile == null || retired) return
        oldFile.delete()
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias("planner.quick-ai.v1")) deleteEntry("planner.quick-ai.v1")
        }
        retired = true
    }
    internal fun retireObsoleteAccess() = synchronized(lock) { retireOldAccess() }
    fun load(provider: QuickAiProvider = selected()): QuickAiConnection? = synchronized(lock) {
        val file = files.getValue(provider)
        try {
            retireOldAccess()
            if (!file.baseFile.exists()) return null
            val bytes = file.openRead().use { it.readBytes() }; check(bytes.size > 28)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val j = JSONObject(String(c.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            // Legacy provider opt-in is intentionally ignored; the master switch is the sole permission.
            QuickAiConnection.create(j.getString("apiKey"), j.getString("model"), provider)
        } catch (_: Exception) { throw QuickAiException("Couldn't unlock the ${provider.label} key. Remove it and enter your key again.") }
    }
    fun save(value: QuickAiConnection) = synchronized(lock) {
        val file = files.getValue(value.provider)
        try {
            retireOldAccess()
            val j = JSONObject().put("apiKey", value.apiKey).put("model", value.model)
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key(true))
            val bytes = c.iv + c.doFinal(j.toString().toByteArray(Charsets.UTF_8))
            val out = file.startWrite()
            try { out.write(bytes); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
        } catch (_: Exception) { throw QuickAiException("Couldn't securely save the AI connection on this phone.") }
    }
    fun clear(provider: QuickAiProvider = selected()) = synchronized(lock) { files.getValue(provider).delete(); retireOldAccess() }
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }; check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    companion object { private val lock = Any() }
}

class QuickAiClient(http: OkHttpClient = OkHttpClient(), private val featuresEnabled: StateFlow<Boolean> = MutableStateFlow(true)) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS).callTimeout(50, TimeUnit.SECONDS).build()

    suspend fun understand(connection: QuickAiConnection, input: QuickInput, answers: List<Pair<String,String>>): QuickAiResult {
        if (!featuresEnabled.value) throw QuickAiException("AI features are turned off.")
        return coroutineScope {
            val requestScope = this
            val monitor = launch(start = CoroutineStart.UNDISPATCHED) {
                featuresEnabled.first { !it }
                requestScope.cancel(CancellationException("AI features turned off"))
            }
            try { understandAllowed(connection, input, answers) }
            finally { monitor.cancel() }
        }
    }

    private suspend fun understandAllowed(connection: QuickAiConnection, input: QuickInput, answers: List<Pair<String,String>>): QuickAiResult {
        return try {
            when (connection.provider) {
                QuickAiProvider.GEMINI -> QuickGeminiProtocol.result(post(connection, QuickGeminiProtocol.request(input, answers)), input)
                QuickAiProvider.OPENAI -> QuickOpenAiProtocol.result(post(connection, QuickOpenAiProtocol.request(connection.model, input, answers)), input)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: QuickAiException) { throw e }
        catch (_: Exception) { throw QuickAiException("${connection.provider.label} returned an invalid interpretation. Your draft is unchanged.") }
    }

    private suspend fun post(connection: QuickAiConnection, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        if (!featuresEnabled.value) throw QuickAiException("AI features are turned off.")
        // This destination is fixed. Keys never appear in URLs or follow redirects.
        val builder = when (connection.provider) {
            QuickAiProvider.GEMINI -> Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/${connection.model}:generateContent")
                .header("x-goog-api-key", connection.apiKey)
            QuickAiProvider.OPENAI -> Request.Builder().url("https://api.openai.com/v1/responses")
                .header("Authorization", "Bearer ${connection.apiKey}")
        }
        val request = builder.post(body.toString().toRequestBody("application/json".toMediaType())).build()
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(QuickAiException("Couldn't reach AI. Check your connection and retry, or continue offline."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val source = it.body?.source() ?: throw QuickAiException("AI returned an empty response.")
                            val bytes = source.readByteArrayBounded(262144)
                            if (!it.isSuccessful) throw QuickAiException(when (it.code) {
                                400, 401, 403 -> "${connection.provider.label} rejected the request. Check your API key, model and API access in AI settings."
                                404 -> "This ${connection.provider.label} model isn't available for your key. Change the model in AI settings."
                                429 -> "AI usage limit reached. Try later or continue offline."
                                else -> "AI couldn't finish this request. Your draft is unchanged; retry or continue offline."
                            })
                            JSONObject(String(bytes, Charsets.UTF_8))
                        }
                        if (!continuation.isCancelled) continuation.resume(result)
                    } catch (e: Exception) {
                        if (!continuation.isCancelled) continuation.resumeWithException((e as? QuickAiException) ?: QuickAiException("AI couldn't finish this request. Check the connection or retry; your draft is unchanged."))
                    }
                }
            })
        }
    }
}

private fun okio.BufferedSource.readByteArrayBounded(limit: Long): ByteArray {
    val buffer = okio.Buffer()
    while (buffer.size <= limit) { if (read(buffer, minOf(8192, limit + 1 - buffer.size)) == -1L) break }
    if (buffer.size > limit) throw QuickAiException("AI response too large.")
    return buffer.readByteArray()
}
