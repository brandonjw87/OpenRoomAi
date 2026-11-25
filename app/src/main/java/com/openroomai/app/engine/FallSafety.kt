package com.openroomai.app.engine

import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Room
import com.openroomai.app.model.Vec2
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.roundToInt

enum class Severity(val label: String, val penalty: Int) {
    FIX_FIRST("Fix first", 14),
    IMPROVE("Improve", 6),
    TIP("Good idea", 0),
}

data class SafetyItem(
    val icon: String,
    val title: String,
    val body: String,
    val severity: Severity,
    /** Where on the plan it applies (for markers), if anywhere specific. */
    val at: Vec2? = null,
    val furnitureId: String? = null,
)

data class NightLight(val at: Vec2, val why: String)

data class FallSafetyReport(
    /** 0–100: 100 = nothing left to fix. */
    val score: Int,
    val items: List<SafetyItem>,
    val good: List<String>,
    /** Walking route used at night (bed or main seat → door). */
    val nightPath: List<Vec2>,
    val nightLights: List<NightLight>,
    /** Places where a route is narrower than a walker needs. */
    val narrowSpots: List<Vec2>,
    /** Shin-height pieces sitting in a walking route. */
    val tripPieces: List<String>,
)

/**
 * Easy-living / aging-in-place check for older adults, built on public guidance:
 * CDC STEADI "Check for Safety" (clear walking paths, no throw rugs, good lighting and night
 * lights, a phone reachable from the floor), accessible-route widths of 36 in (0.91 m) with a
 * 60 in (1.52 m) turning space and NAHB's 5×5 ft clear areas in main rooms, CPSC's advice to
 * anchor tall furniture, and occupational-therapy seating advice (higher seats with armrests).
 * General guidance only — not a substitute for an occupational therapist's home visit.
 */
object FallSafety {
    const val WALKER_PATH = 0.91f
    const val TURNING = 1.52f
    const val SEAT_NEAR_DOOR = 2.0f
    const val BED_EXIT = 0.9f
    /** Pieces at or under this height catch shins, toes and walker legs. */
    const val TRIP_HEIGHT = 0.65f

    fun isSeat(f: Furniture) = f.type == FurnitureType.SOFA || f.type == FurnitureType.ARMCHAIR || f.type == FurnitureType.CHAIR

    private val TALL_TIP = setOf(FurnitureType.WARDROBE, FurnitureType.BOOKSHELF, FurnitureType.DRESSER, FurnitureType.TV_STAND)

    /** Places people head for from the door. */
    fun isDestination(f: Furniture) = isSeat(f) || f.type in setOf(
        FurnitureType.BED, FurnitureType.DESK, FurnitureType.WARDROBE, FurnitureType.DRESSER, FurnitureType.BOOKSHELF, FurnitureType.DINING_TABLE,
    )

    /** Straight "desire lines" from the entry to each destination's nearest usable spot. */
    fun desireLines(room: Room, layout: List<Furniture>): List<Pair<Furniture, com.openroomai.app.model.Segment>> {
        val e = room.entryPoint()
        return layout.filter(::isDestination).map { d -> d to com.openroomai.app.model.Segment(e, d.accessPoints().minByOrNull { it.dist(e) }!!) }
    }

    /** Does low piece [f] sit on the line to [dest]? The last 0.5 m (the approach) is ignored. */
    fun blocksLine(f: Furniture, dest: Furniture, line: com.openroomai.app.model.Segment): Boolean {
        if (f.id == dest.id || f.height > TRIP_HEIGHT || f.type == FurnitureType.BED) return false
        val len = line.length
        if (len < 0.9f) return false
        val corners = f.corners()
        val edges = Geo.edges(corners)
        val n = (len / 0.05f).toInt()
        for (k in 0..n) {
            val t = k / n.toFloat()
            if (t * len > len - 0.5f) break
            val p = line.pointAt(t)
            if (OccupancyGrid.pointInConvex(p, corners) || edges.minOf { it.distanceTo(p) } < 0.25f) return true
        }
        return false
    }

    fun tripPieces(room: Room, layout: List<Furniture>): List<Furniture> {
        val lines = desireLines(room, layout)
        return layout.filter { f -> lines.any { (d, l) -> blocksLine(f, d, l) } }
    }

    /** 0 = a fully clear 90 cm strip beside the bed on its better side, 1 = both sides blocked. */
    fun bedExitObstruction(room: Room, bed: Furniture, layout: List<Furniture>): Float {
        val r = bed.right(); val fr = bed.front()
        var best = 1f
        for (s in listOf(1f, -1f)) {
            var blocked = 0; var n = 0
            // Along the bed's body (skip the head end where nightstands live), across the 90 cm strip.
            for (i in 0 until 5) for (j in 0 until 4) {
                val along = -bed.depth / 2f + 0.5f + (bed.depth - 0.5f) * (i + 0.5f) / 5f
                val across = bed.width / 2f + BED_EXIT * (j + 0.5f) / 4f
                val p = bed.center + r * (s * across) + fr * along
                n++
                if (!Geo.pointInPolygon(p, room.outline) ||
                    layout.any { it.id != bed.id && it.type != FurnitureType.NIGHTSTAND && OccupancyGrid.pointInConvex(p, it.corners()) }
                ) blocked++
            }
            best = minOf(best, blocked.toFloat() / n)
        }
        return best
    }

    fun audit(room: Room, layout: List<Furniture>): FallSafetyReport {
        val items = ArrayList<SafetyItem>()
        val good = ArrayList<String>()
        // Walker-width analysis (90 cm) next to the everyday one (60 cm).
        val walker = OpennessAnalyzer.analyze(room, layout, OccupancyGrid(room, 0.05f), withFlows = true, walkRadius = COMFORT_RADIUS)
        val everyday = OpennessAnalyzer.analyze(room, layout, OccupancyGrid(room, 0.05f), withFlows = true, walkRadius = SQUEEZE_RADIUS)

        // 1) Reachability with a walker.
        val walkerBlocked = walker.blockedPieces.filter { it.type.needsAccess }
        for (f in walkerBlocked) items += SafetyItem(
            "🦯", "No walker-width path to the ${f.name}",
            "There's no ${(WALKER_PATH * 100).roundToInt()} cm wide route to it. A walker or a steadying hand needs about 90 cm (36 in).",
            Severity.FIX_FIRST, f.center, f.id,
        )
        if (walkerBlocked.isEmpty() && layout.isNotEmpty()) good += "Every piece can be reached on a 90 cm walker-width path"

        // 2) Narrow spots on the routes people actually walk.
        val narrow = cluster(everyday.tightSpots.filter { p -> everyday.grid.dist[everyday.grid.idxOf(p)] * 2f < WALKER_PATH }, 0.6f)
        // One note per piece that pinches a path, however many spots it pinches.
        narrow.groupBy { p -> layout.minByOrNull { f -> f.corners().minOf { it.dist(p) } } }.entries.take(4).forEach { (near, spots) ->
            items += SafetyItem(
                "↔️", "Path narrows${near?.let { " by the ${it.name}" } ?: ""}",
                "${if (spots.size > 1) "${spots.size} spots are" else "The route here is"} narrower than 90 cm. Shift ${near?.name ?: "furniture"} a little to open it up.",
                Severity.IMPROVE, spots.first(), near?.id,
            )
        }

        // 3) Shin-height pieces on a desire line. People walk the straight line from the door to
        //    where they're going (and cut corners at night), not the detour a planner would take.
        val trip = tripPieces(room, layout)
        for (f in trip) {
            items += SafetyItem(
                "⚠️", "The ${f.name} is in a walking line",
                "At ${(f.height * 100).roundToInt()} cm it's at shin and walker-leg height, right on the straight way from the door. Move it aside, or pick one with rounded corners.",
                Severity.FIX_FIRST, f.center, f.id,
            )
        }

        // 4) The area just inside each door.
        for (d in room.doors()) {
            val swing = room.doorSwing(d)
            val n = room.inwardNormal(d.wallIndex)
            val apron = swing.map { it + n * 0.3f } + swing
            val hull = Geo.convexHull(apron)
            val inWay = layout.filter { f -> f.corners().any { OccupancyGrid.pointInConvex(it, hull) } || OccupancyGrid.pointInConvex(f.center, hull) }
            for (f in inWay) items += SafetyItem(
                "🚪", "Clear the space inside the door",
                "The ${f.name} is in the door's swing. With a walker you need the door to open fully and room to step in.",
                Severity.IMPROVE, f.center, f.id,
            )
        }

        // 5) Getting out of bed.
        for (bed in layout.filter { it.type == FurnitureType.BED }) {
            val o = bedExitObstruction(room, bed, layout)
            if (o >= 0.35f) items += SafetyItem(
                "🛏️", "No clear side to get out of bed",
                "Leave a 90 cm strip along at least one side of the bed for getting up, a walker, or a helper.",
                Severity.FIX_FIRST, bed.center, bed.id,
            ) else good += "Clear 90 cm side to get out of bed"
        }

        // 6) Turning space.
        if (walker.clearDiameter >= TURNING) good += "1.5 m turning space (${MovePlanner.fmt(walker.clearDiameter)} clear)"
        else items += SafetyItem(
            "🔄", "No 1.5 m turning space",
            "The biggest clear spot is ${MovePlanner.fmt(walker.clearDiameter)}. A 5 ft (1.5 m) circle makes turning with a walker or wheelchair easy.",
            Severity.IMPROVE, walker.clearCenter,
        )

        // 7) Somewhere to sit near the door.
        if (layout.none { isSeat(it) && it.center.dist(room.entryPoint()) <= SEAT_NEAR_DOOR }) items += SafetyItem(
            "🪑", "Add a sturdy seat near the door",
            "A chair with arms within a couple of steps of the door is for shoes, bags and catching your breath.",
            Severity.IMPROVE, room.entryPoint(),
        ) else good += "A seat within a couple of steps of the door"

        // 8) Tall pieces people may steady themselves on.
        for (f in layout.filter { it.type in TALL_TIP || it.height >= 1.4f }) items += SafetyItem(
            "🔩", "Anchor the ${f.name} to the wall",
            "People steady themselves on furniture, and visiting grandkids climb. Strap tall and top-heavy pieces to the wall (CPSC).",
            if (f.height >= 1.4f) Severity.IMPROVE else Severity.TIP, f.center, f.id,
        )

        // 9) Night path + night lights.
        val (path, lights) = nightPath(room, layout)

        // 10) The CDC checklist basics that a floor plan can't see.
        items += SafetyItem("🧶", "No loose throw rugs", "Remove small rugs or fix them down with double-sided tape; keep cords along the walls (CDC checklist).", Severity.TIP)
        items += SafetyItem("💺", "Seats that are easy to get out of", "Look for seats about 46–59 cm (18–24 in) high, not too deep, with armrests that reach the front of the seat.", Severity.TIP)
        items += SafetyItem("📞", "A phone reachable from the floor", "Keep a phone low down or wear an alert button, in case of a fall (CDC checklist).", Severity.TIP)
        if (lights.isNotEmpty()) items += SafetyItem("💡", "Night lights along the way",
            "Plug-in night lights at the ${lights.size} 💡 spots on the plan light the route to the door at night.", Severity.TIP, lights.first().at)

        val score = (100 - items.sumOf { it.severity.penalty }).coerceIn(0, 100)
        return FallSafetyReport(score, items.sortedBy { it.severity.ordinal }, good, path, lights, narrow, trip.map { it.name })
    }

    /** From the bed's getting-up side (or the main seat) to the door, with night-light spots. */
    fun nightPath(room: Room, layout: List<Furniture>): Pair<List<Vec2>, List<NightLight>> {
        val bed = layout.firstOrNull { it.type == FurnitureType.BED }
        val start: Vec2 = when {
            bed != null -> {
                val sides = listOf(1f, -1f).map { s -> bed.center + bed.right() * (s * (bed.width / 2f + 0.35f)) }
                sides.minByOrNull { p -> layout.count { f -> f.id != bed.id && OccupancyGrid.pointInConvex(p, f.corners()) } + if (Geo.pointInPolygon(p, room.outline)) 0 else 5 }!!
            }
            else -> layout.firstOrNull { it.type == FurnitureType.SOFA }?.let { it.center + it.front() * (it.depth / 2f + 0.3f) } ?: return emptyList<Vec2>() to emptyList()
        }
        val g = OccupancyGrid(room, 0.1f)
        g.stamp(layout)
        val goal = room.entryPoint()
        val path = bfs(g, start, goal) ?: return emptyList<Vec2>() to emptyList()
        val simple = simplify(path)
        val lights = ArrayList<NightLight>()
        lights += NightLight(simple.first(), if (bed != null) "Beside the bed" else "By the sofa")
        var sinceLast = 0f
        for (i in 1 until simple.size - 1) {
            val a = (simple[i] - simple[i - 1]).normalized()
            val b = (simple[i + 1] - simple[i]).normalized()
            sinceLast += simple[i].dist(simple[i - 1])
            val turn = Math.toDegrees(acos((a dot b).coerceIn(-1f, 1f).toDouble()))
            if ((turn > 35.0 && sinceLast > 1.0f) || sinceLast > 3.0f) {
                lights += NightLight(simple[i], if (turn > 35.0) "Where the path turns" else "Along the way")
                sinceLast = 0f
            }
        }
        if (simple.last().dist(lights.last().at) > 1.0f) lights += NightLight(simple.last(), "By the door")
        return simple to lights.take(5)
    }

    private fun bfs(g: OccupancyGrid, from: Vec2, to: Vec2): List<Vec2>? {
        fun ok(i: Int) = g.inside[i] && g.dist[i] >= 0.25f
        fun nearestOk(p: Vec2): Int {
            var best = -1; var bd = Float.MAX_VALUE
            val cx = g.gxOf(p.x); val cy = g.gyOf(p.y)
            for (dy in -8..8) for (dx in -8..8) {
                val x = cx + dx; val y = cy + dy
                if (!g.inGrid(x, y)) continue
                val i = y * g.w + x
                if (!ok(i)) continue
                val d = g.center(x, y).dist(p)
                if (d < bd) { bd = d; best = i }
            }
            return best
        }
        val s = nearestOk(from); val t = nearestOk(to)
        if (s < 0 || t < 0) return null
        val prev = IntArray(g.size) { -2 }
        val q = IntArray(g.size); var qh = 0; var qt = 0
        q[qt++] = s; prev[s] = -1
        val dx = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1); val dy = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
        while (qh < qt) {
            val c = q[qh++]
            if (c == t) break
            val cx = c % g.w; val cy = c / g.w
            for (k in 0 until 8) {
                val nx = cx + dx[k]; val ny = cy + dy[k]
                if (!g.inGrid(nx, ny)) continue
                val ni = ny * g.w + nx
                if (prev[ni] != -2 || !ok(ni)) continue
                prev[ni] = c; q[qt++] = ni
            }
        }
        if (prev[t] == -2) return null
        val out = ArrayList<Vec2>()
        var c = t
        while (c >= 0) { out += g.center(c % g.w, c / g.w); c = prev[c] }
        out.reverse()
        return out
    }

    private fun simplify(pts: List<Vec2>): List<Vec2> {
        if (pts.size < 3) return pts
        val out = arrayListOf(pts.first())
        for (i in 1 until pts.size - 1) if (abs((pts[i] - pts[i - 1]) cross (pts[i + 1] - pts[i])) > 1e-5f) out += pts[i]
        out += pts.last()
        return out
    }

    /** Re-sample a polyline every ~10 cm. */
    private fun dense(pts: List<Vec2>): List<Vec2> {
        if (pts.size < 2) return pts
        val out = ArrayList<Vec2>()
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            val n = maxOf(1, (a.dist(b) / 0.1f).toInt())
            for (k in 0 until n) out += a + (b - a) * (k / n.toFloat())
        }
        out += pts.last()
        return out
    }

    private fun cluster(pts: List<Vec2>, r: Float): List<Vec2> {
        val out = ArrayList<Vec2>()
        for (p in pts) if (out.none { it.dist(p) < r }) out += p
        return out
    }
}
