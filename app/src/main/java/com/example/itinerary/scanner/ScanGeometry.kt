package com.example.itinerary.scanner

import kotlin.math.abs

data class ScanPoint(val x: Float, val y: Float)

val fullPageCorners = listOf(ScanPoint(0f, 0f), ScanPoint(1f, 0f), ScanPoint(1f, 1f), ScanPoint(0f, 1f))

// Clockwise, normalized corners. Reject crossed edges and tiny/degenerate quadrilaterals.
fun validScanCorners(points: List<ScanPoint>): Boolean {
    if (points.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) return false
    val turns = points.indices.map { i ->
        val a = points[i]; val b = points[(i + 1) % 4]; val c = points[(i + 2) % 4]
        (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
    }
    val area = abs(points.indices.sumOf { i ->
        val a = points[i]; val b = points[(i + 1) % 4]
        (a.x * b.y - b.x * a.y).toDouble()
    }) / 2
    return turns.all { it > .0001f } && area >= .01
}

fun rotatedScanCorners(points: List<ScanPoint>): List<ScanPoint> =
    listOf(points[3], points[0], points[1], points[2]).map { ScanPoint(1f - it.y, it.x) }
