package com.openroomai.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openroomai.app.ar.ArController
import com.openroomai.app.ar.ArUiState
import com.openroomai.app.ar.ScanStep
import com.openroomai.app.engine.MoveStep
import com.openroomai.app.model.Furniture
import com.openroomai.app.model.OpeningType
import com.openroomai.app.model.Room
import kotlin.math.abs

/** Full-screen ARCore camera view bound to the composable's lifecycle. */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun ArView(controller: ArController, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val glView = remember {
        GLSurfaceView(ctx).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(controller)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setWillNotDraw(false)
            var downX = 0f; var downY = 0f
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
                    MotionEvent.ACTION_UP -> if (abs(e.x - downX) < 24 && abs(e.y - downY) < 24) controller.tap(e.x, e.y)
                }
                true
            }
        }
    }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_RESUME -> {
                    val err = controller.resume(ctx as Activity)
                    if (err != null) onError(err) else glView.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> { glView.onPause(); controller.pause() }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            glView.onPause()
            controller.pause()
        }
    }
    AndroidView({ glView }, modifier)
}

@Composable
private fun Crosshair() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(28.dp).border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape))
        Box(Modifier.size(4.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun MessagePill(text: String, warn: Boolean = false) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, color = if (warn) Palette.Amber else Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StepPills(step: ScanStep) {
    val steps = listOf(ScanStep.OUTLINE to "Walls", ScanStep.OPENINGS to "Doors", ScanStep.FURNITURE to "Furniture")
    val idx = steps.indexOfFirst { it.first == step }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, (_, label) ->
            val active = i == idx
            val done = idx > i
            Box(
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (active) Palette.Mint else if (done) Palette.Mint.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) { Text((if (done) "✓ " else "${i + 1} ") + label, color = if (active) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
fun ScanScreen(controller: ArController, onDone: (Room) -> Unit, onBack: () -> Unit) {
    val st by controller.state.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    remember { controller.startNewScan(); 0 }
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val compass = com.openroomai.app.sensors.Compass(ctx)
        compass.start()
        controller.compass = compass
        onDispose { compass.stop(); controller.compass = null }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ArView(controller, onError = { error = it }, modifier = Modifier.fillMaxSize())
        Crosshair()
        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f))) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                StepPills(st.step)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }
            MessagePill(error ?: st.message, warn = error != null || !st.tracking)
            if (st.depthSupported && st.step == ScanStep.FURNITURE) {
                MessagePill("Depth sensing on · ${"%.1f".format(st.coverage)} m² mapped")
            }
            if (st.step == ScanStep.FURNITURE && st.visionReady) {
                MessagePill(
                    if (st.recognized.isEmpty()) "📷 Camera is looking for sofas, chairs, beds, tables…"
                    else "📷 Recognised: " + st.recognized.joinToString(", "),
                )
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(12.dp).clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScanStats(st)
            when (st.step) {
                ScanStep.OPENINGS -> SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    OpeningType.entries.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = st.openingType == t, onClick = { controller.setOpeningType(t) },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(if (t == OpeningType.DOOR) "🚪 Door" else "🪟 Window") }
                    }
                }
                else -> {}
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledIconButton(onClick = { controller.undo() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Palette.SurfaceHi)) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = Color.White)
                }
                if (st.step == ScanStep.FURNITURE) {
                    FilledIconButton(onClick = { controller.rescanFurniture() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Palette.SurfaceHi)) {
                        Icon(Icons.Default.Refresh, "Rescan", tint = Color.White)
                    }
                }
                Button(
                    onClick = { controller.addAtReticle() },
                    enabled = st.reticleOnFloor,
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp))
                    Text(
                        when (st.step) {
                            ScanStep.OUTLINE -> "Add corner"
                            ScanStep.OPENINGS -> if (st.pendingPoint) "Other edge" else "Mark edge"
                            ScanStep.FURNITURE -> if (st.pendingPoint) "Opposite corner" else "Add item corner"
                            else -> "Add"
                        },
                    )
                }
                when (st.step) {
                    ScanStep.OUTLINE -> FilledIconButton(onClick = { controller.closeOutline() }, enabled = st.corners >= 3) { Icon(Icons.Default.Check, "Close room") }
                    ScanStep.OPENINGS -> FilledIconButton(onClick = { controller.setStep(ScanStep.FURNITURE) }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next") }
                    ScanStep.FURNITURE -> FilledIconButton(onClick = { naming = true }) { Icon(Icons.Default.CheckCircle, "Finish") }
                    else -> {}
                }
            }
        }
    }

    if (naming) {
        var name by remember { mutableStateOf("Living room") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Name this room") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    Text("${st.detected.size} detected + ${st.manual.size} manual items. You can fix types and sizes on the plan.", color = Palette.Muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    naming = false
                    controller.buildRoom(name.ifBlank { "My room" })?.let(onDone) ?: run { error = "Mark at least 3 room corners first." }
                }) { Text("Build plan") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Keep scanning") } },
        )
    }
}

@Composable
private fun ScanStats(st: ArUiState) {
    val txt = when (st.step) {
        ScanStep.FLOOR -> "Looking for the floor…"
        ScanStep.OUTLINE -> "${st.corners} corners" + (if (st.perimeter > 0) " · ${"%.1f".format(st.perimeter)} m walls" else "") + (if (st.area > 0) " · ${"%.1f".format(st.area)} m²" else "")
        ScanStep.OPENINGS -> "${st.openings.count { it.type == OpeningType.DOOR }} doors · ${st.openings.count { it.type == OpeningType.WINDOW }} windows · ${"%.1f".format(st.area)} m² room"
        ScanStep.FURNITURE -> "${st.detected.size} pieces detected · ${st.manual.size} added by hand"
        else -> ""
    }
    Text(txt, color = Color.White, style = MaterialTheme.typography.titleMedium)
}

/** Feature #1: AR ghost preview of the optimized layout in the real room. */
@Composable
fun PreviewScreen(controller: ArController, room: Room, target: List<Furniture>, steps: List<MoveStep>, done: Set<Int>, onToggle: (Int) -> Unit, onBack: () -> Unit) {
    val st by controller.state.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    remember(room.id) { controller.startPreview(room, target); 0 }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ArView(controller, onError = { error = it }, modifier = Modifier.fillMaxSize())
        if (st.step == ScanStep.ALIGN) Crosshair()
        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f))) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                if (st.step == ScanStep.PREVIEW) FilledTonalButton(onClick = { controller.realign() }) {
                    Icon(Icons.Default.MyLocation, null); Spacer(Modifier.width(6.dp)); Text("Re-align")
                }
            }
            MessagePill(error ?: st.message, warn = error != null || !st.tracking)
            if (st.occlusion) MessagePill("👓 Real furniture now hides the ghosts (depth occlusion)")
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            if (st.step == ScanStep.ALIGN) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilledIconButton(onClick = { controller.undo() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Palette.SurfaceHi)) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = Color.White)
                    }
                    Button(onClick = { controller.addAtReticle() }, enabled = st.reticleOnFloor, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text(if (st.alignPoints == 0) "This is corner A" else "This is corner B")
                    }
                }
            } else if (steps.isNotEmpty()) {
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(steps) { i, s ->
                        val isDone = i in done
                        Column(
                            Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(20.dp))
                                .background(if (isDone) Palette.Mint.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.7f)).padding(14.dp),
                        ) {
                            Text("${s.index}. ${s.title}", color = if (isDone) Color.Black else Color.White, style = MaterialTheme.typography.titleMedium)
                            Text(s.detail, color = if (isDone) Color.Black else Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { onToggle(i) },
                                colors = ButtonDefaults.buttonColors(containerColor = if (isDone) Color.Black else Palette.Mint, contentColor = if (isDone) Color.White else Color.Black),
                            ) { Text(if (isDone) "Undo ✓" else "Done ✓") }
                        }
                    }
                }
            }
        }
    }
}

/** "Place it in AR": walk a piece around the real room at true size before buying or moving it. */
@Composable
fun PlaceScreen(
    controller: ArController,
    item: com.openroomai.app.model.Furniture,
    room: Room?,
    onAdd: (com.openroomai.app.model.Vec2, Float) -> Unit,
    onBack: () -> Unit,
) {
    val st by controller.state.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    remember(item.id) { controller.startPlacement(item, room); 0 }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ArView(controller, onError = { error = it }, modifier = Modifier.fillMaxSize())
        if (st.step == ScanStep.ALIGN || !st.placed) Crosshair()
        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f))) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Spacer(Modifier.width(10.dp))
                Text("${item.type.emoji} ${item.name} · ${(item.width * 100).toInt()}×${(item.depth * 100).toInt()} cm", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            MessagePill(error ?: st.message, warn = error != null || !st.tracking)
            if (st.step == ScanStep.PLACE && st.placeStatus.isNotEmpty()) {
                val col = when (st.placeOk) { true -> Palette.Mint; false -> Palette.Coral; null -> Palette.Amber }
                Text(
                    st.placeStatus, color = Color.Black, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(col).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            if (st.occlusion) MessagePill("👓 Depth occlusion on")
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(12.dp).clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (st.step == ScanStep.ALIGN) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledIconButton(onClick = { controller.undo() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Palette.SurfaceHi)) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = Color.White)
                    }
                    Button(onClick = { controller.addAtReticle() }, enabled = st.reticleOnFloor, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text(if (st.alignPoints == 0) "This is corner A" else "This is corner B")
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { controller.rotatePlacement(-15f) }) { Text("⟲ 15°") }
                    Button(onClick = { controller.placeAtReticle() }, enabled = st.reticleOnFloor, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text(if (st.placed) "Move here" else "Put it here")
                    }
                    FilledTonalButton(onClick = { controller.rotatePlacement(15f) }) { Text("15° ⟳") }
                }
                if (room != null) Button(
                    onClick = { controller.placedInPlan?.let { (c, r) -> onAdd(c, r) } ?: run { error = "Put it down first" } },
                    enabled = st.placed && st.placeOk != false,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Mint, contentColor = Color.Black),
                ) { Text("Add to the plan right here") }
            }
        }
    }
}
