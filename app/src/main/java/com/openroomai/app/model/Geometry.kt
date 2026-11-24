package com.openroomai.app.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2D vector on the floor plane, in meters.
 * x = world X, y = world Z (ARCore), so plan coordinates line up 1:1 with the AR world.
 */
@Serializable
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    operator fun div(s: Float) = Vec2(x / s, y / s)
    operator fun unaryMinus() = Vec2(-x, -y)
    infix fun dot(o: Vec2) = x * o.x + y * o.y
    infix fun cross(o: Vec2) = x * o.y - y * o.x
    fun length() = hypot(x, y)
    fun normalized(): Vec2 {
        val l = length()
        return if (l < 1e-6f) Vec2(0f, 0f) else Vec2(x / l, y / l)
    }
    fun rotated(rad: Float): Vec2 {
        val c = cos(rad)
        val s = sin(rad)
        return Vec2(x * c - y * s, x * s + y * c)
    }
    fun perp() = Vec2(-y, x)
    fun dist(o: Vec2) = hypot(x - o.x, y - o.y)
    fun angle() = atan2(y, x)

    companion object {
        val ZERO = Vec2(0f, 0f)
    }
}

data class Segment(val a: Vec2, val b: Vec2) {
    val length get() = a.dist(b)
    val dir get() = (b - a).normalized()
    fun pointAt(t: Float) = a + (b - a) * t
    fun closestT(p: Vec2): Float {
        val ab = b - a
        val l2 = ab dot ab
        if (l2 < 1e-9f) return 0f
        return ((p - a) dot ab / l2).coerceIn(0f, 1f)
    }
    fun distanceTo(p: Vec2) = pointAt(closestT(p)).dist(p)
}

object Geo {
    fun polygonArea(poly: List<Vec2>): Float {
        var a = 0f
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            a += p.x * q.y - q.x * p.y
        }
        return a / 2f
    }

    fun absArea(poly: List<Vec2>) = abs(polygonArea(poly))

    /** Ensures counter-clockwise winding in a y-down plan (i.e. positive signed area). */
    fun normalizeWinding(poly: List<Vec2>): List<Vec2> =
        if (polygonArea(poly) < 0) poly.reversed() else poly

    fun centroid(poly: List<Vec2>): Vec2 {
        if (poly.isEmpty()) return Vec2.ZERO
        val a = polygonArea(poly)
        if (abs(a) < 1e-6f) {
            return poly.fold(Vec2.ZERO) { acc, v -> acc + v } / poly.size.toFloat()
        }
        var cx = 0f
        var cy = 0f
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            val f = p.x * q.y - q.x * p.y
            cx += (p.x + q.x) * f
            cy += (p.y + q.y) * f
        }
        return Vec2(cx / (6f * a), cy / (6f * a))
    }

    fun pointInPolygon(p: Vec2, poly: List<Vec2>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val pi = poly[i]
            val pj = poly[j]
            if ((pi.y > p.y) != (pj.y > p.y) &&
                p.x < (pj.x - pi.x) * (p.y - pi.y) / (pj.y - pi.y + 1e-12f) + pi.x
            ) inside = !inside
            j = i
        }
        return inside
    }

    fun edges(poly: List<Vec2>): List<Segment> = poly.indices.map { Segment(poly[it], poly[(it + 1) % poly.size]) }

    fun distanceToBoundary(p: Vec2, poly: List<Vec2>): Float =
        edges(poly).minOf { it.distanceTo(p) }

    data class Bounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
        val width get() = maxX - minX
        val height get() = maxY - minY
        val center get() = Vec2((minX + maxX) / 2f, (minY + maxY) / 2f)
    }

    fun bounds(points: List<Vec2>): Bounds {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            minX = min(minX, p.x); minY = min(minY, p.y)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y)
        }
        return Bounds(minX, minY, maxX, maxY)
    }

    /** Separating axis test for two convex quads. */
    fun convexOverlap(a: List<Vec2>, b: List<Vec2>): Boolean {
        for (poly in listOf(a, b)) {
            for (i in poly.indices) {
                val e = poly[(i + 1) % poly.size] - poly[i]
                val axis = e.perp()
                var minA = Float.MAX_VALUE; var maxA = -Float.MAX_VALUE
                for (p in a) { val d = p dot axis; minA = min(minA, d); maxA = max(maxA, d) }
                var minB = Float.MAX_VALUE; var maxB = -Float.MAX_VALUE
                for (p in b) { val d = p dot axis; minB = min(minB, d); maxB = max(maxB, d) }
                if (maxA <= minB + 1e-4f || maxB <= minA + 1e-4f) return false
            }
        }
        return true
    }

    /** Andrew monotone chain convex hull. */
    fun convexHull(points: List<Vec2>): List<Vec2> {
        if (points.size < 3) return points
        val pts = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (pts.size < 3) return pts
        val lower = ArrayList<Vec2>()
        for (p in pts) {
            while (lower.size >= 2 && (lower[lower.size - 1] - lower[lower.size - 2]) cross (p - lower[lower.size - 2]) <= 0) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = ArrayList<Vec2>()
        for (p in pts.asReversed()) {
            while (upper.size >= 2 && (upper[upper.size - 1] - upper[upper.size - 2]) cross (p - upper[upper.size - 2]) <= 0) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    data class OrientedRect(val center: Vec2, val width: Float, val depth: Float, val angleRad: Float)

    /** Minimum-area enclosing rectangle via rotating calipers over hull edges. */
    fun minAreaRect(points: List<Vec2>): OrientedRect {
        val hull = convexHull(points)
        if (hull.size < 3) {
            val b = bounds(points)
            return OrientedRect(b.center, max(b.width, 0.05f), max(b.height, 0.05f), 0f)
        }
        var best: OrientedRect? = null
        var bestArea = Float.MAX_VALUE
        for (i in hull.indices) {
            val e = (hull[(i + 1) % hull.size] - hull[i]).normalized()
            val n = e.perp()
            var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
            for (p in hull) {
                val u = p dot e; val v = p dot n
                minU = min(minU, u); maxU = max(maxU, u)
                minV = min(minV, v); maxV = max(maxV, v)
            }
            val area = (maxU - minU) * (maxV - minV)
            if (area < bestArea) {
                bestArea = area
                val cu = (minU + maxU) / 2f
                val cv = (minV + maxV) / 2f
                val center = e * cu + n * cv
                best = OrientedRect(center, maxU - minU, maxV - minV, e.angle())
            }
        }
        return best!!
    }

    fun rectCorners(center: Vec2, width: Float, depth: Float, angleRad: Float): List<Vec2> {
        val hw = width / 2f
        val hd = depth / 2f
        return listOf(Vec2(-hw, -hd), Vec2(hw, -hd), Vec2(hw, hd), Vec2(-hw, hd)).map { it.rotated(angleRad) + center }
    }

    fun normalizeDeg(d: Float): Float {
        var a = d % 360f
        if (a < 0) a += 360f
        return a
    }

    fun shortestAngleDiffDeg(a: Float, b: Float): Float {
        var d = normalizeDeg(b - a)
        if (d > 180f) d -= 360f
        return d
    }

    fun sqr(v: Float) = v * v
    fun clamp01(v: Float) = v.coerceIn(0f, 1f)
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    fun safeSqrt(v: Float) = sqrt(max(0f, v))
}
