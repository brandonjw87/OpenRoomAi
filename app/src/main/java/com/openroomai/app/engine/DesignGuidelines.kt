package com.openroomai.app.engine

import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Room
import com.openroomai.app.model.Vec2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Interior-design guidelines as measurable terms, following the guideline families used in
 * furniture-layout research (Merrell et al. 2011, "Interactive Furniture Layout Using Interior
 * Design Guidelines"; Yu et al. 2011, "Make it Home"): conversation, visual balance, alignment
 * and emphasis. Constants come from common design rules of thumb (e.g. seats for conversation
 * within ~8 ft / 2.4 m).
 */
object DesignGuidelines {
    /** Seats closer than this feel like one conversation group. */
    const val CONVERSATION_MAX = 2.4f

    data class Check(
        /** 0 (bad) … 1 (great). */
        val conversation: Float,
        /** 0 = perfectly balanced; 1 = all visual weight piled at the edge. */
        val imbalance: Float,
        /** Wall the visual weight leans toward (index), or -1 when balanced. */
        val heavyWall: Int,
        /** 0 … 1: how squarely pieces sit relative to their nearest wall. */
        val alignment: Float,
        /** 0 … 1: how well seats face the room's focal point. */
        val emphasis: Float,
        val focal: Vec2?,
        val focalName: String?,
        val farthestSeatGap: Float,
    ) {
        /** Composite 0–100 used in the UI. */
        val score: Int get() = (100f * (0.3f * conversation + 0.25f * (1f - imbalance) + 0.25f * alignment + 0.2f * emphasis)).toInt().coerceIn(0, 100)
    }

    private fun isSeat(f: Furniture) = f.type == FurnitureType.SOFA || f.type == FurnitureType.ARMCHAIR

    /** Where the person sits: a little in front of the piece's center. */
    fun seatPoint(f: Furniture) = f.center + f.front() * (f.depth * 0.15f)

    /**
     * Visual weight: bigger, taller and darker things look heavier. Luminance of the piece's
     * colour stands in for darkness (dark = heavy).
     */
    fun visualWeight(f: Furniture): Float {
        val c = f.type.colorArgb
        val r = (c shr 16 and 0xFF) / 255f; val g = (c shr 8 and 0xFF) / 255f; val b = (c and 0xFF) / 255f
        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return f.footprint() * (0.4f + f.height) * (1.25f - 0.6f * lum)
    }

    /** The focal point seats should face: the TV, else the biggest window. */
    fun focalPoint(room: Room, layout: List<Furniture>): Pair<Vec2, String>? {
        layout.firstOrNull { it.type == FurnitureType.TV_STAND }?.let { return it.center to it.name }
        val w = room.windows().maxByOrNull { it.width } ?: return null
        return room.openingSegment(w).pointAt(0.5f) to "the window"
    }

    fun check(room: Room, layout: List<Furniture>): Check {
        val seats = layout.filter(::isSeat)
        // Conversation: seats within reach of each other, turned toward the group's center.
        var conv = 1f
        var gap = 0f
        if (seats.size >= 2) {
            val pts = seats.map(::seatPoint)
            val center = pts.fold(Vec2.ZERO) { a, p -> a + p } / pts.size.toFloat()
            var dist = 0f
            var facing = 0f
            for (i in seats.indices) {
                val nearest = pts.indices.filter { it != i }.minOf { pts[it].dist(pts[i]) }
                gap = max(gap, nearest)
                dist += max(0f, nearest - CONVERSATION_MAX)
                facing += (1f + (seats[i].front() dot (center - pts[i]).normalized())) / 2f
            }
            conv = Geo.clamp01(facing / seats.size - dist / seats.size * 0.5f)
        }
        // Balance: visual center of mass vs room center, relative to the room's size.
        val c = Geo.centroid(room.outline)
        var wsum = 0f
        var m = Vec2.ZERO
        for (f in layout) { val w = visualWeight(f); wsum += w; m += f.center * w }
        val radius = sqrt(room.area / Math.PI.toFloat()).coerceAtLeast(0.5f)
        var imbalance = 0f
        var heavyWall = -1
        if (wsum > 0f) {
            val com = m / wsum
            imbalance = Geo.clamp01(com.dist(c) / radius)
            if (imbalance > 0.12f) {
                // The wall whose outward direction best matches where the weight leans.
                val lean = (com - c).normalized()
                heavyWall = room.walls().indices.maxByOrNull { -(room.inwardNormal(it) dot lean) } ?: -1
            }
        }
        // Alignment: rotation relative to the nearest wall, 1 when parallel/perpendicular.
        val walls = room.walls()
        var align = 1f
        val alignable = layout.filter { it.type.symmetryDeg > 1f && it.type != FurnitureType.CHAIR }
        if (alignable.isNotEmpty() && walls.isNotEmpty()) {
            align = alignable.sumOf { f -> alignmentOf(f, walls).toDouble() }.toFloat() / alignable.size
        }
        // Emphasis: seats facing the focal point.
        val focal = focalPoint(room, layout)
        var emph = 1f
        if (focal != null && seats.isNotEmpty()) {
            emph = seats.sumOf { s -> ((1f + (s.front() dot (focal.first - s.center).normalized())) / 2f).toDouble() }.toFloat() / seats.size
        }
        return Check(conv, imbalance, heavyWall, align, emph, focal?.first, focal?.second, gap)
    }

    private fun alignmentOf(f: Furniture, walls: List<com.openroomai.app.model.Segment>): Float {
        val w = walls.minByOrNull { it.distanceTo(f.center) } ?: return 1f
        val d = Math.toRadians(Geo.shortestAngleDiffDeg(Math.toDegrees(w.dir.angle().toDouble()).toFloat(), f.rotationDeg).toDouble())
        // cos(4Δ) is 1 at 0°/90°/180°/270° and −1 at 45°.
        return ((1.0 + cos(4 * d)) / 2.0).toFloat()
    }

    /** Optimizer cost (lower is better). Weights are deliberately modest: openness still leads. */
    fun cost(room: Room, layout: List<Furniture>, scale: Float): Float {
        val ck = check(room, layout)
        return scale * (0.9f * (1f - ck.conversation) + 1.1f * ck.imbalance + 0.7f * (1f - ck.alignment) + 0.6f * (1f - ck.emphasis))
    }

    fun insights(room: Room, layout: List<Furniture>): List<Insight> {
        val ck = check(room, layout)
        val out = ArrayList<Insight>()
        if (layout.count(::isSeat) >= 2 && ck.farthestSeatGap > CONVERSATION_MAX)
            out += Insight("🗣️", "Seats are far apart for conversation",
                "Your seats are up to ${MovePlanner.fmt(ck.farthestSeatGap)} apart. Designers keep a conversation group within about 2.4 m (8 ft).", 5)
        if (ck.heavyWall >= 0)
            out += Insight("⚖️", "Visually lopsided toward Wall ${room.wallLabel(ck.heavyWall)}",
                "Big, tall and dark pieces cluster on one side. Balance it with something on the opposite side, or swap one heavy piece for a lighter, leggier one.", 4)
        if (ck.alignment < 0.85f)
            out += Insight("📐", "Some pieces sit at odd angles",
                "Squaring furniture to the walls makes a room read calmer and larger; angled pieces eat corners.", 3)
        if (ck.focalName != null && ck.emphasis < 0.7f)
            out += Insight("🎯", "Seats don't face ${ck.focalName}",
                "Point the seating at the room's focal point so the eye has one clear place to go.", 3)
        return out
    }
}
