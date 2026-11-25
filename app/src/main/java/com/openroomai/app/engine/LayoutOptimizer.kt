package com.openroomai.app.engine

import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Room
import com.openroomai.app.model.Vec2
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

enum class Effort(val label: String, val blurb: String, val movePenalty: Float, val iterations: Int) {
    LIGHT("Quick tidy", "Few, short moves", 1.2f, 3500),
    BALANCED("Balanced", "Best bang for the sweat", 0.35f, 7000),
    REDESIGN("Full redesign", "Anything goes", 0.04f, 11000),
}

data class OptimizeResult(
    val layout: List<Furniture>,
    val before: OpennessReport,
    val after: OpennessReport,
    val violations: List<String>,
) {
    val fits get() = violations.isEmpty()
}

/**
 * Simulated-annealing furniture arranger.
 *
 * Hard rules (heavily penalised): stay inside the room, no overlaps, keep door swings clear,
 * keep each piece's "use zone" clear. Soft goals: backs to walls where it makes sense, sensible
 * groupings (sofa faces TV, coffee table in reach, nightstands by the bed, desk near daylight),
 * minimal sweat, and above all a high openness score.
 */
class LayoutOptimizer(
    private val room: Room,
    private val effort: Effort = Effort.BALANCED,
    seed: Long = 42L,
    private val goal: LayoutGoal = LayoutGoal.EVERYDAY,
) {
    private val baseSeed = seed
    /**
     * Weight of the interior-design guideline terms *during* the search. Measured on the demo
     * room, adding them inside the anneal made both openness and design worse (a bumpier cost
     * landscape), so by default they only judge the finished candidates — see [selectWeight].
     */
    var designWeight = 0f
    /** Weight of the design guidelines when picking the best of the finished candidates. */
    var selectWeight = 1.0f
    private val rnd = Random(seed)
    private val walls = room.walls()
    private val normals = walls.indices.map { room.inwardNormal(it) }
    private val doorSwings = room.doors().map { room.doorSwing(it) }
    private val windowZones = room.windows().map { w ->
        val s = room.openingSegment(w)
        val n = room.inwardNormal(w.wallIndex)
        listOf(s.a, s.b, s.b + n * 0.5f, s.a + n * 0.5f)
    }
    private val grid = OccupancyGrid(room, 0.1f)
    /** Today's hourly sun positions, only when we know which way the room faces. */
    private val sunTimes: List<SunPos> = if (room.northDeg == null || room.windows().isEmpty()) emptyList() else {
        val day = SunModel.dayOf(Season.TODAY)
        (5..19).map { SunModel.position(room.latitude ?: DEFAULT_LATITUDE, day, it + 0.5f) }.filter { it.up }
    }
    private val fineGrid by lazy { OccupancyGrid(room, 0.05f) }

    /**
     * @param start layout to begin from (may include brand-new pieces).
     * @param reference what the room looks like today; moving anything away from it costs effort.
     */
    fun optimize(
        start: List<Furniture>,
        reference: List<Furniture> = start,
        onProgress: (Float) -> Unit = {},
    ): OptimizeResult {
        val before = OpennessAnalyzer.analyze(room, reference, OccupancyGrid(room, 0.05f), withFlows = true, walkRadius = goal.walkRadius)
        if (start.isEmpty()) return OptimizeResult(start, before, before, emptyList())

        // Several independent searches from different starts (fast 10 cm grid), each refined cold,
        // run in parallel — one per CPU core, at least 4. Candidates are then judged on the same
        // fine 5 cm grid the final report uses, so a gap that only "exists" coarsely can't win.
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val chains = cores.coerceIn(4, 8)
        val explore = (effort.iterations * 0.55f).toInt()
        val refine = effort.iterations / 5
        val total = chains.toLong() * (explore + refine)
        val done = AtomicLong(0)
        val progressLock = Any()
        val tick: (Int) -> Unit = { n ->
            val d = done.addAndGet(n.toLong())
            synchronized(progressLock) { onProgress((d.toFloat() / total * 0.95f).coerceAtMost(0.95f)) }
        }
        val pool = Executors.newFixedThreadPool(minOf(chains, cores)) { r -> Thread(r, "optimizer").apply { isDaemon = true } }
        val candidates = ArrayList<List<Furniture>>()
        try {
            val futures = (0 until chains).map { k ->
                pool.submit(Callable {
                    // Each chain owns its grid and RNG, so chains never share mutable state.
                    val worker = LayoutOptimizer(room, effort, baseSeed * 1_000_003L + k * 7919L, goal)
                    worker.designWeight = designWeight
                    val seedLayout = if (k == 0) start else worker.wallSeed(start)
                    val (layout, _) = worker.anneal(reference, seedLayout, explore, 2.0f, tick)
                    worker.anneal(reference, layout, refine, 0.2f, tick).first
                })
            }
            futures.forEach { candidates += it.get() }
        } finally {
            pool.shutdownNow()
        }
        candidates += start
        var bestLayout = start
        var bestCost = Float.MAX_VALUE
        for (c in candidates) {
            val fine = totalCost(reference, c, fineGrid) + selectWeight * DesignGuidelines.cost(room, c, goal.relationScale.coerceIn(0.3f, 1.2f))
            if (fine < bestCost) { bestCost = fine; bestLayout = c }
        }

        val polished = bestLayout.map { snapTidy(it, bestLayout) }
        val polishedCost = totalCost(reference, polished, fineGrid) + selectWeight * DesignGuidelines.cost(room, polished, goal.relationScale.coerceIn(0.3f, 1.2f))
        val finalLayout = if (polishedCost <= bestCost) polished else bestLayout
        val after = OpennessAnalyzer.analyze(room, finalLayout, OccupancyGrid(room, 0.05f), withFlows = true, walkRadius = goal.walkRadius)
        onProgress(1f)
        return OptimizeResult(finalLayout, before, after, violations(finalLayout))
    }

    private fun anneal(original: List<Furniture>, start: List<Furniture>, iters: Int, t0: Float, tick: (Int) -> Unit): Pair<List<Furniture>, Float> {
        val cur = start.toMutableList()
        var curCost = totalCost(original, cur)
        var best = cur.toList()
        var bestCost = curCost
        val movable = cur.indices.filter { !cur[it].locked }
        if (movable.isEmpty()) return best to bestCost
        val t1 = 0.005f
        for (it in 0 until iters) {
            val frac = it.toFloat() / iters
            val temp = t0 * Math.pow((t1 / t0).toDouble(), frac.toDouble()).toFloat()
            val idx = movable[rnd.nextInt(movable.size)]
            val old = cur[idx]
            val proposal = propose(old, cur, temp, movable)
            val swapIdx = proposal.second
            val oldSwap = if (swapIdx >= 0) cur[swapIdx] else null
            cur[idx] = proposal.first
            if (swapIdx >= 0) cur[swapIdx] = proposal.third!!
            val c = totalCost(original, cur)
            val delta = c - curCost
            if (delta <= 0f || rnd.nextFloat() < exp(-delta / temp)) {
                curCost = c
                if (c < bestCost) { bestCost = c; best = cur.toList() }
            } else {
                cur[idx] = old
                if (swapIdx >= 0) cur[swapIdx] = oldSwap!!
            }
            if (it % 250 == 249) tick(250)
        }
        tick(iters % 250)
        return best to bestCost
    }

    /** Returns (new piece, index of swap partner or -1, new partner). */
    private fun propose(f: Furniture, all: List<Furniture>, temp: Float, movable: List<Int>): Triple<Furniture, Int, Furniture?> {
        val r = rnd.nextFloat()
        val scale = (0.15f + 1.2f * min(1f, temp)).coerceAtMost(1.5f)
        return when {
            r < 0.45f -> {
                val d = Vec2(gauss() * scale, gauss() * scale)
                Triple(f.withPose(f.center + d, f.rotationDeg), -1, null)
            }
            r < 0.6f -> {
                val rot = listOf(90f, -90f, 180f)[rnd.nextInt(3)]
                Triple(f.withPose(f.center, f.rotationDeg + rot), -1, null)
            }
            r < 0.85f -> Triple(placeAgainstWall(f, rnd.nextInt(walls.size), rnd.nextFloat()), -1, null)
            else -> {
                val other = movable[rnd.nextInt(movable.size)]
                val o = all[other]
                if (o.id == f.id) Triple(f.withPose(f.center + Vec2(gauss() * 0.2f, gauss() * 0.2f), f.rotationDeg), -1, null)
                else Triple(f.withPose(o.center, o.rotationDeg), other, o.withPose(f.center, f.rotationDeg))
            }
        }
    }

    /** Back of [f] flush against wall [wi] at fraction [t], facing into the room. */
    fun placeAgainstWall(f: Furniture, wi: Int, t: Float): Furniture {
        val w = walls[wi]
        val n = normals[wi]
        val half = f.width / 2f
        val len = w.length
        if (len < f.width) return f
        val tt = (half + t * (len - f.width)) / len
        val p = w.pointAt(tt) + n * (f.depth / 2f + 0.02f)
        // front() = (0,1) rotated by angle; we want front == n.
        val deg = Math.toDegrees((n.angle() - Vec2(0f, 1f).angle()).toDouble()).toFloat()
        return f.withPose(p, deg)
    }

    private fun wallSeed(original: List<Furniture>): List<Furniture> {
        val out = original.toMutableList()
        val order = out.indices.filter { !out[it].locked && out[it].type.wallAffinity >= 0.5f }
            .sortedByDescending { out[it].width }
        val wallOrder = walls.indices.sortedByDescending { walls[it].length }
        for ((k, i) in order.withIndex()) {
            out[i] = placeAgainstWall(out[i], wallOrder[k % wallOrder.size], rnd.nextFloat())
        }
        return out
    }

    // ------------------------------------------------------------------ cost

    fun totalCost(original: List<Furniture>, layout: List<Furniture>, g: OccupancyGrid = grid): Float {
        var hard = 0f
        var soft = 0f
        for ((i, f) in layout.withIndex()) {
            val corners = f.corners()
            hard += outsidePenalty(corners) * 3f
            for (j in i + 1 until layout.size) {
                hard += penetration(corners, layout[j].corners()) * 4f
            }
            for (ds in doorSwings) hard += penetration(corners, ds) * 3f
            if (f.tall) for (wz in windowZones) hard += penetration(corners, wz) * 1.5f
            // Use-zones must be clear (of other pieces, of walls).
            val zones = listOfNotNull(f.frontZone()) + f.sideZones()
            for (z in zones) {
                hard += outsidePenalty(z) * 1.2f
                for (o in layout) {
                    if (o.id == f.id || zoneExempt(f, o)) continue
                    hard += penetration(z, o.corners()) * 1.5f
                }
            }
            // Backs to walls.
            val affinity = if (goal == LayoutGoal.PARTY) max(f.type.wallAffinity, 0.6f) else f.type.wallAffinity
            if (affinity > 0f) soft += affinity * wallMisfit(f) * 0.9f
            soft += relationCost(f, layout) * goal.relationScale + goalCost(f, layout)
            // Sweat: how far it moved from where it sits today.
            val o = original.firstOrNull { it.id == f.id }
            if (o != null) {
                val moved = o.center.dist(f.center)
                val rotated = abs(o.rotationDelta(f)) / 90f
                if (moved > 0.05f || rotated > 0.05f) soft += effort.movePenalty * (0.3f + moved + 0.3f * rotated) * sizeFactor(f)
            }
        }
        // Interior-design guidelines (conversation, balance, alignment, emphasis).
        if (designWeight > 0f) soft += DesignGuidelines.cost(room, layout, goal.relationScale.coerceIn(0.3f, 1.2f) * designWeight)
        val rep = OpennessAnalyzer.analyze(room, layout, g, withFlows = false, walkRadius = goal.walkRadius)
        val open = rep.score / 100f
        // Furniture you can't walk up to is useless, however open the room looks.
        val unreachable = (1f - rep.accessPct) * layout.size
        var goalOpen = goal.clearBonus * rep.clearDiameter
        if (goal == LayoutGoal.ACCESSIBLE) goalOpen -= 6f * max(0f, TURNING_DIAMETER - rep.clearDiameter)
        return hard * 10f + soft + unreachable * 2.5f - open * goal.openWeight - goalOpen
    }

    /** Extra wishes that only matter for some goals. */
    private fun goalCost(f: Furniture, layout: List<Furniture>): Float {
        var c = 0f
        when (goal) {
            LayoutGoal.MOVIE_NIGHT -> when (f.type) {
                FurnitureType.TV_STAND -> for (w in room.windows()) {
                    // Glare: screen facing a window, or a bright window right behind the screen.
                    val mid = room.openingSegment(w).pointAt(0.5f)
                    val toWin = (mid - f.center).normalized()
                    val align = kotlin.math.abs(f.front() dot toWin)
                    if (align > 0.6f) c += 1.2f * (align - 0.6f) / 0.4f * (3f / (1f + mid.dist(f.center)))
                }
                FurnitureType.ARMCHAIR -> {
                    val tv = layout.filter { it.type == FurnitureType.TV_STAND }.minByOrNull { it.center.dist(f.center) }
                    if (tv != null) {
                        val d = tv.center.dist(f.center)
                        c += 0.5f * (max(0f, 1.8f - d) + max(0f, d - 3.4f))
                        c += 0.8f * (1f - (f.front() dot (tv.center - f.center).normalized()))
                    }
                }
                else -> {}
            }
            LayoutGoal.WORK_FROM_HOME -> if (f.type == FurnitureType.DESK) {
                val w = room.windows().minByOrNull { room.openingSegment(it).distanceTo(f.center) }
                if (w != null) {
                    val d = room.openingSegment(w).distanceTo(f.center)
                    c += 0.9f * max(0f, d - 1.0f)
                    // Daylight from the side: no glare on screen, no squinting into the window.
                    c += 0.8f * kotlin.math.abs(f.front() dot room.inwardNormal(w.wallIndex))
                }
                // Out of the doorway traffic.
                room.doors().forEach { d -> if (room.openingSegment(d).distanceTo(f.center) < 1.4f) c += 0.6f }
            }
            else -> {}
        }
        // Sun awareness: plants into the light, screens out of the glare.
        if (sunTimes.isNotEmpty()) when (f.type) {
            FurnitureType.PLANT -> c -= 0.25f * min(4f, sunHours(f.center, f.height, layout, f.id))
            FurnitureType.TV_STAND -> c += 0.35f * sunHours(f.center + f.front() * (f.depth / 2f + 0.05f), f.height + 0.35f, layout, f.id)
            else -> {}
        }
        return c
    }

    private fun sunHours(p: Vec2, z: Float, layout: List<Furniture>, ignore: String): Float {
        var h = 0f
        for (sp in sunTimes) if (SunModel.sunReaches(room, p, z, sp, room.northDeg ?: 0f, layout, ignore)) h += 1f
        return h
    }

    private fun sizeFactor(f: Furniture) = (0.6f + f.footprint()).coerceAtMost(2.5f)

    private fun zoneExempt(owner: Furniture, other: Furniture): Boolean = when (other.type) {
        FurnitureType.CHAIR -> owner.type == FurnitureType.DESK || owner.type == FurnitureType.DINING_TABLE
        FurnitureType.COFFEE_TABLE -> owner.type == FurnitureType.SOFA || owner.type == FurnitureType.ARMCHAIR
        FurnitureType.NIGHTSTAND -> owner.type == FurnitureType.BED
        else -> false
    }

    private fun wallMisfit(f: Furniture): Float {
        val back = f.backCenter()
        var best = Float.MAX_VALUE
        for (i in walls.indices) {
            val d = walls[i].distanceTo(back)
            val align = 1f - (f.front() dot normals[i])
            val c = d + 0.6f * align
            if (c < best) best = c
        }
        return min(best, 3f)
    }

    private fun relationCost(f: Furniture, layout: List<Furniture>): Float {
        var c = 0f
        when (f.type) {
            FurnitureType.SOFA -> {
                val tv = layout.filter { it.type == FurnitureType.TV_STAND }.minByOrNull { it.center.dist(f.center) }
                if (tv != null) {
                    val d = tv.center.dist(f.center)
                    c += 0.6f * (max(0f, 1.8f - d) + max(0f, d - 3.6f))
                    val facing = f.front() dot (tv.center - f.center).normalized()
                    c += 0.8f * (1f - facing)
                }
            }
            FurnitureType.ARMCHAIR -> {
                val anchor = layout.filter { it.type == FurnitureType.SOFA || it.type == FurnitureType.COFFEE_TABLE }
                    .minByOrNull { it.center.dist(f.center) }
                if (anchor != null) {
                    val d = anchor.center.dist(f.center)
                    c += 0.4f * max(0f, d - 2.0f)
                    c += 0.3f * (1f - (f.front() dot (anchor.center - f.center).normalized()))
                }
            }
            FurnitureType.COFFEE_TABLE -> {
                val sofa = layout.filter { it.type == FurnitureType.SOFA }.minByOrNull { it.center.dist(f.center) }
                if (sofa != null) {
                    val target = sofa.center + sofa.front() * (sofa.depth / 2f + 0.42f + f.depth / 2f)
                    c += 0.7f * target.dist(f.center)
                }
            }
            FurnitureType.NIGHTSTAND -> {
                val bed = layout.filter { it.type == FurnitureType.BED }.minByOrNull { it.center.dist(f.center) }
                if (bed != null) {
                    val head = bed.backCenter()
                    val sides = listOf(1f, -1f).map { s -> head + bed.right() * (s * (bed.width / 2f + f.width / 2f + 0.05f)) + bed.front() * (f.depth / 2f) }
                    c += 0.8f * sides.minOf { it.dist(f.center) }
                }
            }
            FurnitureType.CHAIR -> {
                val table = layout.filter { it.type == FurnitureType.DESK || it.type == FurnitureType.DINING_TABLE }
                    .minByOrNull { it.center.dist(f.center) }
                if (table != null) c += 0.6f * max(0f, table.center.dist(f.center) - (table.depth / 2f + 0.45f))
            }
            FurnitureType.DESK -> {
                val ws = room.windows()
                if (ws.isNotEmpty()) {
                    val d = ws.minOf { room.openingSegment(it).distanceTo(f.center) }
                    c += 0.15f * max(0f, d - 1.2f)
                }
            }
            FurnitureType.BED -> {
                // Don't put the bed right in the door's path.
                room.doors().forEach { d ->
                    val s = room.openingSegment(d)
                    if (s.distanceTo(f.center) < 1.3f) c += 0.5f
                }
            }
            else -> {}
        }
        return c
    }

    private fun outsidePenalty(poly: List<Vec2>): Float {
        var p = 0f
        for (v in poly) if (!Geo.pointInPolygon(v, room.outline)) p += 0.05f + Geo.distanceToBoundary(v, room.outline)
        return p
    }

    /** Smallest separating distance along SAT axes; 0 if the quads don't overlap. */
    private fun penetration(a: List<Vec2>, b: List<Vec2>): Float {
        var minOverlap = Float.MAX_VALUE
        for (poly in arrayOf(a, b)) {
            for (i in poly.indices) {
                val axis = (poly[(i + 1) % poly.size] - poly[i]).perp().normalized()
                var minA = Float.MAX_VALUE; var maxA = -Float.MAX_VALUE
                for (p in a) { val d = p dot axis; if (d < minA) minA = d; if (d > maxA) maxA = d }
                var minB = Float.MAX_VALUE; var maxB = -Float.MAX_VALUE
                for (p in b) { val d = p dot axis; if (d < minB) minB = d; if (d > maxB) maxB = d }
                val o = min(maxA, maxB) - max(minA, minB)
                if (o <= 0.005f) return 0f
                if (o < minOverlap) minOverlap = o
            }
        }
        return 0.05f + minOverlap
    }

    /** Square up near-axis rotations and nudge pieces that hover a few cm off a wall. */
    private fun snapTidy(f: Furniture, layout: List<Furniture>): Furniture {
        if (f.locked) return f
        val snappedRot = (Math.round(f.rotationDeg / 90f) * 90f)
        var g = if (abs(Geo.shortestAngleDiffDeg(f.rotationDeg, snappedRot)) < 12f) f.withPose(f.center, snappedRot) else f
        if (g.type.wallAffinity >= 0.5f) {
            val back = g.backCenter()
            val wi = walls.indices.minByOrNull { walls[it].distanceTo(back) } ?: return g
            val d = walls[wi].distanceTo(back)
            if (d in 0.03f..0.35f && (g.front() dot normals[wi]) > 0.95f) {
                g = g.withPose(g.center - normals[wi] * (d - 0.02f), g.rotationDeg)
            }
        }
        return g
    }

    fun violations(layout: List<Furniture>): List<String> {
        val out = ArrayList<String>()
        for ((i, f) in layout.withIndex()) {
            val c = f.corners()
            if (outsidePenalty(c) > 0f) out += "${f.name} sticks through a wall"
            for (j in i + 1 until layout.size) if (penetration(c, layout[j].corners()) > 0.06f) out += "${f.name} overlaps ${layout[j].name}"
            if (doorSwings.any { penetration(c, it) > 0.06f }) out += "${f.name} blocks a door swing"
        }
        return out.distinct()
    }

    private fun gauss(): Float {
        // Box-Muller
        val u1 = max(1e-6f, rnd.nextFloat())
        val u2 = rnd.nextFloat()
        return (kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1.toDouble())) * kotlin.math.cos(2 * Math.PI * u2)).toFloat()
    }
}
