package com.openroomai.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openroomai.app.engine.OpennessReport
import com.openroomai.app.model.Furniture
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Room
import kotlin.math.roundToInt

/** Where the 3D camera sits. */
sealed interface Viewpoint {
    val label: String
    data object Dollhouse : Viewpoint { override val label = "🏠 Dollhouse" }
    data object Door : Viewpoint { override val label = "🚪 From the door" }
    data class Seat(val id: String, override val label: String) : Viewpoint
}

/**
 * Feature: 3D walkthrough. Orbit a dollhouse model of the room or stand in the doorway / sit on
 * your sofa and feel the difference between today's layout and the optimized one.
 */
@Composable
fun Room3DScreen(
    room: Room,
    now: List<Furniture>,
    optimized: List<Furniture>?,
    nowReport: OpennessReport?,
    optimizedReport: OpennessReport?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialView: Viewpoint = Viewpoint.Dollhouse,
    initialAfter: Boolean = optimized != null,
    initialYaw: Float = 0.55f,
) {
    var after by remember { mutableStateOf(initialAfter && optimized != null) }
    var view by remember { mutableStateOf(initialView) }
    val morph by animateFloatAsState(if (after) 1f else 0f, tween(1100), label = "morph3d")
    val layout = if (optimized != null) lerpLayout(now, optimized, morph) else now
    val report = if (after) optimizedReport else nowReport

    val b = Geo.bounds(room.outline)
    val target = Geo.centroid(room.outline).at(0.5f)
    var yaw by remember { mutableFloatStateOf(initialYaw) }
    var pitch by remember { mutableFloatStateOf(0.75f) }
    var dist by remember { mutableFloatStateOf(maxOf(b.width, b.height) * 1.2f + 1.2f) }
    // First-person look offsets (added to the viewpoint's natural heading).
    var lookYaw by remember { mutableFloatStateOf(0f) }
    var lookPitch by remember { mutableFloatStateOf(-0.12f) }
    var touched by remember { mutableStateOf(false) }

    // Gentle auto-spin in dollhouse mode until the user grabs the model.
    LaunchedEffect(view, touched) {
        if (view != Viewpoint.Dollhouse || touched) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { t -> if (last != 0L) yaw += (t - last) / 1e9f * 0.18f; last = t }
        }
    }
    LaunchedEffect(view) { lookYaw = 0f; lookPitch = if (view is Viewpoint.Door) -0.12f else -0.18f }

    val measurer = rememberTextMeasurer()

    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B2433), Palette.Bg)))) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(view) {
                detectTransformGestures { _, pan, zoom, _ ->
                    touched = true
                    if (view == Viewpoint.Dollhouse) {
                        yaw -= pan.x * 0.008f
                        pitch = (pitch + pan.y * 0.006f).coerceIn(0.12f, 1.45f)
                        dist = (dist / zoom).coerceIn(2f, 30f)
                    } else {
                        lookYaw += pan.x * 0.005f
                        lookPitch = (lookPitch + pan.y * 0.004f).coerceIn(-0.9f, 0.6f)
                    }
                }
            },
        ) {
            val cam = when (val v = view) {
                Viewpoint.Dollhouse -> Camera3D.orbit(target, yaw, pitch, dist, size.width, size.height)
                Viewpoint.Door -> {
                    val e = room.entryPoint()
                    // Step back into the doorway itself for the "first impression" view.
                    val d = room.doors().firstOrNull()
                    val stand = if (d != null) room.openingSegment(d).pointAt(0.5f) + room.inwardNormal(d.wallIndex) * 0.15f else e
                    Camera3D.firstPerson(stand.at(1.6f), Camera3D.yawToward(stand, Geo.centroid(room.outline)) + lookYaw, lookPitch, size.width, size.height)
                }
                is Viewpoint.Seat -> {
                    val f = layout.firstOrNull { it.id == v.id }
                    if (f == null) Camera3D.orbit(target, yaw, pitch, dist, size.width, size.height)
                    else Scene3D.seatEye(f).let { (eye, y) -> Camera3D.firstPerson(eye, y + lookYaw, lookPitch, size.width, size.height) }
                }
            }
            val seatId = (view as? Viewpoint.Seat)?.id
            drawRoom3D(cam, room, layout, report, view == Viewpoint.Dollhouse, seatId)

            // Floating labels in dollhouse mode.
            if (view == Viewpoint.Dollhouse) for (f in layout) {
                val c = cam.toCam(f.center.at(f.height + 0.25f))
                if (c.z < 0.3f) continue
                val p = cam.project(c)
                val t = measurer.measure(f.type.emoji, TextStyle(fontSize = 14.sp))
                drawText(t, topLeft = Offset(p.x - t.size.width / 2f, p.y - t.size.height / 2f))
            }
        }

        // ---- Overlay UI
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.45f))) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(room.name, style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text(if (view == Viewpoint.Dollhouse) "Drag to orbit · pinch to zoom" else "Drag to look around", color = Palette.Muted)
                }
                report?.let { ScoreChip(it.score) }
            }
            if (optimized != null) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !after, onClick = { after = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Now") }
                SegmentedButton(selected = after, onClick = { after = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Optimized ✨") }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            report?.let { r ->
                val msg = when (view) {
                    Viewpoint.Dollhouse -> "Open heart of the room: ${"%.1f".format(r.clearDiameter)} m across (green ring)"
                    Viewpoint.Door -> "First impression: you see ${(r.visibilityPct * 100).roundToInt()}% of the floor from the door"
                    is Viewpoint.Seat -> "This is your view from the ${view.label.substringAfter("From your ").lowercase()}"
                }
                Text(
                    msg, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            val seatViews = Scene3D.seats(now).map { Viewpoint.Seat(it.id, "🛋️ From your ${it.name.lowercase()}") }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(Viewpoint.Dollhouse, Viewpoint.Door) + seatViews).forEach { v ->
                    FilterChip(
                        selected = view == v, onClick = { view = v; touched = false },
                        label = { Text(v.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Black.copy(alpha = 0.5f), labelColor = Color.White,
                            selectedContainerColor = Palette.Mint, selectedLabelColor = Color.Black,
                        ),
                    )
                }
            }
        }
    }
}

fun DrawScope.drawRoom3D(cam: Camera3D, room: Room, layout: List<Furniture>, report: OpennessReport?, dollhouse: Boolean, hideId: String? = null) {
    // Floor.
    Scene3D.projectFace(cam, Scene3D.floor(room), backfaceCull = false)?.let { fill(it) }
    // 50 cm floor boards for depth cues.
    val bb = Geo.bounds(room.outline)
    var x = kotlin.math.ceil(bb.minX * 2f) / 2f
    while (x < bb.maxX) {
        clippedFloorLine(cam, room, com.openroomai.app.model.Vec2(x, bb.minY), com.openroomai.app.model.Vec2(x, bb.maxY))
        x += 0.5f
    }
    // Open circle.
    report?.takeIf { it.clearDiameter > 0.4f }?.let { r ->
        val ring = Scene3D.ring(r.clearCenter, r.clearDiameter / 2f)
        for (i in 0 until ring.size - 1 step 2) Scene3D.projectLine(cam, ring[i], ring[i + 1])?.let { (a, b) ->
            drawLine(Palette.Mint, a, b, strokeWidth = 5f, cap = StrokeCap.Round)
        }
    }
    // Walls: in dollhouse mode, walls between the camera and the room become cut-away stubs.
    val (walls, stubs) = Scene3D.walls(room) { i ->
        if (!dollhouse) false else {
            val w = room.walls()[i]
            val n = room.inwardNormal(i)
            val mid = w.pointAt(0.5f)
            ((com.openroomai.app.model.Vec2(cam.eye.x, cam.eye.z) - mid) dot n) < 0f
        }
    }
    walls.mapNotNull { Scene3D.projectFace(cam, it, backfaceCull = false) }.sortedByDescending { it.depth }.forEach { fill(it) }
    // Furniture, painter-sorted far → near.
    layout.filter { it.id != hideId }.flatMap { Scene3D.furniture(it, highlight = false) }
        .mapNotNull { Scene3D.projectFace(cam, it, backfaceCull = true) }
        .sortedByDescending { it.depth }
        .forEach { fill(it) }
    stubs.mapNotNull { Scene3D.projectFace(cam, it, backfaceCull = false) }.forEach { fill(it) }
    // Crisp wall tops.
    if (dollhouse) room.walls().forEach { w ->
        Scene3D.projectLine(cam, w.a.at(Scene3D.WALL_H), w.b.at(Scene3D.WALL_H))?.let { (a, b) -> drawLine(Color.White.copy(alpha = 0.35f), a, b, strokeWidth = 2f) }
    }
}

private fun DrawScope.clippedFloorLine(cam: Camera3D, room: Room, a: com.openroomai.app.model.Vec2, b: com.openroomai.app.model.Vec2) {
    // Sample along the line and draw only the parts inside the room outline.
    val n = 24
    for (k in 0 until n) {
        val p = a + (b - a) * (k / n.toFloat())
        val q = a + (b - a) * ((k + 1) / n.toFloat())
        if (!Geo.pointInPolygon((p + q) / 2f, room.outline)) continue
        Scene3D.projectLine(cam, p.at(0.002f), q.at(0.002f))?.let { (s, e) -> drawLine(Scene3D.FloorLine, s, e, strokeWidth = 1.5f) }
    }
}

private fun DrawScope.fill(p: ScreenPoly) {
    val path = Path().apply {
        moveTo(p.pts[0].x, p.pts[0].y)
        for (i in 1 until p.pts.size) lineTo(p.pts[i].x, p.pts[i].y)
        close()
    }
    drawPath(path, p.color)
    if (p.edge) drawPath(path, Color.Black.copy(alpha = 0.18f), style = Stroke(1.2f, pathEffect = PathEffect.cornerPathEffect(1f)))
}
