package com.example.itinerary.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Never a data class: generated toString()/copy logging must not expose the password.
class NextcloudAccount private constructor(
    val server: HttpUrl,
    val username: String,
    internal val password: String,
    val lastBackup: String? = null,
    val folderPath: String = FOLDER,
) {
    val filesRoot: HttpUrl get() = server.newBuilder()
        .addPathSegments("remote.php/dav/files")
        .addPathSegment(username).addPathSegment("").build()
    val folder: HttpUrl get() = filesRoot.newBuilder().apply {
        folderPath.split('/').forEach { addPathSegment(it) }
        addPathSegment("")
    }.build()

    fun backedUpAt(time: String) = NextcloudAccount(server, username, password, time, folderPath)

    fun withFolder(path: String): NextcloudAccount {
        val normalized = normalizeFolder(path)
        return NextcloudAccount(server, username, password, if (normalized == folderPath) lastBackup else null, normalized)
    }

    companion object {
        const val DEFAULT_SERVER = ""
        const val DEFAULT_USERNAME = ""
        const val FOLDER = "Planner Backups"

        fun create(server: String, username: String, password: String, lastBackup: String? = null,
                   folderPath: String = FOLDER): NextcloudAccount {
            val url = server.trim().toHttpUrlOrNull()
                ?: throw BackupException("Enter a valid HTTPS Nextcloud address.")
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.query != null || url.fragment != null
            ) throw BackupException("Use your Nextcloud HTTPS address without login details, a query or a fragment.")
            val user = username.trim()
            if (user.isEmpty() || user.any { it.isISOControl() || it == ':' || it == '/' || it == '\\' } ||
                user == "." || user == ".."
            ) throw BackupException("Enter your Nextcloud username, as shown in your account settings.")
            if (password.isBlank()) throw BackupException("Enter your Nextcloud app password.")
            val base = url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build()
            return NextcloudAccount(base, user, password, lastBackup, normalizeFolder(folderPath))
        }

        fun normalizeFolder(path: String): String {
            val segments = path.trim().trim('/').split('/').map { it.trim() }
            if (path.any { it.isISOControl() } || path.length > 1024 || segments.size > 32 || segments.any {
                    it.isEmpty() || it == "." || it == ".." || it.toByteArray(Charsets.UTF_8).size > 255 ||
                        it.any { c -> c in "\\:*?\"<>|" }
                }) {
                throw BackupException("Enter a folder path such as Backups/Planner. Don't use a web address, empty folder names, . or ..")
            }
            return segments.joinToString("/")
        }
    }
}

// Device-bound AES-GCM key; the encrypted account is excluded from Android and Planner backups.
// Call only on Dispatchers.IO. A corrupt/inaccessible key is reported, never silently replaced.
class NextcloudAccountStore(context: Context, private val keyAlias: String = "planner.nextcloud.v1") {
    private val file = AtomicFile(File(context.noBackupFilesDir, "nextcloud-account"))

    fun load(): NextcloudAccount? {
        if (!file.baseFile.exists()) return null
        try {
            val bytes = file.openRead().use { it.readBytes() }
            check(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            return NextcloudAccount.create(json.getString("server"), json.getString("username"),
                json.getString("password"), json.optString("lastBackup").takeIf { it.isNotEmpty() },
                json.optString("folderPath", NextcloudAccount.FOLDER))
        } catch (_: Exception) {
            throw BackupException("The saved Nextcloud connection couldn't be unlocked. Disconnect and enter the app password again.")
        }
    }

    fun save(account: NextcloudAccount) {
        try {
            val json = JSONObject().put("server", account.server.toString()).put("username", account.username)
                .put("password", account.password).put("lastBackup", account.lastBackup.orEmpty())
                .put("folderPath", account.folderPath)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
        } catch (_: Exception) {
            throw BackupException("Couldn't save the Nextcloud connection securely on this device.")
        }
    }

    fun clear() { file.delete() }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        check(create) { "Missing device key" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
}
