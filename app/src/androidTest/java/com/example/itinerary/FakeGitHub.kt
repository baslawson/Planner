package com.example.itinerary

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** GitHub's releases/latest for Planner over HTTPS, with Planner.apk and its .sha256 (as the real releases carry them).
 *  [version] null: no release (404). [badSha]: the checksum file doesn't match the APK. */
class FakeGitHub(@Volatile var version: String? = "v0.0.15", @Volatile var apk: ByteArray = ByteArray(300_000) { (it % 251).toByte() }) : Dispatcher() {
    val requests = CopyOnWriteArrayList<String>()
    @Volatile var badSha = false
    @Volatile var draft = false
    private val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
    val server = MockWebServer().also {
        it.dispatcher = this
        it.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        it.start()
    }
    val http: OkHttpClient = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build().let {
        OkHttpClient.Builder().sslSocketFactory(it.sslSocketFactory(), it.trustManager).build()
    }
    val base get() = server.url("/repos/baslawson/Planner/")
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
        requests += path
        val tag = version
        return when (path) {
            "/repos/baslawson/Planner/releases/latest" -> if (tag == null) MockResponse().setResponseCode(404) else MockResponse().setBody(JSONObject()
                .put("tag_name", tag).put("draft", draft).put("prerelease", false).put("html_url", "https://github.com/baslawson/Planner/releases/tag/$tag")
                .put("body", "## New\n- **Faster** sync\n- A fix")
                .put("assets", JSONArray()
                    .put(JSONObject().put("name", "LICENSE").put("browser_download_url", server.url("/dl/LICENSE").toString()))
                    .put(JSONObject().put("name", "Planner.apk").put("browser_download_url", server.url("/dl/Planner.apk").toString()))
                    .put(JSONObject().put("name", "Planner.apk.sha256").put("browser_download_url", server.url("/dl/Planner.apk.sha256").toString())))
                .toString())
            "/dl/Planner.apk" -> MockResponse().setBody(Buffer().write(apk))
            "/dl/Planner.apk.sha256" -> MockResponse().setBody((if (badSha) sha(apk + 1) else sha(apk)) + "  Planner.apk\n")
            else -> MockResponse().setResponseCode(404)
        }
    }
}
