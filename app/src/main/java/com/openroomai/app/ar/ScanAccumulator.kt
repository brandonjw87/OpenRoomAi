package com.openroomai.app.ar

import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Vec2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class DetectedObject(
    val id: Int,
    val center: Vec2,
    val width: Float,
    val depth: Float,
    val angleRad: Float,
    val height: Float,
    val guess: FurnitureType,
    val confidence: Float,
    /** What the camera model recognised it as, e.g. "couch 82%". */
    val seenAs: String? = null,
    /** Average colour seen by the camera (ARGB). */
    val color: Long? = null,
    /** Added from camera sightings alone (the depth scan missed it). */
    val fromCamera: Boolean = false,
    val name: String? = null,
) {
    fun corners() = Geo.rectCorners(center, width, depth, angleRad)
}

/**
 * Accumulates 3D samples (from the depth API and/or feature points) into a top-down height map,
 * then segments it into furniture-sized blobs.
 *
 * Everything here runs on the GL thread; callers snapshot results.
 */
class ScanAccumulator(private val cell: Float = 0.05f) {
    private class Cell {
        var obs = 0
        var floor = 0
        var maxH = 0f
        var sumH = 0f
    }

    private val cells = HashMap<Long, Cell>()
    var samples = 0L
        private set

    private fun key(ix: Int, iz: Int): Long = (ix.toLong() shl 32) or (iz.toLong() and 0xffffffffL)
    private fun ixOf(v: Float) = floor(v / cell).toInt()

    fun clear() { cells.clear(); samples = 0 }

    fun add(x: Float, y: Float, z: Float, floorY: Float) {
        val h = y - floorY
        if (h < -0.08f || h > 2.3f) return
        val k = key(ixOf(x), ixOf(z))
        val c = cells.getOrPut(k) { Cell() }
        samples++
        if (h < 0.035f) { c.floor++; return }
        if (h < 0.07f) return
        c.obs++
        c.sumH += h
        if (h > c.maxH) c.maxH = h
    }

    /** Coverage: cells observed at all; drives the "scan progress" meter. */
    fun coveredArea(): Float = cells.size * cell * cell

    /**
     * @param outline room outline (world x/z) used to drop wall hits; null = keep everything.
     */
    fun detect(outline: List<Vec2>?): List<DetectedObject> {
        val valid = HashMap<Long, Float>()
        for ((k, c) in cells) {
            if (c.obs < 4) continue
            if (c.obs < c.floor * 0.6f) continue
            val ix = (k shr 32).toInt()
            val iz = k.toInt()
            val p = Vec2((ix + 0.5f) * cell, (iz + 0.5f) * cell)
            if (outline != null && outline.size >= 3) {
                if (!Geo.pointInPolygon(p, outline)) continue
                if (Geo.distanceToBoundary(p, outline) < 0.12f) continue
            }
            // A cell whose points reach above ~2.1m and are thin is most likely a wall/door frame.
            valid[k] = c.maxH
        }
        // Connected components with a 1-cell bridge tolerance.
        val seen = HashSet<Long>()
        val out = ArrayList<DetectedObject>()
        var id = 0
        for (start in valid.keys) {
            if (start in seen) continue
            val comp = ArrayList<Long>()
            val stack = ArrayDeque<Long>()
            stack.add(start); seen.add(start)
            while (stack.isNotEmpty()) {
                val k = stack.removeLast()
                comp.add(k)
                val ix = (k shr 32).toInt(); val iz = k.toInt()
                for (dz in -2..2) for (dx in -2..2) {
                    if (dx == 0 && dz == 0) continue
                    val nk = key(ix + dx, iz + dz)
                    if (nk in valid && nk !in seen) { seen.add(nk); stack.add(nk) }
                }
            }
            val area = comp.size * cell * cell
            if (area < 0.06f) continue
            val pts = ArrayList<Vec2>(comp.size * 4)
            val heights = FloatArray(comp.size)
            comp.forEachIndexed { i, k ->
                val ix = (k shr 32).toInt(); val iz = k.toInt()
                val x0 = ix * cell; val z0 = iz * cell
                pts.add(Vec2(x0, z0)); pts.add(Vec2(x0 + cell, z0)); pts.add(Vec2(x0, z0 + cell)); pts.add(Vec2(x0 + cell, z0 + cell))
                heights[i] = valid[k]!!
            }
            heights.sort()
            val h = heights[(heights.size * 0.85f).toInt().coerceAtMost(heights.size - 1)]
            val r = Geo.minAreaRect(pts)
            // Keep "width" as the long side to make type guessing simpler.
            val (w, d, ang) = if (r.width >= r.depth) Triple(r.width, r.depth, r.angleRad)
            else Triple(r.depth, r.width, r.angleRad + (Math.PI / 2).toFloat())
            if (w > 3.2f || (h > 2.05f && d < 0.25f)) continue // walls, door frames, curtains
            val fill = area / (w * d)
            out += DetectedObject(id++, r.center, w, d, ang, h, guess(w, d, h), Geo.clamp01(fill * min(1f, comp.size / 60f)))
        }
        return out.sortedByDescending { it.width * it.depth }
    }

    companion object {
        fun guess(w: Float, d: Float, h: Float): FurnitureType {
            val area = w * d
            return when {
                h > 1.5f && d < 0.45f -> FurnitureType.BOOKSHELF
                h > 1.5f -> FurnitureType.WARDROBE
                area > 2.4f && h < 0.8f -> FurnitureType.BED
                w in 1.4f..2.8f && d in 0.65f..1.1f && h in 0.55f..1.05f -> FurnitureType.SOFA
                h < 0.55f && area in 0.25f..1.2f -> FurnitureType.COFFEE_TABLE
                h in 0.62f..0.82f && w >= 1.3f && d >= 0.7f -> FurnitureType.DINING_TABLE
                h in 0.62f..0.82f && w in 0.8f..1.8f -> FurnitureType.DESK
                w < 0.6f && d < 0.6f && h > 0.75f -> FurnitureType.CHAIR
                w < 0.65f && d < 0.55f -> FurnitureType.NIGHTSTAND
                w in 0.7f..1.1f && d in 0.7f..1.1f -> FurnitureType.ARMCHAIR
                h in 0.8f..1.4f && d < 0.65f -> FurnitureType.DRESSER
                d < 0.55f && w > 1.2f && h < 0.75f -> FurnitureType.TV_STAND
                else -> FurnitureType.OTHER
            }
        }

        fun maxOf3(a: Float, b: Float, c: Float) = max(a, max(b, c))
    }
}
