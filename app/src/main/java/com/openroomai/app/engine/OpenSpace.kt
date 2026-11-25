package com.openroomai.app.engine

import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Room
import com.openroomai.app.model.Segment
import com.openroomai.app.model.Vec2
import kotlin.math.max
import kotlin.math.min

/** Walkway width tiers: geometric comfort rules for circulation lanes. */
object Lanes {
    /** Under 12″ you can't get through at all. */
    const val MIN_PASS = 0.305f
    /** Under 30″ a walkway is a bottleneck. */
    const val PINCH = 0.762f
    /** 36″: a clear, comfortable lane. */
    const val CLEAR = 0.914f
    /** 44″: generous, two people can pass. */
    const val GENEROUS = 1.118f
    /** Gaps narrower than 18″ aren't usable open floor. */
    const val USABLE = 0.457f
    /** An open zone needs 36″ on its short side to be floor you can use for something. */
    const val MIN_ZONE_SIDE = 0.914f

    fun tier(width: Float) = when {
        width < PINCH -> Tier.PINCH
        width < CLEAR -> Tier.TIGHT
        width < GENEROUS -> Tier.CLEAR
        else -> Tier.WIDE
    }
}

enum class Tier(val label: String) { PINCH("Bottleneck"), TIGHT("Tight"), CLEAR("Clear"), WIDE("Wide open") }

/** Comfort clearances, used as geometric rules. */
object Clearances {
    /** Seat front to coffee table: comfortable legroom that still keeps the middle open. */
    const val LEGROOM_MIN = 0.356f // 14″
    const val LEGROOM_MAX = 0.457f // 18″
    const val DINING_PULL_OUT = 0.914f // 36″ behind dining chairs
    const val BED_SIDE = 0.61f // 24″ along a side of the bed
    const val DESK_CHAIR = 0.914f // 36″ for a desk chair to roll back
}

/** The largest contiguous open floor rectangle, aligned with the room's walls. */
data class OpenZone(val center: Vec2, val width: Float, val depth: Float, val angleRad: Float) {
    val area get() = width * depth
    val long get() = max(width, depth)
    val short get() = min(width, depth)
    fun corners() = Geo.rectCorners(center, width, depth, angleRad)
}

/** The best walking lane from the way in to one destination. */
data class Corridor(
    val targetId: String,
    val targetName: String,
    /** Clear width at the narrowest point of the best lane (under [Lanes.MIN_PASS] = can't get there). */
    val width: Float,
    /** Route in plan coordinates, sampled every few inches (empty when routes weren't traced). */
    val path: List<Vec2> = emptyList(),
    /** Clear width at each [path] point. */
    val widths: List<Float> = emptyList(),
    val pinch: Vec2? = null,
    /** 1 = dead straight; lower = winding. */
    val straightness: Float = 1f,
) {
    val reachable get() = width >= Lanes.MIN_PASS
    val tier get() = Lanes.tier(width)
}

data class Pinch(val at: Vec2, val width: Float, val between: String, val targetName: String)

data class SpaceNote(val icon: String, val title: String, val body: String, val at: Vec2? = null, val furnitureId: String? = null)

data class OpenSpaceReport(
    val zone: OpenZone?,
    /** Open floor in regions at least 18″ wide; slivers don't count. */
    val usableArea: Float,
    /** All furniture-free floor outside door swings. */
    val freeArea: Float,
    val floorArea: Float,
    val corridors: List<Corridor>,
    val pinches: List<Pinch> = emptyList(),
    val notes: List<SpaceNote> = emptyList(),
) {
    val lanes get() = corridors.filter { it.reachable }
    val blocked get() = corridors.filter { !it.reachable }
    /** Narrowest lane to anywhere you can get to; null when there's nowhere to go. */
    val narrowest: Float? get() = lanes.minOfOrNull { it.width }
    val narrowestLane: Corridor? get() = lanes.minByOrNull { it.width }
    val tightLanes get() = lanes.count { it.width < Lanes.CLEAR }
    val usablePct get() = if (floorArea <= 0f) 0f else usableArea / floorArea
}

/** Before and after, in the numbers people care about. */
data class SpaceDiff(val before: OpenSpaceReport, val after: OpenSpaceReport) {
    val usableGain get() = after.usableArea - before.usableArea
    val zoneGain get() = (after.zone?.area ?: 0f) - (before.zone?.area ?: 0f)
    val laneGain: Float?
        get() {
            val a = after.narrowest; val b = before.narrowest
            return if (a == null || b == null) null else a - b
        }
    val tightFixed get() = before.tightLanes - after.tightLanes
    val newlyReachable get() = before.blocked.count { b -> after.lanes.any { it.targetId == b.targetId } }

    /** Walkways that got at least an inch wider, most-widened first: (destination, before, after). */
    val widened: List<Triple<String, Float, Float>>
        get() = after.corridors.mapNotNull { c ->
            val b = before.corridors.firstOrNull { it.targetId == c.targetId } ?: return@mapNotNull null
            if (c.width - b.width >= Units.INCH) Triple(c.targetName, b.width, c.width) else null
        }.sortedByDescending { it.third - it.second }

    /** Did anything people would notice get better? */
    val improved get() = usableGain > 0.01f || zoneGain > 0.05f || widened.isNotEmpty() || newlyReachable > 0
}

/**
 * Open Area AI's measuring tape: how much usable open floor there is, the single biggest open
 * zone, and how wide every walking lane really is. Every suggestion is ranked by these numbers.
 *
 * A [Meter] keeps the room-aligned grid around, so measuring many candidate layouts of the same
 * room only costs the per-layout work. Use one meter per thread.
 */
object OpenSpace {
    const val FINE = 0.025f // ~1″ cells for numbers people see
    const val FAST = 0.05f  // ~2″ cells for screening candidates

    fun analyze(room: Room, layout: List<Furniture>, cell: Float = FINE, withNotes: Boolean = true): OpenSpaceReport =
        Meter(room, cell).measure(layout, routes = true, notes = withNotes)

    fun diff(room: Room, before: List<Furniture>, after: List<Furniture>, cell: Float = FINE): SpaceDiff {
        val m = Meter(room, cell)
        return SpaceDiff(m.measure(before, routes = true), m.measure(after, routes = true))
    }

    class Meter(val room: Room, val cell: Float = FINE) {
        private val frame = RoomFrame.of(room)
        private val local = frame.toLocal(room)
        private val swings = local.doors().map { local.doorSwing(it) }
        val grid = SpaceGrid(local, cell)
        private val floorArea = room.area

        fun measure(layout: List<Furniture>, routes: Boolean = false, notes: Boolean = false): OpenSpaceReport {
            val g = grid
            val ll = layout.map { frame.toLocal(it) }
            g.stamp(ll, swings)
            val zone = largestZone(g)?.let { (c, w, d) -> OpenZone(frame.toWorld(c), w, d, frame.angle) }
            var free = 0
            for (i in 0 until g.size) if (g.open(i)) free++
            val usable = usableCells(g)
            val corridors = corridors(g, local, ll, routes).map { c ->
                if (c.path.isEmpty()) c else c.copy(path = c.path.map(frame::toWorld), pinch = c.pinch?.let(frame::toWorld))
            }
            val pinches = ArrayList<Pinch>()
            if (routes) for (c in corridors.filter { it.reachable }.sortedBy { it.width }) {
                val p = c.pinch ?: continue
                if (c.width >= Lanes.CLEAR || pinches.any { it.at.dist(p) < 0.5f }) continue
                pinches += Pinch(p, c.width, between(room, layout, p, c.width), c.targetName)
            }
            val cellA = cell * cell
            val rep = OpenSpaceReport(zone, usable * cellA, free * cellA, floorArea, corridors, pinches)
            return if (notes) rep.copy(notes = notes(room, layout, rep)) else rep
        }
    }

    // ------------------------------------------------------------------ largest open zone

    /** Maximal empty rectangle via row histograms and a monotonic stack: O(cells). */
    private fun largestZone(g: SpaceGrid): Triple<Vec2, Float, Float>? {
        val heights = IntArray(g.w)
        val stack = IntArray(g.w + 1)
        val minSide = Lanes.MIN_ZONE_SIDE / g.cell - 1e-3f
        val minAny = Lanes.USABLE / g.cell - 1e-3f
        var best = 0; val b = IntArray(4)
        var any = 0; val a = IntArray(4)
        for (y in 0 until g.h) {
            for (x in 0 until g.w) heights[x] = if (g.open(y * g.w + x)) heights[x] + 1 else 0
            var sp = 0
            for (x in 0..g.w) {
                val cur = if (x < g.w) heights[x] else 0
                while (sp > 0 && heights[stack[sp - 1]] >= cur) {
                    val hgt = heights[stack[--sp]]
                    val left = if (sp == 0) 0 else stack[sp - 1] + 1
                    val wid = x - left
                    val area = hgt * wid
                    val short = min(hgt, wid)
                    if (area > best && short >= minSide) { best = area; b[0] = left; b[1] = x - 1; b[2] = y - hgt + 1; b[3] = y }
                    if (area > any && short >= minAny) { any = area; a[0] = left; a[1] = x - 1; a[2] = y - hgt + 1; a[3] = y }
                }
                stack[sp++] = x
            }
        }
        val r = when {
            best > 0 -> b
            any > 0 -> a
            else -> return null
        }
        val c0 = g.center(r[0], r[2]); val c1 = g.center(r[1], r[3])
        return Triple((c0 + c1) / 2f, (r[1] - r[0] + 1) * g.cell, (r[3] - r[2] + 1) * g.cell)
    }

    // ------------------------------------------------------------------ usable open area

    /** Morphological opening with an 18″ disc: open floor you can actually stand and move in. */
    private fun usableCells(g: SpaceGrid): Int {
        val r = Lanes.USABLE / 2f
        var anyCenter = false
        val centers = BooleanArray(g.size) { i -> (g.open(i) && g.clearance[i] >= r).also { if (it) anyCenter = true } }
        if (!anyCenter) return 0
        val d2 = g.edt(centers, borderIsSource = false)
        val rc = r / g.cell
        val r2 = rc * rc
        var n = 0
        for (i in 0 until g.size) if (g.open(i) && d2[i] <= r2) n++
        return n
    }

    // ------------------------------------------------------------------ corridors

    private val DX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
    private val DY = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
    private val STEP = floatArrayOf(1f, 1f, 1f, 1f, 1.4142135f, 1.4142135f, 1.4142135f, 1.4142135f)

    /** Pieces people walk up to. Nightstands, chairs and coffee tables live beside something that is. */
    fun isDestination(f: Furniture) = when (f.type) {
        FurnitureType.PLANT, FurnitureType.NIGHTSTAND, FurnitureType.CHAIR, FurnitureType.COFFEE_TABLE -> false
        else -> true
    }

    private fun corridors(g: SpaceGrid, room: Room, layout: List<Furniture>, routes: Boolean): List<Corridor> {
        val dests = layout.filter(::isDestination).map { f -> Triple(f.id, f.name, f.accessPoints()) } +
            room.doors().drop(1).mapIndexed { k, d ->
                val s = room.openingSegment(d)
                Triple("door${k + 1}", "the other door", listOf(s.pointAt(0.5f) + room.inwardNormal(d.wallIndex) * 0.35f))
            }
        if (dests.isEmpty()) return emptyList()
        val start = bestCellNear(g, room.entryPoint(), 0.6f) { g.width(it) }
            ?: return dests.map { (id, name, _) -> Corridor(id, name, 0f) }
        if (!routes) {
            // Screening: widths only, via one union-find sweep (no heap, no per-target search).
            val w = widestFast(g, start, dests.map { (_, _, pts) -> cellsNear(g, pts, 0.45f) })
            if (w != null) return dests.mapIndexed { k, (id, name, _) -> Corridor(id, name, w[k]) }
        }
        val best = widest(g, start)
        val out = ArrayList<Corridor>(dests.size)
        for ((id, name, pts) in dests) {
            var target = -1; var tv = 0f
            for (p in pts) {
                val c = bestCellNear(g, p, 0.45f) { best[it] } ?: continue
                if (best[c] > tv) { tv = best[c]; target = c }
            }
            if (target < 0 || tv < Lanes.MIN_PASS || !routes) { out += Corridor(id, name, max(0f, tv)); continue }
            val cells = route(g, start, target, tv - 1e-4f) ?: listOf(start, target)
            var pinch = cells.first(); var pw = Float.MAX_VALUE
            for (i in cells) { val wv = g.width(i); if (wv < pw) { pw = wv; pinch = i } }
            val path = ArrayList<Vec2>(); val widths = ArrayList<Float>()
            val every = max(1, (0.075f / g.cell).toInt())
            for ((k, i) in cells.withIndex()) if (k % every == 0 || k == cells.size - 1) { path += g.center(i); widths += g.width(i) }
            var len = 0f
            for (k in 1 until path.size) len += path[k].dist(path[k - 1])
            val straight = if (len < 1e-3f) 1f else (path.first().dist(path.last()) / len).coerceIn(0f, 1f)
            out += Corridor(id, name, tv, path, widths, g.center(pinch), straight)
        }
        return out
    }

    /** Walkable cells within [radius] of any of [pts] (duplicates are harmless downstream). */
    private fun cellsNear(g: SpaceGrid, pts: List<Vec2>, radius: Float): IntArray {
        val r = (radius / g.cell).toInt() + 1
        val side = 2 * r + 1
        val out = IntArray(pts.size * side * side)
        var n = 0
        val r2 = radius * radius
        for (p in pts) {
            val cx = g.gxOf(p.x); val cy = g.gyOf(p.y)
            for (dy in -r..r) for (dx in -r..r) {
                val x = cx + dx; val y = cy + dy
                if (!g.inGrid(x, y)) continue
                val i = y * g.w + x
                if (!g.walkable(i)) continue
                val ex = g.bounds.minX + (x + 0.5f) * g.cell - p.x
                val ey = g.bounds.minY + (y + 0.5f) * g.cell - p.y
                if (ex * ex + ey * ey <= r2) out[n++] = i
            }
        }
        return out.copyOf(n)
    }

    /**
     * Widest-path widths from [start] to each target cell set, in one sweep: activate cells from
     * the widest down (counting sort on the integer squared clearance), union neighbours, and a
     * target's width is the level at which one of its cells first joins the start's component
     * (Kruskal-style maximum spanning forest). Each component carries a bitmask of the targets
     * it touches, so targets never act as shortcuts between components. Same numbers as
     * [widest], several times faster. Null when there are too many targets for the mask.
     */
    private fun widestFast(g: SpaceGrid, start: Int, targets: List<IntArray>): FloatArray? {
        val t = targets.size
        if (t > 64) return null
        val n = g.size
        val out = FloatArray(t)
        var maxD = 0
        for (i in 0 until n) if (g.walkable(i) && g.d2[i] > maxD) maxD = g.d2[i]
        val count = IntArray(maxD + 1)
        for (i in 0 until n) if (g.walkable(i)) count[g.d2[i]]++
        val first = IntArray(maxD + 1)
        var acc = 0
        for (d in maxD downTo 0) { first[d] = acc; acc += count[d] }
        val order = IntArray(acc)
        val fill = first.copyOf()
        for (i in 0 until n) if (g.walkable(i)) order[fill[g.d2[i]]++] = i
        val member = LongArray(n)
        targets.forEachIndexed { k, cells -> for (c in cells) member[c] = member[c] or (1L shl k) }
        val parent = IntArray(n) { -1 }
        val mask = LongArray(n)
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var y = x
            while (parent[y] != r) { val nx = parent[y]; parent[y] = r; y = nx }
            return r
        }
        val all = if (t == 64) -1L else (1L shl t) - 1
        var done = 0L
        for (d in maxD downTo 0) {
            val from = first[d]; val to = from + count[d]
            if (from == to) continue
            for (j in from until to) {
                val c = order[j]
                parent[c] = c
                mask[c] = member[c]
                val cx = c % g.w; val cy = c / g.w
                for (k in 0 until 8) {
                    val nx = cx + DX[k]; val ny = cy + DY[k]
                    if (!g.inGrid(nx, ny)) continue
                    val nb = ny * g.w + nx
                    if (parent[nb] < 0) continue
                    val ra = find(c); val rb = find(nb)
                    if (ra != rb) { parent[ra] = rb; mask[rb] = mask[rb] or mask[ra] }
                }
            }
            if (parent[start] < 0) continue
            val fresh = mask[find(start)] and done.inv()
            if (fresh != 0L) {
                val w = g.widthOfD2(d)
                for (k in 0 until t) if (fresh and (1L shl k) != 0L) out[k] = w
                done = done or fresh
                if (done == all) break
            }
        }
        return out
    }

    /** Walkable cell within [radius] of [p] with the highest [score]; null if none. */
    private inline fun bestCellNear(g: SpaceGrid, p: Vec2, radius: Float, score: (Int) -> Float): Int? {
        val r = (radius / g.cell).toInt() + 1
        val cx = g.gxOf(p.x); val cy = g.gyOf(p.y)
        var best = -1; var bv = -Float.MAX_VALUE
        for (dy in -r..r) for (dx in -r..r) {
            val x = cx + dx; val y = cy + dy
            if (!g.inGrid(x, y)) continue
            val i = y * g.w + x
            if (!g.walkable(i)) continue
            val d = g.center(x, y).dist(p)
            if (d > radius) continue
            val v = score(i) - 0.002f * d
            if (v > bv) { bv = v; best = i }
        }
        return if (best >= 0) best else null
    }

    /**
     * Widest-path (maximin) search from [start]: for every cell, the widest lane it can be
     * reached through. Dijkstra on a max-heap keyed by the bottleneck width.
     */
    private fun widest(g: SpaceGrid, start: Int): FloatArray {
        val best = FloatArray(g.size) { -1f }
        val heap = PackedHeap(g.size / 4 + 16, max = true)
        best[start] = g.width(start)
        heap.push(best[start], start)
        while (heap.size > 0) {
            val v = heap.peekKey(); val c = heap.pop()
            if (v < best[c]) continue
            val cx = c % g.w; val cy = c / g.w
            for (k in 0 until 8) {
                val nx = cx + DX[k]; val ny = cy + DY[k]
                if (!g.inGrid(nx, ny)) continue
                val n = ny * g.w + nx
                if (!g.walkable(n)) continue
                val nv = min(v, g.width(n))
                if (nv > best[n]) { best[n] = nv; heap.push(nv, n) }
            }
        }
        return best
    }

    /**
     * The route people would actually take at that width: A* over cells at least [minWidth]
     * wide, with a mild preference for the middle of the lane so routes don't scrape furniture.
     */
    private fun route(g: SpaceGrid, start: Int, target: Int, minWidth: Float): List<Int>? {
        val cost = FloatArray(g.size) { Float.MAX_VALUE }
        val prev = IntArray(g.size) { -1 }
        val heap = PackedHeap(256, max = false)
        val tp = g.center(target)
        fun h(i: Int) = g.center(i).dist(tp) / g.cell
        cost[start] = 0f
        heap.push(h(start), start)
        val comfy = Lanes.GENEROUS / 2f
        while (heap.size > 0) {
            val c = heap.pop()
            if (c == target) break
            val cx = c % g.w; val cy = c / g.w
            val cc = cost[c]
            for (k in 0 until 8) {
                val nx = cx + DX[k]; val ny = cy + DY[k]
                if (!g.inGrid(nx, ny)) continue
                val n = ny * g.w + nx
                if (!g.walkable(n) || g.width(n) < minWidth) continue
                val hug = 1f - min(g.clearance[n], comfy) / comfy
                val nc = cc + STEP[k] * (1f + 0.6f * hug)
                if (nc < cost[n]) { cost[n] = nc; prev[n] = c; heap.push(nc + h(n), n) }
            }
        }
        if (cost[target] == Float.MAX_VALUE) return null
        val out = ArrayList<Int>()
        var c = target
        while (c >= 0) { out += c; if (c == start) break; c = prev[c] }
        out.reverse()
        return out
    }

    // ------------------------------------------------------------------ naming + notes

    /** "between the Sofa and the TV stand" or "between the Sofa and Wall C". */
    fun between(room: Room, layout: List<Furniture>, p: Vec2, width: Float): String {
        val near = ArrayList<Pair<String, Float>>()
        for (f in layout) {
            val c = f.corners()
            val d = if (OccupancyGrid.pointInConvex(p, c)) 0f else Geo.edges(c).minOf { it.distanceTo(p) }
            near += "the ${f.name}" to d
        }
        room.walls().forEachIndexed { i, w -> near += "Wall ${room.wallLabel(i)}" to w.distanceTo(p) }
        val two = near.filter { it.second <= width / 2f + 0.25f }.sortedBy { it.second }.map { it.first }.distinct().take(2)
        return when (two.size) {
            2 -> "between ${two[0]} and ${two[1]}"
            1 -> "beside ${two[0]}"
            else -> "on the way in"
        }
    }

    /** Free distance from [p] along [dir] until a wall or a piece (except [ignore]), up to [max]. */
    fun clearRun(room: Room, layout: List<Furniture>, p: Vec2, dir: Vec2, ignore: Set<String>, max: Float = 2f): Float {
        val step = 0.0127f // ½″
        val others = layout.filter { it.id !in ignore }.map { it.corners() }
        var t = step
        while (t < max) {
            val q = p + dir * t
            if (!Geo.pointInPolygon(q, room.outline)) return t - step
            if (others.any { OccupancyGrid.pointInConvex(q, it) }) return t - step
            t += step
        }
        return max
    }

    /** Gap from a seat's front edge to the table in front of it; null if the table isn't in front. */
    fun legroom(seat: Furniture, table: Furniture): Float? {
        val fr = seat.front(); val r = seat.right()
        val front = seat.center + fr * (seat.depth / 2f)
        val cs = table.corners()
        val lat = cs.map { (it - seat.center) dot r }
        if (lat.max() < -seat.width / 2f || lat.min() > seat.width / 2f) return null
        val near = cs.minOf { (it - front) dot fr }
        return if (near < -0.05f || near > 1.2f) null else max(0f, near)
    }

    /** The coffee table each seat uses (nearest one in front of it), with the legroom gap. */
    fun legrooms(layout: List<Furniture>): List<Triple<Furniture, Furniture, Float>> {
        val tables = layout.filter { it.type == FurnitureType.COFFEE_TABLE }
        if (tables.isEmpty()) return emptyList()
        return layout.filter { it.type == FurnitureType.SOFA || it.type == FurnitureType.ARMCHAIR }.mapNotNull { s ->
            tables.mapNotNull { t -> legroom(s, t)?.let { t to it } }.minByOrNull { it.second }?.let { (t, gap) -> Triple(s, t, gap) }
        }
    }

    /** Does a low piece sit on the straight line from the door (ignoring the last 50 cm of approach)? */
    fun cutsLine(f: Furniture, line: Segment): Boolean {
        val len = line.length
        if (len < 0.9f) return false
        val c = f.corners()
        val edges = Geo.edges(c)
        val n = (len / 0.05f).toInt()
        for (k in 0..n) {
            val t = k / n.toFloat()
            if (t * len > len - 0.5f) break
            val p = line.pointAt(t)
            if (OccupancyGrid.pointInConvex(p, c) || edges.minOf { it.distanceTo(p) } < 0.15f) return true
        }
        return false
    }

    private fun notes(room: Room, layout: List<Furniture>, rep: OpenSpaceReport): List<SpaceNote> {
        val out = ArrayList<SpaceNote>()
        for (p in rep.pinches) out += SpaceNote(
            if (p.width < Lanes.PINCH) "⛔" else "↔️",
            "Walkway to ${p.targetName.withArticle()} narrows to ${Units.width(p.width)}",
            "It pinches ${p.between}. ${Units.width(Lanes.CLEAR)} feels clear and ${Units.width(Lanes.GENEROUS)} feels generous. The Micro-Nudges above show the quickest fix.",
            p.at,
        )
        for (c in rep.blocked) out += SpaceNote(
            "🚧", "No clear way to ${c.targetName.withArticle()}",
            "Every route is under ${Units.width(Lanes.MIN_PASS)} wide. Try Widest flow paths.",
        )
        // Low pieces on the straight line from the door to where people go: flow has to zig-zag.
        val e = room.entryPoint()
        val dests = layout.filter(::isDestination)
        for (f in layout) {
            if (f.height > 0.65f || f.type == FurnitureType.BED) continue
            val cuts = dests.any { d -> d.id != f.id && cutsLine(f, Segment(e, d.accessPoints().minBy { it.dist(e) })) }
            if (cuts) out += SpaceNote(
                "🧭", "The ${f.name} sits in the line from the door",
                "People walk in straight lines. Shifting it out of that line keeps the flow open.", f.center, f.id,
            )
        }
        for ((seat, table, gap) in legrooms(layout)) {
            if (gap < Clearances.LEGROOM_MIN || gap > Clearances.LEGROOM_MAX + 0.1f) out += SpaceNote(
                "🦵", "The ${table.name} is ${Units.width(gap)} from the ${seat.name}",
                "${Units.width(Clearances.LEGROOM_MIN)}–${Units.width(Clearances.LEGROOM_MAX)} gives comfortable legroom and keeps the middle open.",
                table.center, table.id,
            )
        }
        for (f in layout) when (f.type) {
            FurnitureType.DINING_TABLE -> {
                val ignore = layout.filter { it.type == FurnitureType.CHAIR }.map { it.id }.toSet() + f.id
                // Chairs pull out from the long sides.
                val tight = listOf(f.front(), -f.front()).minOf { d -> runFromEdge(room, layout, f, d, f.depth / 2f, f.right(), f.width, ignore) }
                if (tight < Clearances.DINING_PULL_OUT) out += SpaceNote(
                    "🍽️", "Only ${Units.width(tight)} behind a side of the ${f.name}",
                    "${Units.width(Clearances.DINING_PULL_OUT)} lets chairs pull out without bumping.", f.center, f.id,
                )
            }
            FurnitureType.BED -> {
                val ignore = layout.filter { it.type == FurnitureType.NIGHTSTAND }.map { it.id }.toSet() + f.id
                val gaps = listOf(f.right(), -f.right()).map { d -> runFromEdge(room, layout, f, d, f.width / 2f, f.front(), f.depth, ignore) }
                if (gaps.max() < Clearances.BED_SIDE) out += SpaceNote(
                    "🛏️", "Both sides of the ${f.name} are under ${Units.width(Clearances.BED_SIDE)}",
                    "Leave ${Units.width(Clearances.BED_SIDE)} along at least one side to get in and make the bed.", f.center, f.id,
                )
            }
            FurnitureType.DESK -> {
                val ignore = layout.filter { it.type == FurnitureType.CHAIR }.map { it.id }.toSet() + f.id
                val gap = runFromEdge(room, layout, f, f.front(), f.depth / 2f, f.right(), f.width * 0.5f, ignore)
                if (gap < Clearances.DESK_CHAIR) out += SpaceNote(
                    "🪑", "${Units.width(gap)} in front of the ${f.name}",
                    "${Units.width(Clearances.DESK_CHAIR)} lets the chair roll back without hitting anything.", f.center, f.id,
                )
            }
            else -> {}
        }
        for (d in room.doors()) {
            val swing = room.doorSwing(d)
            for (f in layout) if (Geo.convexOverlap(f.corners(), swing)) out += SpaceNote(
                "🚪", "The ${f.name} is in a door's swing",
                "The door can't open all the way. Even a few inches out of the arc helps.", f.center, f.id,
            )
        }
        return out
    }

    /**
     * Clear distance out from one edge of a piece: the median of three rays cast from along the
     * edge, so one stray corner doesn't decide it.
     */
    private fun runFromEdge(room: Room, layout: List<Furniture>, f: Furniture, dir: Vec2, half: Float, along: Vec2, span: Float, ignore: Set<String>): Float {
        val base = f.center + dir * (half + 0.01f)
        val runs = listOf(-0.35f, 0f, 0.35f).map { k -> clearRun(room, layout, base + along * (k * span), dir, ignore) }.sorted()
        return runs[1]
    }

    private fun String.withArticle() = if (startsWith("the ")) this else "the $this"
}

/** Minimal binary heap of (float key, int value) packed into longs: no boxing in the hot loop. */
internal class PackedHeap(cap: Int, private val max: Boolean) {
    private var a = LongArray(cap.coerceAtLeast(16))
    var size = 0
        private set

    private fun pack(key: Float, value: Int): Long {
        // Non-negative floats keep their order as raw bits; flip them for a min-heap.
        val bits = key.coerceAtLeast(0f).toRawBits().toLong()
        val k = if (max) bits else 0x7fffffffL - bits
        return (k shl 32) or (value.toLong() and 0xffffffffL)
    }

    fun push(key: Float, value: Int) {
        if (size == a.size) a = a.copyOf(size * 2)
        var i = size++
        a[i] = pack(key, value)
        while (i > 0) {
            val p = (i - 1) / 2
            if (a[p] >= a[i]) break
            val t = a[p]; a[p] = a[i]; a[i] = t; i = p
        }
    }

    fun peekKey(): Float {
        val k = a[0] ushr 32
        return Float.fromBits((if (max) k else 0x7fffffffL - k).toInt())
    }

    fun pop(): Int {
        val top = a[0]
        a[0] = a[--size]
        var i = 0
        while (true) {
            val l = 2 * i + 1; val r = l + 1
            var m = i
            if (l < size && a[l] > a[m]) m = l
            if (r < size && a[r] > a[m]) m = r
            if (m == i) break
            val t = a[m]; a[m] = a[i]; a[i] = t; i = m
        }
        return (top and 0xffffffffL).toInt()
    }
}
