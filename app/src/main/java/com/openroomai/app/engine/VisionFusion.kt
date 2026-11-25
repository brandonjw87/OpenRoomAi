package com.openroomai.app.engine

import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Vec2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Axis-aligned box in normalized image coordinates (0…1, y down). */
data class NBox(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = max(0f, r - l)
    val h get() = max(0f, b - t)
    val area get() = w * h
    fun intersect(o: NBox) = NBox(max(l, o.l), max(t, o.t), min(r, o.r), min(b, o.b))
    fun iou(o: NBox): Float {
        val i = intersect(o).area
        val u = area + o.area - i
        return if (u <= 0f) 0f else i / u
    }
}

/** One camera-model detection; [box] is in the *upright* (display-oriented) image. */
data class Detection(val label: String, val score: Float, val box: NBox, val colorArgb: Int? = null)

/**
 * Pinhole model of ARCore's CPU camera image (sensor orientation) at capture time.
 * [worldFromCamera] is `Pose.toMatrix()` (column-major) of `camera.pose`: +X right, +Y up and
 * −Z forward relative to the image readout.
 */
class CameraSnapshot(
    val worldFromCamera: FloatArray,
    val fx: Float, val fy: Float, val cx: Float, val cy: Float,
    val width: Int, val height: Int,
    /** Clockwise rotation that turns the sensor image upright on screen. */
    val rotationDeg: Int,
)

/**
 * "Judging furniture in pictures": fuses an on-device COCO object detector with the depth
 * scan. Pictures alone are unreliable (in testing, a dresser came back as "refrigerator" and a
 * staircase as "bench"), so the camera only *votes* for a 3D cluster when the boxes line up,
 * votes accumulate over several frames, and the measured size has the final say.
 */
object VisionFusion {
    /** Per-class minimum confidence, tuned on SUN RGB-D style indoor photos. */
    val MIN_SCORE = mapOf(
        "couch" to 0.45f, "bed" to 0.5f, "chair" to 0.45f, "dining table" to 0.4f, "tv" to 0.5f,
        "potted plant" to 0.45f, "refrigerator" to 0.5f, "bench" to 0.55f, "book" to 0.35f,
        "toilet" to 0.5f, "sink" to 0.5f, "oven" to 0.5f,
    )

    /** Classes trusted enough to add a piece the depth scan missed (needs ≥ 2 sightings). */
    val CAMERA_ONLY = setOf("couch", "chair", "bed", "dining table", "potted plant")

    fun relevant(d: Detection): Boolean = MIN_SCORE[d.label]?.let { d.score >= it } ?: false

    private fun norm(rot: Int) = ((rot % 360) + 360) % 360

    fun uprightToSensor(x: Float, y: Float, rot: Int): Vec2 = when (norm(rot)) {
        90 -> Vec2(y, 1f - x)
        180 -> Vec2(1f - x, 1f - y)
        270 -> Vec2(1f - y, x)
        else -> Vec2(x, y)
    }

    fun sensorToUpright(x: Float, y: Float, rot: Int): Vec2 = when (norm(rot)) {
        90 -> Vec2(1f - y, x)
        180 -> Vec2(1f - x, 1f - y)
        270 -> Vec2(y, 1f - x)
        else -> Vec2(x, y)
    }

    fun boxToSensor(b: NBox, rot: Int): NBox {
        val pts = listOf(uprightToSensor(b.l, b.t, rot), uprightToSensor(b.r, b.t, rot), uprightToSensor(b.l, b.b, rot), uprightToSensor(b.r, b.b, rot))
        return NBox(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }

    /** World point → sensor-normalized pixel, or null when behind / beside the camera. */
    fun project(cam: CameraSnapshot, x: Float, y: Float, z: Float): Vec2? {
        val m = cam.worldFromCamera
        val dx = x - m[12]; val dy = y - m[13]; val dz = z - m[14]
        val cxv = dx * m[0] + dy * m[1] + dz * m[2]
        val cyv = dx * m[4] + dy * m[5] + dz * m[6]
        val czv = dx * m[8] + dy * m[9] + dz * m[10]
        if (czv > -0.15f) return null
        val u = cam.cx + cam.fx * cxv / -czv
        val v = cam.cy - cam.fy * cyv / -czv
        return Vec2(u / cam.width, v / cam.height)
    }

    /** Image box of a furniture box (footprint corners × floor…top), clipped to the frame. */
    fun projectBox(cam: CameraSnapshot, corners: List<Vec2>, floorY: Float, height: Float): NBox? {
        val pts = ArrayList<Vec2>(8)
        for (c in corners) for (yy in floatArrayOf(floorY, floorY + height)) pts += project(cam, c.x, yy, c.y) ?: return null
        val full = NBox(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
        val vis = full.intersect(NBox(0f, 0f, 1f, 1f))
        if (vis.area < 0.003f || vis.area < 0.3f * full.area) return null
        return vis
    }

    /** Sensor-normalized pixel → point on the floor plane, or null if the ray misses it. */
    fun floorPoint(cam: CameraSnapshot, sx: Float, sy: Float, floorY: Float): Vec2? {
        val m = cam.worldFromCamera
        val rx = (sx * cam.width - cam.cx) / cam.fx
        val ry = -(sy * cam.height - cam.cy) / cam.fy
        val rz = -1f
        val wx = m[0] * rx + m[4] * ry + m[8] * rz
        val wy = m[1] * rx + m[5] * ry + m[9] * rz
        val wz = m[2] * rx + m[6] * ry + m[10] * rz
        if (abs(wy) < 1e-4f) return null
        val t = (floorY - m[13]) / wy
        if (t <= 0f) return null
        val p = Vec2(m[12] + wx * t, m[14] + wz * t)
        return if (p.dist(Vec2(m[12], m[14])) > 7f) null else p
    }

    /** A 3D cluster as the camera sees it. */
    data class Cluster(val key: Vec2, val corners: List<Vec2>, val height: Float)

    /** Pairs each relevant detection with the cluster it overlaps best (IoU, or containment for small things). */
    fun match(cam: CameraSnapshot, dets: List<Detection>, clusters: List<Cluster>, floorY: Float): Pair<List<Pair<Cluster, Detection>>, List<Detection>> {
        val views = clusters.mapNotNull { c -> projectBox(cam, c.corners, floorY, c.height)?.let { c to it } }
        val matched = ArrayList<Pair<Cluster, Detection>>()
        val unmatched = ArrayList<Detection>()
        for (d in dets.filter(::relevant)) {
            val sb = boxToSensor(d.box, cam.rotationDeg)
            var best: Cluster? = null
            var bestScore = 0f
            for ((c, vb) in views) {
                val inter = sb.intersect(vb).area
                val containment = if (sb.area > 0f) inter / sb.area else 0f
                val s = max(sb.iou(vb), 0.6f * containment)
                if (s > bestScore) { bestScore = s; best = c }
            }
            if (best != null && bestScore >= 0.25f) matched += best to d else unmatched += d
        }
        return matched to unmatched
    }

    /** Running votes per spot on the floor, so one noisy frame can't relabel anything. */
    class VoteBook(private val radius: Float = 0.55f) {
        class Entry(var at: Vec2) {
            val votes = HashMap<String, Float>()
            var sightings = 0
            var cameraOnly = true
            private var r = 0L; private var g = 0L; private var b = 0L; private var n = 0
            fun addColor(argb: Int) { r += (argb shr 16) and 0xFF; g += (argb shr 8) and 0xFF; b += argb and 0xFF; n++ }
            val color: Long? get() = if (n == 0) null else 0xFF000000L or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            fun best(): Pair<String, Float>? = votes.maxByOrNull { it.value }?.let { it.key to it.value }
        }

        val entries = ArrayList<Entry>()

        fun vote(at: Vec2, d: Detection, cameraOnly: Boolean) {
            val e = entries.filter { it.at.dist(at) < radius }.minByOrNull { it.at.dist(at) } ?: Entry(at).also { entries += it }
            if (!cameraOnly) { e.cameraOnly = false; e.at = at }
            e.votes[d.label] = (e.votes[d.label] ?: 0f) + d.score
            e.sightings++
            d.colorArgb?.let { e.addColor(it) }
        }

        fun near(at: Vec2): Entry? = entries.filter { it.at.dist(at) < radius }.minByOrNull { it.at.dist(at) }

        fun clear() = entries.clear()
    }

    /**
     * Final type for a measured box given the camera's votes. Size and height gate every
     * label, e.g. a "refrigerator" lower than 1.3 m is really a dresser.
     */
    fun refine(geometry: FurnitureType, entry: VoteBook.Entry?, w: Float, d: Float, h: Float): Pair<FurnitureType, String?> {
        val best = entry?.best() ?: return geometry to null
        val (label, total) = best
        if (total < 0.9f) return geometry to null
        val long = max(w, d); val short = min(w, d)
        val conf = (100 * total / entry.sightings.coerceAtLeast(1)).roundToInt().coerceAtMost(99)
        val seen = "$label $conf%"
        val t = when (label) {
            "couch" -> if (long >= 1.25f) FurnitureType.SOFA else FurnitureType.ARMCHAIR
            "chair" -> if (long >= 0.7f && short >= 0.65f) FurnitureType.ARMCHAIR else FurnitureType.CHAIR
            "bed" -> if (long * short >= 1.4f) FurnitureType.BED else geometry
            "dining table" -> when {
                h < 0.55f -> FurnitureType.COFFEE_TABLE
                long >= 1.3f && short >= 0.7f -> FurnitureType.DINING_TABLE
                else -> FurnitureType.DESK
            }
            "tv" -> FurnitureType.TV_STAND
            "potted plant" -> if (long <= 0.9f) FurnitureType.PLANT else geometry
            "refrigerator" -> if (h >= 1.3f) FurnitureType.OTHER else FurnitureType.DRESSER
            "book" -> if (h >= 1.1f) FurnitureType.BOOKSHELF else geometry
            "bench", "toilet", "sink", "oven" -> FurnitureType.OTHER
            else -> geometry
        }
        return t to seen
    }

    /** Friendly name for OTHER pieces the camera recognised. */
    fun nameFor(label: String): String? = when (label) {
        "refrigerator" -> "Fridge"
        "bench" -> "Bench"
        "toilet" -> "Toilet"
        "sink" -> "Sink"
        "oven" -> "Oven"
        else -> null
    }
}
