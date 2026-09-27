package com.example.itinerary.scanner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.example.itinerary.data.Attachment
import com.example.itinerary.data.AttachmentStore
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlin.math.max
import kotlin.math.roundToInt

/** Bundled Latin-script recognition: document images are processed on the device. */
object DocumentText {
    const val MAX_PAGES = 50
    const val MAX_TEXT = 200_000
    fun supports(attachment: Attachment) = attachment.url == null &&
        (attachment.mimeType.startsWith("image/") || attachment.mimeType == "application/pdf")

    suspend fun index(attachment: Attachment, store: AttachmentStore): Attachment {
        if (!supports(attachment)) return attachment
        return try {
            withContext(Dispatchers.IO) {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val text = StringBuilder()
                var partial = false
                suspend fun read(bitmap: Bitmap) {
                    try {
                        currentCoroutineContext().ensureActive()
                        // Wait on IO until ML Kit releases its image; cancellation must not recycle an in-use bitmap.
                        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                        currentCoroutineContext().ensureActive()
                        if (text.isNotEmpty()) text.append('\n')
                        text.append(result.text)
                        if (text.length > MAX_TEXT) { text.setLength(MAX_TEXT); partial = true }
                    } finally { bitmap.recycle() }
                }
                try {
                    val file = store.fileFor(attachment.fileName)
                    if (attachment.mimeType == "application/pdf") {
                        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                            partial = pdf.pageCount > MAX_PAGES
                            for (i in 0 until minOf(pdf.pageCount, MAX_PAGES)) {
                                currentCoroutineContext().ensureActive()
                                if (text.length >= MAX_TEXT) { partial = true; break }
                                val bitmap = pdf.openPage(i).use { page ->
                                    val scale = 2000f / max(page.width, page.height)
                                    Bitmap.createBitmap(max(1, (page.width * scale).roundToInt()), max(1, (page.height * scale).roundToInt()), Bitmap.Config.ARGB_8888).also {
                                        it.eraseColor(Color.WHITE)
                                        try { page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                                        catch (e: Throwable) { it.recycle(); throw e }
                                    }
                                }
                                read(bitmap)
                            }
                        }
                    } else read(ScanImages.decode(file, 2400))
                    attachment.copy(recognizedText = text.toString(), textStatus = when {
                        partial -> "PARTIAL"; text.isBlank() -> "EMPTY"; else -> "READY"
                    })
                } finally { recognizer.close() }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { attachment.copy(textStatus = "FAILED") }
        catch (_: OutOfMemoryError) { attachment.copy(textStatus = "FAILED") }
    }
}
