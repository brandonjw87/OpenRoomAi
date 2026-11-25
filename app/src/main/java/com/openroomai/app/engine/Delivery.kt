package com.openroomai.app.engine

import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Vec2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How a piece gets in, best option first. */
enum class DeliveryMethod(val icon: String, val label: String) {
    STRAIGHT("✅", "Carry it straight through"),
    TILT("✅", "Tilt it on its side through the door"),
    DOOR_OFF("⚠️", "Take the door off its hinges"),
    LEGS_OFF("⚠️", "Unscrew the legs first"),
    DOOR_AND_LEGS("⚠️", "Door off its hinges + legs off"),
    DISASSEMBLE("🔧", "Take it apart (it usually comes apart)"),
    NO_FIT("❌", "Won't fit through this door"),
}

data class DeliveryVerdict(
    val furniture: Furniture,
    val method: DeliveryMethod,
    /** Spare room in the tightest direction (m); negative = too big by that much. */
    val margin: Float,
    /** null when no hallway was given. */
    val cornerOk: Boolean?,
    val cornerNeedsTilt: Boolean,
    val detail: String,
)

/**
 * Moving Day: will it get in? Doorways are checked with the piece's real side profile rotated in
 * the door plane (the "diagonal depth" trick movers use for sofas). Hallway corners use the
 * classic corner-turn bound for a rectangle of width t between corridors of widths a and b:
 *     L_max = min over θ of  a / cos θ + b / sin θ − t·(tan θ + cot θ)
 * and, if needed, tilting the piece up toward the ceiling while turning (approximation).
 */
object Delivery {
    /** Door stops and hinges eat into the nominal width. */
    const val FRAME_LOSS = 0.04f

    /** Side profile (depth × height) as a convex outline, from the same low-poly shapes the 3D view uses. */
    fun sideProfile(f: Furniture, withLegs: Boolean = true): List<Vec2> {
        val d = f.depth; val h = f.height
        val leg = if (withLegs) legHeight(f) else 0f
        val body = (h - legHeight(f)).coerceAtLeast(0.1f)
        val top = leg + body
        val pts = when (f.type) {
            FurnitureType.SOFA, FurnitureType.ARMCHAIR -> {
                // Seat + arms (full depth, arm height) and the back (thin, full height).
                val arm = min(top, leg + 0.52f)
                listOf(Vec2(0f, 0f), Vec2(d, 0f), Vec2(d, arm), Vec2(0.22f, top), Vec2(0f, top))
            }
            FurnitureType.BED -> listOf(Vec2(0f, 0f), Vec2(d, 0f), Vec2(d, min(top, 0.55f)), Vec2(0.08f, max(top, 1.0f)), Vec2(0f, max(top, 1.0f)))
            else -> listOf(Vec2(0f, 0f), Vec2(d, 0f), Vec2(d, top), Vec2(0f, top))
        }
        return Geo.convexHull(pts)
    }

    fun legHeight(f: Furniture) = when (f.type) {
        FurnitureType.SOFA, FurnitureType.ARMCHAIR -> 0.12f
        FurnitureType.COFFEE_TABLE, FurnitureType.DINING_TABLE, FurnitureType.DESK -> (f.height - 0.04f).coerceAtLeast(0f)
        FurnitureType.TV_STAND, FurnitureType.NIGHTSTAND, FurnitureType.DRESSER -> 0.08f
        else -> 0f
    }

    private fun comesApart(f: Furniture) = f.type in setOf(
        FurnitureType.BED, FurnitureType.WARDROBE, FurnitureType.DESK, FurnitureType.DINING_TABLE, FurnitureType.BOOKSHELF,
    )

    /**
     * Smallest slack when fitting [poly] in a W×H opening over in-plane rotations; ≥ 0 means it fits.
     * Also returns whether the best fit needed a rotation (i.e. "tilting").
     */
    fun fitInOpening(poly: List<Vec2>, w: Float, h: Float): Pair<Float, Boolean> {
        var best = -Float.MAX_VALUE
        var bestTilted = false
        var deg = 0
        while (deg < 180) {
            val a = (deg * PI / 180.0).toFloat()
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (p in poly) {
                val r = p.rotated(a)
                minX = min(minX, r.x); maxX = max(maxX, r.x); minY = min(minY, r.y); maxY = max(maxY, r.y)
            }
            val slack = min(w - (maxX - minX), h - (maxY - minY))
            if (slack > best + 1e-4f) { best = slack; bestTilted = deg % 90 != 0 }
            deg += 1
        }
        return best to bestTilted
    }

    private fun box(a: Float, b: Float) = listOf(Vec2(0f, 0f), Vec2(a, 0f), Vec2(a, b), Vec2(0f, b))

    /** Classic corner-turn bound (see class doc). */
    fun maxLengthAroundCorner(a: Float, b: Float, thickness: Float): Float {
        var best = Float.MAX_VALUE
        var deg = 1.0
        while (deg < 89.5) {
            val t = Math.toRadians(deg)
            val l = (a / cos(t) + b / sin(t) - thickness * (Math.tan(t) + 1.0 / Math.tan(t))).toFloat()
            if (l < best) best = l
            deg += 0.25
        }
        return max(0f, best)
    }

    fun check(
        f: Furniture,
        doorWidth: Float,
        doorHeight: Float = 2.0f,
        hallWidth: Float? = null,
        ceiling: Float = 2.45f,
    ): DeliveryVerdict {
        val clearW = doorWidth - FRAME_LOSS
        // Which axis goes through the door first: the length (profile), the depth, or the height.
        class Option(val slack: Float, val tilted: Boolean)
        fun options(w: Float, withLegs: Boolean): List<Option> = listOf(
            sideProfile(f, withLegs),
            box(f.width, f.height - if (withLegs) 0f else legHeight(f)),
            box(f.width, f.depth),
        ).map { poly -> fitInOpening(poly, w, doorHeight).let { (sl, t) -> Option(sl, t) } }
        // Prefer a plain carry (any axis, no in-plane tilt); tilt only when that's the only way.
        fun pick(opts: List<Option>): Pair<Option?, Boolean> {
            val fits = opts.filter { it.slack >= 0f }
            fits.filter { !it.tilted }.maxByOrNull { it.slack }?.let { return it to false }
            return fits.maxByOrNull { it.slack } to true
        }
        val main = options(clearW, true)
        val (chosen, tiltNeeded) = pick(main)
        val slack = main.maxOf { it.slack }
        val doorOff = pick(options(doorWidth, true)).first
        val legsOff = if (legHeight(f) > 0f) pick(options(clearW, false)).first else null
        val both = if (legHeight(f) > 0f) pick(options(doorWidth, false)).first else null
        val (method, margin) = when {
            chosen != null && !tiltNeeded -> DeliveryMethod.STRAIGHT to chosen.slack
            chosen != null -> DeliveryMethod.TILT to chosen.slack
            doorOff != null -> DeliveryMethod.DOOR_OFF to doorOff.slack
            legsOff != null -> DeliveryMethod.LEGS_OFF to legsOff.slack
            both != null -> DeliveryMethod.DOOR_AND_LEGS to both.slack
            comesApart(f) -> DeliveryMethod.DISASSEMBLE to slack
            else -> DeliveryMethod.NO_FIT to slack
        }

        // Hallway corner: the longest dimension travels horizontally, the thinner profile side across.
        var cornerOk: Boolean? = null
        var needsTilt = false
        var upend = false
        if (hallWidth != null) {
            val dims = floatArrayOf(f.width, f.depth, f.height).sorted()
            val length = dims[2]
            val thickness = minOf(dims[0], profileMinWidth(f))
            val lMax = maxLengthAroundCorner(hallWidth, hallWidth, thickness)
            if (length <= lMax || turns(length, thickness, hallWidth)) cornerOk = true
            else {
                // Tilt the leading end up while turning: horizontal footprint shrinks.
                val rise = (ceiling - dims[1] - 0.05f).coerceAtLeast(0f)
                val horizontal = sqrt(max(0f, length * length - rise * rise))
                cornerOk = horizontal <= lMax
                needsTilt = cornerOk == true
                // Last resort movers use: stand it on end, then the footprint is the other two sides.
                if (cornerOk == false && length <= ceiling - 0.05f &&
                    turns(dims[1], dims[0], hallWidth)
                ) { cornerOk = true; upend = true }
            }
        }

        val detail = buildString {
            append(
                when (method) {
                    DeliveryMethod.STRAIGHT -> "${MovePlanner.fmt(margin)} to spare in a ${MovePlanner.fmt(doorWidth)} door."
                    DeliveryMethod.TILT -> "Angle it like movers do (\"diagonal depth\") — ${MovePlanner.fmt(margin)} to spare."
                    DeliveryMethod.DOOR_OFF -> "The door stops eat ${(FRAME_LOSS * 100).toInt()} cm; without the door it clears by ${MovePlanner.fmt(margin)}."
                    DeliveryMethod.LEGS_OFF -> "Without its legs it clears by ${MovePlanner.fmt(margin)}."
                    DeliveryMethod.DOOR_AND_LEGS -> "With the door off its hinges and the legs unscrewed it clears by ${MovePlanner.fmt(margin)}."
                    DeliveryMethod.DISASSEMBLE -> "Too big in one piece (by ${MovePlanner.fmt(-margin)}), but this kind of furniture is usually built to come apart."
                    DeliveryMethod.NO_FIT -> "Too big by ${MovePlanner.fmt(-margin)} even tilted. Check for a wider door, a window or a balcony."
                },
            )
            when (cornerOk) {
                true -> append(
                    when {
                        upend -> " Hallway corner: stand it on end to turn."
                        needsTilt -> " Hallway corner: tilt it up as you turn."
                        else -> " Hallway corner: OK."
                    },
                )
                false -> append(" Hallway corner: too long to turn — measure the hall again or go in another way.")
                null -> {}
            }
        }
        return DeliveryVerdict(f, method, margin, cornerOk, needsTilt || upend, detail)
    }

    /**
     * Can a footprint L × t get round a right-angle corner of two [hall]-wide corridors? Either it
     * slides round without rotating (both sides fit across a corridor), or it pivots (corner bound).
     */
    fun turns(length: Float, thickness: Float, hall: Float): Boolean =
        (length <= hall && thickness <= hall) || length <= maxLengthAroundCorner(hall, hall, thickness)

    /** Narrowest the side profile gets when rotated — the sofa "diagonal depth". */
    fun profileMinWidth(f: Furniture): Float {
        val hull = sideProfile(f)
        var best = Float.MAX_VALUE
        for (i in hull.indices) {
            val e = (hull[(i + 1) % hull.size] - hull[i]).normalized()
            val n = e.perp()
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (p in hull) { val v = p dot n; lo = min(lo, v); hi = max(hi, v) }
            best = min(best, hi - lo)
        }
        return best
    }
}
