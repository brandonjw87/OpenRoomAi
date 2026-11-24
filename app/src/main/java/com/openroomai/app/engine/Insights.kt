package com.openroomai.app.engine

import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Room
import kotlin.math.roundToInt

data class Insight(val icon: String, val title: String, val body: String, val impact: Int)

/**
 * Plain-language "why it will feel bigger" explanations plus designer tricks that don't
 * involve moving anything heavy.
 */
object Insights {

    fun explain(room: Room, before: OpennessReport, after: OpennessReport, layout: List<Furniture>): List<Insight> {
        val out = ArrayList<Insight>()
        val dClear = after.clearDiameter - before.clearDiameter
        if (dClear > 0.15f) out += Insight(
            "⭕", "A bigger open heart",
            "The largest uninterrupted floor patch grows from ${MovePlanner.fmt(before.clearDiameter)} to ${MovePlanner.fmt(after.clearDiameter)} across. " +
                "An empty center is the #1 thing that makes a room read as spacious.",
            (dClear * 20).roundToInt(),
        )
        val dVis = after.visibilityPct - before.visibilityPct
        if (dVis > 0.03f) out += Insight(
            "👀", "Deeper view from the door",
            "You'll see ${(after.visibilityPct * 100).roundToInt()}% of the floor from the doorway (was ${(before.visibilityPct * 100).roundToInt()}%). " +
                "Tall pieces moved to the edges stop chopping the room into pockets.",
            (dVis * 100).roundToInt(),
        )
        val dReach = after.reachablePct - before.reachablePct
        if (dReach > 0.03f) out += Insight(
            "🚶", "Easier to walk around",
            "Walkable floor goes from ${(before.reachablePct * 100).roundToInt()}% to ${(after.reachablePct * 100).roundToInt()}%. " +
                "Clear 90 cm paths mean no sideways shuffling past corners.",
            (dReach * 100).roundToInt(),
        )
        if (after.lightPct > before.lightPct + 0.05f) out += Insight(
            "☀️", "More daylight",
            "Tall furniture no longer stands in front of windows, so light can reach deeper into the room.",
            ((after.lightPct - before.lightPct) * 80).roundToInt(),
        )
        if (after.accessPct > before.accessPct) out += Insight(
            "🧭", if (after.accessPct >= 0.999f) "Everything is usable" else "More pieces within reach",
            "${(after.accessPct * 100).roundToInt()}% of your furniture now has a clear approach from the door (was ${(before.accessPct * 100).roundToInt()}%).",
            ((after.accessPct - before.accessPct) * 60).roundToInt(),
        )
        if (after.blockedPieces.isNotEmpty()) out += Insight(
            "⚠️", "Still hard to reach",
            after.blockedPieces.joinToString { it.name } + " can't be reached with a 60 cm path. Consider a smaller piece or removing one item.",
            -10,
        )

        // Designer tricks that don't require moving anything.
        val tall = layout.count { it.tall }
        if (tall >= 2) out += Insight("📏", "Group the tall stuff",
            "Keep tall pieces on the same wall (ideally the one you face when entering is NOT it). A single tall wall reads as architecture, several read as clutter.", 4)
        if (room.windows().isNotEmpty()) out += Insight("🪞", "Mirror the window",
            "Hang a large mirror on a wall perpendicular to the window; it bounces daylight and doubles the view depth.", 5)
        else out += Insight("💡", "Layer your lighting",
            "No windows marked: add uplights in the corners. Bright corners push walls visually outward.", 5)
        if (layout.any { it.type == FurnitureType.SOFA || it.type == FurnitureType.ARMCHAIR }) out += Insight("🦵", "Show some leg",
            "Furniture on legs lets you see floor underneath it, which makes it look ~20% lighter than skirted pieces.", 3)
        if (after.footprintPct > 0.35f) out += Insight("📦", "Furniture covers ${(after.footprintPct * 100).roundToInt()}% of the floor",
            "Designers aim for under 35%. Removing or downsizing one item will help more than any rearrangement.", 6)
        out += Insight("🎨", "Light walls, one rug",
            "A single large rug that fits under the front legs of the seating unifies the zone; light walls and matching curtains to the ceiling add height.", 2)
        return out.sortedByDescending { it.impact }
    }
}
