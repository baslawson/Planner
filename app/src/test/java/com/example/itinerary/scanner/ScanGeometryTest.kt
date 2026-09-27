package com.example.itinerary.scanner

import org.junit.Assert.*
import org.junit.Test

class ScanGeometryTest {
    @Test fun acceptsConvexPageButRejectsCrossedCollapsedAndNonFiniteCorners() {
        assertTrue(validScanCorners(fullPageCorners))
        assertTrue(validScanCorners(listOf(ScanPoint(.2f,.1f),ScanPoint(.9f,.2f),ScanPoint(.8f,.9f),ScanPoint(.1f,.8f))))
        assertFalse(validScanCorners(listOf(fullPageCorners[0],fullPageCorners[2],fullPageCorners[1],fullPageCorners[3])))
        assertFalse(validScanCorners(List(4) { ScanPoint(.5f,.5f) }))
        assertFalse(validScanCorners(fullPageCorners.map { ScanPoint(it.x / 100, it.y / 100) }))
        assertFalse(validScanCorners(fullPageCorners.toMutableList().apply { set(0,ScanPoint(Float.NaN,0f)) }))
        assertFalse(validScanCorners(fullPageCorners.toMutableList().apply { set(0,ScanPoint(-.1f,0f)) }))
    }
    @Test fun rotatingPreservesPhysicalCropAndRestoresAfterFourTurns() {
        val crop = listOf(ScanPoint(.1f,.2f),ScanPoint(.9f,.2f),ScanPoint(.9f,.8f),ScanPoint(.1f,.8f))
        var rotated = crop
        repeat(4) { rotated = rotatedScanCorners(rotated); assertTrue(validScanCorners(rotated)) }
        crop.zip(rotated).forEach { (a,b) -> assertEquals(a.x,b.x,.00001f);assertEquals(a.y,b.y,.00001f) }
        assertEquals(.2f,rotatedScanCorners(crop)[0].x,.00001f)
        assertEquals(.1f,rotatedScanCorners(crop)[0].y,.00001f)
    }
}
