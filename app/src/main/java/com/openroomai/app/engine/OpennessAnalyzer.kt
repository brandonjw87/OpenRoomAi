package com.openroomai.app.engine

import com.openroomai.app.model.Furniture
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Room
import com.openroomai.app.model.Vec2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Person-sized clearance radii (meters, measured from walkway center to the nearest obstacle). */
const val SQUEEZE_RADIUS = 0.3f   // 60 cm: you can pass sideways
const val COMFORT_RADIUS = 0.45f  // 90 cm: a comfortable walkway (and wheelchair minimum)
const val TURNING_DIAMETER = 1.5f // wheelchair turning circle

data class FlowPath(val target: Furniture, val points: List<Vec2>, val minWidth: Float)

data class OpennessReport(
    val score: Int,
    val clearDiameter: Float,
    val clearCenter: Vec2,
    val reachablePct: Float,
    val visibilityPct: Float,
    val lightPct: Float,
    val accessPct: Float,
    val footprintPct: Float,
    val flows: List<FlowPath>,
    val tightSpots: List<Vec2>,
    val blockedPieces: List<Furniture>,
    val windowBlockers: List<Furniture>,
    /** Heatmap: per-cell distance to obstacles (m), with geometry to draw it. */
    val grid: OccupancyGrid,
    val visible: BooleanArray,
    /** Clearance radius used for "walkable" (0.3 normally, 0.45 for wheelchair goal). */
    val walkRadius: Float = SQUEEZE_RADIUS,
) {
    val turningCircleOk get() = clearDiameter >= TURNING_DIAMETER

    val label: String
        get() = when {
            // The kids' favourite number. Its meaning is that it has no meaning — like a 67 room: perfectly mid.
            score == 67 -> "Six… seven 🤲"
            score >= 85 -> "Airy ✨"
            score >= 70 -> "Open"
            score >= 55 -> "Comfortable"
            score >= 40 -> "Snug"
            else -> "Cramped"
        }
}

/**
 * Turns a layout into an "open feeling" score. The ingredients come from how people actually
 * perceive space: a big uninterrupted floor patch, being able to walk everywhere without
 * squeezing, seeing deep into the room from the doorway, and daylight that isn't walled off.
 */
object OpennessAnalyzer {

    fun analyze(room: Room, layout: List<Furniture>, cell: Float = 0.1f, withFlows: Boolean = true, walkRadius: Float = SQUEEZE_RADIUS): OpennessReport {
        val g = OccupancyGrid(room, cell)
        return analyze(room, layout, g, withFlows, walkRadius)
    }

    fun analyze(room: Room, layout: List<Furniture>, g: OccupancyGrid, withFlows: Boolean, walkRadius: Float = SQUEEZE_RADIUS): OpennessReport {
        g.stamp(layout)
        val n = g.size

        // 1) Biggest clear circle on the floor.
        var best = 0f
        var bestIdx = 0
        var freeCount = 0
        for (i in 0 until n) {
            if (g.inside[i] && !g.blocked[i]) {
                freeCount++
                if (g.dist[i] > best) { best = g.dist[i]; bestIdx = i }
            }
        }
        val clearDiameter = best * 2f
        val clearCenter = g.center(bestIdx % g.w, bestIdx / g.w)

        // 2) Walkable floor reachable from the entry without squeezing.
        val entry = room.entryPoint()
        val start = nearestWalkable(g, entry, walkRadius)
        val parent = IntArray(n) { -2 }
        var reachable = 0
        if (start >= 0) {
            val q = IntArray(n)
            var qh = 0; var qt = 0
            q[qt++] = start; parent[start] = -1
            while (qh < qt) {
                val c = q[qh++]
                reachable++
                val cx = c % g.w; val cy = c / g.w
                for (k in 0 until 8) {
                    val nx = cx + DX[k]; val ny = cy + DY[k]
                    if (!g.inGrid(nx, ny)) continue
                    val ni = ny * g.w + nx
                    if (parent[ni] != -2 || g.dist[ni] < walkRadius) continue
                    parent[ni] = c
                    q[qt++] = ni
                }
            }
        }
        val reachablePct = min(1f, reachable.toFloat() / g.emptyWalkable(walkRadius))

        // 3) Can you get to the usable side of every piece?
        val flows = ArrayList<FlowPath>()
        val blockedPieces = ArrayList<Furniture>()
        var accessible = 0
        val tight = LinkedHashSet<Int>()
        for (f in layout) {
            if (!f.type.needsAccess) { accessible++; continue }
            val target = f.accessPoints().map { nearestReached(g, it, parent, walkRadius + 0.3f) }.firstOrNull { it >= 0 } ?: -1
            if (target >= 0) {
                accessible++
                if (withFlows) {
                    val pts = ArrayList<Vec2>()
                    var c = target
                    var minW = Float.MAX_VALUE
                    while (c >= 0) {
                        pts.add(g.center(c % g.w, c / g.w))
                        minW = min(minW, g.dist[c] * 2f)
                        if (g.dist[c] < COMFORT_RADIUS) tight.add(c)
                        c = parent[c]
                    }
                    pts.reverse()
                    flows.add(FlowPath(f, simplify(pts), minW))
                }
            } else blockedPieces.add(f)
        }
        val accessPct = if (layout.isEmpty()) 1f else accessible.toFloat() / layout.size

        // 4) Sightlines: how much floor you see from the doorway past tall furniture.
        val visible = BooleanArray(n)
        val rays = 180
        val step = g.cell * 0.5f
        val maxLen = g.bounds.width + g.bounds.height
        for (r in 0 until rays) {
            val a = (2.0 * PI * r / rays).toFloat()
            val d = Vec2(cos(a), sin(a))
            var t = 0f
            while (t < maxLen) {
                val p = entry + d * t
                val gx = g.gxOf(p.x); val gy = g.gyOf(p.y)
                if (!g.inGrid(gx, gy)) break
                val i = gy * g.w + gx
                if (!g.inside[i] || g.tall[i]) break
                visible[i] = true
                t += step
            }
        }
        var visCount = 0
        var visDen = 0
        for (i in 0 until n) if (g.inside[i] && !g.tall[i]) { visDen++; if (visible[i]) visCount++ }
        val visibilityPct = if (visDen == 0) 0f else visCount.toFloat() / visDen

        // 5) Daylight: tall pieces parked in front of windows.
        val windowBlockers = LinkedHashSet<Furniture>()
        var lightSamples = 0
        var lightOk = 0
        for (wdw in room.windows()) {
            val seg = room.openingSegment(wdw)
            val nrm = room.inwardNormal(wdw.wallIndex)
            for (s in 0 until 8) {
                val p = seg.pointAt((s + 0.5f) / 8f)
                lightSamples++
                var ok = true
                for (f in layout) {
                    if (f.height < 1.0f) continue
                    val probe1 = p + nrm * 0.25f
                    val probe2 = p + nrm * 0.6f
                    if (OccupancyGrid.pointInConvex(probe1, f.corners()) || OccupancyGrid.pointInConvex(probe2, f.corners())) {
                        ok = false; windowBlockers.add(f)
                    }
                }
                if (ok) lightOk++
            }
        }
        val lightPct = if (lightSamples == 0) 1f else lightOk.toFloat() / lightSamples

        val footprint = layout.sumOf { it.footprint().toDouble() }.toFloat()
        val footprintPct = (footprint / room.area.coerceAtLeast(0.1f)).coerceIn(0f, 1f)

        val minSide = min(g.bounds.width, g.bounds.height)
        val clearNorm = Geo.clamp01(clearDiameter / max(0.8f, 0.6f * minSide))

        val raw = 0.27f * clearNorm + 0.25f * reachablePct + 0.18f * visibilityPct + 0.12f * lightPct + 0.18f * accessPct
        val score = (raw * 100f).roundToInt().coerceIn(0, 100)

        val tightPts = tight.take(60).map { g.center(it % g.w, it / g.w) }
        return OpennessReport(
            score, clearDiameter, clearCenter, reachablePct, visibilityPct, lightPct, accessPct,
            footprintPct, flows, tightPts, blockedPieces, windowBlockers.toList(), g, visible, walkRadius,
        )
    }

    private val DX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
    private val DY = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)

    private fun nearestWalkable(g: OccupancyGrid, p: Vec2, radius: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        val r = (1.2f / g.cell).toInt()
        val cx = g.gxOf(p.x); val cy = g.gyOf(p.y)
        for (dy in -r..r) for (dx in -r..r) {
            val x = cx + dx; val y = cy + dy
            if (!g.inGrid(x, y)) continue
            val i = y * g.w + x
            if (g.dist[i] < radius) continue
            val d = g.center(x, y).dist(p)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun nearestReached(g: OccupancyGrid, p: Vec2, parent: IntArray, maxDist: Float): Int {
        var best = -1
        var bestD = maxDist
        val r = (maxDist / g.cell).toInt() + 1
        val cx = g.gxOf(p.x); val cy = g.gyOf(p.y)
        for (dy in -r..r) for (dx in -r..r) {
            val x = cx + dx; val y = cy + dy
            if (!g.inGrid(x, y)) continue
            val i = y * g.w + x
            if (parent[i] == -2) continue
            val d = g.center(x, y).dist(p)
            if (d <= bestD) { bestD = d; best = i }
        }
        return best
    }

    /** Drop collinear grid steps so paths draw as smooth polylines. */
    private fun simplify(pts: List<Vec2>): List<Vec2> {
        if (pts.size < 3) return pts
        val out = ArrayList<Vec2>()
        out.add(pts.first())
        for (i in 1 until pts.size - 1) {
            val a = pts[i] - pts[i - 1]
            val b = pts[i + 1] - pts[i]
            if (kotlin.math.abs(a cross b) > 1e-5f) out.add(pts[i])
        }
        out.add(pts.last())
        return out
    }
}
