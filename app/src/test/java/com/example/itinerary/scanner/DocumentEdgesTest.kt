package com.example.itinerary.scanner

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class DocumentEdgesTest {
    private val width=240
    private val height=300
    private val corners=listOf(ScanPoint(.18f,.13f),ScanPoint(.86f,.19f),ScanPoint(.78f,.88f),ScanPoint(.12f,.81f))
    private fun fixture(page: (Int,Int)->Int, background: Int = 35): IntArray = IntArray(width*height) { index ->
        val x=index%width;val y=index/width;val p=ScanPoint(x.toFloat()/(width-1),y.toFloat()/(height-1))
        val inside=corners.indices.all { i -> val a=corners[i];val b=corners[(i+1)%4];(b.x-a.x)*(p.y-a.y)-(b.y-a.y)*(p.x-a.x)>=0 }
        if (inside) page(x,y) else background
    }
    private fun assertCorners(gray: IntArray) {
        val detected=DocumentEdges.detect(gray,width,height)
        assertNotNull("Page outline should be found",detected)
        corners.zip(detected!!).forEach { (expected,actual) ->
            assertEquals(expected.x,actual.x,.035f);assertEquals(expected.y,actual.y,.035f)
        }
    }
    @Test fun findsPerspectivePaperWithTextAndShadow() {
        assertCorners(fixture({ x,y -> if (y%24 in 10..12 && x in 70..170) 45 else 150+x/3 }))
    }
    @Test fun findsLightPaperOnLightBackgroundAndDarkPaperOnLightBackground() {
        assertCorners(fixture({ _,_ -> 245 },190))
        assertCorners(fixture({ _,_ -> 40 },220))
    }
    @Test fun rejectsBlankLowContrastAndRoundObjects() {
        assertNull(DocumentEdges.detect(IntArray(width*height) { 160 },width,height))
        assertNull(DocumentEdges.detect(fixture({ _,_ -> 165 },160),width,height))
        val circle=IntArray(width*height) { i -> val x=i%width-120;val y=i/width-150;if (x*x+y*y<80*80) 240 else 30 }
        assertNull(DocumentEdges.detect(circle,width,height))
    }
    @Test fun doesNotTurnRandomNoiseIntoAPage() {
        val random=java.util.Random(4821)
        assertNull(DocumentEdges.detect(IntArray(width*height) { random.nextInt(256) },width,height))
    }
}
