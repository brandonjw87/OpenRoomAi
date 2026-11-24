package com.openroomai.app.engine

import kotlinx.serialization.Serializable

/**
 * What the room is *for*. Each goal re-weights the optimizer so the "best" layout matches how
 * you actually live in the room.
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
    EVERYDAY("Everyday", "🏡", "Balanced: open, walkable, everything where you expect it.", SQUEEZE_RADIUS, 10f, 0f, 1f),
    MOVIE_NIGHT("Movie night", "🍿", "Every seat faces the screen at a good distance, no window glare.", SQUEEZE_RADIUS, 8f, 0f, 2.2f),
    WORK_FROM_HOME("Work from home", "💻", "Desk gets side daylight and stays out of the traffic path.", SQUEEZE_RADIUS, 9f, 0f, 1f),
    PARTY("Party mode", "🎉", "Everything to the walls. Maximum dance floor.", SQUEEZE_RADIUS, 15f, 2.5f, 0.35f),
    ACCESSIBLE("Wheelchair friendly", "♿", "90 cm paths everywhere and a 1.5 m turning circle.", COMFORT_RADIUS, 11f, 0f, 0.8f),
}
