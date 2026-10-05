package com.example.itinerary

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class AttachmentOrientationRegressionTest {
    @Test fun thumbnailsApplyAllEightExifOrientations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = (context.applicationContext as ItineraryApp).attachmentStore
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        // Independent expected corner orders: upper-left, upper-right, lower-left, lower-right.
        val expected = listOf(
            listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0), listOf(2, 3, 0, 1),
            listOf(0, 2, 1, 3), listOf(2, 0, 3, 1), listOf(3, 1, 2, 0), listOf(1, 3, 0, 2),
        )
        val source = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        for (y in 0 until source.height) for (x in 0 until source.width)
            source.setPixel(x, y, colors[(if (y >= 20) 2 else 0) + if (x >= 40) 1 else 0])
        try {
            for (orientation in 1..8) {
                val name = "qa-orientation-$orientation.jpg"
                val file = store.writableFileFor(name)
                try {
                    file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
                    ExifInterface(file.path).apply {
                        setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes()
                    }
                    val thumbnail = store.thumbnail(name, 160)
                    assertNotNull("Orientation $orientation preview", thumbnail)
                    val image = thumbnail!!
                    assertEquals(if (orientation >= 5) 40 else 80, image.width)
                    assertEquals(if (orientation >= 5) 80 else 40, image.height)
                    val observed = listOf(1 to 1, 3 to 1, 1 to 3, 3 to 3).map { (x, y) ->
                        val pixel = image.getPixel(image.width * x / 4, image.height * y / 4)
                        colors.indices.minBy { i ->
                            val c = colors[i]
                            val red = Color.red(pixel) - Color.red(c)
                            val green = Color.green(pixel) - Color.green(c)
                            val blue = Color.blue(pixel) - Color.blue(c)
                            red * red + green * green + blue * blue
                        }
                    }
                    assertEquals("Orientation $orientation corners", expected[orientation - 1], observed)
                } finally { store.delete(name) }
            }
        } finally { source.recycle(); store.clearThumbnails() }
    }
}
