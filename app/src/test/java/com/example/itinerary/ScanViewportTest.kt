package com.example.itinerary

import com.example.itinerary.scanner.*
import org.junit.Assert.*
import org.junit.Test

class ScanViewportTest {
    @Test fun zoomKeepsTheImagePointUnderTheFingers() {
        val original = CropViewport()
        val before = original.frame(600f,800f,900f,1200f,24f)
        val point = before.imagePoint(260f,350f)
        val zoomed = original.transform(600f,800f,900f,1200f,24f,260f,350f,0f,0f,2f)
        val after = zoomed.frame(600f,800f,900f,1200f,24f).imagePoint(260f,350f)
        assertEquals(point.x,after.x,.00001f);assertEquals(point.y,after.y,.00001f)
    }
    @Test fun panBoundsKeepEveryImageEdgeReachable() {
        val zoomed = CropViewport(3f)
        val topLeft = zoomed.transform(600f,800f,900f,1200f,24f,300f,400f,10000f,10000f,1f).frame(600f,800f,900f,1200f,24f)
        assertEquals(24f,topLeft.left,.001f);assertEquals(24f,topLeft.top,.001f)
        val bottomRight = zoomed.transform(600f,800f,900f,1200f,24f,300f,400f,-10000f,-10000f,1f).frame(600f,800f,900f,1200f,24f)
        assertEquals(576f,bottomRight.left+bottomRight.width,.001f)
        assertEquals(776f,bottomRight.top+bottomRight.height,.001f)
    }
    @Test fun zoomLimitsAndFitDoNotAllowImageToGetLost() {
        val max = CropViewport().transform(600f,800f,900f,1200f,24f,300f,400f,0f,0f,100f)
        assertEquals(5f,max.zoom,0f)
        val fitted = max.transform(600f,800f,900f,1200f,24f,20f,30f,900f,800f,.001f)
        assertEquals(CropViewport(),fitted)
        val panAtFit = CropViewport().transform(600f,800f,1200f,900f,24f,300f,400f,1000f,1000f,1f)
        assertEquals(CropViewport(),panAtFit)
    }
    @Test fun rotatedAndPortraitFramesPreserveAspectAndMapCorners() {
        for ((w,h) in listOf(900f to 1200f,1200f to 900f)) {
            val f = CropViewport(2.2f,55f,-34f).frame(600f,800f,w,h,24f)
            assertEquals(w/h,f.width/f.height,.00001f)
            assertEquals(ScanPoint(0f,0f),f.imagePoint(f.left,f.top))
            val far = f.imagePoint(f.left+f.width,f.top+f.height)
            assertEquals(1f,far.x,.00001f);assertEquals(1f,far.y,.00001f)
        }
    }
}
