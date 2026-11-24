package com.openroomai.app.model

import com.openroomai.app.engine.LayoutGoal
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.PI

/**
 * What a piece of furniture is and how it "wants" to live in a room.
 *
 * @param wallAffinity 0..1 how strongly its back should touch a wall.
 * @param frontClearance meters of free floor needed on its front (+local Y) side to actually use it.
 * @param sideClearance meters needed on its left/right sides (beds, dining tables).
 * @param blocksView true for tall pieces that cut sightlines and light.
 */
@Serializable
enum class FurnitureType(
    val label: String,
    val emoji: String,
    val defW: Float,
    val defD: Float,
    val defH: Float,
    val wallAffinity: Float,
    val frontClearance: Float,
    val sideClearance: Float,
    val colorArgb: Long,
) {
    SOFA("Sofa", "🛋️", 2.0f, 0.9f, 0.85f, 0.75f, 0.45f, 0f, 0xFF5B8DEF),
    ARMCHAIR("Armchair", "💺", 0.85f, 0.85f, 0.9f, 0.3f, 0.45f, 0f, 0xFF7FA6F5),
    COFFEE_TABLE("Coffee table", "☕", 1.1f, 0.6f, 0.45f, 0f, 0f, 0f, 0xFFC9A66B),
    TV_STAND("TV stand", "📺", 1.6f, 0.45f, 0.6f, 1f, 0.6f, 0f, 0xFF6F6F7F),
    BED("Bed", "🛏️", 1.6f, 2.0f, 0.6f, 1f, 0.6f, 0.6f, 0xFFB48EE0),
    NIGHTSTAND("Nightstand", "🗄️", 0.5f, 0.4f, 0.6f, 1f, 0f, 0f, 0xFFA68A6D),
    DESK("Desk", "🖥️", 1.2f, 0.6f, 0.75f, 0.9f, 0.9f, 0f, 0xFF4DB6AC),
    CHAIR("Chair", "🪑", 0.5f, 0.5f, 0.9f, 0f, 0f, 0f, 0xFF80CBC4),
    DINING_TABLE("Dining table", "🍽️", 1.6f, 0.9f, 0.75f, 0f, 0.8f, 0.8f, 0xFFD4A373),
    WARDROBE("Wardrobe", "🚪", 1.2f, 0.6f, 2.0f, 1f, 0.8f, 0f, 0xFF8D6E63),
    BOOKSHELF("Bookshelf", "📚", 0.9f, 0.35f, 1.8f, 1f, 0.6f, 0f, 0xFFA1887F),
    DRESSER("Dresser", "🧺", 1.2f, 0.5f, 0.9f, 1f, 0.7f, 0f, 0xFFBCAAA4),
    PLANT("Plant", "🪴", 0.45f, 0.45f, 1.2f, 0.5f, 0f, 0f, 0xFF66BB6A),
    OTHER("Other", "📦", 0.8f, 0.6f, 0.8f, 0.5f, 0.3f, 0f, 0xFF90A4AE);

    val blocksView get() = defH >= 1.4f

    /** Degrees after which the piece looks identical (plants: any, tables: 180°). */
    val symmetryDeg: Float
        get() = when (this) {
            PLANT -> 1f
            COFFEE_TABLE, DINING_TABLE -> 180f
            else -> 360f
        }

    /** Things nobody needs to "walk up to". */
    val needsAccess get() = this != PLANT
}

@Serializable
data class Furniture(
    val id: String = UUID.randomUUID().toString(),
    val type: FurnitureType,
    val center: Vec2,
    val width: Float = type.defW,
    val depth: Float = type.defD,
    val height: Float = type.defH,
    /** Rotation in degrees; 0 means the front (+local Y) faces plan +Y. */
    val rotationDeg: Float = 0f,
    val locked: Boolean = false,
    val name: String = type.label,
    /** True when added through "Will it fit?" and not yet owned. */
    val isNew: Boolean = false,
) {
    val angleRad get() = (rotationDeg * PI / 180.0).toFloat()
    fun corners(): List<Vec2> = Geo.rectCorners(center, width, depth, angleRad)
    /** Unit vector the furniture "faces" (its usable side). */
    fun front(): Vec2 = Vec2(0f, 1f).rotated(angleRad)
    fun right(): Vec2 = Vec2(1f, 0f).rotated(angleRad)
    fun backCenter(): Vec2 = center - front() * (depth / 2f)
    fun footprint() = width * depth
    val tall get() = height >= 1.4f

    /** Rectangle in front of the piece that must stay clear for it to be usable. */
    fun frontZone(): List<Vec2>? {
        val c = type.frontClearance
        if (c <= 0f) return null
        val zc = center + front() * (depth / 2f + c / 2f)
        return Geo.rectCorners(zc, width, c, angleRad)
    }

    fun sideZones(): List<List<Vec2>> {
        val c = type.sideClearance
        if (c <= 0f) return emptyList()
        val r = right()
        return listOf(1f, -1f).map { s ->
            Geo.rectCorners(center + r * (s * (width / 2f + c / 2f)), c, depth, angleRad)
        }
    }

    /** Points a person may walk to in order to use this piece (along its front edge). */
    fun accessPoints(): List<Vec2> {
        val off = front() * (depth / 2f + 0.15f)
        val r = right()
        return listOf(0f, -0.4f, 0.4f).map { center + off + r * (it * width) }
    }

    /** Rotation change that actually matters, respecting symmetry. */
    fun rotationDelta(other: Furniture): Float {
        val sym = type.symmetryDeg
        if (sym <= 1f) return 0f
        var d = Geo.shortestAngleDiffDeg(rotationDeg, other.rotationDeg) % sym
        if (d > sym / 2f) d -= sym
        if (d < -sym / 2f) d += sym
        return d
    }

    fun withPose(c: Vec2, rotDeg: Float) = copy(center = c, rotationDeg = Geo.normalizeDeg(rotDeg))
}

@Serializable
enum class OpeningType { DOOR, WINDOW }

/** A door or window living on wall [wallIndex] at fraction [t] along it, [width] meters wide. */
@Serializable
data class Opening(
    val id: String = UUID.randomUUID().toString(),
    val type: OpeningType,
    val wallIndex: Int,
    val t: Float,
    val width: Float,
)

@Serializable
data class Room(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "My room",
    /** Floor outline in meters, plan coordinates (x, y) == ARCore world (x, z). */
    val outline: List<Vec2>,
    val furniture: List<Furniture> = emptyList(),
    val openings: List<Opening> = emptyList(),
    val optimized: List<Furniture>? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Floor height in AR world space for the scan session (for AR preview). */
    val floorY: Float = 0f,
    val scannedInAr: Boolean = false,
    /** Identifies the AR session the coordinates came from; a different session needs re-alignment. */
    val arSessionToken: String = "",
    /** What the room is optimized for (movie night, work from home, …). */
    val goal: LayoutGoal = LayoutGoal.EVERYDAY,
) {
    val area get() = Geo.absArea(outline)
    fun walls(): List<Segment> = Geo.edges(outline)
    fun wallLabel(i: Int): String = ('A' + (i % 26)).toString()

    /** Inward normal of a wall (outline is normalized to positive signed area). */
    fun inwardNormal(i: Int): Vec2 {
        val w = walls()[i]
        val n = w.dir.perp()
        val mid = w.pointAt(0.5f)
        return if (Geo.pointInPolygon(mid + n * 0.05f, outline)) n else -n
    }

    fun openingSegment(o: Opening): Segment {
        val w = walls()[o.wallIndex.coerceIn(0, outline.size - 1)]
        val half = (o.width / 2f) / w.length.coerceAtLeast(0.01f)
        val t0 = (o.t - half).coerceIn(0f, 1f)
        val t1 = (o.t + half).coerceIn(0f, 1f)
        return Segment(w.pointAt(t0), w.pointAt(t1))
    }

    /** The square a door sweeps when opening into the room. */
    fun doorSwing(o: Opening): List<Vec2> {
        val s = openingSegment(o)
        val n = inwardNormal(o.wallIndex)
        val depth = s.length
        return listOf(s.a, s.b, s.b + n * depth, s.a + n * depth)
    }

    fun doors() = openings.filter { it.type == OpeningType.DOOR }
    fun windows() = openings.filter { it.type == OpeningType.WINDOW }

    /** Entry point a little inside the main door, or the wall-A midpoint if no door is marked. */
    fun entryPoint(): Vec2 {
        val d = doors().firstOrNull()
        return if (d != null) {
            val s = openingSegment(d)
            s.pointAt(0.5f) + inwardNormal(d.wallIndex) * 0.4f
        } else {
            walls()[0].pointAt(0.5f) + inwardNormal(0) * 0.4f
        }
    }

    companion object {
        fun rectangle(name: String, w: Float, l: Float) = Room(
            name = name,
            outline = listOf(Vec2(0f, 0f), Vec2(w, 0f), Vec2(w, l), Vec2(0f, l)),
        )
    }
}
