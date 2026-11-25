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
 * Fine floor grid for open-space measurements, with an exact Euclidean distance transform
 * (Felzenszwalb & Huttenlocher's separable lower-envelope method) instead of a chamfer
 * approximation, so widths are honest to about one cell (1″ at the default size).
 * Build it on a room that's already in its [RoomFrame], so rectangles hug the walls.
 */
class SpaceGrid(val room: Room, val cell: Float = 0.025f) {
    val bounds = Geo.bounds(room.outline)
    val w = max(1, ceil(bounds.width / cell).toInt())
    val h = max(1, ceil(bounds.height / cell).toInt())
    val size = w * h

    val inside = BooleanArray(size)
    /** Covered by furniture. */
    val blocked = BooleanArray(size)
    /** Inside a door's swing: walkable, but not open floor you can use. */
    val swing = BooleanArray(size)
    /** Meters from each walkable cell centre to the nearest furniture / wall cell centre. */
    val clearance = FloatArray(size)
    /** The same distance as an exact integer: squared, in cells (orders cells by width cheaply). */
    val d2 = IntArray(size)

    init {
        for (gy in 0 until h) for (gx in 0 until w) inside[gy * w + gx] = Geo.pointInPolygon(center(gx, gy), room.outline)
    }

    fun center(gx: Int, gy: Int) = Vec2(bounds.minX + (gx + 0.5f) * cell, bounds.minY + (gy + 0.5f) * cell)
    fun center(i: Int) = center(i % w, i / w)
    fun gxOf(x: Float) = floor((x - bounds.minX) / cell).toInt()
    fun gyOf(y: Float) = floor((y - bounds.minY) / cell).toInt()
    fun inGrid(gx: Int, gy: Int) = gx in 0 until w && gy in 0 until h
    fun walkable(i: Int) = inside[i] && !blocked[i]
    fun open(i: Int) = inside[i] && !blocked[i] && !swing[i]

    /**
     * Clear lane width through cell [i]: the widest body that fits there. Clearance is measured
     * between cell centres, which runs about half a cell long per side, hence the correction.
     */
    fun width(i: Int): Float = max(0f, 2f * clearance[i] - 0.5f * cell)

    /** [width] for a cell whose squared distance is [d2] cells². */
    fun widthOfD2(d2: Int): Float = max(0f, 2f * sqrt(d2.toFloat()) * cell - 0.5f * cell)

    fun stamp(layout: List<Furniture>, swings: List<List<Vec2>>) {
        java.util.Arrays.fill(blocked, false)
        java.util.Arrays.fill(swing, false)
        fill(layout.map { it.corners() }, blocked)
        fill(swings, swing)
        // Obstacles for clearance: furniture and everything outside the walls.
        val src = BooleanArray(size) { !inside[it] || blocked[it] }
        val sq = edt(src)
        for (i in 0 until size) {
            val v = if (src[i]) 0 else (sq[i] + 0.5f).toInt()
            d2[i] = v
            clearance[i] = sqrt(v.toFloat()) * cell
        }
    }

    private fun fill(polys: List<List<Vec2>>, into: BooleanArray) {
        for (c in polys) {
            val b = Geo.bounds(c)
            val x0 = max(0, gxOf(b.minX)); val x1 = min(w - 1, gxOf(b.maxX))
            val y0 = max(0, gyOf(b.minY)); val y1 = min(h - 1, gyOf(b.maxY))
            for (gy in y0..y1) for (gx in x0..x1) {
                val i = gy * w + gx
                if (inside[i] && OccupancyGrid.pointInConvex(center(gx, gy), c)) into[i] = true
            }
        }
    }

    /**
     * Squared distance (in cells²) from every cell to the nearest `true` cell of [src]. With
     * [borderIsSource], the ring of cells just outside the grid counts as `true` too — right for
     * obstacles (beyond the grid is beyond the walls), wrong for anything else.
     */
    fun edt(src: BooleanArray, borderIsSource: Boolean = true): FloatArray {
        val f = FloatArray(size) { if (src[it]) 0f else INF }
        val pad = if (borderIsSource) 0f else INF
        val col = FloatArray(h + 2); val colOut = FloatArray(h + 2)
        for (x in 0 until w) {
            col[0] = pad
            for (y in 0 until h) col[y + 1] = f[y * w + x]
            col[h + 1] = pad
            dt1(col, h + 2, colOut)
            for (y in 0 until h) f[y * w + x] = colOut[y + 1]
        }
        val row = FloatArray(w + 2); val rowOut = FloatArray(w + 2)
        for (y in 0 until h) {
            row[0] = pad
            for (x in 0 until w) row[x + 1] = f[y * w + x]
            row[w + 1] = pad
            dt1(row, w + 2, rowOut)
            for (x in 0 until w) f[y * w + x] = rowOut[x + 1]
        }
        return f
    }

    private val vBuf = IntArray(max(w, h) + 3)
    private val zBuf = DoubleArray(max(w, h) + 4)

    /**
     * 1-D squared distance transform (lower envelope of parabolas). Only finite samples enter
     * the envelope, which keeps it exact even when most of a row is free floor.
     */
    private fun dt1(f: FloatArray, n: Int, out: FloatArray) {
        val v = vBuf; val z = zBuf
        fun meet(q: Int, p: Int): Double = ((f[q] + q.toDouble() * q) - (f[p] + p.toDouble() * p)) / (2.0 * (q - p))
        var k = -1
        for (q in 0 until n) {
            if (f[q] >= INF) continue
            if (k < 0) { k = 0; v[0] = q; z[0] = Double.NEGATIVE_INFINITY; z[1] = Double.POSITIVE_INFINITY; continue }
            var s = meet(q, v[k])
            while (s <= z[k]) { k--; s = meet(q, v[k]) }
            k++
            v[k] = q; z[k] = s; z[k + 1] = Double.POSITIVE_INFINITY
        }
        if (k < 0) { for (q in 0 until n) out[q] = INF; return }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val d = (q - v[k]).toFloat()
            out[q] = d * d + f[v[k]]
        }
    }

    private companion object {
        const val INF = 1e20f
    }
}
