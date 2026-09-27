package com.example.itinerary.scanner

import kotlin.math.max
import kotlin.math.min

/** Display-only transform. Crop corners and exported pixels remain in image coordinates. */
data class CropViewport(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    fun frame(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float, inset: Float): CropFrame {
        val fit = min((viewWidth - 2 * inset) / imageWidth, (viewHeight - 2 * inset) / imageHeight).coerceAtLeast(.001f)
        val width = imageWidth * fit * zoom
        val height = imageHeight * fit * zoom
        return CropFrame((viewWidth - width) / 2 + panX, (viewHeight - height) / 2 + panY, width, height)
    }

    fun transform(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float, inset: Float,
                  focusX: Float, focusY: Float, deltaX: Float, deltaY: Float, factor: Float): CropViewport {
        val before = frame(viewWidth, viewHeight, imageWidth, imageHeight, inset)
        val nextZoom = (zoom * factor).coerceIn(1f, 5f)
        val ratio = nextZoom / zoom
        val width = before.width * ratio
        val height = before.height * ratio
        val left = focusX - (focusX - deltaX - before.left) * ratio
        val top = focusY - (focusY - deltaY - before.top) * ratio
        // At either extreme leave enough room to grab image-edge corners clear of system gestures.
        val limitX = max(0f, (width - viewWidth) / 2 + inset)
        val limitY = max(0f, (height - viewHeight) / 2 + inset)
        return CropViewport(nextZoom,
            if (limitX == 0f) 0f else (left - (viewWidth - width) / 2).coerceIn(-limitX, limitX),
            if (limitY == 0f) 0f else (top - (viewHeight - height) / 2).coerceIn(-limitY, limitY))
    }
}

data class CropFrame(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun imagePoint(x: Float, y: Float) = ScanPoint((x - left) / width, (y - top) / height)
}
