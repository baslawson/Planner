package com.example.itinerary.scanner

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import com.example.itinerary.data.Attachment
import com.example.itinerary.data.AttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Bounded decoding, EXIF normalization, perspective correction and private PDF/JPEG export. */
object ScanImages {
    const val MAX_EDGE = 2400
    const val MAX_PAGES = 10

    fun decode(file: File, maxEdge: Int = MAX_EDGE): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Couldn't read this image" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxEdge * 2) sample *= 2
        var bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        try {
            val exif = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply {
                when (exif) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) { bitmap.recycle(); bitmap = rotated }
            }
            val scale = maxEdge.toFloat() / max(bitmap.width, bitmap.height)
            if (scale < 1f) {
                val scaled = Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).roundToInt()), max(1, (bitmap.height * scale).roundToInt()), true)
                if (scaled !== bitmap) { bitmap.recycle(); bitmap = scaled }
            }
            return bitmap
        } catch (e: Throwable) { bitmap.recycle(); throw e }
    }

    fun rotate(source: Bitmap, turns: Int): Bitmap = if (turns % 4 == 0) source else
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { setRotate(turns * 90f) }, true)

    fun crop(source: Bitmap, corners: List<ScanPoint>): Bitmap {
        require(validScanCorners(corners)) { "Adjust the page corners" }
        val points = corners.map { ScanPoint(it.x * source.width, it.y * source.height) }
        fun distance(a: ScanPoint, b: ScanPoint) = hypot(a.x - b.x, a.y - b.y)
        val width = max(1, max(distance(points[0], points[1]), distance(points[3], points[2])).roundToInt())
        val height = max(1, max(distance(points[0], points[3]), distance(points[1], points[2])).roundToInt())
        val transform = Matrix()
        check(transform.setPolyToPoly(points.flatMap { listOf(it.x, it.y) }.toFloatArray(), 0,
            floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat()), 0, 4))
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { result ->
            Canvas(result).apply { drawColor(Color.WHITE); drawBitmap(source, transform, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) }
        }
    }

    data class Page(val file: File, val corners: List<ScanPoint> = fullPageCorners, val turns: Int = 0, val autoDetected: Boolean = false)

    fun preparePage(file: File): Page {
        val image = decode(file, 320)
        try {
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            val gray = IntArray(pixels.size) { i ->
                val pixel = pixels[i]
                (Color.red(pixel) * 77 + Color.green(pixel) * 150 + Color.blue(pixel) * 29) shr 8
            }
            val detected = if (image.width >= 8 && image.height >= 8) DocumentEdges.detect(gray, image.width, image.height) else null
            return Page(file, corners = detected ?: fullPageCorners, autoDetected = detected != null)
        } finally { image.recycle() }
    }

    suspend fun export(pages: List<Page>, pdf: Boolean, store: AttachmentStore): List<Attachment> {
        require(pages.size in 1..MAX_PAGES)
        val created = mutableListOf<File>()
        try {
            return withContext(Dispatchers.IO) {
                val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
                fun target(ext: String) = store.writableFileFor("${UUID.randomUUID()}.$ext").also { created += it }
                fun render(page: Page): Bitmap {
                    val decoded = decode(page.file)
                    val rotated = try { rotate(decoded, page.turns) } catch (e: Throwable) { decoded.recycle(); throw e }
                    if (rotated !== decoded) decoded.recycle()
                    return try { crop(rotated, page.corners) } finally { rotated.recycle() }
                }
                val attachments = if (pdf) {
                    val destination = target("pdf")
                    val document = PdfDocument()
                    try {
                        pages.forEachIndexed { index, page ->
                            currentCoroutineContext().ensureActive()
                            val image = render(page)
                            try {
                                // One point per ~3 pixels keeps typical documents at a useful print size.
                                val width = max(1, image.width / 3); val height = max(1, image.height / 3)
                                val output = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                                try { output.canvas.drawBitmap(image, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG)) }
                                finally { document.finishPage(output) }
                            } finally { image.recycle() }
                        }
                        currentCoroutineContext().ensureActive()
                        destination.outputStream().use(document::writeTo)
                    } finally { document.close() }
                    listOf(Attachment(itemId = 0, name = "Scan $stamp", fileName = destination.name, mimeType = "application/pdf"))
                } else pages.mapIndexed { index, page ->
                    currentCoroutineContext().ensureActive()
                    val image = render(page)
                    val destination = target("jpg")
                    try { destination.outputStream().use { check(image.compress(Bitmap.CompressFormat.JPEG, 92, it)) } }
                    finally { image.recycle() }
                    Attachment(itemId = 0, name = "Scan $stamp" + if (pages.size > 1) " (${index + 1})" else "",
                        fileName = destination.name, mimeType = "image/jpeg")
                }
                attachments.map { DocumentText.index(it, store) }
            }
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { created.forEach { it.delete() } }
            throw e
        }
    }
}
