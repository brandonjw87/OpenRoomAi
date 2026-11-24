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
            b.center.dist(a.center) > 0.08f || abs(b.rotationDelta(a)) > 5f
        }.map { it.id }.toMutableList()
        // New items from "Will it fit?" are placed last.
        val newcomers = after.filter { a -> before.none { it.id == a.id } }

        val steps = ArrayList<MoveStep>()
        var guard = 0
        while (pending.isNotEmpty() && guard++ < 100) {
            val ready = pending.firstOrNull { id ->
                val target = afterById[id]!!
                current.values.none { o -> o.id != id && Geo.convexOverlap(o.corners(), target.corners()) }
            }
            if (ready != null) {
                val from = current[ready]!!
                val to = afterById[ready]!!
                steps += describe(room, steps.size + 1, from, to, false)
                current[ready] = to
                pending.remove(ready)
            } else {
                // Deadlock: park the piece blocking the most targets in the clear middle of the room.
                val blockerId = pending.maxByOrNull { id ->
                    val f = current[id]!!
                    pending.count { other -> other != id && Geo.convexOverlap(f.corners(), afterById[other]!!.corners()) }
                }!!
                val from = current[blockerId]!!
                val park = parkingSpot(room, from, current.values.filter { it.id != blockerId } + pending.filter { it != blockerId }.map { afterById[it]!! })
                steps += describe(room, steps.size + 1, from, park, true)
                current[blockerId] = park
            }
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

    private fun parkingSpot(room: Room, f: Furniture, obstacles: List<Furniture>): Furniture {
        val g = OccupancyGrid(room, 0.1f)
        g.stamp(obstacles)
        val needed = maxOf(f.width, f.depth) / 2f
        var best = Geo.centroid(room.outline)
        var bestScore = -1f
        for (i in 0 until g.size) {
            if (!g.inside[i] || g.blocked[i]) continue
            val p = g.center(i % g.w, i / g.w)
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
        if (dist > 0.08f) {
            val wall = wallInDirection(room, from.center, move)
            parts += "Slide it ${fmt(dist)} toward Wall ${room.wallLabel(wall)}"
        }
        if (abs(rot) > 170f) parts += "turn it around to face the other way"
        else if (abs(rot) > 5f) {
            val dir = if (rot > 0) "clockwise" else "counter-clockwise"
            parts += "turn it ${abs(rot).roundToInt()}° $dir (as seen on the plan)"
        }
        val title = when {
            temp -> "Temporarily park the ${from.name}"
            dist <= 0.08f -> "Rotate the ${from.name}"
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
    private fun wallInDirection(room: Room, p: Vec2, dir: Vec2): Int {
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

    fun fmt(m: Float): String = if (m < 1f) "${(m * 100).roundToInt()} cm" else "%.1f m".format(m)
}
