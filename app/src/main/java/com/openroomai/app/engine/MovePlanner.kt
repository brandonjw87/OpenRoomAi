package com.openroomai.app.engine

import com.openroomai.app.model.Furniture
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Room
import com.openroomai.app.model.Vec2
import kotlin.math.abs
import kotlin.math.roundToInt

data class MoveStep(
    val index: Int,
    val furniture: Furniture,
    val from: Furniture,
    val to: Furniture,
    val title: String,
    val detail: String,
    val temporary: Boolean = false,
    val distance: Float,
)

/**
 * Turns "before" and "after" layouts into an ordered, doable list of moves.
 * Ordering matters: you can't put the sofa where the bookshelf still stands, so we move whatever
 * frees space first and park blockers in the open middle when there's a deadlock.
 */
object MovePlanner {

    fun plan(room: Room, before: List<Furniture>, after: List<Furniture>): List<MoveStep> {
        val afterById = after.associateBy { it.id }
        val current = before.associateBy { it.id }.toMutableMap()
        val pending = before.filter { b ->
            val a = afterById[b.id] ?: return@filter false
            b.center.dist(a.center) > MIN_MOVE || abs(b.rotationDelta(a)) > 2f
        }.map { it.id }.toMutableList()
        // New items from "Will it fit?" are placed last.
        val newcomers = after.filter { a -> before.none { it.id == a.id } }

        val steps = ArrayList<MoveStep>()
        val parked = HashSet<String>()
        // Only pieces that still have to move can be "in the way": the final layout is valid,
        // so overlaps with pieces that stay put are just touching edges.
        fun blocks(o: Furniture, target: Furniture): Boolean =
            o.id != target.id && o.id in pending && Geo.convexOverlap(shrink(o), shrink(target))
        while (pending.isNotEmpty()) {
            val ready = pending.firstOrNull { id ->
                val target = afterById[id]!!
                current.values.none { o -> blocks(o, target) }
            }
            // Deadlock: park one blocker (each at most once); if all are parked already, just go.
            val blocker = if (ready == null) pending.filter { it !in parked }.maxByOrNull { id ->
                val f = current[id]!!
                pending.count { other -> other != id && Geo.convexOverlap(shrink(f), shrink(afterById[other]!!)) }
            } else null
            if (ready == null && blocker != null) {
                parked += blocker
                val from = current[blocker]!!
                val park = parkingSpot(room, from, current.values.filter { it.id != blocker } + pending.filter { it != blocker }.map { afterById[it]!! })
                steps += describe(room, steps.size + 1, from, park, true)
                current[blocker] = park
                continue
            }
            val id = ready ?: pending.first()
            val from = current[id]!!
            val to = afterById[id]!!
            steps += describe(room, steps.size + 1, from, to, false)
            current[id] = to
            pending.remove(id)
        }
        for (n in newcomers) {
            val anchor = nearestWall(room, n.center)
            steps += MoveStep(
                steps.size + 1, n, n, n,
                "Place the new ${n.name}",
                "Set it ${fmt(anchor.second)} from Wall ${room.wallLabel(anchor.first)}, facing ${facingText(room, n)}. " +
                    "Footprint ${fmt(n.width)} × ${fmt(n.depth)}.",
                distance = 0f,
            )
        }
        return steps
    }

    /** Footprint shrunk by 3 cm per side so flush neighbours don't count as collisions. */
    private fun shrink(f: Furniture) = Geo.rectCorners(f.center, (f.width - 0.06f).coerceAtLeast(0.05f), (f.depth - 0.06f).coerceAtLeast(0.05f), f.angleRad)

    /**
     * Somewhere roomy to stand a piece for a moment: inside the walls, clear of everything still
     * standing or still to arrive, and never in a door's swing (you'll need that door).
     */
    private fun parkingSpot(room: Room, f: Furniture, obstacles: List<Furniture>): Furniture {
        val g = OccupancyGrid(room, 0.1f)
        g.stamp(obstacles)
        val swings = room.doors().map { room.doorSwing(it) }
        val needed = maxOf(f.width, f.depth) / 2f
        var best = Geo.centroid(room.outline)
        var bestScore = -Float.MAX_VALUE
        for (i in 0 until g.size) {
            if (!g.inside[i] || g.blocked[i]) continue
            val p = g.center(i % g.w, i / g.w)
            val corners = f.withPose(p, f.rotationDeg).corners()
            if (swings.any { Geo.convexOverlap(corners, it) } || Geo.outsideAmount(corners, room.outline) > 0f) continue
            val s = g.dist[i] - needed - 0.1f * p.dist(f.center)
            if (s > bestScore) { bestScore = s; best = p }
        }
        return f.withPose(best, f.rotationDeg)
    }

    private fun describe(room: Room, n: Int, from: Furniture, to: Furniture, temp: Boolean): MoveStep {
        val move = to.center - from.center
        val dist = move.length()
        val rot = from.rotationDelta(to)
        val parts = ArrayList<String>()
        if (dist > MIN_MOVE) {
            val wall = wallInDirection(room, from.center, move)
            parts += "Slide it ${if (dist < 0.61f) Units.nudge(dist) else fmt(dist)} toward Wall ${room.wallLabel(wall)}"
        }
        if (abs(rot) > 170f) parts += "turn it around to face the other way"
        else if (abs(rot) > 2f) {
            val dir = if (rot > 0) "clockwise" else "counter-clockwise"
            parts += "turn it ${abs(rot).roundToInt()}° $dir (as seen on the plan)"
        }
        val title = when {
            temp -> "Temporarily park the ${from.name}"
            dist <= MIN_MOVE -> "Rotate the ${from.name}"
            dist <= MICRO && abs(rot) <= 2f -> "Nudge the ${from.name}"
            else -> "Move the ${from.name}"
        }
        val tail = if (temp) "We'll bring it back in a later step." else {
            val (wi, d) = nearestWall(room, to.backCenter())
            if (to.type.symmetryDeg <= 1f) "End about ${fmt(nearestWall(room, to.center).second)} off Wall ${room.wallLabel(nearestWall(room, to.center).first)}."
            else if (to.type.wallAffinity >= 0.5f && d < 0.12f) "End with its back flush against Wall ${room.wallLabel(wi)}, facing ${facingText(room, to)}."
            else "End about ${fmt(nearestWall(room, to.center).second)} off Wall ${room.wallLabel(nearestWall(room, to.center).first)}, facing ${facingText(room, to)}."
        }
        val detail = (parts.joinToString(", then ").replaceFirstChar { it.uppercase() } + ". " + tail).trim()
        return MoveStep(n, from, from, to, title, detail, temp, dist)
    }

    /** The wall you're walking toward when moving in [dir] from [p] (first wall hit by the ray). */
    fun wallInDirection(room: Room, p: Vec2, dir: Vec2): Int {
        val d = dir.normalized()
        var best = 0
        var bestT = Float.MAX_VALUE
        room.walls().forEachIndexed { i, w ->
            val e = w.b - w.a
            val den = d cross e
            if (abs(den) < 1e-6f) return@forEachIndexed
            val t = ((w.a - p) cross e) / den
            val u = ((w.a - p) cross d) / den
            if (t > 0 && u in 0f..1f && t < bestT) { bestT = t; best = i }
        }
        return best
    }

    fun nearestWall(room: Room, p: Vec2): Pair<Int, Float> {
        val walls = room.walls()
        val i = walls.indices.minByOrNull { walls[it].distanceTo(p) } ?: 0
        return i to walls[i].distanceTo(p)
    }

    private fun facingText(room: Room, f: Furniture): String {
        val fr = f.front()
        val walls = room.walls()
        // The wall it faces is the one whose inward normal points back at it.
        val i = walls.indices.maxByOrNull { -(room.inwardNormal(it) dot fr) } ?: 0
        return "Wall ${room.wallLabel(i)}"
    }

    fun fmt(m: Float): String = Units.len(m)

    /** Anything under ¼″ isn't a move. */
    const val MIN_MOVE = 0.006f
    /** A foot or less without turning: a nudge, not a haul. */
    const val MICRO = 0.305f

    fun isMicro(s: MoveStep) = !s.temporary && s.distance <= MICRO && abs(s.from.rotationDelta(s.to)) <= 2f
}
