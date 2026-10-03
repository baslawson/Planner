package com.example.itinerary.scanner

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Conservative page-outline detection on a small, upright luminance image. No capture automation. */
object DocumentEdges {
    fun detect(gray: IntArray, width: Int, height: Int): List<ScanPoint>? {
        require(width >= 8 && height >= 8 && gray.size == width * height)
        require(width <= 400 && height <= 400)
        val smooth = IntArray(gray.size)
        val histogram = IntArray(256)
        for (y in 0 until height) for (x in 0 until width) {
            var sum = 0; var count = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until width && yy in 0 until height) { sum += gray[yy * width + xx].coerceIn(0,255); count++ }
            }
            smooth[y * width + x] = sum / count
            histogram[sum / count]++
        }
        fun percentile(fraction: Double): Int {
            var count = 0
            for (i in histogram.indices) { count += histogram[i]; if (count >= gray.size * fraction) return i }
            return 255
        }
        val low = percentile(.05); val high = percentile(.95)
        if (high - low < 12) return null
        // Otsu plus several contrast levels handles both shadowed paper and light backgrounds.
        val total = histogram.indices.sumOf { it.toLong() * histogram[it] }
        var count = 0; var sum = 0L; var bestVariance = -1.0; var split = (low + high) / 2
        for (t in 0..254) {
            count += histogram[t]; sum += t.toLong() * histogram[t]
            if (count == 0 || count == gray.size) continue
            val delta = sum.toDouble() / count - (total - sum).toDouble() / (gray.size - count)
            val variance = count.toDouble() * (gray.size - count) * delta * delta
            if (variance > bestVariance) { bestVariance = variance; split = t }
        }
        val thresholds = (listOf(split) + listOf(.2,.4,.6,.8).map { (low + (high - low) * it).toInt() }).distinct()
        var winner: List<ScanPoint>? = null
        var winningScore = 0.0
        val queue = IntArray(gray.size)
        for (threshold in thresholds) for (bright in listOf(true,false)) {
            val seen = BooleanArray(gray.size)
            fun foreground(i: Int) = (smooth[i] > threshold) == bright
            for (seed in smooth.indices) {
                if (seen[seed] || !foreground(seed)) continue
                var head = 0; var tail = 1; queue[0] = seed; seen[seed] = true
                val boundary = ArrayList<ScanPoint>()
                var componentSum = 0L
                while (head < tail) {
                    val p = queue[head++]; val x = p % width; val y = p / width
                    componentSum += smooth[p]
                    var edge = false
                    for (direction in 0..3) {
                        val xx = x + if (direction == 0) -1 else if (direction == 1) 1 else 0
                        val yy = y + if (direction == 2) -1 else if (direction == 3) 1 else 0
                        if (xx !in 0 until width || yy !in 0 until height) { edge = true; continue }
                        val n = yy * width + xx
                        if (!foreground(n)) edge = true
                        else if (!seen[n]) { seen[n] = true; queue[tail++] = n }
                    }
                    if (edge) boundary += ScanPoint(x.toFloat(),y.toFloat())
                }
                if (tail < gray.size * .08 || boundary.size < 4) continue
                val hull = convexHull(boundary)
                val hullArea = area(hull)
                if (hullArea < gray.size * .10 || hullArea > gray.size * .96 || tail / hullArea < .60) continue
                val quad = hull.toMutableList()
                // Remove the least significant bend until the convex outline has four corners.
                while (quad.size > 4) {
                    val index = quad.indices.minBy { i -> abs(cross(quad[(i + quad.size - 1) % quad.size],quad[i],quad[(i + 1) % quad.size])) }
                    quad.removeAt(index)
                }
                if (quad.size != 4) continue
                val quadArea = area(quad)
                if (quadArea / hullArea < .90) continue // Reject circles, triangles and irregular clutter.
                val start = quad.indices.minBy { quad[it].x + quad[it].y }
                val ordered = List(4) { quad[(start + it) % 4] }
                val normalized = ordered.map { ScanPoint(it.x / (width - 1), it.y / (height - 1)) }
                if (!validScanCorners(normalized)) continue
                // Compare the region with a narrow band just outside the proposed page.
                val center = ScanPoint(ordered.sumOf { it.x.toDouble() }.toFloat()/4,ordered.sumOf { it.y.toDouble() }.toFloat()/4)
                var outsideSum = 0L; var outsideCount = 0
                for (i in 0..3) for (step in 1..9) {
                    val a = ordered[i]; val b = ordered[(i+1)%4]; val t = step/10f
                    val x = a.x + (b.x-a.x)*t; val y = a.y + (b.y-a.y)*t
                    val dx=x-center.x;val dy=y-center.y;val length=sqrt(dx*dx+dy*dy).coerceAtLeast(1f)
                    val xx=(x+dx/length*4).toInt();val yy=(y+dy/length*4).toInt()
                    if (xx in 0 until width && yy in 0 until height) { outsideSum+=smooth[yy*width+xx];outsideCount++ }
                }
                if (outsideCount < 12) continue
                val contrast = abs(componentSum.toDouble()/tail - outsideSum.toDouble()/outsideCount)
                if (contrast < 12) continue
                val score = quadArea * min(1.0,tail / hullArea) * sqrt(contrast)
                if (score > winningScore) {
                    winningScore = score
                    // Keep a small margin so thresholding/blur doesn't trim the paper's actual edge.
                    val padded = ordered.map { p ->
                        val dx=p.x-center.x;val dy=p.y-center.y;val length=sqrt(dx*dx+dy*dy).coerceAtLeast(1f)
                        ScanPoint(((p.x+dx/length*1.5f)/(width-1)).coerceIn(0f,1f),((p.y+dy/length*1.5f)/(height-1)).coerceIn(0f,1f))
                    }
                    winner = padded.takeIf(::validScanCorners) ?: normalized
                }
            }
        }
        return winner
    }
    private fun cross(a: ScanPoint,b: ScanPoint,c: ScanPoint): Double =
        ((b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)).toDouble()
    private fun area(points: List<ScanPoint>): Double = abs(points.indices.sumOf { i ->
        val a=points[i];val b=points[(i+1)%points.size];(a.x*b.y-b.x*a.y).toDouble()
    })/2
    private fun convexHull(points: List<ScanPoint>): List<ScanPoint> {
        val sorted=points.distinct().sortedWith(compareBy<ScanPoint> { it.x }.thenBy { it.y })
        fun half(values: List<ScanPoint>): List<ScanPoint> {
            val result=ArrayList<ScanPoint>()
            for (p in values) {
                while (result.size>=2 && cross(result[result.size-2],result.last(),p)<=0) result.removeAt(result.lastIndex)
                result+=p
            }
            return result.dropLast(1)
        }
        return half(sorted)+half(sorted.asReversed())
    }
}
