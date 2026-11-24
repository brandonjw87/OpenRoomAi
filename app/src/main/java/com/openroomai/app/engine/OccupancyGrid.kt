package com.openroomai.app.engine

import com.openroomai.app.model.Furniture
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Room
import com.openroomai.app.model.Vec2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rasterized floor plan. Cells are [cell] meters wide; index = gy * w + gx.
 */
class OccupancyGrid(val room: Room, val cell: Float = 0.1f) {
    val bounds = Geo.bounds(room.outline)
    val w = max(1, ceil(bounds.width / cell).toInt())
    val h = max(1, ceil(bounds.height / cell).toInt())
    val size = w * h

    /** Static: cell center inside the room outline. */
    val inside = BooleanArray(size)
    val insideCount: Int

    // Dynamic layers, rebuilt by [stamp].
    val blocked = BooleanArray(size)
    val tall = BooleanArray(size)
    val dist = FloatArray(size)

    /** Walkable cells in the empty room: the denominator for "how much floor can you still use". */
    val emptyWalkable: Int

    init {
        var n = 0
        for (gy in 0 until h) for (gx in 0 until w) {
            if (Geo.pointInPolygon(center(gx, gy), room.outline)) {
                inside[gy * w + gx] = true; n++
            }
        }
        insideCount = n
        stamp(emptyList())
        var walk = 0
        for (i in 0 until size) if (inside[i] && dist[i] >= SQUEEZE_RADIUS) walk++
        emptyWalkable = walk.coerceAtLeast(1)
    }

    fun center(gx: Int, gy: Int) = Vec2(bounds.minX + (gx + 0.5f) * cell, bounds.minY + (gy + 0.5f) * cell)
    fun gxOf(x: Float) = floor((x - bounds.minX) / cell).toInt()
    fun gyOf(y: Float) = floor((y - bounds.minY) / cell).toInt()
    fun inGrid(gx: Int, gy: Int) = gx in 0 until w && gy in 0 until h
    fun idxOf(p: Vec2): Int {
        val gx = gxOf(p.x).coerceIn(0, w - 1)
        val gy = gyOf(p.y).coerceIn(0, h - 1)
        return gy * w + gx
    }

    fun stamp(furniture: List<Furniture>) {
        java.util.Arrays.fill(blocked, false)
        java.util.Arrays.fill(tall, false)
        for (f in furniture) {
            val c = f.corners()
            val b = Geo.bounds(c)
            val x0 = max(0, gxOf(b.minX)); val x1 = min(w - 1, gxOf(b.maxX))
            val y0 = max(0, gyOf(b.minY)); val y1 = min(h - 1, gyOf(b.maxY))
            for (gy in y0..y1) for (gx in x0..x1) {
                val i = gy * w + gx
                if (!inside[i]) continue
                if (pointInConvex(center(gx, gy), c)) {
                    blocked[i] = true
                    if (f.tall) tall[i] = true
                }
            }
        }
        computeDistance()
    }

    /** 8-neighbour chamfer distance (meters) from each free cell to nearest blocked/outside cell. */
    private fun computeDistance() {
        val inf = 1e9f
        val d1 = cell
        val d2 = cell * sqrt(2f)
        for (i in 0 until size) dist[i] = if (inside[i] && !blocked[i]) inf else 0f
        // Distance is measured to cell centers; a free cell touching an obstacle gets ~cell.
        for (gy in 0 until h) for (gx in 0 until w) {
            val i = gy * w + gx
            if (dist[i] == 0f) continue
            var v = dist[i]
            v = min(v, at(gx - 1, gy) + d1)
            v = min(v, at(gx, gy - 1) + d1)
            v = min(v, at(gx - 1, gy - 1) + d2)
            v = min(v, at(gx + 1, gy - 1) + d2)
            dist[i] = v
        }
        for (gy in h - 1 downTo 0) for (gx in w - 1 downTo 0) {
            val i = gy * w + gx
            if (dist[i] == 0f) continue
            var v = dist[i]
            v = min(v, at(gx + 1, gy) + d1)
            v = min(v, at(gx, gy + 1) + d1)
            v = min(v, at(gx + 1, gy + 1) + d2)
            v = min(v, at(gx - 1, gy + 1) + d2)
            dist[i] = v
        }
    }

    private fun at(gx: Int, gy: Int): Float = if (inGrid(gx, gy)) dist[gy * w + gx] else 0f

    companion object {
        fun pointInConvex(p: Vec2, quad: List<Vec2>): Boolean {
            var sign = 0
            for (i in quad.indices) {
                val a = quad[i]; val b = quad[(i + 1) % quad.size]
                val c = (b - a) cross (p - a)
                val s = if (c > 0) 1 else if (c < 0) -1 else 0
                if (s != 0) {
                    if (sign == 0) sign = s else if (s != sign) return false
                }
            }
            return true
        }
    }
}
