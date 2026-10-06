package com.example.itinerary

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModelStore
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.Assert.*
import androidx.room.Room
import com.example.itinerary.data.*
import com.example.itinerary.reminders.ReminderScheduler
import com.example.itinerary.ui.TripsViewModel
import com.example.itinerary.ui.NextcloudDialog
import com.example.itinerary.ui.BusyDialog
import com.example.itinerary.ui.ImportConfirmDialog
import com.example.itinerary.ui.theme.ItineraryTheme
import com.example.itinerary.ui.nextcloudBackupLabel
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import java.io.File
import java.security.KeyStore
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile

/** Real TLS/WebDAV requests on the emulator, with an isolated Room DB and private test directories. */
@Suppress("DEPRECATION")
class NextcloudBackupTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: MockWebServer
    private lateinit var client: NextcloudClient
    private lateinit var trusted: OkHttpClient
    private lateinit var account: NextcloudAccount
    private lateinit var sandbox: File
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repo: Repository
    private lateinit var backup: BackupManager
    private lateinit var service: NextcloudBackups
    private lateinit var accounts: NextcloudAccountStore
    private lateinit var fixture: DavFixture
    private val alias = "planner.nextcloud.instrumentation"

    @Before fun setUp() {
        val base = instrumentation.targetContext
        sandbox = File(base.cacheDir, "nextcloud-instrumentation").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(sandbox, "files").apply { mkdirs() }
            override fun getCacheDir() = File(sandbox, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(sandbox, "no_backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("nextcloud_test_$name", mode)
        }
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("backup_status", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        settings = SettingsRepository(context)
        val attachments = AttachmentStore(context)
        repo = Repository(database, attachments, ReminderScheduler(context))
        backup = BackupManager(context, repo, attachments, settings)
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        trusted = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build()
        client = NextcloudClient(trusted)
        account = NextcloudAccount.create(server.url("/").toString(), "bas", "test-password-only")
        fixture = DavFixture()
        server.dispatcher = fixture
        accounts = NextcloudAccountStore(context, alias)
        service = NextcloudBackups(context, backup, accounts, client)
    }

    @After fun tearDown() {
        database.close()
        server.shutdown()
        instrumentation.targetContext.deleteSharedPreferences("nextcloud_test_settings")
        instrumentation.targetContext.deleteSharedPreferences("nextcloud_test_backup_status")
        sandbox.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }

    @Test fun testFullBackupRestoreIncludesSettingsFilesLinksAndReminders() = runBlocking {
        val day = LocalDate.of(2000, 1, 2) // No test alarm can fire on the user's device.
        val trip = Trip(910001, "Round-trip plan", "Description", day, day, 4, 6, 0xFF008080.toInt())
        val item = ItineraryItem(910002, trip.id, day, LocalTime.of(13, 45), "Flight", "Perth", "Notes", "Travel", 2)
        val reminder = Reminder(910003, item.id, 2, ReminderUnit.HOURS, true)
        val attachment = Attachment(910004, item.id, "Ticket.pdf", "test-ticket.pdf", "application/pdf")
        val link = Attachment(910005, item.id, "Booking", "", "text/uri-list", "https://example.com/booking")
        val bytes = "%PDF-1.4\nNextcloud test attachment bytes\n%%EOF".toByteArray()
        File(context.filesDir, "attachments").mkdirs()
        File(context.filesDir, "attachments/test-ticket.pdf").writeBytes(bytes)
        repo.replaceAll(DataSnapshot(listOf(trip), listOf(item), listOf(reminder), listOf(attachment, link)))
        settings.setThemeMode(ThemeMode.DARK)
        settings.setTimeFormat(TimeFormat.HOUR_24)
        settings.setHiddenCategories(setOf("Flight"))
        settings.setTextSizePercent(120)
        settings.setScrollBarColor(0xFF5CC8FF.toInt())
        settings.setScrollBarSeeThrough(45)
        val expected = repo.snapshot()
        val expectedSettings = settings.snapshot()
        val saved = service.connect(account.server.toString(), account.username, account.password)
        service.upload(saved)
        val listing = service.list(saved)
        assertEquals(1, listing.size)
        assertTrue(fixture.files.keys.none { it.contains(".upload-") })
        val archive = File(sandbox, "uploaded.zip").apply { writeBytes(fixture.files.values.single()) }
        ZipFile(archive).use { zip ->
            assertNotNull(zip.getEntry("data.json"))
            assertTrue(zip.getInputStream(zip.getEntry("attachments/test-ticket.pdf")).readBytes().contentEquals(bytes))
            assertFalse(zip.getInputStream(zip.getEntry("data.json")).bufferedReader().readText().contains(account.password))
        }
        repo.replaceAll(DataSnapshot(emptyList(), emptyList(), emptyList(), emptyList()))
        settings.setThemeMode(ThemeMode.LIGHT)
        settings.setScrollBarColor(0xFFFF6FB5.toInt())
        settings.setScrollBarSeeThrough(0)
        File(context.filesDir, "attachments/test-ticket.pdf").delete()
        val staged = service.stage(saved, listing.single())
        assertTrue(repo.snapshot().trips.isEmpty()) // Staging never restores before confirmation.
        assertEquals(1, staged.plans)
        assertEquals(2, staged.attachments)
        backup.restore(staged)
        assertEquals(expected, repo.snapshot())
        assertEquals(expectedSettings, settings.snapshot())
        assertTrue(File(context.filesDir, "attachments/test-ticket.pdf").readBytes().contentEquals(bytes))
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("nextcloud-") })
        assertEquals(listOf("PUT", "MOVE"), fixture.methods.filter { it == "PUT" || it == "MOVE" })
    }

    @Test fun testCredentialsEncryptedReloadableAndDisconnectKeepsServerFiles() = runBlocking {
        val saved = service.connect(account.server.toString(), account.username, account.password)
        val raw = File(context.noBackupFilesDir, "nextcloud-account").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains(account.password))
        val loaded = NextcloudAccountStore(context, alias).load()!!
        assertEquals(account.password, loaded.password)
        assertEquals(account.server, loaded.server)
        assertEquals(account.username, loaded.username)
        assertFalse(loaded.toString().contains(account.password))
        service.upload(saved)
        service.disconnect()
        assertNull(accounts.load())
        assertEquals(1, fixture.files.size)
    }

    @Test fun testInvalidAccountAndInsecureUrlAreRejectedBeforeNetworking() {
        for (url in listOf("http://localhost/", "https://user:secret@example.com", "https://example.com/?token=secret", "not a url")) {
            expectFailure { NextcloudAccount.create(url, "bas", "secret") }
        }
        for (user in listOf("", "..", "bas/other", "bas:other", "ba\ns")) {
            expectFailure { NextcloudAccount.create(account.server.toString(), user, "secret") }
        }
        expectFailure { NextcloudAccount.create(account.server.toString(), "bas", "") }
        assertEquals(0, server.requestCount)
    }

    @Test fun testBadLoginAndStorageFullLeaveExistingBackupIntact() = runBlocking {
        val wrong = NextcloudAccount.create(account.server.toString(), "bas", "wrong")
        assertTrue(expectFailure { client.checkConnection(wrong) }.contains("rejected"))
        assertNull(accounts.load())
        val saved = service.connect(account.server.toString(), "bas", account.password)
        service.upload(saved)
        assertEquals("Nextcloud",backup.status.state.value.destination)
        val successTime=backup.status.state.value.lastSuccess
        val existing = fixture.files.toMap()
        fixture.uploadCode = 507
        assertTrue(expectSuspendFailure { service.upload(saved) }.contains("storage"))
        assertEquals("FAILED",backup.status.state.value.outcome)
        assertEquals(successTime,backup.status.state.value.lastSuccess)
        assertEquals("Nextcloud",backup.status.state.value.destination)
        assertEquals(existing.keys, fixture.files.keys)
        assertTrue(existing.values.single().contentEquals(fixture.files.values.single()))
        assertEquals(1, client.list(saved).size)
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("nextcloud-") })
    }

    @Test fun testFailedMoveDoesNotPublishOrListTemporaryUpload() = runBlocking {
        fixture.moveCode = 403
        assertTrue(expectSuspendFailure { service.upload(account) }.contains("denied"))
        assertTrue(client.list(account).isEmpty())
        assertTrue(fixture.files.isEmpty())
    }

    @Test fun testCorruptDownloadDoesNotChangeDataAndIsCleanedUp() = runBlocking {
        val day = LocalDate.of(2000, 1, 2)
        repo.saveTrip(Trip(name = "Keep this plan", destination = "", startDate = day, endDate = day))
        val expected = repo.snapshot()
        fixture.directories.add(account.folder.encodedPath)
        fixture.files[account.folder.encodedPath + "Planner-backup-broken.zip"] = "This isn't a ZIP".toByteArray()
        expectSuspendFailure { service.stage(account, client.list(account).single()) }
        assertEquals(expected, repo.snapshot())
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("nextcloud-") })
    }

    @Test fun testCancelStagedDownloadLeavesDataAndSettingsUnchanged() = runBlocking {
        service.upload(account)
        val before = repo.snapshot()
        val beforeSettings = settings.snapshot()
        val staged = service.stage(account, client.list(account).single())
        backup.discard(staged)
        assertEquals(before, repo.snapshot())
        assertEquals(beforeSettings, settings.snapshot())
        assertFalse(staged.file.exists())
    }

    @Test fun testChangedFileAndTruncatedDownloadAreRejected() {
        val target = File(context.cacheDir, "download.zip")
        val file = NextcloudBackup("Planner-backup-test.zip", 100, null, "\"old\"")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(412)
        }
        assertTrue(expectFailure { client.download(account, file, target) }.contains("changed"))
        assertFalse(target.exists())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("too short")
                .setHeader("Content-Length", 100).setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END)
        }
        try {
            client.download(account, file.copy(etag = null), target)
            fail("Truncated download accepted")
        } catch (_: java.io.IOException) { } catch (_: BackupException) { }
        assertFalse(target.exists())
    }

    // R5-2: a backup's upload and download aren't cut off by the whole-call limit (here 1 s instead of 5 min) on a slow
    // link; other requests keep it. A download that breaks off says so plainly.
    @Test fun testSlowBackupTransfersOutlastTheCallTimeout() {
        val slow = NextcloudClient(trusted, callTimeoutMs = 1000)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = fixture.dispatch(request).apply {
                if (request.method == "PUT") setHeadersDelay(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
        val archive = File(context.cacheDir, "slow-upload.zip").apply { writeBytes(ByteArray(64 * 1024) { it.toByte() }) }
        val name = slow.upload(account, archive)
        assertEquals(64 * 1024, fixture.files.values.single().size)
        val bytes = ByteArray(64 * 1024) { (it * 7).toByte() }
        val target = File(context.cacheDir, "slow-download.zip")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(Buffer().write(bytes))
                .throttleBody(8 * 1024, 300, java.util.concurrent.TimeUnit.MILLISECONDS) // about 2.4 s
        }
        slow.download(account, NextcloudBackup(name, bytes.size.toLong(), null, null), target)
        assertArrayEquals(bytes, target.readBytes())
        // Anything else still has the limit.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = fixture.dispatch(request)
                .setHeadersDelay(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        expectFailure { slow.checkConnection(account) }
        // Cut off mid-way: a plain message, not a raw I/O error, and no partial file.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(Buffer().write(bytes))
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        }
        assertTrue(expectFailure { slow.download(account, NextcloudBackup(name, null, null, null), target) }.contains("interrupted"))
        assertFalse(target.exists())
    }

    @Test fun testRedirectDoesNotReceiveCredentials() {
        val other = MockWebServer()
        other.start()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(307)
                    .setHeader("Location", other.url("/steal"))
            }
            assertTrue(expectFailure { client.checkConnection(account) }.contains("redirected"))
            assertEquals(0, other.requestCount)
        } finally { other.shutdown() }
    }

    @Test fun testUntrustedCertificateIsRejected() {
        assertTrue(expectFailure { NextcloudClient().checkConnection(account) }.contains("certificate"))
    }

    @Test fun testListingRejectsForeignAndNestedPathsAndFailedProperties() {
        val base = account.folder.encodedPath
        val xml = multistatus(listOf(
            entry(base + "Planner-backup-good.zip", false, 10),
            entry("https://evil.example/Planner-backup-evil.zip", false, 10),
            entry(base + "sub/Planner-backup-nested.zip", false, 10),
            entry(base + ".upload-hidden", false, 10),
            entry(base + "Planner-backup-directory.zip/", true),
            entry(base + "Planner-backup-denied.zip", false, 10).replace("200 OK", "403 Forbidden"),
        ))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(207).setBody(xml)
        }
        assertEquals(listOf("Planner-backup-good.zip"), client.list(account).map { it.name })
    }

    @Test fun testXmlExternalEntitiesAreRejected() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(207).setBody(
                "<!DOCTYPE x [<!ENTITY xxe SYSTEM 'file:///etc/hosts'>]><d:multistatus xmlns:d='DAV:'>&xxe;</d:multistatus>")
        }
        assertTrue(expectFailure { client.list(account) }.contains("invalid file list"))
    }

    @Test fun testUiUploadListAndConfirmedRestore() = runBlocking {
        val day = LocalDate.of(2000, 1, 2)
        repo.saveTrip(Trip(name = "Nextcloud UI test", destination = "", startDate = day, endDate = day))
        val expected = repo.snapshot()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val viewModels = ViewModelStore()
        lateinit var vm: TripsViewModel
        try {
            instrumentation.runOnMainSync {
                vm = TripsViewModel(repo, settings, backup, service)
                viewModels.put("nextcloud-test", vm)
                vm.loadNextcloud()
                activity.setContent {
                    ItineraryTheme {
                        val cloud by vm.cloud.collectAsState()
                        val busy by vm.backupBusy.collectAsState()
                        val staged by vm.stagedImport.collectAsState()
                        NextcloudDialog(cloud, busy != null, vm::connectNextcloud, vm::disconnectNextcloud,
                            vm::uploadNextcloud, vm::listNextcloud, vm::importNextcloud, {}, vm::saveNextcloudFolder)
                        busy?.let { BusyDialog(it) }
                        staged?.let { ImportConfirmDialog(it, vm::confirmImport, vm::cancelImport) }
                    }
                }
            }
            await { vm.backupBusy.value == null }
            assertFalse(vm.cloud.value.connected)
            val fields = mutableListOf<AccessibilityNodeInfo>()
            await {
                fields.clear()
                fun collect(node: AccessibilityNodeInfo) {
                    if (node.className?.toString() == "android.widget.EditText") fields.add(node)
                    for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it) }
                }
                instrumentation.uiAutomation.freshRoot?.let { collect(it) }
                fields.size == 3
            }
            assertTrue("First-use server, username and password must be blank", fields.all { it.text.isNullOrEmpty() })
            instrumentation.runOnMainSync {
                vm.connectNextcloud(account.server.toString(), account.username, account.password)
            }
            await { vm.cloud.value.connected && vm.backupBusy.value == null }
            screenshot("connected")
            editFolder("/Planner Backups/")
            clickText("Save folder")
            await { vm.backupBusy.value == null && folderField()?.text?.toString() == "Planner Backups" }
            editFolder("../Outside")
            clickText("Save folder")
            await { vm.cloud.value.error && vm.backupBusy.value == null }
            assertEquals("Planner Backups", accounts.load()!!.folderPath)
            editFolder("Backups/Planner")
            screenshot("folder-draft")
            clickText("Save folder")
            await { vm.cloud.value.folderPath == "Backups/Planner" && vm.backupBusy.value == null }
            assertEquals("Backups/Planner", NextcloudAccountStore(context, alias).load()!!.folderPath)
            screenshot("folder-saved")
            clickText("Back up now")
            await { vm.cloud.value.status?.startsWith("Backup uploaded") == true && vm.backupBusy.value == null }
            assertEquals(1, fixture.files.size)
            screenshot("upload-success")
            clickText("Restore from Nextcloud")
            await { vm.cloud.value.backups?.size == 1 && vm.backupBusy.value == null }
            screenshot("backup-list")
            repo.deleteTrip(expected.trips.single())
            val name = nextcloudBackupLabel(vm.cloud.value.backups!!.single(), TimeFormat.SYSTEM, instrumentation.targetContext)
            clickText(name)
            await { vm.stagedImport.value != null }
            assertTrue(repo.snapshot().trips.isEmpty())
            screenshot("restore-confirmation")
            clickText("Cancel")
            await { vm.stagedImport.value == null }
            assertTrue(repo.snapshot().trips.isEmpty())
            clickText(name)
            await { vm.stagedImport.value != null }
            clickText("Replace everything")
            await { vm.backupBusy.value == null && vm.backupNote.value == "Backup restored." && vm.cloud.value.status == "Backup restored." }
            assertNull(vm.backupMessage.value)
            assertEquals(expected, repo.snapshot())
            editFolder("Other/Backups")
            clickText("Save folder")
            await { vm.cloud.value.folderPath == "Other/Backups" && vm.backupBusy.value == null }
            assertNull(vm.cloud.value.backups)
            assertNull(vm.cloud.value.lastBackup)
            clickText("Restore from Nextcloud")
            await { vm.cloud.value.backups?.isEmpty() == true && vm.backupBusy.value == null }
            assertEquals(1, fixture.files.size)
            clickText("Disconnect")
            await { !vm.cloud.value.connected && vm.backupBusy.value == null }
            assertNull(accounts.load())
            assertEquals(1, fixture.files.size)
        } finally {
            instrumentation.runOnMainSync { viewModels.clear(); activity.finish() }
        }
    }

    @Test fun testNestedFolderCreationPersistenceAndIsolation() = runBlocking {
        // Folder changes apply to the connected account; an upload must not reconnect a missing login.
        accounts.save(account)
        service.upload(account)
        val oldFiles = fixture.files.toMap()
        val changed = service.saveFolder(account.backedUpAt("2026-09-21T00:00:00Z"), " /Backups/Plans 100%/ ")
        assertEquals("Backups/Plans 100%", changed.folderPath)
        assertNull(changed.lastBackup)
        assertEquals(changed.folder, NextcloudAccountStore(context, alias).load()!!.folder)
        client.checkConnection(changed) // Must still check the Files root, not the missing parent.
        assertTrue(client.list(changed).isEmpty())
        service.upload(changed)
        service.upload(changed)
        assertEquals(listOf(account.folder.encodedPath, account.filesRoot.encodedPath + "Backups/",
            account.filesRoot.encodedPath + "Backups/Plans%20100%25/"), fixture.created.toList())
        assertEquals(2, client.list(changed).size)
        assertEquals(1, client.list(account).size)
        assertTrue(oldFiles.all { (name, bytes) -> fixture.files[name]!!.contentEquals(bytes) })
        val staged = service.stage(changed, client.list(changed).first())
        backup.discard(staged)
        assertEquals(changed.folderPath, accounts.load()!!.folderPath)
        assertNotNull(accounts.load()!!.lastBackup)
    }

    @Test fun testFolderValidationAndSegmentEscaping() {
        for (path in listOf("", " ", "/", "../Other", "Backups/../Other", "./Backups", "Backups//Planner",
            "https://example.com/Files", "Backups\\Planner", "Backups/\nPlanner", "a".repeat(256))) {
            expectFailure { account.withFolder(path) }
        }
        val escaped = account.withFolder("Archives/Café & 100%")
        assertEquals(listOf("Archives", "Café & 100%", ""), escaped.folder.pathSegments.takeLast(3))
        assertEquals(account.filesRoot, escaped.filesRoot)
        assertEquals("Planner Backups", account.withFolder(" /Planner Backups/ ").folderPath)
        assertEquals(0, server.requestCount)
    }

    @Test fun testOldEncryptedConnectionUsesDefaultFolder() {
        accounts.save(account)
        val file = File(context.noBackupFilesDir, "nextcloud-account")
        val bytes = file.readBytes()
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = org.json.JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        json.remove("folderPath")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        file.writeBytes(cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)))
        val loaded = NextcloudAccountStore(context, alias).load()!!
        assertEquals("Planner Backups", loaded.folderPath)
        assertEquals(account.password, loaded.password)
    }

    @Test fun testParentFileOrPermissionDeniedPreventsUpload() = runBlocking {
        for (denied in listOf(false, true)) {
            val requests = CopyOnWriteArrayList<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request.method.orEmpty())
                    return if (request.method == "MKCOL") MockResponse().setResponseCode(if (denied) 403 else 405)
                    else MockResponse().setResponseCode(207).setBody(multistatus(listOf(entry(request.requestUrl!!.encodedPath, false))))
                }
            }
            expectSuspendFailure { service.upload(account.withFolder("Blocked/Planner")) }
            assertFalse(requests.contains("PUT"))
            assertFalse(requests.contains("MOVE"))
            assertFalse(requests.contains("DELETE"))
        }
    }

    @Test fun testRapidUploadTapsCreateOnlyOneBackup() = runBlocking {
        service.connect(account.server.toString(), account.username, account.password)
        val viewModels = ViewModelStore()
        lateinit var vm: TripsViewModel
        try {
            instrumentation.runOnMainSync {
                vm = TripsViewModel(repo, settings, backup, service)
                viewModels.put("nextcloud-test", vm)
                vm.loadNextcloud()
            }
            await { vm.cloud.value.connected && vm.backupBusy.value == null }
            instrumentation.runOnMainSync { vm.uploadNextcloud(); vm.uploadNextcloud() }
            await { vm.backupBusy.value == null }
            assertEquals(1, fixture.methods.count { it == "PUT" })
            assertEquals(1, fixture.files.size)
        } finally { instrumentation.runOnMainSync { viewModels.clear() } }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for UI/operation")
            Thread.sleep(50)
        }
        instrumentation.waitForIdleSync()
    }

    private fun folderField(): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.className?.toString() == "android.widget.EditText") return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { find(it) }?.let { return it }
            return null
        }
        return instrumentation.uiAutomation.freshRoot?.let { find(it) }
    }

    private fun editFolder(value: String) {
        await {
            // Return to the field if a previous operation scrolled down to the backup list.
            fun scrollBack(node: AccessibilityNodeInfo) {
                if (node.isScrollable) node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                for (i in 0 until node.childCount) node.getChild(i)?.let { scrollBack(it) }
            }
            instrumentation.uiAutomation.freshRoot?.let { scrollBack(it) }
            folderField()?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }) == true
        }
        await { folderField()?.text?.toString() == value }
    }

    private fun clickText(text: String) {
        var clicked = false
        await {
            val root = instrumentation.uiAutomation.freshRoot
            fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (n.text?.toString() == text && n.isVisibleToUser) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { find(it) }?.let { return it }
                return null
            }
            val match = root?.let { find(it) }
            var node = match
            while (node != null && !node.isClickable) node = node.parent
            if (node != null) clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked && root != null) {
                fun scroll(n: AccessibilityNodeInfo): Boolean {
                    if (n.isScrollable && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                    for (i in 0 until n.childCount) if (n.getChild(i)?.let { scroll(it) } == true) return true
                    return false
                }
                scroll(root)
            }
            clicked
        }
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(300) // Allow dialog fade animations to finish before capturing evidence.
        val folder = File(instrumentation.targetContext.cacheDir, "qa-nextcloud-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    private fun expectFailure(block: () -> Unit): String {
        try { block() } catch (e: BackupException) { return e.message.orEmpty() }
        throw AssertionError("Expected BackupException")
    }
    private suspend fun expectSuspendFailure(block: suspend () -> Unit): String {
        try { block() } catch (e: BackupException) { return e.message.orEmpty() }
        throw AssertionError("Expected BackupException")
    }

    private fun entry(href: String, folder: Boolean, size: Int = 0): String =
        "<d:response><d:href>${href.replace("&", "&amp;")}</d:href><d:propstat><d:prop>" +
            "<d:resourcetype>${if (folder) "<d:collection/>" else ""}</d:resourcetype>" +
            "<d:getcontentlength>$size</d:getcontentlength><d:getetag>&quot;fixture&quot;</d:getetag>" +
            "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
    private fun multistatus(entries: List<String>) =
        "<?xml version='1.0'?><d:multistatus xmlns:d='DAV:'>${entries.joinToString("")}</d:multistatus>"

    private inner class DavFixture : Dispatcher() {
        val files = ConcurrentHashMap<String, ByteArray>()
        val methods = CopyOnWriteArrayList<String>()
        val directories = ConcurrentHashMap.newKeySet<String>().apply { add(account.filesRoot.encodedPath) }
        val created = CopyOnWriteArrayList<String>()
        var uploadCode = 201
        var moveCode = 201
        override fun dispatch(request: RecordedRequest): MockResponse {
            methods.add(request.method.orEmpty())
            if (request.getHeader("Authorization") != Credentials.basic("bas", account.password, Charsets.UTF_8)) {
                return MockResponse().setResponseCode(401)
            }
            val url = request.requestUrl!!
            val path = url.encodedPath
            val parent = path.trimEnd('/').substringBeforeLast('/') + "/"
            return when (request.method) {
                "PROPFIND" -> {
                    val entries = when {
                        path in directories -> listOf(entry(path, true)) +
                            if (request.getHeader("Depth") == "1") files.filterKeys { it.substringBeforeLast('/') + "/" == path }.map { (name, data) ->
                                entry(name, false, data.size)
                            } else emptyList()
                        else -> return MockResponse().setResponseCode(404)
                    }
                    MockResponse().setResponseCode(207).setBody(multistatus(entries))
                }
                "MKCOL" -> MockResponse().setResponseCode(when {
                    path in directories -> 405
                    parent !in directories -> 409
                    else -> { directories.add(path); created.add(path); 201 }
                })
                "PUT" -> {
                    if (parent !in directories) return MockResponse().setResponseCode(409)
                    if (uploadCode == 201) files[path] = request.body.readByteArray()
                    MockResponse().setResponseCode(uploadCode)
                }
                "MOVE" -> {
                    if (moveCode == 201) {
                        val destination = request.getHeader("Destination")!!.toHttpUrl()
                        assertEquals("F", request.getHeader("Overwrite"))
                        files[destination.encodedPath] = files.remove(path)!!
                    }
                    MockResponse().setResponseCode(moveCode)
                }
                "GET" -> files[path]?.let { MockResponse().setBody(Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
                "DELETE" -> MockResponse().setResponseCode(if (files.remove(path) != null) 204 else 404)
                else -> MockResponse().setResponseCode(405)
            }
        }
    }
}
