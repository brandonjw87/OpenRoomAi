package com.openroomai.app.engine

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * One place for every user-facing measurement. Geometry is always meters internally; this turns
 * it into inches/feet/sq ft or cm/m/m², and decides what "a small nudge" means in each system.
 */
object Units {
    enum class System { IMPERIAL, METRIC }

    const val INCH = 0.0254f
    const val FOOT = 0.3048f
    const val SQFT = 0.09290304f

    @Volatile var system: System = defaultFor(Locale.getDefault())

    val imperial get() = system == System.IMPERIAL

    fun defaultFor(locale: Locale): System =
        if (locale.country.uppercase() in setOf("US", "LR", "MM")) System.IMPERIAL else System.METRIC

    /** Lengths: 14″ / 7′ 4″ or 35 cm / 2.4 m. */
    fun len(m: Float): String {
        val v = abs(m)
        val sign = if (m < 0) "−" else ""
        return if (imperial) {
            val totalIn = (v / INCH).roundToInt()
            if (totalIn < 24) "$sign$totalIn″" else {
                val ft = totalIn / 12; val inch = totalIn % 12
                if (inch == 0) "$sign$ft′" else "$sign$ft′ $inch″"
            }
        } else {
            if (v < 1f) "$sign${(v * 100).roundToInt()} cm" else "$sign${"%.1f".format(v)} m"
        }
    }

    /**
     * Widths of walkways and gaps: whole inches up to 5 feet (a "36″ walkway" is how everyone
     * talks about it), or centimeters up to 2 m.
     */
    fun width(m: Float): String {
        val v = abs(m)
        return if (imperial) {
            val inch = (v / INCH).roundToInt()
            if (inch < 60) "$inch″" else len(v)
        } else if (v < 2f) "${(v * 100).roundToInt()} cm" else len(v)
    }

    /** Short nudge distances keep fractions honest: ½″ steps, or whole centimeters. */
    fun nudge(m: Float): String = if (imperial) {
        val halves = (abs(m) / INCH * 2).roundToInt()
        val whole = halves / 2
        when {
            halves % 2 == 0 -> "$whole″"
            whole == 0 -> "½″"
            else -> "$whole½″"
        }
    } else "${(abs(m) * 100).roundToInt()} cm"

    /** Areas: 45.2 sq ft or 4.2 m². */
    fun area(m2: Float): String = if (imperial) "%.1f sq ft".format(m2 / SQFT) else "%.1f m²".format(m2)

    /** Signed area gain, e.g. "+8.5 sq ft" or "+0.35 m²" (small metric gains keep two decimals). */
    fun areaGain(m2: Float): String {
        val sign = if (m2 >= 0) "+" else "−"
        val v = abs(m2)
        return sign + if (!imperial && v < 1f) "%.2f m²".format(v) else area(v)
    }

    /** Width × depth, e.g. 7′ 4″ × 6′ 2″. */
    fun dims(w: Float, d: Float): String = "${len(w)} × ${len(d)}"

    /** Plain number of the user's area unit (for big numeric badges). */
    fun areaValue(m2: Float): Float = if (imperial) m2 / SQFT else m2
    val areaUnit get() = if (imperial) "sq ft" else "m²"

    /** Plain number of the user's short length unit (inches / cm). */
    fun shortValue(m: Float): Float = if (imperial) m / INCH else m * 100f
    val shortUnit get() = if (imperial) "″" else " cm"

    /** Drag snapping: 1″ or 1 cm. */
    val snapStep: Float get() = if (imperial) INCH else 0.01f

    fun snap(v: Float): Float = (floor(v / snapStep + 0.5f) * snapStep)

    /** The nudge ladder the micro-optimizer tries, smallest first. */
    val nudgeSteps: List<Float>
        get() = if (imperial) listOf(1f, 2f, 3f, 4f, 6f, 9f, 12f).map { it * INCH }
        else listOf(0.02f, 0.05f, 0.08f, 0.1f, 0.15f, 0.25f, 0.3f)
}
