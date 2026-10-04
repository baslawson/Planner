package com.example.itinerary.data

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// S6-1: a backup upload or download can be cancelled at once and is stopped after its overall limit, leaving nothing
// half-written. Against a local HTTPS server on this machine.
class BackupTransferTest {
    private lateinit var server: MockWebServer
    private lateinit var trusted: OkHttpClient
    private lateinit var account: NextcloudAccount
    private lateinit var folder: File
    private val bytes = ByteArray(64 * 1024) { (it * 7).toByte() }

    @Before fun setUp() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.start()
        val client = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        trusted = OkHttpClient.Builder().sslSocketFactory(client.sslSocketFactory(), client.trustManager).build()
        account = NextcloudAccount.create(server.url("/").toString(), "bas", "test-password-only")
        folder = Files.createTempDirectory("backup-transfer").toFile()
    }

    @After fun tearDown() {
        server.shutdown()
        folder.deleteRecursively()
    }

    // About 6.4 s for the whole file.
    private fun slowBody() = MockResponse().setBody(Buffer().write(bytes)).throttleBody(1024, 100, TimeUnit.MILLISECONDS)

    @Test fun cancelStopsADownloadAtOnceAndLeavesNoFile() {
        server.enqueue(slowBody())
        val target = File(folder, "download.zip")
        val transfer = BackupTransfer()
        var failure: Exception? = null
        val started = System.nanoTime()
        val worker = thread {
            try { NextcloudClient(trusted).download(account, NextcloudBackup("Planner-backup-x.zip", null, null, null), target, transfer) }
            catch (e: Exception) { failure = e }
        }
        Thread.sleep(500)
        transfer.cancel()
        worker.join(5_000)
        assertFalse("still running", worker.isAlive)
        assertTrue(failure is TransferCancelledException)
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(4))
        assertFalse(target.exists())
    }

    @Test fun cancelledBeforeItStartsNothingIsSent() {
        val transfer = BackupTransfer().apply { cancel() }
        val archive = File(folder, "upload.zip").apply { writeBytes(bytes) }
        assertThrows(TransferCancelledException::class.java) { NextcloudClient(trusted).upload(account, archive, transfer) }
        val target = File(folder, "download.zip")
        assertThrows(TransferCancelledException::class.java) {
            NextcloudClient(trusted).download(account, NextcloudBackup("Planner-backup-x.zip", null, null, null), target, transfer)
        }
        assertEquals(0, server.requestCount)
        assertFalse(target.exists())
    }

    @Test fun aTransferOverItsLimitIsStoppedAndSaysSo() {
        server.enqueue(slowBody())
        val target = File(folder, "download.zip")
        val e = assertThrows(BackupException::class.java) {
            NextcloudClient(trusted, transferTimeoutMs = 700).download(account, NextcloudBackup("Planner-backup-x.zip", null, null, null), target)
        }
        assertEquals(NextcloudClient.TOO_LONG, e.message)
        assertFalse(e is TransferCancelledException)
        assertFalse(target.exists())
    }

    @Test fun aTransferWithinItsLimitCompletes() {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)).throttleBody(16 * 1024, 100, TimeUnit.MILLISECONDS))
        val target = File(folder, "download.zip")
        NextcloudClient(trusted, callTimeoutMs = 100, transferTimeoutMs = 10_000)
            .download(account, NextcloudBackup("Planner-backup-x.zip", null, null, null), target, BackupTransfer())
        assertArrayEquals(bytes, target.readBytes())
    }

    // AS-1: the limit grows with the file, so a large backup on a modest link isn't refused every time.
    @Test fun theLimitGrowsWithTheFile() {
        val client = NextcloudClient(trusted)
        assertEquals(TimeUnit.MINUTES.toMillis(30), client.transferLimitMs(null))
        assertEquals(TimeUnit.MINUTES.toMillis(30) + 1000, client.transferLimitMs(32 * 1024))
        // 2 GB of attachments: about 18 hours more at the slowest link it allows.
        assertTrue(client.transferLimitMs(2L shl 30) > TimeUnit.HOURS.toMillis(18))
        val slow = MockResponse().setBody(Buffer().write(bytes)).throttleBody(4 * 1024, 100, TimeUnit.MILLISECONDS) // about 1.6 s
        server.enqueue(slow)
        val target = File(folder, "download.zip")
        NextcloudClient(trusted, transferTimeoutMs = 300, minBytesPerSecond = 16 * 1024)
            .download(account, NextcloudBackup("Planner-backup-x.zip", bytes.size.toLong(), null, null), target, BackupTransfer())
        assertArrayEquals(bytes, target.readBytes())
        // The same file with no size listed has only the base limit.
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)).throttleBody(4 * 1024, 100, TimeUnit.MILLISECONDS))
        val e = assertThrows(BackupException::class.java) {
            NextcloudClient(trusted, transferTimeoutMs = 300, minBytesPerSecond = 16 * 1024)
                .download(account, NextcloudBackup("Planner-backup-x.zip", null, null, null), target)
        }
        assertEquals(NextcloudClient.TOO_LONG, e.message)
        assertFalse(target.exists())
    }
}
