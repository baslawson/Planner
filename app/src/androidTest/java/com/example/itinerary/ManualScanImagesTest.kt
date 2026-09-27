package com.example.itinerary

import android.content.ContextWrapper
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.example.itinerary.data.AttachmentStore
import com.example.itinerary.scanner.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class ManualScanImagesTest {
    private lateinit var folder: File
    private lateinit var store: AttachmentStore
    @Before fun setup() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        folder = File(base.cacheDir,"manual-scan-test-${UUID.randomUUID()}").apply { mkdirs() }
        store = AttachmentStore(object : ContextWrapper(base) { override fun getFilesDir() = File(folder,"files").apply { mkdirs() } })
    }
    @After fun cleanup() { folder.deleteRecursively() }
    private fun image(name: String, color: Int, width: Int = 600, height: Int = 900): File {
        val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return File(folder,name).also { file -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) };bitmap.recycle() }
    }
    @Test fun autoCropFindsPaperAndExportUsesAdjustedCorners() = runBlocking {
        val bitmap=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.rgb(30,50,60));drawRect(120f,100f,700f,900f,Paint().apply { color=Color.WHITE }) }
        val file=File(folder,"document.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) };bitmap.recycle()
        val page=ScanImages.preparePage(file)
        assertTrue(page.autoDetected)
        assertEquals(.15f,page.corners[0].x,.03f);assertEquals(.10f,page.corners[0].y,.03f)
        assertEquals(.875f,page.corners[2].x,.03f);assertEquals(.90f,page.corners[2].y,.03f)
        // A user's smaller crop must win over the automatically detected outline at export.
        val adjusted=page.copy(corners=listOf(ScanPoint(.2f,.2f),ScanPoint(.8f,.2f),ScanPoint(.8f,.8f),ScanPoint(.2f,.8f)))
        val attachment=ScanImages.export(listOf(adjusted),false,store).single()
        val result=BitmapFactory.decodeFile(store.fileFor(attachment.fileName).path)
        assertEquals(480,result.width);assertEquals(600,result.height)
        assertTrue(Color.red(result.getPixel(10,10))>240);result.recycle()
        assertFalse(ScanImages.preparePage(image("blank.jpg",Color.WHITE)).autoDetected)
    }
    @Test fun perspectiveCropKeepsInteriorAndRejectsBadCorners() {
        val source = Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888)
        Canvas(source).apply { drawColor(Color.RED);drawRect(100f,100f,300f,300f,Paint().apply { color=Color.BLUE }) }
        val crop = ScanImages.crop(source,listOf(ScanPoint(.25f,.25f),ScanPoint(.75f,.25f),ScanPoint(.75f,.75f),ScanPoint(.25f,.75f)))
        assertEquals(200,crop.width);assertEquals(200,crop.height);assertEquals(Color.BLUE,crop.getPixel(100,100));assertEquals(Color.BLUE,crop.getPixel(10,10))
        val skewed = ScanImages.crop(source,listOf(ScanPoint(.3f,.25f),ScanPoint(.7f,.3f),ScanPoint(.75f,.75f),ScanPoint(.25f,.7f)))
        assertEquals(Color.BLUE,skewed.getPixel(skewed.width/2,skewed.height/2))
        assertThrows(IllegalArgumentException::class.java) { ScanImages.crop(source,List(4) { ScanPoint(.5f,.5f) }) }
        source.recycle();crop.recycle();skewed.recycle()
    }
    @Test fun decodeBoundsImagesAndHonorsExifRotationAndMirroring() {
        val file = image("large.jpg",Color.WHITE,3000,1000)
        ExifInterface(file.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString());saveAttributes() }
        val decoded = ScanImages.decode(file,1200)
        assertEquals(400,decoded.width);assertEquals(1200,decoded.height);decoded.recycle()
        val bitmap = Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.BLUE);drawRect(0f,0f,100f,100f,Paint().apply { color=Color.RED }) }
        val mirrored = File(folder,"mirror.jpg");mirrored.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,100,it) };bitmap.recycle()
        ExifInterface(mirrored.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_FLIP_HORIZONTAL.toString());saveAttributes() }
        val result = ScanImages.decode(mirrored)
        assertTrue(Color.blue(result.getPixel(25,50))>200);assertTrue(Color.red(result.getPixel(175,50))>200);result.recycle()
    }
    @Test fun jpegAndPdfExportPreservePageOrderAndRotation() = runBlocking {
        val pages=listOf(ScanImages.Page(image("red.jpg",Color.RED),turns=1),ScanImages.Page(image("blue.jpg",Color.BLUE)))
        val jpegs=ScanImages.export(pages,false,store)
        assertEquals(2,jpegs.size);assertTrue(jpegs.all { it.mimeType=="image/jpeg" })
        val first=BitmapFactory.decodeFile(store.fileFor(jpegs[0].fileName).path)
        assertEquals(900,first.width);assertEquals(600,first.height);first.recycle()
        val pdf=ScanImages.export(pages,true,store).single()
        assertEquals("application/pdf",pdf.mimeType)
        PdfRenderer(ParcelFileDescriptor.open(store.fileFor(pdf.fileName),ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            assertEquals(2,renderer.pageCount)
            repeat(2) { i -> renderer.openPage(i).use { page ->
                assertEquals(if (i==0) 300 else 200,page.width)
                val bitmap=Bitmap.createBitmap(page.width,page.height,Bitmap.Config.ARGB_8888)
                page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val color=bitmap.getPixel(page.width/2,page.height/2)
                assertTrue(if (i==0) Color.red(color)>200 && Color.blue(color)<50 else Color.blue(color)>200 && Color.red(color)<50)
                bitmap.recycle()
            } }
        }
    }
    @Test fun failedAndCancelledExportsLeaveNoPartialAttachments() = runBlocking {
        val valid=ScanImages.Page(image("valid.jpg",Color.GREEN))
        val broken=ScanImages.Page(File(folder,"missing.jpg"))
        val original=store.writableFileFor("keep.txt").apply { writeText("existing") }
        for (pdf in listOf(false,true)) {
            try { ScanImages.export(listOf(valid,broken),pdf,store);fail("Invalid second page must fail") } catch (_: IllegalArgumentException) { }
            assertEquals(listOf("keep.txt"),original.parentFile!!.list()!!.toList())
        }
        val job=launch { currentCoroutineContext().cancel();ScanImages.export(listOf(valid),false,store) }
        job.join();assertTrue(job.isCancelled)
        assertEquals(listOf("keep.txt"),original.parentFile!!.list()!!.toList());assertEquals("existing",original.readText())
    }
}
