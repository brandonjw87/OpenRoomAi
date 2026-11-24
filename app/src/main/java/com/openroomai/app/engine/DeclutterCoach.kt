package com.openroomai.app.engine

import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Room

sealed interface DeclutterAction {
    data object Remove : DeclutterAction
    data class Resize(val width: Float, val depth: Float) : DeclutterAction
    data class Lower(val height: Float) : DeclutterAction
}

data class DeclutterIdea(
    val furnitureId: String,
    val name: String,
    val emoji: String,
    val icon: String,
    val title: String,
    val body: String,
    val gain: Int,
    val action: DeclutterAction,
)

/**
 * "What would help most if it went away (or shrank)?" Re-scores the room for every candidate
 * change, so suggestions are ranked by real openness gain rather than generic advice.
 * Gains are lower bounds: re-optimizing afterwards usually finds a bit more.
 */
object DeclutterCoach {

    fun suggest(room: Room, layout: List<Furniture>, goal: LayoutGoal = LayoutGoal.EVERYDAY, max: Int = 6): List<DeclutterIdea> {
        if (layout.isEmpty()) return emptyList()
        val grid = OccupancyGrid(room, 0.05f)
        fun score(l: List<Furniture>) = OpennessAnalyzer.analyze(room, l, grid, withFlows = false, walkRadius = goal.walkRadius).score
        val base = score(layout)
        val ideas = ArrayList<DeclutterIdea>()

        for (f in layout) {
            if (f.locked) continue
            val others = layout.filter { it.id != f.id }

            // 1) Remove it entirely (or wall-mount, for TVs).
            // Nobody wants "get rid of your bed" advice: anchors only get the compact option.
            val anchor = f.type == FurnitureType.SOFA || f.type == FurnitureType.BED ||
                (f.type == FurnitureType.DESK && goal == LayoutGoal.WORK_FROM_HOME)
            val removeGain = if (anchor) 0 else score(others) - base
            if (removeGain >= 2) {
                val (icon, title, body) = when (f.type) {
                    FurnitureType.TV_STAND -> Triple("📺", "Wall-mount the TV",
                        "Ditching the stand frees ${fmtArea(f.footprint())} of floor. Floating storage below keeps the look light.")
                    FurnitureType.COFFEE_TABLE -> Triple("☕", "Swap the coffee table for nesting tables",
                        "Pull them out when you need them; the middle of the room stays clear the rest of the time.")
                    FurnitureType.DINING_TABLE -> Triple("🍽️", "Go drop-leaf",
                        "A drop-leaf or extendable table gives back ${fmtArea(f.footprint() * 0.5f)} on non-dinner-party days.")
                    else -> Triple("🗑️", "Let go of the ${f.name}",
                        "Does it spark joy? Removing it frees ${fmtArea(f.footprint())} and opens the room up.")
                }
                ideas += DeclutterIdea(f.id, f.name, f.type.emoji, icon, title, body, removeGain, DeclutterAction.Remove)
            }

            // 2) A compact version of the same thing.
            if (f.footprint() > 0.45f && f.type != FurnitureType.PLANT) {
                val nw = (f.width * 0.8f).coerceAtLeast(minWidth(f.type))
                val nd = (f.depth * 0.88f).coerceAtLeast(0.35f)
                if (nw < f.width - 0.05f) {
                    // Keep the back where it is, so a sofa against a wall stays against the wall.
                    val c = f.backCenter() + f.front() * (nd / 2f)
                    val smaller = f.copy(center = c, width = nw, depth = nd)
                    val g = score(others + smaller) - base
                    if (g >= 2) ideas += DeclutterIdea(
                        f.id, f.name, f.type.emoji, "📏", "Go compact: ${compactName(f.type)}",
                        "A ${MovePlanner.fmt(nw)} × ${MovePlanner.fmt(nd)} version instead of ${MovePlanner.fmt(f.width)} × ${MovePlanner.fmt(f.depth)}.",
                        g, DeclutterAction.Resize(nw, nd),
                    )
                }
            }

            // 3) Tall → low: sightlines and daylight come back.
            if (f.tall) {
                val low = f.copy(height = 0.85f)
                val g = score(others + low) - base
                if (g >= 2) ideas += DeclutterIdea(
                    f.id, f.name, f.type.emoji, "⬇️", "Swap the tall ${f.name} for a low sideboard",
                    "Storage at hip height keeps the view across the room and the light from the window.",
                    g, DeclutterAction.Lower(0.85f),
                )
            }
        }
        // Best idea per piece, best pieces first.
        return ideas.groupBy { it.furnitureId }.map { (_, v) -> v.maxBy { it.gain } }
            .sortedByDescending { it.gain }
            .take(max)
    }

    fun apply(f: Furniture, a: DeclutterAction): Furniture? = when (a) {
        DeclutterAction.Remove -> null
        is DeclutterAction.Resize -> f.copy(center = f.backCenter() + f.front() * (a.depth / 2f), width = a.width, depth = a.depth)
        is DeclutterAction.Lower -> f.copy(height = a.height, name = "Low ${f.name.lowercase()}")
    }

    private fun minWidth(t: FurnitureType) = when (t) {
        FurnitureType.SOFA -> 1.4f
        FurnitureType.BED -> 1.4f
        FurnitureType.DINING_TABLE -> 1.0f
        FurnitureType.DESK -> 0.9f
        else -> 0.5f
    }

    private fun compactName(t: FurnitureType) = when (t) {
        FurnitureType.SOFA -> "a loveseat"
        FurnitureType.BED -> "a smaller bed"
        FurnitureType.DESK -> "a slim desk"
        FurnitureType.DINING_TABLE -> "a round bistro table"
        FurnitureType.WARDROBE -> "a narrower wardrobe"
        FurnitureType.ARMCHAIR -> "a slipper chair"
        else -> "a smaller ${t.label.lowercase()}"
    }

    private fun fmtArea(m2: Float) = "%.1f m²".format(m2)
}
