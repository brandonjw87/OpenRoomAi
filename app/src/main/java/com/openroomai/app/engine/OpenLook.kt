package com.openroomai.app.engine

import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Mirror
import com.openroomai.app.model.Paint
import com.openroomai.app.model.Room
import com.openroomai.app.model.Segment
import com.openroomai.app.model.Vec2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class MirrorSpot(
    val mirror: Mirror,
    val score: Float,
    /** You'd see the window in it from the doorway / sofa. */
    val reflectsWindow: Boolean,
    /** Faces a window: bounces daylight back into the room. */
    val bouncesDaylight: Boolean,
    /** Extra "see-through" depth the reflection adds from the doorway (m). */
    val extraDepth: Float,
    val reason: String,
)

/**
 * Open-Look Studio: designer tricks that make a room *look* bigger without moving walls —
 * mirrors placed by reflection ray tracing, light-reflective paint, and visual balance.
 */
object OpenLook {

    /** Common paint families with typical Light Reflectance Values. */
    val PAINTS = listOf(
        Paint("Chalk white", 0xFFF3F1EA, 88),
        Paint("Soft grey", 0xFFD9DCDF, 72),
        Paint("Warm greige", 0xFFD6CBBB, 63),
        Paint("Pale sage", 0xFFC8D2BE, 57),
        Paint("Dusty blue", 0xFFADBFCC, 50),
        Paint("Terracotta", 0xFFC07A5A, 27),
        Paint("Deep navy", 0xFF26364A, 8),
    )

    fun paintVerdict(p: Paint): String = when {
        p.lrv >= 70 -> "LRV ${p.lrv}: bounces most of the light — walls seem to recede and the room reads bigger."
        p.lrv >= 50 -> "LRV ${p.lrv}: balanced. Pair with white ceilings and trim to keep it airy."
        else -> "LRV ${p.lrv}: absorbs light and pulls walls closer. Use it on one accent wall (e.g. behind the TV), not all four."
    }

    private class View(val p: Vec2, val weight: Float, val isEntry: Boolean)

    fun mirrorSpots(room: Room, layout: List<Furniture>, width: Float = 0.9f, max: Int = 3): List<MirrorSpot> {
        val walls = room.walls()
        if (walls.isEmpty()) return emptyList()
        val b = Geo.bounds(room.outline)
        val diag = kotlin.math.hypot(b.width, b.height).coerceAtLeast(1f)
        val views = buildList {
            add(View(room.entryPoint(), 1f, true))
            layout.firstOrNull { it.type == FurnitureType.SOFA }?.let { add(View(DesignGuidelines.seatPoint(it), 0.6f, false)) }
        }
        val tall = layout.filter { it.height >= 1.3f }
        val candidates = ArrayList<MirrorSpot>()
        for (wi in walls.indices) {
            val w = walls[wi]
            val n = room.inwardNormal(wi)
            if (w.length < width + 0.2f) continue
            val half = width / 2f
            var c = half + 0.1f
            while (c <= w.length - half - 0.1f) {
                val t = c / w.length
                val seg = Segment(w.pointAt((c - half) / w.length), w.pointAt((c + half) / w.length))
                c += 0.15f
                if (overlapsOpening(room, wi, seg)) continue
                // Hidden behind a tall piece standing against this wall?
                if (tall.any { f -> f.corners().any { seg.distanceTo(it) < 0.3f } }) continue
                // Hang it above whatever low piece sits in front.
                val below = layout.filter { f -> f.corners().any { seg.distanceTo(it) < 0.35f } }.maxOfOrNull { it.height } ?: 0f
                val bottom = max(0.7f, below + 0.15f)
                if (bottom > 1.5f) continue

                var score = 0f
                var reflects = false
                var extra = 0f
                for (v in views) {
                    if (((v.p - seg.pointAt(0.5f)) dot n) < 0.3f) continue // viewer behind/at the wall
                    var acc = 0f
                    var samples = 0
                    for (k in 0 until 7) {
                        val p = seg.pointAt((k + 0.5f) / 7f)
                        val d = p - v.p
                        val len = d.length()
                        if (len < 0.4f) continue
                        val dir = d / len
                        val cosInc = abs(dir dot n)
                        if (cosInc < 0.2f) continue // too grazing to read as a mirror
                        if (blocked(v.p, p, tall)) continue
                        val r = dir - n * (2f * (dir dot n))
                        val (travel, window) = trace(room, p + r * 0.01f, r, tall)
                        samples++
                        acc += cosInc * (0.7f * (travel / diag) + if (window) 1.3f else 0f)
                        if (window) reflects = true
                        if (v.isEntry) extra += travel / 7f
                    }
                    if (samples > 0) score += v.weight * acc / 7f
                }
                // Daylight bounce: mirror facing a window across the room.
                var bounce = false
                for (win in room.windows()) {
                    val wm = room.openingSegment(win).pointAt(0.5f)
                    val toWin = wm - seg.pointAt(0.5f)
                    val facing = toWin.normalized() dot n
                    if (facing > 0.5f && !blocked(seg.pointAt(0.5f), wm, tall)) {
                        bounce = true
                        score += 0.45f * facing * min(1.5f, win.width)
                    }
                }
                if (score <= 0.05f) continue
                val reason = when {
                    reflects -> "From the doorway you'd see the window in it — like a second window."
                    bounce -> "Faces the window: bounces daylight deep into the room."
                    else -> "Adds about ${MovePlanner.fmt(extra)} of see-through depth from the door."
                }
                candidates += MirrorSpot(Mirror(wallIndex = wi, t = t, width = width, bottom = bottom, top = bottom + 1.2f), score, reflects, bounce, extra, reason)
            }
        }
        // Best first, at most one suggestion per meter of wall.
        val picked = ArrayList<MirrorSpot>()
        for (s in candidates.sortedByDescending { it.score }) {
            val p = room.mirrorSegment(s.mirror).pointAt(0.5f)
            if (picked.none { room.mirrorSegment(it.mirror).pointAt(0.5f).dist(p) < 1.2f }) picked += s
            if (picked.size >= max) break
        }
        return picked
    }

    private fun overlapsOpening(room: Room, wi: Int, seg: Segment): Boolean =
        (room.openings.map { it.wallIndex to room.openingSegment(it) } + room.mirrors.map { it.wallIndex to room.mirrorSegment(it) })
            .any { (w, o) -> w == wi && segmentsOverlap(seg, o, 0.1f) }

    private fun segmentsOverlap(a: Segment, b: Segment, margin: Float): Boolean {
        val dir = a.dir
        val a0 = 0f; val a1 = a.length
        val b0 = (b.a - a.a) dot dir; val b1 = (b.b - a.a) dot dir
        return min(a1, max(b0, b1)) + margin > max(a0, min(b0, b1))
    }

    /** Tall furniture between two floor points blocks the eye-level view. */
    private fun blocked(a: Vec2, b: Vec2, tall: List<Furniture>): Boolean = tall.any { f ->
        val c = f.corners()
        (0..8).any { k -> OccupancyGrid.pointInConvex(a + (b - a) * (k / 8f), c) }
    }

    /** March from [p] along [dir] until a wall or tall piece; did we hit a window? */
    private fun trace(room: Room, p: Vec2, dir: Vec2, tall: List<Furniture>): Pair<Float, Boolean> {
        var bestT = Float.MAX_VALUE
        var bestWall = -1
        var hit = Vec2.ZERO
        room.walls().forEachIndexed { i, w ->
            val e = w.b - w.a
            val den = dir cross e
            if (abs(den) < 1e-6f) return@forEachIndexed
            val t = ((w.a - p) cross e) / den
            val u = ((w.a - p) cross dir) / den
            if (t > 1e-3f && u in 0f..1f && t < bestT) { bestT = t; bestWall = i; hit = p + dir * t }
        }
        if (bestWall < 0) return 0f to false
        // Stop early at tall furniture.
        var k = 0.1f
        while (k < bestT) {
            val q = p + dir * k
            if (tall.any { OccupancyGrid.pointInConvex(q, it.corners()) }) return k to false
            k += 0.1f
        }
        val window = room.windows().any { it.wallIndex == bestWall && room.openingSegment(it).distanceTo(hit) < 0.05f }
        return bestT to window
    }
}
