package com.example.itinerary.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

// Owns the files behind attachments. Everything is copied into app-private storage so an
// attachment keeps working after the original is moved or deleted.
class AttachmentStore(private val context: Context) {
    private data class ThumbKey(val name: String, val maxPx: Int, val length: Long, val modified: Long)
    private val thumbnails = object : android.util.LruCache<ThumbKey, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: ThumbKey, value: Bitmap) = value.allocationByteCount
        // Do not recycle evicted bitmaps: a visible Compose row may still own one.
    }
    fun taskDraftFiles(): Set<String> = TaskDraftStore(context).files()
    // Big Recently deleted bundles (see DeletedPayloads).
    fun deletedPayloads() = DeletedPayloads(File(context.filesDir, "recently-deleted"))
    fun clearThumbnails() = thumbnails.evictAll()
    private fun invalidateThumbnail(name: String) {
        thumbnails.snapshot().keys.filter { it.name == name }.forEach(thumbnails::remove)
    }
    // Only the calls that are about to write need the folder to exist, so reading a file (every
    // attachment row asks for its path, and again for its thumbnail) costs no file system call.
    private val dir: File = File(context.filesDir, "attachments")

    private fun writableDir(): File = dir.apply { mkdirs() }

    private val authority = "${context.packageName}.fileprovider"

    fun fileFor(fileName: String): File = File(dir, fileName)

    // For a caller that is about to write this file (restoring a backup), so the folder is there even
    // on an install that has never had an attachment.
    fun writableFileFor(fileName: String): File {
        invalidateThumbnail(fileName)
        return File(writableDir(), fileName)
    }

    fun uriFor(fileName: String): Uri = FileProvider.getUriForFile(context, authority, fileFor(fileName))

    fun newPhotoFile(): File = File(writableDir(), "${UUID.randomUUID()}.jpg")

    fun delete(fileName: String) {
        // A link has no file (its name is empty); File(dir, "") is the attachments folder itself, which must not be touched.
        if (fileName.isBlank()) return
        invalidateThumbnail(fileName)
        fileFor(fileName).delete()
    }

    // Copies a picked document or image into the store. Returns null if it could not be read.
    suspend fun import(uri: Uri): Attachment? {
        var target: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Attachment"
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                currentCoroutineContext().ensureActive()
                val ext = name.substringAfterLast('.', "")
                    .takeIf { it.isNotEmpty() && it.length <= 8 && it.all(Char::isLetterOrDigit) }
                val fileName = UUID.randomUUID().toString() + (ext?.let { ".$it" } ?: "")
                val destination = writableFileFor(fileName)
                target = destination
                val input = resolver.openInputStream(uri) ?: return@withContext null
                input.use { source ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                com.example.itinerary.scanner.DocumentText.index(Attachment(itemId = 0, name = name, fileName = fileName, mimeType = mime), this@AttachmentStore)
            }
        } catch (e: Exception) {
            // Also handles cancellation on return from IO, before the caller receives the new file.
            withContext(NonCancellable + Dispatchers.IO) { runCatching { target?.delete() } }
            if (e is CancellationException) throw e
            return null
        }
    }

    // Small preview decoded at reduced size, rotated per the photo's EXIF orientation.
    @Synchronized fun thumbnail(fileName: String, maxPx: Int): Bitmap? {
        require(maxPx > 0)
        val file = fileFor(fileName)
        val key = ThumbKey(fileName, maxPx, file.length(), file.lastModified())
        thumbnails.get(key)?.let { return it }
        val path = file.path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null

        val degrees = runCatching {
            when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val result = if (degrees == 0f) bitmap else {
            val matrix = Matrix().apply { postRotate(degrees) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        }
        thumbnails.put(key, result)
        return result
    }
}
