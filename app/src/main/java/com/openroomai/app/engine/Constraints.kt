package com.openroomai.app.engine

import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Room
import com.openroomai.app.model.Vec2
import kotlin.math.max
import kotlin.math.min

/**
 * The physical rules a layout must obey, shared by the optimizer and the Micro-Nudge engine:
 * inside the walls (concave rooms too), no overlaps, door swings clear, tall pieces out of
 * windows, and each piece's own use zone kept free. 0 = legal; more = worse.
 */
class Constraints(val room: Room) {
    val walls = room.walls()
    val normals = walls.indices.map { room.inwardNormal(it) }
    val doorSwings = room.doors().map { room.doorSwing(it) }
    val windowZones = room.windows().map { w ->
        val s = room.openingSegment(w)
        val n = room.inwardNormal(w.wallIndex)
        listOf(s.a, s.b, s.b + n * 0.5f, s.a + n * 0.5f)
    }

    fun outside(poly: List<Vec2>): Float = Geo.outsideAmount(poly, room.outline)

    /** Hard cost of a whole layout. */
    fun hardCost(layout: List<Furniture>): Float {
        var hard = 0f
        for ((i, f) in layout.withIndex()) {
            val corners = f.corners()
            hard += own(f, corners)
            for (j in i + 1 until layout.size) hard += penetration(corners, layout[j].corners()) * 4f
            hard += zones(f, layout)
        }
        return hard
    }

    /**
     * Hard cost of everything that involves piece [f] in [layout] (it, its overlaps, its zones
     * and other pieces' zones it sits in). Comparing this before and after moving [f] tells
     * whether a move broke anything, without re-checking the whole room.
     */
    fun pieceCost(f: Furniture, layout: List<Furniture>): Float {
        val corners = f.corners()
        var hard = own(f, corners)
        for (o in layout) {
            if (o.id == f.id) continue
            hard += penetration(corners, o.corners()) * 4f
            // Its zones vs others, and others' zones vs it.
            for (z in useZones(o)) if (!zoneExempt(o, f)) hard += penetration(z, corners) * 1.5f
        }
        hard += zones(f, layout)
        return hard
    }

    private fun own(f: Furniture, corners: List<Vec2>): Float {
        var hard = outside(corners) * 3f
        for (ds in doorSwings) hard += penetration(corners, ds) * 3f
        if (f.tall) for (wz in windowZones) hard += penetration(corners, wz) * 1.5f
        return hard
    }

    private fun zones(f: Furniture, layout: List<Furniture>): Float {
        var hard = 0f
        for (z in useZones(f)) {
            hard += outside(z) * 1.2f
            for (o in layout) {
                if (o.id == f.id || zoneExempt(f, o)) continue
                hard += penetration(z, o.corners()) * 1.5f
            }
        }
        return hard
    }

    fun useZones(f: Furniture): List<List<Vec2>> = listOfNotNull(f.frontZone()) + f.sideZones()

    /**
     * Every rule term involving [f] in [layout], keyed so a move can be checked term by term:
     * a fair move never makes any single one worse (no trading one clash for another).
     */
    fun terms(f: Furniture, layout: List<Furniture>): Map<String, Float> {
        val m = HashMap<String, Float>()
        val c = f.corners()
        m["out"] = outside(c)
        doorSwings.forEachIndexed { i, ds -> m["door:$i"] = penetration(c, ds) }
        if (f.tall) windowZones.forEachIndexed { i, wz -> m["win:$i"] = penetration(c, wz) }
        val zs = useZones(f)
        zs.forEachIndexed { k, z -> m["zoneOut:$k"] = outside(z) }
        for (o in layout) {
            if (o.id == f.id) continue
            val oc = o.corners()
            m["hit:${o.id}"] = penetration(c, oc)
            if (!zoneExempt(f, o)) zs.forEachIndexed { k, z -> m["zone:$k:${o.id}"] = penetration(z, oc) }
            if (!zoneExempt(o, f)) useZones(o).forEachIndexed { k, z -> m["zoneOf:${o.id}:$k"] = penetration(z, c) }
        }
        return m
    }

    /** True when moving [moved] from [before] to [after] makes no rule term worse for any of them. */
    fun noWorse(before: List<Furniture>, after: List<Furniture>, moved: Collection<Furniture>, eps: Float = 1e-4f): Boolean {
        for (p in moved) {
            val was = before.firstOrNull { it.id == p.id } ?: return false
            val t0 = terms(was, before)
            for ((k, v) in terms(p, after)) if (v > (t0[k] ?: 0f) + eps) return false
        }
        return true
    }

    /**
     * How much a move repairs: a rule term cleared completely is worth a lot (1.5 + its size),
     * a partial improvement only its size. 0 when nothing improved.
     */
    fun repairValue(before: List<Furniture>, after: List<Furniture>, moved: Collection<Furniture>): Float {
        var v = 0f
        for (p in moved) {
            val was = before.firstOrNull { it.id == p.id } ?: continue
            val t1 = terms(p, after)
            for ((k, v0) in terms(was, before)) {
                if (v0 <= 1e-3f) continue
                val v1 = t1[k] ?: 0f
                v += if (v1 <= 1e-4f) 1.5f + 4f * v0 else 4f * kotlin.math.max(0f, v0 - v1)
            }
        }
        return v
    }

    /** Plain words for what a move fixed ("out of the door swing", "no longer hitting the Sofa"). */
    fun fixes(before: List<Furniture>, after: List<Furniture>, moved: Collection<Furniture>): List<String> {
        val names = (before + after).associate { it.id to it.name }
        val out = LinkedHashSet<String>()
        for (p in moved) {
            val was = before.firstOrNull { it.id == p.id } ?: continue
            val t0 = terms(was, before)
            val t1 = terms(p, after)
            for ((k, v0) in t0) {
                val v1 = t1[k] ?: 0f
                if (v0 <= 1e-3f || v1 >= v0 - 1e-3f) continue
                val gone = v1 <= 1e-4f
                val parts = k.split(':')
                out += when (parts[0]) {
                    "out" -> "back inside the walls"
                    "door" -> if (gone) "out of the door swing" else "less in the door swing"
                    "win" -> "off the window"
                    "hit" -> if (gone) "no longer hitting the ${names[parts[1]]}" else "less overlap with the ${names[parts[1]]}"
                    "zone", "zoneOut" -> "room to use the ${p.name} again"
                    else -> "room to use the ${names[parts[1]]} again"
                }
            }
        }
        return out.toList()
    }

    fun zoneExempt(owner: Furniture, other: Furniture): Boolean = when (other.type) {
        FurnitureType.CHAIR -> owner.type == FurnitureType.DESK || owner.type == FurnitureType.DINING_TABLE
        FurnitureType.COFFEE_TABLE -> owner.type == FurnitureType.SOFA || owner.type == FurnitureType.ARMCHAIR
        FurnitureType.NIGHTSTAND -> owner.type == FurnitureType.BED
        else -> false
    }

    fun violations(layout: List<Furniture>): List<String> {
        val out = ArrayList<String>()
        for ((i, f) in layout.withIndex()) {
            val c = f.corners()
            if (outside(c) > 0f) out += "${f.name} sticks through a wall"
            for (j in i + 1 until layout.size) if (penetration(c, layout[j].corners()) > 0.06f) out += "${f.name} overlaps ${layout[j].name}"
            if (doorSwings.any { penetration(c, it) > 0.06f }) out += "${f.name} blocks a door swing"
        }
        return out.distinct()
    }

    /** Index of the wall nearest [p] and the distance to it. */
    fun nearestWall(p: Vec2): Pair<Int, Float> {
        val i = walls.indices.minByOrNull { walls[it].distanceTo(p) } ?: 0
        return i to walls[i].distanceTo(p)
    }

    /**
     * Distance from the edge of [f] on side [dir] (a unit vector along one of its axes) to the
     * wall it faces, when that wall runs parallel to the edge; null otherwise.
     */
    fun gapToWall(f: Furniture, dir: Vec2): Float? {
        val r = f.right(); val fr = f.front()
        val half = if (kotlin.math.abs(dir dot fr) > 0.5f) f.depth / 2f else f.width / 2f
        val along = if (kotlin.math.abs(dir dot fr) > 0.5f) r else fr
        val span = if (kotlin.math.abs(dir dot fr) > 0.5f) f.width else f.depth
        var best: Float? = null
        for ((i, w) in walls.withIndex()) {
            // Parallel, and facing back at this edge.
            if ((normals[i] dot dir) > -0.98f) continue
            // The edge's middle must project onto the wall segment.
            val mid = f.center + dir * half
            val t = w.closestT(mid)
            if (t <= 0f || t >= 1f) continue
            val d = (mid - w.a) dot normals[i]
            if (d < -0.01f) continue
            // All along the edge, nothing else of the room sits closer (concave corners).
            val ok = listOf(-0.45f, 0.45f).all { k -> w.closestT(mid + along * (k * span)) in 0f..1f }
            if (!ok) continue
            if (best == null || d < best) best = d
        }
        return best?.let { max(0f, it) }
    }

    companion object {
        /** Smallest separating distance along SAT axes; 0 when the quads don't overlap. */
        fun penetration(a: List<Vec2>, b: List<Vec2>): Float {
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
    }
}
