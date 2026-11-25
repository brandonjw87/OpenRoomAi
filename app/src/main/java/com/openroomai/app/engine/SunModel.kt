package com.openroomai.app.engine

import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Room
import com.openroomai.app.model.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

const val WINDOW_SILL = 0.9f
const val WINDOW_TOP = 2.1f
const val DEFAULT_LATITUDE = 40f

enum class Season(val label: String, val dayOfYear: Int) {
    TODAY("Today", -1),
    SUMMER("Midsummer", 172),
    EQUINOX("Equinox", 80),
    WINTER("Midwinter", 355),
}

/** Where the sun is: [altitude] above the horizon and compass [azimuth], both radians. */
data class SunPos(val altitude: Float, val azimuth: Float) {
    val up get() = altitude > 0.035f // ~2°: below that, no useful direct light indoors
}

data class SunInsight(val icon: String, val title: String, val body: String)

data class SunReport(
    val grid: OccupancyGrid,
    /** Direct sun on each floor cell at the selected time. */
    val litNow: BooleanArray,
    /** Hours of direct sun per cell over the selected day. */
    val sunHours: FloatArray,
    val maxHours: Float,
    val sunNow: SunPos,
    val sunriseHour: Float?,
    val sunsetHour: Float?,
    val insights: List<SunInsight>,
    /** Per piece: hours of direct sun on it (plants love it, screens hate it). */
    val pieceHours: Map<String, Float>,
)

/**
 * Sunlight simulator: sun position from latitude + date + solar time, then rays from each floor
 * spot toward the sun, through window openings, with tall furniture casting shadows.
 */
object SunModel {

    /** Classic declination / hour-angle model; [solarHour] is local solar time (12 = noon). */
    fun position(latDeg: Float, dayOfYear: Int, solarHour: Float): SunPos {
        val phi = Math.toRadians(latDeg.toDouble())
        val decl = Math.toRadians(23.44 * sin(2 * PI * (284 + dayOfYear) / 365.0))
        val h = Math.toRadians(15.0 * (solarHour - 12.0))
        val sinAlt = sin(phi) * sin(decl) + cos(phi) * cos(decl) * cos(h)
        val alt = asin(sinAlt.coerceIn(-1.0, 1.0))
        var az = atan2(-sin(h), tan(decl) * cos(phi) - sin(phi) * cos(h))
        if (az < 0) az += 2 * PI
        return SunPos(alt.toFloat(), az.toFloat())
    }

    /** Solar hours when the sun is above the horizon (null in polar day/night). */
    fun daylight(latDeg: Float, dayOfYear: Int): Pair<Float, Float>? {
        val phi = Math.toRadians(latDeg.toDouble())
        val decl = Math.toRadians(23.44 * sin(2 * PI * (284 + dayOfYear) / 365.0))
        val c = -tan(phi) * tan(decl)
        if (c <= -1.0 || c >= 1.0) return null
        val h0 = Math.toDegrees(acos(c)) / 15.0
        return (12.0 - h0).toFloat() to (12.0 + h0).toFloat()
    }

    /** Unit floor-plan vector pointing toward compass bearing [bearingRad] for a room facing [northDeg]. */
    fun planDir(bearingRad: Float, northDeg: Float): Vec2 {
        val rel = bearingRad - Math.toRadians(northDeg.toDouble()).toFloat()
        // Plan "up" is −Y and compass bearings turn clockwise, which is +X on a y-down plan.
        return Vec2(sin(rel), -cos(rel))
    }

    /** Compass bearing (degrees) a wall's window looks out toward. */
    fun windowFacingDeg(room: Room, wallIndex: Int, northDeg: Float): Float {
        val out = -room.inwardNormal(wallIndex)
        // Invert planDir: rel = atan2(x, −y).
        val rel = Math.toDegrees(atan2(out.x, -out.y).toDouble()).toFloat()
        return Geo.normalizeDeg(rel + northDeg)
    }

    fun compassName(deg: Float): String {
        val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((Geo.normalizeDeg(deg) + 22.5f) / 45f).toInt()) % 8]
    }

    /**
     * Does the ray from floor point [p] (at height [z]) toward the sun get out through a window?
     * Returns false when a wall, the window frame, or a tall piece of furniture is in the way.
     */
    fun sunReaches(room: Room, p: Vec2, z: Float, sun: SunPos, northDeg: Float, layout: List<Furniture>, ignoreId: String? = null): Boolean {
        if (!sun.up) return false
        val u = planDir(sun.azimuth, northDeg)
        val rise = tan(sun.altitude)
        val walls = room.walls()
        for (w in room.windows()) {
            val wall = walls.getOrNull(w.wallIndex) ?: continue
            val n = room.inwardNormal(w.wallIndex)
            if ((u dot n) >= -1e-3f) continue // sun is on the room side of this wall
            val seg = room.openingSegment(w)
            // Intersect p + s·u with the window segment.
            val e = seg.b - seg.a
            val den = u cross e
            if (abs(den) < 1e-6f) continue
            val s = ((seg.a - p) cross e) / den
            val t = ((seg.a - p) cross u) / den
            if (s <= 0f || t !in 0f..1f) continue
            val hz = z + s * rise
            if (hz < WINDOW_SILL || hz > WINDOW_TOP) continue
            if (shadowed(p, z, u, rise, s, layout, ignoreId)) continue
            return true
        }
        return false
    }

    /** Tall pieces between the spot and the window cast shadows. */
    private fun shadowed(p: Vec2, z: Float, u: Vec2, rise: Float, maxS: Float, layout: List<Furniture>, ignoreId: String?): Boolean {
        for (f in layout) {
            if (f.id == ignoreId || f.height <= z + 0.05f) continue
            // Ray vs oriented rectangle in the piece's local frame (slab method).
            val local = (p - f.center).rotated(-f.angleRad)
            val dir = u.rotated(-f.angleRad)
            var tMin = 0f
            var tMax = maxS
            for (axis in 0..1) {
                val o = if (axis == 0) local.x else local.y
                val d = if (axis == 0) dir.x else dir.y
                val half = if (axis == 0) f.width / 2f else f.depth / 2f
                if (abs(d) < 1e-6f) {
                    if (o < -half || o > half) { tMin = 1f; tMax = 0f }
                } else {
                    var t1 = (-half - o) / d
                    var t2 = (half - o) / d
                    if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
                    if (t1 > tMin) tMin = t1
                    if (t2 < tMax) tMax = t2
                }
            }
            if (tMin <= tMax && z + tMin * rise < f.height) return true
        }
        return false
    }

    /** Spots under furniture are measured at the furniture's top surface. */
    private fun surfaceHeight(g: OccupancyGrid, i: Int, c: Vec2, layout: List<Furniture>): Float =
        if (!g.blocked[i]) 0f else layout.firstOrNull { OccupancyGrid.pointInConvex(c, it.corners()) }?.height ?: 0f

    /** Cheap re-trace for one moment (time slider) reusing a full day's report. */
    fun atHour(room: Room, layout: List<Furniture>, rep: SunReport, season: Season, solarHour: Float): SunReport {
        val g = rep.grid
        g.stamp(layout)
        val sun = position(room.latitude ?: DEFAULT_LATITUDE, dayOf(season), solarHour)
        val lit = BooleanArray(g.size)
        for (i in 0 until g.size) {
            if (!g.inside[i]) continue
            val c = g.center(i % g.w, i / g.w)
            if (sunReaches(room, c, surfaceHeight(g, i, c, layout), sun, room.northDeg ?: 0f, layout, null)) lit[i] = true
        }
        return rep.copy(litNow = lit, sunNow = sun)
    }

    fun dayOf(season: Season): Int =
        if (season.dayOfYear > 0) season.dayOfYear else java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)

    fun simulate(
        room: Room,
        layout: List<Furniture>,
        season: Season,
        solarHour: Float,
        cell: Float = 0.1f,
        step: Float = 0.5f,
    ): SunReport {
        val north = room.northDeg ?: 0f
        val lat = room.latitude ?: DEFAULT_LATITUDE
        val day = dayOf(season)
        val g = OccupancyGrid(room, cell)
        g.stamp(layout)
        val now = position(lat, day, solarHour)
        val lit = BooleanArray(g.size)
        val hours = FloatArray(g.size)
        val sunTimes = ArrayList<Pair<Float, SunPos>>()
        var hr = 4f
        while (hr <= 20f) { val sp = position(lat, day, hr); if (sp.up) sunTimes += hr to sp; hr += step }
        for (i in 0 until g.size) {
            if (!g.inside[i]) continue
            val c = g.center(i % g.w, i / g.w)
            val z = surfaceHeight(g, i, c, layout)
            if (sunReaches(room, c, z, now, north, layout, null)) lit[i] = true
            var hsum = 0f
            for ((_, sp) in sunTimes) if (sunReaches(room, c, z, sp, north, layout, null)) hsum += step
            hours[i] = hsum
        }
        val maxH = hours.maxOrNull() ?: 0f

        // Hours on each piece: plants measured at the foliage, screens at the screen face.
        val pieceHours = HashMap<String, Float>()
        for (f in layout) {
            val (pt, z) = when (f.type) {
                FurnitureType.TV_STAND -> (f.center + f.front() * (f.depth / 2f + 0.05f)) to (f.height + 0.35f)
                FurnitureType.PLANT -> f.center to f.height
                FurnitureType.DESK -> (f.center) to f.height + 0.02f
                else -> f.center to f.height
            }
            var h = 0f
            for ((_, sp) in sunTimes) if (sunReaches(room, pt, z, sp, north, layout, f.id)) h += step
            pieceHours[f.id] = h
        }
        val daylight = daylight(lat, day)
        val insights = insights(room, layout, sunTimes, step, pieceHours, maxH, north)
        return SunReport(g, lit, hours, maxH, now, daylight?.first, daylight?.second, insights, pieceHours)
    }

    private fun insights(
        room: Room,
        layout: List<Furniture>,
        sunTimes: List<Pair<Float, SunPos>>,
        step: Float,
        pieceHours: Map<String, Float>,
        maxH: Float,
        north: Float,
    ): List<SunInsight> {
        val out = ArrayList<SunInsight>()
        if (room.windows().isEmpty()) {
            out += SunInsight("🪟", "No windows marked", "Add windows on the plan to see where sunlight lands.")
            return out
        }
        if (room.northDeg == null) out += SunInsight("🧭", "Compass not set",
            "Results assume the top of the plan faces north. Calibrate with the compass for real sun angles.")
        val facing = room.windows().map { compassName(windowFacingDeg(room, it.wallIndex, north)) }.distinct()
        // When does any direct sun come in?
        val litHours = sunTimes.filter { (_, sp) -> room.windows().isNotEmpty() && roomGetsSun(room, sp, north) }.map { it.first }
        if (litHours.isEmpty()) out += SunInsight("🌥️", "No direct sun this day",
            "Windows face ${facing.joinToString("/")}. Soft, even light — great for screens, tough for sun-loving plants.")
        else out += SunInsight("☀️", "Direct sun ${fmtHour(litHours.first())}–${fmtHour(litHours.last() + step)}",
            "Through the ${facing.joinToString("/")}-facing window${if (facing.size > 1 || room.windows().size > 1) "s" else ""}. Sunniest spot: ${"%.1f".format(maxH)} h of sun.")
        for (f in layout) {
            val h = pieceHours[f.id] ?: continue
            when (f.type) {
                FurnitureType.TV_STAND -> if (h >= 1f) out += SunInsight("📺", "Glare on the TV for ~${fmt1(h)} h",
                    "Sun hits the screen directly. Turn it so the window is to its side, or close sheer curtains in the afternoon.")
                FurnitureType.PLANT -> out += if (h >= 2f) SunInsight("🪴", "Happy ${f.name.lowercase()}: ${fmt1(h)} h of sun", "Right where it should be.")
                else SunInsight("🪴", "${f.name} only gets ${fmt1(h)} h of sun", "Most houseplants want 2–6 h. The optimizer will try to move it into the light.")
                FurnitureType.DESK -> if (h >= 1.5f) out += SunInsight("💻", "Desk in direct sun ${fmt1(h)} h",
                    "Hot and glary for laptop work. Side light is ideal — try the Work-from-home goal.")
                FurnitureType.BED -> if (h >= 0.5f) out += SunInsight("🛏️", "Sun on the bed",
                    "Lovely for a natural wake-up… or annoying on weekends. Blackout curtains are your friend.")
                else -> {}
            }
        }
        return out
    }

    private fun roomGetsSun(room: Room, sp: SunPos, north: Float): Boolean {
        val u = planDir(sp.azimuth, north)
        return room.windows().any { (u dot room.inwardNormal(it.wallIndex)) < -0.05f }
    }

    private fun fmt1(h: Float) = if (h == floor(h)) "${h.roundToInt()}" else "%.1f".format(h)

    fun fmtHour(h: Float): String {
        val hh = floor(h).toInt()
        val mm = ((h - hh) * 60).roundToInt()
        val ampm = if (hh < 12) "am" else "pm"
        val h12 = ((hh + 11) % 12) + 1
        return if (mm == 0) "$h12 $ampm" else "$h12:${"%02d".format(mm)} $ampm"
    }
}
