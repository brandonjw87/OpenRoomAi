package com.openroomai.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Geo
import com.openroomai.app.model.OpeningType
import com.openroomai.app.model.Room
import com.openroomai.app.model.Vec2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** World space: x = plan x, y = up, z = plan y (same as ARCore). */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(this dot this)
    fun norm(): V3 { val l = len(); return if (l < 1e-6f) this else this * (1f / l) }
}

fun Vec2.at(h: Float) = V3(x, h, y)

class Camera3D(val eye: V3, forward: V3, val fovDeg: Float, val width: Float, val height: Float) {
    val fwd = forward.norm()
    val right = (fwd cross V3(0f, 1f, 0f)).norm().let { if (it.len() < 0.5f) V3(1f, 0f, 0f) else it }
    val up = right cross fwd
    /** FOV applies to the shorter screen side, so portrait phones get a natural horizontal view. */
    private val focal = (min(width, height) / 2f) / tan(Math.toRadians(fovDeg / 2.0).toFloat())

    /** Camera space: x right, y up, z forward (depth). */
    fun toCam(p: V3): V3 { val d = p - eye; return V3(d dot right, d dot up, d dot fwd) }
    fun project(c: V3) = Offset(width / 2f + focal * c.x / c.z, height / 2f - focal * c.y / c.z)

    companion object {
        const val NEAR = 0.06f

        fun orbit(target: V3, yaw: Float, pitch: Float, dist: Float, w: Float, h: Float): Camera3D {
            val eye = target + V3(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch)) * dist
            return Camera3D(eye, target - eye, 60f, w, h)
        }

        /** yaw 0 looks toward -z (the "top" of the plan). */
        fun firstPerson(eye: V3, yaw: Float, pitch: Float, w: Float, h: Float) =
            Camera3D(eye, V3(-sin(yaw) * cos(pitch), sin(pitch), -cos(yaw) * cos(pitch)), 80f, w, h)

        fun yawToward(from: Vec2, to: Vec2): Float { val d = to - from; return kotlin.math.atan2(-d.x, -d.y) }
    }
}

/** A flat polygon in world space with its outward normal and base color. */
class Face(val pts: List<V3>, val normal: V3, val color: Color, val edge: Boolean = true)

/** A projected, clipped, shaded polygon ready to draw. */
class ScreenPoly(val pts: List<Offset>, val depth: Float, val color: Color, val edge: Boolean)

object Scene3D {
    private val light = V3(0.45f, 1f, 0.35f).norm()
    val Floor = Color(0xFFC8B08E)
    val FloorLine = Color(0x33000000)
    val WallCol = Color(0xFFE7E9EE)
    val DoorCol = Color(0xFF8D6E63)
    val GlassCol = Color(0xFF9FD3F7)
    val MirrorCol = Color(0xFFDCEAF5)
    val MirrorGlint = Color(0xFFFFFFFF)
    const val WALL_H = 2.45f

    fun shade(c: Color, n: V3): Color {
        val k = 0.58f + 0.42f * max(0f, n dot light)
        return Color(c.red * k, c.green * k, c.blue * k, c.alpha)
    }

    /** Clip a polygon to the near plane (camera space) — Sutherland–Hodgman on one plane. */
    fun clipNear(poly: List<V3>): List<V3> {
        val out = ArrayList<V3>(poly.size + 2)
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val ain = a.z >= Camera3D.NEAR
            val bin = b.z >= Camera3D.NEAR
            if (ain) out.add(a)
            if (ain != bin) {
                val t = (Camera3D.NEAR - a.z) / (b.z - a.z)
                out.add(a + (b - a) * t)
            }
        }
        return out
    }

    fun projectFace(cam: Camera3D, f: Face, backfaceCull: Boolean): ScreenPoly? {
        if (backfaceCull) {
            val c = f.pts.fold(V3(0f, 0f, 0f)) { a, p -> a + p } * (1f / f.pts.size)
            if ((cam.eye - c) dot f.normal < 0f) return null
        }
        val camPts = clipNear(f.pts.map { cam.toCam(it) })
        if (camPts.size < 3) return null
        var depth = 0f
        for (p in camPts) depth += p.z
        depth /= camPts.size
        return ScreenPoly(camPts.map { cam.project(it) }, depth, shade(f.color, f.normal), f.edge)
    }

    fun projectLine(cam: Camera3D, a: V3, b: V3): Pair<Offset, Offset>? {
        var ca = cam.toCam(a); var cb = cam.toCam(b)
        if (ca.z < Camera3D.NEAR && cb.z < Camera3D.NEAR) return null
        if (ca.z < Camera3D.NEAR) ca = ca + (cb - ca) * ((Camera3D.NEAR - ca.z) / (cb.z - ca.z))
        if (cb.z < Camera3D.NEAR) cb = cb + (ca - cb) * ((Camera3D.NEAR - cb.z) / (ca.z - cb.z))
        return cam.project(ca) to cam.project(cb)
    }

    // ------------------------------------------------------------------ geometry

    fun floor(room: Room): Face = Face(room.outline.map { it.at(0f) }, V3(0f, 1f, 0f), Floor, edge = false)

    /**
     * Walls as inward-facing quads. [stubFor] returns true for walls that would hide the room
     * from an outside camera — those are drawn as low cut-away stubs (dollhouse style).
     */
    fun walls(room: Room, stubFor: (Int) -> Boolean): Pair<List<Face>, List<Face>> {
        val full = ArrayList<Face>()
        val stubs = ArrayList<Face>()
        room.walls().forEachIndexed { i, w ->
            val n = room.inwardNormal(i)
            val nn = V3(n.x, 0f, n.y)
            val stub = stubFor(i)
            val h = if (stub) 0.14f else WALL_H
            val quad = listOf(w.a.at(0f), w.b.at(0f), w.b.at(h), w.a.at(h))
            (if (stub) stubs else full) += Face(quad, nn, room.paint?.let { Color(it.argb) } ?: WallCol)
            if (!stub) for (o in room.openings.filter { it.wallIndex == i }) {
                val s = room.openingSegment(o)
                val off = n * 0.012f
                val (y0, y1, col) = if (o.type == OpeningType.DOOR) Triple(0f, 2.05f, DoorCol) else Triple(0.9f, 2.15f, GlassCol)
                full += Face(listOf((s.a + off).at(y0), (s.b + off).at(y0), (s.b + off).at(y1), (s.a + off).at(y1)), nn, col)
            }
            // Mirrors: a silvery pane with a brighter glint stripe.
            if (!stub) for (m in room.mirrors.filter { it.wallIndex == i }) {
                val s = room.mirrorSegment(m)
                val off = n * 0.015f
                full += Face(listOf((s.a + off).at(m.bottom), (s.b + off).at(m.bottom), (s.b + off).at(m.top), (s.a + off).at(m.top)), nn, MirrorCol)
                val g0 = s.pointAt(0.15f) + n * 0.017f; val g1 = s.pointAt(0.32f) + n * 0.017f
                full += Face(listOf(g0.at(m.bottom + 0.1f), g1.at(m.bottom + 0.1f), g1.at(m.top - 0.1f), g0.at(m.top - 0.1f)), nn, MirrorGlint, edge = false)
            }
        }
        return full to stubs
    }

    private class Part(val lx: Float, val ly: Float, val w: Float, val d: Float, val z0: Float, val z1: Float, val color: Color)

    /** Recognizable low-poly shapes per furniture type, in furniture-local coords. */
    private fun parts(f: Furniture): List<Part> {
        val w = f.width; val d = f.depth; val h = f.height
        val c = Color(f.displayArgb)
        val dark = Color(c.red * 0.7f, c.green * 0.7f, c.blue * 0.7f)
        val wood = Color(0xFF9C7A54)
        val white = Color(0xFFF2F2F2)
        fun legs(top: Float, inset: Float = 0.04f, s: Float = 0.05f) = listOf(-1f, 1f).flatMap { sx ->
            listOf(-1f, 1f).map { sy -> Part(sx * (w / 2f - inset - s / 2f), sy * (d / 2f - inset - s / 2f), s, s, 0f, top, dark) }
        }
        return when (f.type) {
            FurnitureType.SOFA, FurnitureType.ARMCHAIR -> {
                val arm = min(0.18f, w * 0.15f)
                listOf(
                    Part(0f, 0.05f, w - 2 * arm, d - 0.1f, 0.12f, 0.44f, c),
                    Part(0f, -d / 2f + 0.11f, w, 0.22f, 0.12f, h, dark),
                    Part(-w / 2f + arm / 2f, 0f, arm, d, 0.12f, min(h, 0.64f), dark),
                    Part(w / 2f - arm / 2f, 0f, arm, d, 0.12f, min(h, 0.64f), dark),
                ) + legs(0.12f, 0.06f, 0.06f)
            }
            FurnitureType.BED -> listOf(
                Part(0f, 0f, w, d, 0.08f, 0.32f, dark),
                Part(0f, 0.04f, w - 0.06f, d - 0.12f, 0.32f, 0.55f, white),
                Part(-w / 4f, -d / 2f + 0.3f, w / 2f - 0.12f, 0.35f, 0.55f, 0.66f, white),
                Part(w / 4f, -d / 2f + 0.3f, w / 2f - 0.12f, 0.35f, 0.55f, 0.66f, white),
                Part(0f, 0.35f, w - 0.04f, d * 0.55f, 0.55f, 0.6f, c),
                Part(0f, -d / 2f + 0.04f, w, 0.08f, 0f, max(h, 1.0f), wood),
            ) + legs(0.08f, 0.03f, 0.07f)
            FurnitureType.COFFEE_TABLE, FurnitureType.DINING_TABLE, FurnitureType.DESK ->
                listOf(Part(0f, 0f, w, d, h - 0.04f, h, c)) + legs(h - 0.04f)
            FurnitureType.CHAIR -> listOf(
                Part(0f, 0f, w, d, 0.42f, 0.47f, c),
                Part(0f, -d / 2f + 0.03f, w, 0.05f, 0.47f, h, c),
            ) + legs(0.42f, 0.03f, 0.04f)
            FurnitureType.TV_STAND -> {
                val tw = w * 0.85f
                listOf(
                    Part(0f, 0f, w, d, 0.06f, h, c),
                    Part(0f, -d / 2f + 0.12f, tw, 0.05f, h + 0.08f, h + 0.08f + tw * 0.56f, Color(0xFF15171C)),
                    Part(0f, -d / 2f + 0.12f, 0.1f, 0.08f, h, h + 0.1f, Color(0xFF15171C)),
                ) + legs(0.06f)
            }
            FurnitureType.PLANT -> listOf(
                Part(0f, 0f, w * 0.6f, d * 0.6f, 0f, 0.32f, Color(0xFFB36A4A)),
                Part(0f, 0f, w, d, 0.32f, h, c),
            )
            FurnitureType.BOOKSHELF -> listOf(Part(0f, 0f, w, d, 0f, h, c)) +
                (1..4).map { k -> Part(0f, d / 2f - 0.005f, w - 0.06f, 0.01f, h * k / 5f - 0.02f, h * k / 5f, dark) }
            FurnitureType.NIGHTSTAND, FurnitureType.DRESSER -> listOf(Part(0f, 0f, w, d, 0.08f, h, c)) + legs(0.08f)
            else -> listOf(Part(0f, 0f, w, d, 0f, h, c))
        }
    }

    fun furniture(f: Furniture, highlight: Boolean): List<Face> {
        val r = f.right(); val fr = f.front()
        val out = ArrayList<Face>()
        for (p in parts(f)) {
            val col = if (highlight) Color(0xFF2EE6A8) else p.color
            val center = f.center + r * p.lx + fr * p.ly
            val c = Geo.rectCorners(center, p.w, p.d, f.angleRad)
            val bottom = c.map { it.at(p.z0) }
            val top = c.map { it.at(p.z1) }
            out += Face(top, V3(0f, 1f, 0f), col)
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                val e = c[j] - c[i]
                // Corners go clockwise in a y-down plan → outward normal is (e.y, -e.x)…check against the center.
                var n = Vec2(e.y, -e.x).normalized()
                if (((c[i] + c[j]) / 2f - center) dot n < 0) n = -n
                out += Face(listOf(bottom[i], bottom[j], top[j], top[i]), V3(n.x, 0f, n.y), col)
            }
        }
        return out
    }

    /** Dashed-looking ring on the floor showing the biggest open circle. */
    fun ring(center: Vec2, r: Float, segments: Int = 48): List<V3> =
        (0..segments).map { k -> val a = k * 2f * Math.PI.toFloat() / segments; V3(center.x + cos(a) * r, 0.01f, center.y + sin(a) * r) }

    /** Furniture you sit or lie on: candidate first-person viewpoints. */
    fun seats(layout: List<Furniture>) = layout.filter { it.type == FurnitureType.SOFA || it.type == FurnitureType.ARMCHAIR || it.type == FurnitureType.BED }

    fun seatEye(f: Furniture): Pair<V3, Float> {
        val p = f.center + f.front() * (if (f.type == FurnitureType.BED) -f.depth * 0.25f else f.depth * 0.05f)
        val h = if (f.type == FurnitureType.BED) 1.0f else 1.12f
        return p.at(h) to Camera3D.yawToward(p, p + f.front())
    }
}
