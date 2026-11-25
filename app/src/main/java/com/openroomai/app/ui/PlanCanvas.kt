package com.openroomai.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.openroomai.app.engine.COMFORT_RADIUS
import com.openroomai.app.engine.OccupancyGrid
import com.openroomai.app.engine.OpennessReport
import com.openroomai.app.engine.SQUEEZE_RADIUS
import com.openroomai.app.engine.SunModel
import com.openroomai.app.engine.SunReport
import com.openroomai.app.model.Furniture
import com.openroomai.app.model.Geo
import com.openroomai.app.model.OpeningType
import com.openroomai.app.model.Room
import com.openroomai.app.model.Vec2
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt

data class Layers(
    val heatmap: Boolean = true,
    val flows: Boolean = false,
    val sightlines: Boolean = false,
    val openCircle: Boolean = true,
    val labels: Boolean = true,
    val sunlight: Boolean = false,
    /** Sunlight layer: whole-day sun hours instead of the selected moment. */
    val sunHours: Boolean = false,
)

/** Maps plan meters to canvas pixels, fitting the room with padding. */
class PlanTransform(room: Room, size: Size, pad: Float) {
    private val b = Geo.bounds(room.outline)
    val scale = min((size.width - 2 * pad) / b.width.coerceAtLeast(0.5f), (size.height - 2 * pad) / b.height.coerceAtLeast(0.5f))
    private val ox = (size.width - b.width * scale) / 2f - b.minX * scale
    private val oy = (size.height - b.height * scale) / 2f - b.minY * scale
    fun toScreen(p: Vec2) = Offset(p.x * scale + ox, p.y * scale + oy)
    fun toPlan(o: Offset) = Vec2((o.x - ox) / scale, (o.y - oy) / scale)
}

private fun heatBitmap(rep: OpennessReport): ImageBitmap {
    val g = rep.grid
    val px = IntArray(g.size)
    for (i in 0 until g.size) {
        if (!g.inside[i] || g.blocked[i]) { px[i] = 0; continue }
        val d = g.dist[i]
        px[i] = when {
            d < SQUEEZE_RADIUS -> 0x26FF5370
            d < COMFORT_RADIUS -> 0x30FFC94D
            else -> {
                // Deeper green = more open.
                val t = Geo.clamp01((d - COMFORT_RADIUS) / 1.2f)
                val a = (0x30 + (0x50 * t)).toInt()
                (a shl 24) or 0x2EE6A8
            }
        }
    }
    val bmp = Bitmap.createBitmap(g.w, g.h, Bitmap.Config.ARGB_8888)
    bmp.setPixels(px, 0, g.w, 0, 0, g.w, g.h)
    return bmp.asImageBitmap()
}

private fun sunBitmap(sun: SunReport, hoursMode: Boolean): ImageBitmap {
    val g = sun.grid
    val px = IntArray(g.size)
    val maxH = sun.maxHours.coerceAtLeast(1f)
    for (i in 0 until g.size) {
        if (!g.inside[i]) continue
        px[i] = if (hoursMode) {
            val t = sun.sunHours[i] / maxH
            if (t <= 0f) 0x22000033 else ((0x30 + (0x9F * t).toInt()) shl 24) or 0xFFC93D
        } else if (sun.litNow[i]) 0x99FFD54F.toInt() else if (g.blocked[i]) 0 else 0x33000020
    }
    val bmp = Bitmap.createBitmap(g.w, g.h, Bitmap.Config.ARGB_8888)
    bmp.setPixels(px, 0, g.w, 0, 0, g.w, g.h)
    return bmp.asImageBitmap()
}

private fun sightBitmap(rep: OpennessReport): ImageBitmap {
    val g = rep.grid
    val px = IntArray(g.size)
    for (i in 0 until g.size) {
        px[i] = if (g.inside[i] && !rep.visible[i] && !g.tall[i]) 0x88101420.toInt() else 0
    }
    val bmp = Bitmap.createBitmap(g.w, g.h, Bitmap.Config.ARGB_8888)
    bmp.setPixels(px, 0, g.w, 0, 0, g.w, g.h)
    return bmp.asImageBitmap()
}

/**
 * The floor plan. Shows [layout]; when [ghost] is given, draws it as dashed "where it is now"
 * outlines with arrows to the new spots.
 */
@Composable
fun PlanCanvas(
    room: Room,
    layout: List<Furniture>,
    report: OpennessReport?,
    modifier: Modifier = Modifier,
    layers: Layers = Layers(),
    sun: SunReport? = null,
    ghost: List<Furniture>? = null,
    selectedId: String? = null,
    highlightId: String? = null,
    interactive: Boolean = false,
    compact: Boolean = false,
    onSelect: (String?) -> Unit = {},
    onMove: (String, Vec2) -> Unit = { _, _ -> },
    onTapEmpty: (Vec2) -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val heat = remember(report) { report?.let { heatBitmap(it) } }
    val sight = remember(report) { report?.let { sightBitmap(it) } }
    val sunImg = remember(sun, layers.sunHours) { if (layers.sunlight) sun?.let { sunBitmap(it, layers.sunHours) } else null }
    val layoutState by rememberUpdatedState(layout)
    val roomState by rememberUpdatedState(room)
    val pad = if (compact) 10f else 56f

    val onSelectS by rememberUpdatedState(onSelect)
    val onMoveS by rememberUpdatedState(onMove)
    val onTapEmptyS by rememberUpdatedState(onTapEmpty)

    var gestureMod = Modifier as Modifier
    if (interactive) {
        gestureMod = Modifier
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val tr = PlanTransform(roomState, Size(size.width.toFloat(), size.height.toFloat()), pad)
                    val p = tr.toPlan(o)
                    val hit = layoutState.lastOrNull { OccupancyGrid.pointInConvex(p, it.corners()) }
                    if (hit != null) onSelectS(hit.id) else { onSelectS(null); onTapEmptyS(p) }
                }
            }
            .pointerInput(Unit) {
                // Only drags that start on a piece of furniture are ours; others scroll the page.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tr = PlanTransform(roomState, Size(size.width.toFloat(), size.height.toFloat()), pad)
                    val p0 = tr.toPlan(down.position)
                    val hit = layoutState.lastOrNull { f ->
                        OccupancyGrid.pointInConvex(p0, f.corners().map { c -> f.center + (c - f.center) * 1.15f })
                    } ?: return@awaitEachGesture
                    val grab = hit.center - p0
                    val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
                    onSelectS(hit.id)
                    drag(start.id) { change ->
                        change.consume()
                        val p = tr.toPlan(change.position) + grab
                        // Snap to 5 cm so things line up nicely.
                        onMoveS(hit.id, Vec2((p.x * 20f).roundToInt() / 20f, (p.y * 20f).roundToInt() / 20f))
                    }
                }
            }
    }

    Canvas(modifier.then(gestureMod)) {
        val tr = PlanTransform(room, size, pad)
        drawPlan(this, tr, room, layout, report, layers, ghost, selectedId, highlightId, heat, sight, measurer, compact)
        if (sunImg != null && sun != null) drawSun(tr, room, sun, sunImg, layers.sunHours, measurer)
        if (!compact && room.northDeg != null) drawCompass(room.northDeg, measurer)
    }
}

fun drawPlan(
    ds: DrawScope,
    tr: PlanTransform,
    room: Room,
    layout: List<Furniture>,
    report: OpennessReport?,
    layers: Layers,
    ghost: List<Furniture>?,
    selectedId: String?,
    highlightId: String?,
    heat: ImageBitmap?,
    sight: ImageBitmap?,
    measurer: TextMeasurer,
    compact: Boolean,
) = with(ds) {
    val s = tr.scale
    val outline = room.outline.map { tr.toScreen(it) }
    val floor = Path().apply {
        moveTo(outline[0].x, outline[0].y)
        for (i in 1 until outline.size) lineTo(outline[i].x, outline[i].y)
        close()
    }
    drawPath(floor, Palette.SurfaceHi)

    // 50 cm dot grid.
    if (!compact) {
        val b = Geo.bounds(room.outline)
        var x = (b.minX * 2).toInt() / 2f
        while (x <= b.maxX) {
            var y = (b.minY * 2).toInt() / 2f
            while (y <= b.maxY) {
                val p = Vec2(x, y)
                if (Geo.pointInPolygon(p, room.outline)) drawCircle(Palette.Muted.copy(alpha = 0.25f), 1.5f, tr.toScreen(p))
                y += 0.5f
            }
            x += 0.5f
        }
    }

    if (report != null) {
        val g = report.grid
        val tl = tr.toScreen(Vec2(g.bounds.minX, g.bounds.minY))
        val dst = IntSize((g.w * g.cell * s).roundToInt(), (g.h * g.cell * s).roundToInt())
        if (layers.heatmap && heat != null) drawImage(heat, dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()), dstSize = dst, filterQuality = FilterQuality.Low)
        if (layers.sightlines && sight != null) {
            drawImage(sight, dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()), dstSize = dst, filterQuality = FilterQuality.Low)
            val e = tr.toScreen(room.entryPoint())
            drawCircle(Palette.Amber, 7f, e)
            drawCircle(Palette.Amber.copy(alpha = 0.3f), 18f, e)
        }
        if (layers.openCircle && report.clearDiameter > 0.3f) {
            val c = tr.toScreen(report.clearCenter)
            val r = report.clearDiameter / 2f * s
            drawCircle(Palette.Mint.copy(alpha = 0.10f), r, c)
            drawCircle(Palette.Mint, r, c, style = Stroke(2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
            if (!compact) {
                val t = measurer.measure("${"%.1f".format(report.clearDiameter)} m open", TextStyle(color = Palette.Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                drawText(t, topLeft = Offset(c.x - t.size.width / 2f, c.y - r + 8f)) // inside the ring: clear of wall labels
            }
        }
    }

    // Walls.
    val walls = room.walls()
    walls.forEachIndexed { i, w ->
        drawLine(Palette.Wall, tr.toScreen(w.a), tr.toScreen(w.b), strokeWidth = if (compact) 3f else 7f, cap = StrokeCap.Round)
        if (!compact) {
            val mid = w.pointAt(0.5f) - room.inwardNormal(i) * (24f / s)
            val c = tr.toScreen(mid)
            drawCircle(Palette.Surface, 15f, c)
            drawCircle(Palette.Muted, 15f, c, style = Stroke(1.5f))
            val t = measurer.measure(room.wallLabel(i), TextStyle(color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold))
            drawText(t, topLeft = Offset(c.x - t.size.width / 2f, c.y - t.size.height / 2f))
        }
    }
    // Doors & windows.
    for (o in room.openings) {
        val seg = room.openingSegment(o)
        val a = tr.toScreen(seg.a); val b = tr.toScreen(seg.b)
        if (o.type == OpeningType.DOOR) {
            drawLine(Palette.SurfaceHi, a, b, strokeWidth = if (compact) 4f else 9f)
            if (!compact) {
                val n = room.inwardNormal(o.wallIndex)
                val tip = seg.a + n * seg.length
                drawLine(Palette.Door, a, tr.toScreen(tip), strokeWidth = 3f)
                val start = Math.toDegrees((n.angle()).toDouble()).toFloat()
                val sweep = Math.toDegrees(((seg.b - seg.a).angle() - n.angle()).toDouble()).toFloat().let { if (it > 180) it - 360 else if (it < -180) it + 360 else it }
                val r = seg.length * s
                drawArc(Palette.Door.copy(alpha = 0.7f), start, sweep, false, topLeft = Offset(a.x - r, a.y - r), size = Size(2 * r, 2 * r),
                    style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
            }
        } else {
            drawLine(Palette.Sky, a, b, strokeWidth = if (compact) 4f else 10f)
            if (!compact) drawLine(Palette.SurfaceHi, a, b, strokeWidth = 3f)
        }
    }

    // Walk paths.
    if (layers.flows && report != null) {
        for (f in report.flows) {
            if (f.points.size < 2) continue
            val path = Path().apply {
                val p0 = tr.toScreen(f.points[0]); moveTo(p0.x, p0.y)
                for (k in 1 until f.points.size) { val p = tr.toScreen(f.points[k]); lineTo(p.x, p.y) }
            }
            val col = if (f.minWidth >= 2 * COMFORT_RADIUS) Palette.Violet else Palette.Amber
            drawPath(path, col.copy(alpha = 0.85f), style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 9f))))
        }
        for (p in report.tightSpots) drawCircle(Palette.Coral.copy(alpha = 0.5f), 3f, tr.toScreen(p))
    }

    // Ghosts: current positions + arrows.
    if (ghost != null) {
        val target = layout.associateBy { it.id }
        for (g in ghost) {
            val t = target[g.id] ?: continue
            if (g.center.dist(t.center) < 0.08f && kotlin.math.abs(g.rotationDelta(t)) < 5f) continue
            val p = polyPath(g.corners().map { tr.toScreen(it) })
            drawPath(p, Palette.Coral.copy(alpha = 0.8f), style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            drawArrow(tr.toScreen(g.center), tr.toScreen(t.center), Palette.Coral.copy(alpha = 0.8f))
        }
    }

    // Furniture.
    for (f in layout) {
        val pts = f.corners().map { tr.toScreen(it) }
        val base = Color(f.type.colorArgb)
        val sel = f.id == selectedId || f.id == highlightId
        drawPath(polyPath(pts), base.copy(alpha = if (sel) 0.95f else 0.75f))
        drawPath(polyPath(pts), if (sel) Color.White else base, style = Stroke(if (sel) 4f else 2f))
        // Front edge = the side you use.
        val fl = f.center + f.front() * (f.depth / 2f) - f.right() * (f.width / 2f * 0.8f)
        val fr = f.center + f.front() * (f.depth / 2f) + f.right() * (f.width / 2f * 0.8f)
        drawLine(Color.White.copy(alpha = 0.85f), tr.toScreen(fl), tr.toScreen(fr), strokeWidth = if (compact) 1.5f else 3.5f, cap = StrokeCap.Round)
        if (f.isNew && !compact) drawPath(polyPath(pts), Palette.Amber, style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
        if (f.locked && !compact) drawCircle(Palette.Amber, 5f, tr.toScreen(f.center + f.right() * (f.width / 2f - 0.1f) - f.front() * (f.depth / 2f - 0.1f)))
        if (layers.labels && !compact) {
            val c = tr.toScreen(f.center)
            val short = min(f.width, f.depth) * s
            val emoji = measurer.measure(f.type.emoji, TextStyle(fontSize = if (short > 40) 16.sp else 11.sp))
            drawText(emoji, topLeft = Offset(c.x - emoji.size.width / 2f, c.y - emoji.size.height / 2f - if (short > 46) 7f else 0f))
            if (short > 46 && f.width * s > 70) {
                val t = measurer.measure(f.name, TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
                if (t.size.width < f.width * s) drawText(t, topLeft = Offset(c.x - t.size.width / 2f, c.y + 6f))
            }
        }
    }
}

private fun polyPath(pts: List<Offset>) = Path().apply {
    moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
    close()
}

private fun DrawScope.drawArrow(from: Offset, to: Offset, color: Color) {
    drawLine(color, from, to, strokeWidth = 2.5f, cap = StrokeCap.Round)
    val ang = kotlin.math.atan2(to.y - from.y, to.x - from.x)
    rotate(Math.toDegrees(ang.toDouble()).toFloat(), pivot = to) {
        val p = Path().apply { moveTo(to.x, to.y); lineTo(to.x - 14f, to.y - 7f); lineTo(to.x - 14f, to.y + 7f); close() }
        drawPath(p, color)
    }
}

/** Linear blend between two layouts for the before → after morph animation. */
fun lerpLayout(a: List<Furniture>, b: List<Furniture>, t: Float): List<Furniture> {
    if (t <= 0f) return a
    val am = a.associateBy { it.id }
    return b.map { tb ->
        val ta = am[tb.id] ?: return@map tb
        if (t >= 1f) return@map tb
        val e = (1 - kotlin.math.cos(t * PI.toFloat())) / 2f
        val c = ta.center + (tb.center - ta.center) * e
        val r = ta.rotationDeg + Geo.shortestAngleDiffDeg(ta.rotationDeg, tb.rotationDeg) * e
        tb.withPose(c, r)
    }
}

private fun DrawScope.drawSun(tr: PlanTransform, room: Room, sun: SunReport, img: ImageBitmap, hoursMode: Boolean, measurer: TextMeasurer) {
    val g = sun.grid
    val tl = tr.toScreen(Vec2(g.bounds.minX, g.bounds.minY))
    val dst = IntSize((g.w * g.cell * tr.scale).roundToInt(), (g.h * g.cell * tr.scale).roundToInt())
    drawImage(img, dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()), dstSize = dst, filterQuality = FilterQuality.Low)
    // A sun marker on the canvas edge, in the direction the light comes from.
    if (!hoursMode && sun.sunNow.up) {
        val dir = SunModel.planDir(sun.sunNow.azimuth, room.northDeg ?: 0f)
        val c = tr.toScreen(Geo.centroid(room.outline))
        val inset = 26f
        val tx = if (dir.x > 1e-4f) (size.width - inset - c.x) / dir.x else if (dir.x < -1e-4f) (inset - c.x) / dir.x else Float.MAX_VALUE
        val ty = if (dir.y > 1e-4f) (size.height - inset - c.y) / dir.y else if (dir.y < -1e-4f) (inset - c.y) / dir.y else Float.MAX_VALUE
        val t = minOf(tx, ty)
        val p = Offset(c.x + dir.x * t, c.y + dir.y * t)
        drawCircle(Color(0x66FFD54F), 24f, p)
        drawCircle(Color(0xFFFFD54F), 24f, p, style = Stroke(2f))
        val txt = measurer.measure("☀️", TextStyle(fontSize = 17.sp))
        drawText(txt, topLeft = Offset(p.x - txt.size.width / 2f, p.y - txt.size.height / 2f))
    }
}

/** Compass rose in the top-right corner: where north is on this plan. */
private fun DrawScope.drawCompass(northDeg: Float, measurer: TextMeasurer) {
    val c = Offset(size.width - 48f, 48f)
    val n = SunModel.planDir(0f, northDeg)
    drawCircle(Color.Black.copy(alpha = 0.5f), 36f, c)
    drawCircle(Color.White.copy(alpha = 0.25f), 36f, c, style = Stroke(1.5f))
    val tip = Offset(c.x + n.x * 22f, c.y + n.y * 22f)
    val tail = Offset(c.x - n.x * 18f, c.y - n.y * 18f)
    drawLine(Palette.Coral, c, tip, strokeWidth = 6f, cap = StrokeCap.Round)
    drawLine(Color.White.copy(alpha = 0.7f), c, tail, strokeWidth = 4f, cap = StrokeCap.Round)
    val t = measurer.measure("N", TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold))
    drawText(t, topLeft = Offset(tip.x + n.x * 2f - t.size.width / 2f, tip.y + n.y * 2f - t.size.height / 2f - 10f * (1f - kotlin.math.abs(n.x))))
}
