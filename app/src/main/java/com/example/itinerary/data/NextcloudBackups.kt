package com.example.itinerary.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

class NextcloudBackups(
    private val context: Context,
    private val backup: BackupManager,
    private val store: NextcloudAccountStore = NextcloudAccountStore(context),
    private val client: NextcloudClient = NextcloudClient(),
) {
    suspend fun savedAccount(): NextcloudAccount? = withContext(Dispatchers.IO) { store.load() }

    suspend fun connect(server: String, username: String, password: String): NextcloudAccount = withContext(Dispatchers.IO) {
        val account = NextcloudAccount.create(server, username, password)
        client.checkConnection(account)
        store.save(account)
        account
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) { store.clear() }

    suspend fun saveFolder(account: NextcloudAccount, path: String): NextcloudAccount = withContext(Dispatchers.IO) {
        account.withFolder(path).also { store.save(it) }
    }

    suspend fun list(account: NextcloudAccount): List<NextcloudBackup> = withContext(Dispatchers.IO) { client.list(account) }

    // [cancel] (S6-1): Cancel in the pop-up; nothing is uploaded once it is pressed before the upload starts.
    suspend fun upload(account: NextcloudAccount, cancel: BackupTransfer? = null): NextcloudAccount = backup.status.track("Nextcloud") { withContext(Dispatchers.IO) {
        val file = File.createTempFile("nextcloud-export-", ".zip", context.cacheDir)
        try {
            backup.export(Uri.fromFile(file), trackStatus = false)
            cancel?.check()
            client.upload(account, file, cancel)
            val updated = account.backedUpAt(Instant.now().toString())
            // The upload is successful even if recording its time on the device fails.
            runCatching { store.save(updated) }
            updated
        } finally {
            file.delete()
        }
    } }

    // [cancel] (S6-1): after Cancel nothing is kept, not even a backup already checked, so no restore is offered.
    suspend fun stage(account: NextcloudAccount, remote: NextcloudBackup, cancel: BackupTransfer? = null): StagedBackup = withContext(Dispatchers.IO) {
        val file = File.createTempFile("nextcloud-download-", ".zip", context.cacheDir)
        try {
            client.download(account, remote, file, cancel)
            backup.stage(Uri.fromFile(file)).also { staged ->
                if (cancel != null && cancel.cancelled) { backup.discard(staged); cancel.check() }
            }
        } finally {
            file.delete()
        }
    }
}
