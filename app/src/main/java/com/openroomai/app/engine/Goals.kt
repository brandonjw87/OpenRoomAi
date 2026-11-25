package com.openroomai.app.engine

import kotlinx.serialization.Serializable

/**
 * What the room is used for. Each use re-weights how furniture relates (seats face the screen,
 * the desk gets side daylight, …) while the space goal decides what "open" means.
 */
@Serializable
enum class LayoutGoal(
    val label: String,
    val emoji: String,
    val blurb: String,
    /** Clearance radius that counts as a walkway (m). */
    val walkRadius: Float,
    /** Weight of the openness score in the optimizer. */
    val openWeight: Float,
    /** Extra reward per meter of biggest clear circle. */
    val clearBonus: Float,
    /** Multiplier for furniture-relationship goals (sofa↔TV, nightstand↔bed, …). */
    val relationScale: Float,
) {
    EVERYDAY("Everyday", "🏡", "Open, walkable, everything where you expect it.", SQUEEZE_RADIUS, 10f, 0f, 1f),
    MOVIE_NIGHT("Movie night", "🍿", "Every seat faces the screen at a good distance, no window glare.", SQUEEZE_RADIUS, 8f, 0f, 2.2f),
    WORK_FROM_HOME("Work from home", "💻", "Desk gets side daylight and stays out of the main walkway.", SQUEEZE_RADIUS, 9f, 0f, 1f),
    PARTY("Entertaining", "🎉", "Seating to the edges, the middle wide open for guests.", SQUEEZE_RADIUS, 15f, 2.5f, 0.35f),
}

/**
 * One-tap space goals: what the optimizer should liberate first. Weights are in the optimizer's
 * cost units (see [LayoutOptimizer.totalCost]).
 */
@Serializable
enum class SpaceGoal(
    val label: String,
    val emoji: String,
    val blurb: String,
    /** Cost per meter a piece moves (lower = more rearranging). */
    val movePenalty: Float,
    val iterations: Int,
    /** Reward for the largest open zone, as a share of the floor. */
    val zoneWeight: Float,
    /** Reward for usable open floor, as a share of the floor. */
    val usableWeight: Float,
    /** Reward for lane width to everything (0…1 per lane, averaged). */
    val laneWeight: Float,
    /** Cost for each lane under 36″, scaled by how far under. */
    val tightWeight: Float,
) {
    MAX_OPEN_CENTER(
        "Max open center", "⬜", "Consolidate the open floor into one big zone.",
        movePenalty = 0.12f, iterations = 8000, zoneWeight = 14f, usableWeight = 6f, laneWeight = 3f, tightWeight = 1.5f,
    ),
    WIDEST_FLOW(
        "Widest flow paths", "↔️", "Wide, straight walkways to everything. No pinch points.",
        movePenalty = 0.25f, iterations = 7000, zoneWeight = 4f, usableWeight = 4f, laneWeight = 10f, tightWeight = 5f,
    ),
    QUICK_WINS(
        "Quick wins", "⚡", "A few small nudges. Nothing heavy.",
        movePenalty = 1.2f, iterations = 0, zoneWeight = 6f, usableWeight = 6f, laneWeight = 5f, tightWeight = 2.5f,
    ),
}
