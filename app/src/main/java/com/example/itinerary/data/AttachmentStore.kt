package com.example.itinerary.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    // For ML Kit, started on first use (DocumentText.index).
    internal val appContext: Context get() = context.applicationContext

    private data class ThumbKey(val name: String, val maxPx: Int, val length: Long, val modified: Long)
    private val thumbnails = object : android.util.LruCache<ThumbKey, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: ThumbKey, value: Bitmap) = value.allocationByteCount
        // Do not recycle evicted bitmaps: a visible Compose row may still own one.
    }
    // Files that gave no preview, as they were then: not opened again each time their row shows (ED-8).
    private val noThumbnail = java.util.Collections.synchronizedSet(HashSet<ThumbKey>())
    fun taskDraftFiles(): Set<String> = TaskDraftStore(context).files()
    fun noteDraftFiles(): Set<String> = NoteDraftStore(context).files()
    fun eventDraftFiles(): Set<String> = EditorDraftStore(context).files()
    // Big Recently deleted bundles (see DeletedPayloads).
    fun deletedPayloads() = DeletedPayloads(File(context.filesDir, "recently-deleted"))
    fun clearThumbnails() { thumbnails.evictAll(); noThumbnail.clear() }
    private fun invalidateThumbnail(name: String) {
        thumbnails.snapshot().keys.filter { it.name == name }.forEach(thumbnails::remove)
        synchronized(noThumbnail) { noThumbnail.removeAll { it.name == name } }
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

    private fun isPdf(file: File): Boolean = runCatching {
        file.inputStream().use { input -> ByteArray(5).let { head -> input.read(head) == 5 && String(head, Charsets.US_ASCII) == "%PDF-" } }
    }.getOrDefault(false)

    private fun pdfFirstPage(file: File, maxPx: Int): Bitmap? = run {
        // The descriptor is closed by its own use {} too: PdfRenderer's constructor failing (a locked or damaged file)
        // would leave it open (ED-8).
        // A locked or damaged PDF is no preview for good (remembered); other failures are thrown, to be tried again (EU-6).
        android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> (try { android.graphics.pdf.PdfRenderer(fd) }
            catch (e: java.io.IOException) { return null } catch (e: SecurityException) { return null }).use { pdf ->
            if (pdf.pageCount == 0) return@use null
            pdf.openPage(0).use { page ->
                val scale = maxPx.toFloat() / maxOf(page.width, page.height)
                Bitmap.createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(android.graphics.Color.WHITE)
                    page.render(it, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        } }
    }

    // Small preview decoded at reduced size, rotated per the photo's EXIF orientation. Not one lock for all: a slow PDF
    // doesn't hold up every other preview (ED-8); the cache is safe to share.
    fun thumbnail(fileName: String, maxPx: Int): Bitmap? {
        require(maxPx > 0)
        val file = fileFor(fileName)
        val key = ThumbKey(fileName, maxPx, file.length(), file.lastModified())
        thumbnails.get(key)?.let { return it }
        if (key in noThumbnail) return null
        // A file that can't give one (locked, damaged, not a picture) is remembered; a failure that may pass (out of
        // memory, a read error) is tried again next time (EU-6).
        val decoded = try { decodeThumbnail(file, maxPx) } catch (e: Throwable) { return null }
        return decoded?.also { thumbnails.put(key, it) } ?: null.also {
            if (noThumbnail.size > 200) noThumbnail.clear()
            noThumbnail += key
        }
    }

    private fun decodeThumbnail(file: File, maxPx: Int): Bitmap? {
        // A PDF (or a scan saved as one) shows its first page (wish list #4); a locked or damaged one shows its kind.
        if (isPdf(file)) return pdfFirstPage(file, maxPx)
        val path = file.path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null

        val orientation = runCatching {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = imageOrientationMatrix(orientation)
        return if (matrix.isIdentity) bitmap else {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        }
    }
}
