package com.openroomai.app.engine

import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Room
import com.openroomai.app.model.Vec2
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

enum class NudgeKind { SLIDE, FLUSH, TUCK, ROTATE, COMBO }

/** One small, concrete adjustment and exactly what it buys. */
data class Nudge(
    val key: String,
    val kind: NudgeKind,
    val title: String,
    val detail: String,
    /** New poses for every piece this nudge moves (the first one is the piece it's about). */
    val poses: List<Furniture>,
    /** Usable open floor gained (m²). */
    val usableGain: Float,
    /** Growth of the largest open zone (m²). */
    val zoneGain: Float,
    /** The walkway this helps most, if any: name, width before and after (m). */
    val lane: Triple<String, Float, Float>?,
    /** Longest distance any piece travels (m). */
    val travel: Float,
    val score: Float,
) {
    val pieceId get() = poses.first().id
    val ids get() = poses.map { it.id }.toSet()
    fun applyTo(layout: List<Furniture>): List<Furniture> {
        val m = poses.associateBy { it.id }
        return layout.map { m[it.id] ?: it }
    }
}

/**
 * The Precision Micro-Nudge Engine ("3-Inch Optimizer"). Instead of redesigning the room, it
 * tries hundreds of small, realistic adjustments (slide an inch or a few, push flush to a wall,
 * tuck into a corner, turn a table 90°, or two of those together) and ranks them by usable
 * floor gained and walkway width gained, per inch of effort.
 *
 * Each candidate must keep every hard rule (walls, overlaps, door swings, use zones), keep seats
 * at a comfortable legroom distance and chairs at their tables, and never narrow a walkway that
 * was already tight. Candidates are screened on a 2″ grid in parallel, then the best are
 * re-measured on the 1″ grid so the numbers shown are honest.
 */
object MicroNudge {
    /** How the gains trade off for each space goal. */
    data class Weights(val area: Float, val zone: Float, val lane: Float)

    fun weights(goal: SpaceGoal) = when (goal) {
        SpaceGoal.MAX_OPEN_CENTER -> Weights(area = 1.0f, zone = 0.8f, lane = 0.3f)
        SpaceGoal.WIDEST_FLOW -> Weights(area = 0.5f, zone = 0.2f, lane = 1.2f)
        SpaceGoal.QUICK_WINS -> Weights(area = 1.0f, zone = 0.5f, lane = 0.6f)
    }

    /** Back gap left when pushing something "flush" (baseboards, cables). */
    const val FLUSH_GAP = 0.015f
    private const val MAX_FLUSH = 0.6f
    private const val SCREEN_KEEP = 18
    private const val MIN_SCORE = 0.6f

    private val pool: ExecutorService by lazy {
        val n = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6)
        Executors.newFixedThreadPool(n) { r -> Thread(r, "micro-nudge").apply { isDaemon = true } }
    }

    /** Ranked nudges for [layout], best first, at most one per piece. */
    fun suggest(
        room: Room,
        layout: List<Furniture>,
        goal: SpaceGoal = SpaceGoal.QUICK_WINS,
        max: Int = 6,
        exclude: Set<String> = emptySet(),
        parallel: Boolean = true,
    ): List<Nudge> {
        if (layout.isEmpty()) return emptyList()
        val w = weights(goal)
        val cons = Constraints(room)
        val cands = candidates(room, cons, layout, exclude)
        if (cands.isEmpty()) return emptyList()

        // 1) Screen everything on the fast grid.
        val screened: List<Pair<Cand, Float>> = screen(room, layout, cands, w, parallel)
        val shortlist = screened.sortedByDescending { it.second }
            .distinctBy { it.first.key }
            .take(SCREEN_KEEP)
            .map { it.first }
        if (shortlist.isEmpty()) return emptyList()

        // 2) Re-measure the shortlist on the fine grid; these are the numbers people see.
        val fine = OpenSpace.Meter(room, OpenSpace.FINE)
        val base = fine.measure(layout)
        val out = ArrayList<Nudge>()
        for (c in shortlist) {
            val after = c.applyTo(layout)
            val rep = fine.measure(after)
            val g = gains(base, rep, w, OpenSpace.FINE) ?: continue
            val repair = cons.repairValue(layout, after, c.poses)
            val net = g.value + repair + styleBonus(c) - effort(c, layout)
            if (net < MIN_SCORE) continue
            val fixes = if (repair > 0f) cons.fixes(layout, after, c.poses) else emptyList()
            if (g.usableSqft < 0.3f && g.laneIn < 1f && g.zoneSqft < 1f && fixes.isEmpty()) continue
            out += Nudge(c.key, c.kind, c.title, detail(g, rep, fixes), c.poses, g.usable, g.zone, g.lane, c.travel, net)
        }
        return out.sortedByDescending { it.score }.distinctBy { it.pieceId }.take(max)
    }

    /**
     * Quick Wins: apply the best nudge, re-plan, repeat, up to [maxMoves] different pieces.
     * Returns the nudges in the order they were applied.
     */
    fun chain(room: Room, layout: List<Furniture>, goal: SpaceGoal = SpaceGoal.QUICK_WINS, maxMoves: Int = 4, parallel: Boolean = true): List<Nudge> {
        var cur = layout
        val used = HashSet<String>()
        val out = ArrayList<Nudge>()
        repeat(maxMoves) {
            val n = suggest(room, cur, goal, max = 1, exclude = used, parallel = parallel).firstOrNull() ?: return out
            out += n
            used += n.ids
            cur = n.applyTo(cur)
        }
        return out
    }

    fun applyAll(layout: List<Furniture>, nudges: List<Nudge>): List<Furniture> = nudges.fold(layout) { l, n -> n.applyTo(l) }

    // ------------------------------------------------------------------ candidates

    private class Cand(val key: String, val kind: NudgeKind, val title: String, val poses: List<Furniture>, val travel: Float, val rotated: Boolean) {
        fun applyTo(layout: List<Furniture>): List<Furniture> {
            val m = poses.associateBy { it.id }
            return layout.map { m[it.id] ?: it }
        }
    }

    private val ROTATABLE = setOf(FurnitureType.COFFEE_TABLE, FurnitureType.DINING_TABLE, FurnitureType.ARMCHAIR, FurnitureType.CHAIR, FurnitureType.OTHER)

    private fun candidates(room: Room, cons: Constraints, layout: List<Furniture>, exclude: Set<String>): List<Cand> {
        val out = ArrayList<Cand>()
        fun valid(poses: List<Furniture>): Boolean {
            if (poses.any { p -> layout.first { it.id == p.id }.locked }) return false
            val after = layout.map { f -> poses.firstOrNull { it.id == f.id } ?: f }
            return cons.noWorse(layout, after, poses) && relationsOk(layout, after)
        }
        for (f in layout) {
            if (f.locked || f.id in exclude) continue
            val group = companions(f, layout).filter { it.id !in exclude }
            fun moved(d: Vec2) = listOf(f.withPose(f.center + d, f.rotationDeg)) + group.map { it.withPose(it.center + d, it.rotationDeg) }
            val tag = if (group.isEmpty()) "" else " (bring the ${group.joinToString(" and ") { it.name }} along)"
            val dirs = listOf("back" to -f.front(), "forward" to f.front(), "left" to -f.right(), "right" to f.right())

            // Slides along the piece's own axes, smallest first; stop at the first that doesn't fit.
            for ((name, d) in dirs) {
                for (s in Units.nudgeSteps) {
                    val poses = moved(d * s)
                    if (!valid(poses)) break
                    out += Cand("slide:${f.id}:$name:${(s * 1000).toInt()}", NudgeKind.SLIDE, slideTitle(room, f, name, d, s) + tag, poses, s, false)
                }
            }
            // Flush to the wall behind or beside it.
            val gaps = HashMap<String, Float>()
            for ((name, d) in dirs) {
                if (name == "forward") continue
                val gap = cons.gapToWall(f, d) ?: continue
                if (gap <= FLUSH_GAP + 0.006f || gap > MAX_FLUSH) continue
                gaps[name] = gap
                val poses = moved(d * (gap - FLUSH_GAP))
                if (!valid(poses)) continue
                val wall = room.wallLabel(MovePlanner.wallInDirection(room, f.center, d))
                out += Cand("flush:${f.id}:$name", NudgeKind.FLUSH, "Push the ${f.name} flush against Wall $wall (${Units.nudge(gap - FLUSH_GAP)})$tag", poses, gap - FLUSH_GAP, false)
            }
            // Tuck into a corner: flush on the back and one side at once.
            val back = gaps["back"]
            if (back != null) for (side in listOf("left", "right")) {
                val sg = gaps[side] ?: continue
                val sd = dirs.first { it.first == side }.second
                val delta = -f.front() * (back - FLUSH_GAP) + sd * (sg - FLUSH_GAP)
                val poses = moved(delta)
                if (!valid(poses)) continue
                val w1 = room.wallLabel(MovePlanner.wallInDirection(room, f.center, -f.front()))
                val w2 = room.wallLabel(MovePlanner.wallInDirection(room, f.center, sd))
                out += Cand("tuck:${f.id}:$side", NudgeKind.TUCK, "Tuck the ${f.name} into the corner of Walls $w1 and $w2$tag", poses, delta.length(), false)
            }
            // A quarter turn, if needed with a small shift to make it fit, or by moving one neighbour.
            if (f.type in ROTATABLE && group.isEmpty()) {
                val turns = if (f.type.symmetryDeg <= 180f) listOf(90f) else listOf(90f, -90f)
                for (t in turns) rotateCands(room, cons, layout, f, t, ::valid, exclude, out)
            }
        }
        return out
    }

    private fun rotateCands(
        room: Room, cons: Constraints, layout: List<Furniture>, f: Furniture, turn: Float,
        valid: (List<Furniture>) -> Boolean, exclude: Set<String>, out: MutableList<Cand>,
    ) {
        val rot = f.withPose(f.center, f.rotationDeg + turn)
        val turnText = if (f.type.symmetryDeg <= 180f) "90°" else if (turn > 0) "90° clockwise" else "90° counter-clockwise"
        val title = "Rotate the ${f.name} $turnText"
        if (valid(listOf(rot))) {
            out += Cand("rot:${f.id}:$turn", NudgeKind.ROTATE, title, listOf(rot), 0f, true); return
        }
        val axes = listOf(rot.front(), -rot.front(), rot.right(), -rot.right())
        for (s in Units.nudgeSteps) for (d in axes) {
            val p = rot.withPose(rot.center + d * s, rot.rotationDeg)
            if (valid(listOf(p))) {
                val wall = room.wallLabel(MovePlanner.wallInDirection(room, f.center, d))
                out += Cand("rot:${f.id}:$turn", NudgeKind.ROTATE, "$title and slide it ${Units.nudge(s)} toward Wall $wall", listOf(p), s, true)
                return
            }
        }
        // Exactly one neighbour in the way? Move it a little too.
        val others = layout.filter { it.id != f.id && (Constraints.penetration(rot.corners(), it.corners()) > 0f || cons.useZones(it).any { z -> Constraints.penetration(z, rot.corners()) > 0f }) }
        if (others.size != 1) return
        val b = others.first()
        if (b.locked || b.id in exclude) return
        for (s in Units.nudgeSteps) for ((name, d) in listOf("back" to -b.front(), "left" to -b.right(), "right" to b.right(), "forward" to b.front())) {
            val bp = b.withPose(b.center + d * s, b.rotationDeg)
            if (valid(listOf(rot, bp))) {
                out += Cand("combo:${f.id}:$turn:${b.id}", NudgeKind.COMBO, "$title and " + slideTitle(room, b, name, d, s).replaceFirstChar { it.lowercase() }, listOf(rot, bp), s, true)
                return
            }
        }
    }

    private fun slideTitle(room: Room, f: Furniture, name: String, d: Vec2, s: Float): String {
        val wall = room.wallLabel(MovePlanner.wallInDirection(room, f.center, d))
        return when (name) {
            "back" -> "Push the ${f.name} ${Units.nudge(s)} back toward Wall $wall"
            "forward" -> "Pull the ${f.name} ${Units.nudge(s)} forward, toward Wall $wall"
            else -> "Slide the ${f.name} ${Units.nudge(s)} toward Wall $wall"
        }
    }

    /** Pieces that travel with [f]: chairs at a desk or table, nightstands at a bed, the coffee table in front of a sofa. */
    fun companions(f: Furniture, layout: List<Furniture>): List<Furniture> = when (f.type) {
        FurnitureType.DESK, FurnitureType.DINING_TABLE -> layout.filter { it.type == FurnitureType.CHAIR && !it.locked && edgeGap(f, it) < 0.45f }
        FurnitureType.BED -> layout.filter { it.type == FurnitureType.NIGHTSTAND && !it.locked && edgeGap(f, it) < 0.2f }
        FurnitureType.SOFA -> OpenSpace.legrooms(layout).filter { it.first.id == f.id && !it.second.locked && it.third < 0.6f }.map { it.second }
        else -> emptyList()
    }

    /** Smallest gap between two pieces (0 when they touch or overlap). */
    fun edgeGap(a: Furniture, b: Furniture): Float {
        val ca = a.corners(); val cb = b.corners()
        if (Geo.convexOverlap(ca, cb)) return 0f
        val ea = Geo.edges(ca); val eb = Geo.edges(cb)
        return min(cb.minOf { p -> ea.minOf { it.distanceTo(p) } }, ca.minOf { p -> eb.minOf { it.distanceTo(p) } })
    }

    /** Keeps what makes furniture usable: legroom, chairs at their tables, nightstands at the bed, seats facing the screen. */
    private fun relationsOk(before: List<Furniture>, after: List<Furniture>): Boolean {
        val byId = after.associateBy { it.id }
        for ((seat, table, gap) in OpenSpace.legrooms(before)) {
            val s = byId[seat.id] ?: continue; val t = byId[table.id] ?: continue
            val g = OpenSpace.legroom(s, t) ?: return false
            if (gap >= Clearances.LEGROOM_MIN && g < Clearances.LEGROOM_MIN - 1e-3f) return false
            if (gap < Clearances.LEGROOM_MIN && g < gap - 1e-3f) return false
            if (gap <= Clearances.LEGROOM_MAX + 0.05f && g > Clearances.LEGROOM_MAX + 0.05f) return false
        }
        for (c in before) {
            val keep = when (c.type) {
                FurnitureType.CHAIR -> before.filter { it.type == FurnitureType.DESK || it.type == FurnitureType.DINING_TABLE }.map { it to 0.45f }
                FurnitureType.NIGHTSTAND -> before.filter { it.type == FurnitureType.BED }.map { it to 0.2f }
                else -> emptyList()
            }
            for ((anchor, lim) in keep) {
                if (edgeGap(anchor, c) >= lim) continue
                val a2 = byId[anchor.id] ?: continue; val c2 = byId[c.id] ?: continue
                if (edgeGap(a2, c2) >= lim) return false
            }
        }
        for (s in before.filter { it.type == FurnitureType.SOFA }) {
            val tv = before.filter { it.type == FurnitureType.TV_STAND }.minByOrNull { it.center.dist(s.center) } ?: continue
            val f0 = s.front() dot (tv.center - s.center).normalized()
            val s2 = byId[s.id] ?: continue; val t2 = byId[tv.id] ?: continue
            val f1 = s2.front() dot (t2.center - s2.center).normalized()
            if (f1 < f0 - 0.03f && f1 < 0.97f) return false
        }
        return true
    }

    // ------------------------------------------------------------------ scoring

    private class Gains(val usable: Float, val zone: Float, val laneIn: Float, val lane: Triple<String, Float, Float>?, val value: Float) {
        val usableSqft get() = usable / Units.SQFT
        val zoneSqft get() = zone / Units.SQFT
    }

    /** Null when the move narrows a walkway it shouldn't. */
    private fun gains(base: OpenSpaceReport, rep: OpenSpaceReport, w: Weights, cell: Float): Gains? {
        val tol = 0.5f * cell + 1e-4f
        val baseById = base.corridors.associateBy { it.targetId }
        var laneM = 0f
        var best: Triple<String, Float, Float>? = null
        for (c in rep.corridors) {
            val b = baseById[c.targetId] ?: continue
            if (b.reachable && !c.reachable) return null
            if (b.width >= Lanes.CLEAR && c.width < Lanes.CLEAR - tol) return null
            if (b.width < Lanes.CLEAR && c.width < b.width - tol) return null
            val d = 0.4f * (min(c.width, Lanes.GENEROUS) - min(b.width, Lanes.GENEROUS)) + 0.6f * (min(c.width, Lanes.CLEAR) - min(b.width, Lanes.CLEAR))
            laneM += d
            if (b.width < Lanes.GENEROUS && c.width - b.width > 0.02f && (best == null || c.width - b.width > best.third - best.second)) {
                best = Triple(c.targetName, b.width, c.width)
            }
        }
        val du = rep.usableArea - base.usableArea
        val dz = (rep.zone?.area ?: 0f) - (base.zone?.area ?: 0f)
        val laneIn = laneM / Units.INCH
        val value = w.area * du / Units.SQFT + w.zone * dz / Units.SQFT + w.lane * laneIn
        return Gains(du, dz, laneIn, best, value)
    }

    /** "Flush against the wall" is a cleaner instruction than "slide 9″", for the same result. */
    private fun styleBonus(c: Cand) = if (c.kind == NudgeKind.FLUSH || c.kind == NudgeKind.TUCK) 0.3f else 0f

    private fun effort(c: Cand, layout: List<Furniture>): Float {
        val size = c.poses.sumOf { p -> (0.6f + (layout.firstOrNull { it.id == p.id } ?: p).footprint()).coerceAtMost(2.5f).toDouble() }.toFloat()
        val travelIn = c.travel / Units.INCH
        return 0.03f * travelIn * size + (if (c.rotated) 0.8f else 0f) + (if (c.poses.size > 1 && c.kind == NudgeKind.COMBO) 0.4f else 0f)
    }

    private fun screen(room: Room, layout: List<Furniture>, cands: List<Cand>, w: Weights, parallel: Boolean): List<Pair<Cand, Float>> {
        fun run(part: List<Cand>): List<Pair<Cand, Float>> {
            val m = OpenSpace.Meter(room, OpenSpace.FAST)
            val base = m.measure(layout)
            val cons = Constraints(room)
            return part.mapNotNull { c ->
                val after = c.applyTo(layout)
                val g = gains(base, m.measure(after), w, OpenSpace.FAST) ?: return@mapNotNull null
                c to (g.value + cons.repairValue(layout, after, c.poses) + styleBonus(c) - effort(c, layout))
            }
        }
        val n = if (parallel) (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6) else 1
        if (n == 1 || cands.size < 24) return run(cands)
        val chunks = cands.chunked((cands.size + n - 1) / n)
        return chunks.map { part -> pool.submit(Callable { run(part) }) }.flatMap { it.get() }
    }

    private fun detail(g: Gains, rep: OpenSpaceReport, fixes: List<String>): String {
        // Most important first, two at most, then any cost, so it stays one glanceable line.
        val gains = ArrayList<Pair<Float, String>>()
        fixes.firstOrNull()?.let { gains += (if (it.startsWith("out of") || it.startsWith("no longer") || it.startsWith("back inside")) 9f else 0.8f) to it }
        if (g.usableSqft >= 0.3f) gains += g.usableSqft to "${Units.areaGain(g.usable)} open floor"
        g.lane?.let { (name, a, b) ->
            if (b - a >= Units.INCH * 0.9f) gains += 0.6f * (b - a) / Units.INCH to "walkway to ${if (name.startsWith("the ")) name else "the $name"} ${Units.width(a)} → ${Units.width(b)}"
        }
        val z = rep.zone
        if (g.zoneSqft >= 1f && z != null) gains += 0.5f * g.zoneSqft to "open zone ${Units.areaGain(g.zone)}"
        val parts = gains.sortedByDescending { it.first }.take(2).map { it.second }.toMutableList()
        if (g.usableSqft <= -0.3f) parts += "uses ${Units.area(-g.usable)}"
        return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }.ifEmpty { "Small but free" }
    }
}
