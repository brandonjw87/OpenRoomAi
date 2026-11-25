package com.openroomai.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Window
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openroomai.app.engine.DeclutterIdea
import com.openroomai.app.engine.DesignGuidelines
import com.openroomai.app.engine.Lanes
import com.openroomai.app.engine.LayoutGoal
import com.openroomai.app.engine.MovePlanner
import com.openroomai.app.engine.MoveStep
import com.openroomai.app.engine.Nudge
import com.openroomai.app.engine.OpenSpaceReport
import com.openroomai.app.engine.OptimizeResult
import com.openroomai.app.engine.SpaceGoal
import com.openroomai.app.engine.SpaceNote
import com.openroomai.app.engine.Tier
import com.openroomai.app.engine.Units
import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Opening
import com.openroomai.app.model.OpeningType
import com.openroomai.app.model.Room
import com.openroomai.app.model.Vec2
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class EditTool { SELECT, DOOR, WINDOW }

/** What the plan shows: the room today, a before/after wipe, or the plan. */
private enum class PlanView(val label: String) { BEFORE("Before"), COMPARE("Compare"), AFTER("After") }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlanScreen(
    vm: AppViewModel,
    arSupported: Boolean?,
    onBack: () -> Unit,
    onPreview: () -> Unit,
    onOpen3D: () -> Unit = {},
    onOpenMoving: () -> Unit = {},
    onPlaceInAr: (Furniture) -> Unit = {},
) {
    val room = vm.room ?: return
    val result = vm.result
    @Suppress("UNUSED_VARIABLE") val units = vm.units // re-render every measurement when units change
    var view by remember(room.id) { mutableStateOf(PlanView.BEFORE) }
    var layers by remember { mutableStateOf(Layers()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var tool by remember { mutableStateOf(EditTool.SELECT) }
    var catalog by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var showOpenLook by remember { mutableStateOf(false) }
    var showDeclutter by remember { mutableStateOf(false) }
    var showMirrorSpots by remember { mutableStateOf(false) }
    var wipe by remember { mutableFloatStateOf(0.5f) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()

    LaunchedEffect(result) { view = if (result != null) PlanView.AFTER else PlanView.BEFORE }
    LaunchedEffect(vm.glowUpFile) {
        val f = vm.glowUpFile ?: return@LaunchedEffect
        vm.glowUpFile = null
        GlowUp.share(ctx, f, room, result?.before?.score ?: 0, result?.after?.score ?: 0)
    }
    val after = view == PlanView.AFTER && result != null
    val compare = view == PlanView.COMPARE && result != null
    val planLayout = if (after && result != null) result.layout else room.furniture
    LaunchedEffect(showOpenLook, room.outline, room.openings, room.mirrors, after, result) {
        if (showOpenLook) vm.refreshOpenLook(planLayout)
    }
    LaunchedEffect(layers.sunlight, after, room, result, vm.sunSeason, vm.sunHour) {
        if (layers.sunlight) vm.refreshSun(planLayout)
    }
    val morph by animateFloatAsState(if (after) 1f else 0f, tween(900), label = "morph")
    val moving = morph in 0.01f..0.99f
    val preview = if (!after && !compare) vm.previewNudge else null
    val shownLayout = when {
        preview != null -> preview.applyTo(room.furniture)
        result != null -> lerpLayout(room.furniture, result.layout, morph)
        else -> room.furniture
    }
    val shownSpace: OpenSpaceReport? = when {
        after || compare -> result?.space?.after
        preview != null -> vm.previewSpace
        else -> vm.space
    }
    val baseSpace: OpenSpaceReport? = when {
        after || compare -> result?.space?.before
        preview != null -> vm.space
        else -> null
    }
    val selectedItem = room.furniture.firstOrNull { it.id == selected }

    LazyColumn(Modifier.fillMaxSize().background(Palette.Bg), contentPadding = PaddingValues(bottom = 48.dp)) {
        item {
            TopAppBar(
                title = { Text(room.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { renaming = true }) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { vm.undo() }, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = { vm.redo() }, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    IconButton(onClick = {
                        scope.launch { sharePlan(ctx, room, layer.toImageBitmap(), vm.steps, result?.before?.score ?: vm.report?.score, result?.after?.score, result?.space) }
                    }) { Icon(Icons.Default.Share, "Share") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg),
            )
        }

        item {
            val shownScore = if (after || compare) result?.after else vm.report
            SpaceHeader(
                score = shownScore?.score,
                label = shownScore?.label,
                scoreFrom = if (after || compare) result?.before?.score else null,
                cur = shownSpace,
                base = baseSpace,
                previewing = preview != null,
            )
        }

        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                        PlanView.entries.forEachIndexed { i, v ->
                            SegmentedButton(
                                selected = view == v, enabled = v == PlanView.BEFORE || result != null,
                                onClick = { view = v }, shape = SegmentedButtonDefaults.itemShape(i, PlanView.entries.size),
                            ) { Text(v.label, maxLines = 1) }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = onOpen3D, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.Default.ViewInAr, null); Spacer(Modifier.width(4.dp)); Text("3D")
                    }
                }
                Spacer(Modifier.height(10.dp))
                val b = Geo.bounds(room.outline)
                val aspect = (b.width / b.height).coerceIn(0.6f, 1.6f)
                Box(
                    Modifier.fillMaxWidth().aspectRatio(aspect).heightIn(max = 520.dp).clip(RoundedCornerShape(24.dp)).background(Palette.Surface)
                        .drawWithContent {
                            // Record into a layer so "Share" can grab the plan as an image; software canvases can't host layers.
                            if (drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                                layer.record { this@drawWithContent.drawContent() }; drawLayer(layer)
                            } else drawContent()
                        },
                ) {
                    if (compare && result != null) {
                        PlanCanvas(
                            room, result.layout, result.after, Modifier.fillMaxSize().drawWithContent { clipRect(left = size.width * wipe) { this@drawWithContent.drawContent() } },
                            layers = layers.copy(heatmap = false, sunlight = false), space = result.space?.after,
                        )
                        PlanCanvas(
                            room, room.furniture, vm.report, Modifier.fillMaxSize().drawWithContent { clipRect(right = size.width * wipe) { this@drawWithContent.drawContent() } },
                            layers = layers.copy(heatmap = false, sunlight = false), space = result.space?.before,
                        )
                        Canvas(Modifier.fillMaxSize()) {
                            val x = size.width * wipe
                            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4f, cap = StrokeCap.Round)
                            drawCircle(Color.White, 14f, Offset(x, size.height / 2f))
                            drawCircle(Palette.Bg, 14f, Offset(x, size.height / 2f), style = Stroke(3f))
                        }
                        WipeTag("Before", Modifier.align(Alignment.TopStart))
                        WipeTag("After", Modifier.align(Alignment.TopEnd))
                    } else PlanCanvas(
                        room = room,
                        layout = shownLayout,
                        report = if (moving || preview != null) null else if (after) result?.after else vm.report,
                        modifier = Modifier.fillMaxSize(),
                        layers = layers,
                        sun = if (layers.sunlight && !moving) vm.sunReport else null,
                        mirrorSpots = if (showMirrorSpots && showOpenLook) vm.mirrorSpots else emptyList(),
                        space = if (moving) null else shownSpace,
                        nudge = preview,
                        ghost = if (after || preview != null) room.furniture else null,
                        selectedId = selected,
                        interactive = !after && preview == null,
                        onSelect = { if (tool == EditTool.SELECT) selected = it },
                        onMove = { id, p -> vm.moveFurniture(id, p) },
                        onTapEmpty = { p ->
                            if (tool != EditTool.SELECT) {
                                addOpeningAt(room, p, if (tool == EditTool.DOOR) OpeningType.DOOR else OpeningType.WINDOW)?.let { vm.addOpening(it) }
                                tool = EditTool.SELECT
                            }
                        },
                    )
                    if (tool != EditTool.SELECT) Text(
                        "Tap on a wall to place the ${if (tool == EditTool.DOOR) "door" else "window"}",
                        Modifier.align(Alignment.TopCenter).padding(10.dp).clip(RoundedCornerShape(50)).background(Palette.Amber).padding(horizontal = 12.dp, vertical = 5.dp),
                        color = Color.Black, style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (preview != null) Row(
                    Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Amber.copy(alpha = 0.16f)).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Previewing", color = Palette.Amber, style = MaterialTheme.typography.labelMedium)
                        Text(preview.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { vm.togglePreview(preview) }) { Text("Hide") }
                    Button(onClick = { vm.applyNudge(preview) }, enabled = vm.progress == null, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Apply") }
                }
                if (compare) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Before", color = Palette.Muted, style = MaterialTheme.typography.labelLarge)
                    Slider(wipe, { wipe = it }, Modifier.weight(1f).padding(horizontal = 8.dp))
                    Text("After", color = Palette.Muted, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LayerChip("Open zone", layers.openZone) { layers = layers.copy(openZone = !layers.openZone) }
                    LayerChip("Flow", layers.flows) { layers = layers.copy(flows = !layers.flows) }
                    LayerChip("Clearance map", layers.heatmap) {
                        // Clearance and sun maps fight for the same pixels; show one at a time.
                        layers = if (layers.heatmap) layers.copy(heatmap = false) else layers.copy(heatmap = true, sunlight = false)
                    }
                    LayerChip("View from door", layers.sightlines) { layers = layers.copy(sightlines = !layers.sightlines) }
                    LayerChip("☀️ Sunlight", layers.sunlight) {
                        layers = if (layers.sunlight) layers.copy(sunlight = false) else layers.copy(sunlight = true, heatmap = false)
                    }
                }
                if (layers.flows) FlowLegend()
                if (layers.heatmap) ClearanceLegend()
            }
        }
        if (layers.sunlight) item {
            SunCard(vm, room, layers.sunHours) { layers = layers.copy(sunHours = it) }
        }

        // ---- Micro-nudges for the room as it is.
        if (!after && !compare) item { NudgeCard(vm, enabled = vm.progress == null) }

        // ---- One-tap space goals.
        item { GoalsCard(vm, room, result) }

        if (result != null) {
            item {
                val dBefore = remember(room.furniture, room.openings) { DesignGuidelines.check(room, room.furniture).score }
                val dAfter = remember(result.layout, room.openings) { DesignGuidelines.check(room, result.layout).score }
                DiffCard(result, vm.steps, dBefore, dAfter)
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onPreview, enabled = arSupported != false && vm.steps.isNotEmpty(), modifier = Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Default.ViewInAr, null); Spacer(Modifier.width(6.dp)); Text("See it in AR")
                    }
                    OutlinedButton(onClick = { vm.acceptLayout(); view = PlanView.BEFORE }, enabled = vm.steps.isNotEmpty(), modifier = Modifier.weight(1f).height(52.dp)) {
                        Text("I moved it all ✓")
                    }
                }
            }
            item {
                val gp = vm.glowUpProgress
                if (gp != null) Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    LinearProgressIndicator(progress = { gp }, Modifier.fillMaxWidth())
                    Text("Rolling the cameras… 🎬", color = Palette.Muted)
                } else FilledTonalButton(
                    onClick = { vm.makeGlowUp() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(52.dp),
                ) { Text("🎬  Share the before/after GIF") }
            }
            if (vm.steps.isNotEmpty()) {
                item {
                    Text(
                        "Your plan · ${vm.doneSteps.size}/${vm.steps.size} done",
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                    )
                    Text("Ordered so nothing ends up stuck behind something else.", color = Palette.Muted, modifier = Modifier.padding(horizontal = 16.dp))
                }
                itemsIndexed(vm.steps) { i, s -> StepCard(s, i in vm.doneSteps) { vm.toggleStep(i) } }
            } else item {
                Text("Nothing to move: this is already the most open layout we could find.", color = Palette.Muted, modifier = Modifier.padding(16.dp))
            }
            if (result.violations.isNotEmpty()) item {
                Text("⚠️ Heads-up: " + result.violations.joinToString("; ") + ". Try removing a piece or Max open center.", color = Palette.Amber, modifier = Modifier.padding(16.dp))
            }
            item { Text("Why it'll feel bigger", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
            itemsIndexed(vm.insights) { _, ins ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface).padding(14.dp)) {
                    Text(ins.icon, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(ins.title, style = MaterialTheme.typography.titleMedium)
                        Text(ins.body, color = Palette.Muted)
                    }
                }
            }
        }
        if (!after && !compare) vm.space?.let { sp ->
            item { NotesCard(sp.notes) { id -> if (id != null) selected = id } }
        }

        // ---- Edit tools (the room as it is).
        if (!after && !compare) item {
            Card(Modifier.padding(16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Edit the room", style = MaterialTheme.typography.titleMedium)
                    Text("Drag pieces to match reality (snaps to the ${if (Units.imperial) "inch" else "centimeter"}). Tap one to change what it is or its size.", color = Palette.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { catalog = true }, contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Default.Add, null); Text("Furniture") }
                        OutlinedButton(onClick = { tool = EditTool.DOOR; selected = null }, contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Default.DoorFront, null); Text("Door") }
                        OutlinedButton(onClick = { tool = EditTool.WINDOW; selected = null }, contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Default.Window, null); Text("Window") }
                    }
                    if (room.openings.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        room.openings.forEach { o ->
                            FilterChip(
                                selected = false, onClick = { vm.removeOpening(o.id) },
                                label = { Text((if (o.type == OpeningType.DOOR) "🚪" else "🪟") + " Wall ${room.wallLabel(o.wallIndex)} ✕") },
                            )
                        }
                    }
                    AnimatedVisibility(selectedItem != null) {
                        selectedItem?.let { f -> ItemEditor(f, vm, onDelete = { vm.removeFurniture(f.id); selected = null }) }
                    }
                }
            }
        }

        // ---- More tools, out of the way until wanted.
        item {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                Text("More tools", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolButton("🛒 Will it fit?") { catalog = true }
                    ToolButton("🚚 Moving Day") { onOpenMoving() }
                    ToolButton(if (showOpenLook) "🪞 Open-Look Studio ✓" else "🪞 Open-Look Studio") { showOpenLook = !showOpenLook }
                    ToolButton(if (showDeclutter) "🧹 Declutter coach ✓" else "🧹 Declutter coach") { showDeclutter = !showDeclutter }
                    ToolButton("🧊 3D view") { onOpen3D() }
                }
            }
        }
        if (showOpenLook) item {
            OpenLookCard(vm, room, planLayout, showMirrorSpots, { showMirrorSpots = it }, onOpen3D)
        }
        if (showDeclutter) {
            item {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
                    Text("🧹 Declutter coach", style = MaterialTheme.typography.titleLarge)
                    Text("Ranked by how much more open the room gets. Rearranging is free; these cost a bit of letting go.", color = Palette.Muted)
                    if (vm.declutter.isEmpty()) Text("Nothing here is worth letting go of. Nice.", color = Palette.Muted, modifier = Modifier.padding(top = 6.dp))
                }
            }
            itemsIndexed(vm.declutter, key = { _, d -> d.furnitureId + d.title }) { _, idea -> DeclutterCard(idea, busy = vm.progress != null) { vm.applyIdea(idea) } }
        }
    }

    if (catalog) CatalogDialog(vm, onDismiss = { catalog = false; vm.dismissFit() }, onPlaceInAr = if (arSupported != false) { item -> catalog = false; onPlaceInAr(item) } else null)
    if (renaming) {
        var name by remember { mutableStateOf(room.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename room") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(name.ifBlank { room.name }); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

private fun progressLine(goal: SpaceGoal?, p: Float) = when (goal) {
    SpaceGoal.QUICK_WINS -> if (p < 0.5f) "Trying every inch…" else "Ranking the quick wins…"
    SpaceGoal.WIDEST_FLOW -> when {
        p < 0.3f -> "Walking every route from the door…"
        p < 0.7f -> "Widening the tight spots…"
        else -> "Straightening the walkways…"
    }
    else -> when {
        p < 0.2f -> "Measuring twice, lifting zero times…"
        p < 0.45f -> "Asking the sofa where it really wants to live…"
        p < 0.7f -> "Negotiating with the bookshelf…"
        p < 0.9f -> "Consolidating the open floor…"
        else -> "Fine-tuning to the inch…"
    }
}

@Composable
private fun WipeTag(text: String, modifier: Modifier) {
    Text(
        text, modifier.padding(10.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 4.dp),
        color = Color.White, style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun LayerChip(label: String, on: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = on, onClick = onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.Mint.copy(alpha = 0.2f), selectedLabelColor = Palette.Mint),
    )
}

@Composable
private fun ToolButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) { Text(label) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegendRow(items: List<Pair<Color, String>>) {
    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (c, t) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c))
                Spacer(Modifier.width(4.dp))
                Text(t, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun FlowLegend() = LegendRow(
    listOf(
        tierColor(Tier.WIDE) to "${Units.width(Lanes.GENEROUS)}+ wide",
        tierColor(Tier.CLEAR) to "${Units.width(Lanes.CLEAR)}–${Units.width(Lanes.GENEROUS)} clear",
        tierColor(Tier.TIGHT) to "${Units.width(Lanes.PINCH)}–${Units.width(Lanes.CLEAR)} tight",
        tierColor(Tier.PINCH) to "under ${Units.width(Lanes.PINCH)}",
    ),
)

@Composable
private fun ClearanceLegend() = LegendRow(
    listOf(
        Palette.Coral to "under ${Units.width(2 * com.openroomai.app.engine.SQUEEZE_RADIUS)} gap",
        Palette.Amber to "${Units.width(2 * com.openroomai.app.engine.SQUEEZE_RADIUS)}–${Units.width(2 * com.openroomai.app.engine.COMFORT_RADIUS)}",
        Palette.Mint to "open floor",
    ),
)

fun tierColor(t: Tier) = when (t) {
    Tier.WIDE -> Palette.Mint
    Tier.CLEAR -> Palette.Sky
    Tier.TIGHT -> Palette.Amber
    Tier.PINCH -> Palette.Coral
}

// ------------------------------------------------------------------ header

@Composable
private fun SpaceHeader(score: Int?, label: String?, scoreFrom: Int?, cur: OpenSpaceReport?, base: OpenSpaceReport?, previewing: Boolean) {
    val anim by animateIntAsState(score ?: 0, tween(900), label = "score")
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                val col = scoreColor(anim)
                Canvas(Modifier.fillMaxSize()) {
                    drawArc(Palette.SurfaceHi, 135f, 270f, false, style = Stroke(10f, cap = StrokeCap.Round), topLeft = Offset(6f, 6f), size = Size(size.width - 12f, size.height - 12f))
                    drawArc(col, 135f, 270f * anim / 100f, false, style = Stroke(10f, cap = StrokeCap.Round), topLeft = Offset(6f, 6f), size = Size(size.width - 12f, size.height - 12f))
                }
                Text(if (score == null) "…" else "$anim", style = MaterialTheme.typography.titleLarge, color = col, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (previewing) "Openness · previewing a nudge" else "Openness", color = Palette.Muted)
                Text(label ?: "Measuring…", style = MaterialTheme.typography.headlineSmall)
                when {
                    scoreFrom != null && score != null -> {
                        val d = score - scoreFrom
                        Text(if (d > 0) "$scoreFrom → $score (+$d)" else "Already near the best layout", color = if (d > 0) Palette.Mint else Palette.Muted, style = MaterialTheme.typography.titleMedium)
                    }
                    cur != null -> Text("${(cur.usablePct * 100).roundToInt()}% of the floor is usable open space", color = Palette.Muted)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val usable = cur?.usableArea
            MetricTile(
                "Usable floor", usable?.let { Units.area(it) } ?: "…", null,
                delta = if (base != null && usable != null) areaDelta(usable - base.usableArea) else null,
                good = base == null || usable == null || usable >= base.usableArea - 1e-3f,
                color = Palette.Text, modifier = Modifier.weight(1f),
            )
            val z = cur?.zone
            MetricTile(
                "Open zone", z?.let { Units.dims(it.long, it.short) } ?: "none", z?.let { Units.area(it.area) },
                delta = if (base != null && cur != null) areaDelta((z?.area ?: 0f) - (base.zone?.area ?: 0f)) else null,
                good = base == null || (z?.area ?: 0f) >= (base.zone?.area ?: 0f) - 1e-3f,
                color = Palette.Mint, modifier = Modifier.weight(1f),
            )
            val n = cur?.narrowest
            val bn = base?.narrowest
            MetricTile(
                "Tightest spot", n?.let { Units.width(it) } ?: if (cur == null) "…" else "—", n?.let { Lanes.tier(it).label },
                delta = if (n != null && bn != null && abs(n - bn) >= Units.INCH * 0.5f) (if (n > bn) "+" else "−") + Units.width(abs(n - bn)) else null,
                good = n == null || bn == null || n >= bn,
                color = n?.let { tierColor(Lanes.tier(it)) } ?: Palette.Muted, modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun areaDelta(d: Float): String? = if (abs(d) < 0.01f) null else Units.areaGain(d)

@Composable
private fun MetricTile(label: String, value: String, sub: String?, delta: String?, good: Boolean, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Palette.Surface).padding(horizontal = 10.dp, vertical = 9.dp)) {
        Text(label, color = Palette.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, color = Palette.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        if (delta != null) Text(delta, color = if (good) Palette.Mint else Palette.Coral, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

// ------------------------------------------------------------------ micro-nudges

@Composable
private fun NudgeCard(vm: AppViewModel, enabled: Boolean) {
    Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.Surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚡ Micro-Nudges", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (vm.nudging) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Text("Small moves, measured to the inch. Tap one to preview it on the plan.", color = Palette.Muted)
            val list = vm.nudges
            when {
                list.isEmpty() && vm.nudging -> Text("Measuring every inch…", color = Palette.Muted)
                list.isEmpty() -> Text("No small move frees up meaningful space right now. Try a space goal below.", color = Palette.Muted)
                else -> list.forEach { n ->
                    NudgeRow(n, previewing = vm.previewNudge?.key == n.key, enabled = enabled, onPreview = { vm.togglePreview(n) }, onApply = { vm.applyNudge(n) })
                }
            }
        }
    }
}

@Composable
private fun NudgeRow(n: Nudge, previewing: Boolean, enabled: Boolean, onPreview: () -> Unit, onApply: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (previewing) Palette.Amber.copy(alpha = 0.14f) else Palette.SurfaceHi)
            .clickable(onClick = onPreview).padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
    ) {
        Text(n.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(n.detail, color = Palette.Mint, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPreview) { Text(if (previewing) "Hide" else "Preview") }
            Button(onClick = onApply, enabled = enabled, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Apply") }
        }
    }
}

// ------------------------------------------------------------------ space goals

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalsCard(vm: AppViewModel, room: Room, result: OptimizeResult?) {
    Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.Surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Free up space", style = MaterialTheme.typography.titleMedium)
            Text("One tap. Pick what matters most.", color = Palette.Muted)
            val busy = vm.progress != null
            SpaceGoal.entries.forEach { g ->
                GoalTile(
                    g, selected = result != null && result.spaceGoal == g, running = vm.runningGoal == g,
                    enabled = !busy && room.furniture.isNotEmpty(),
                ) { vm.optimize(g) }
            }
            val p = vm.progress
            if (p != null) {
                LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth())
                Text(progressLine(vm.runningGoal, p), color = Palette.Muted)
            }
            if (room.furniture.isEmpty()) Text("Add some furniture first: an empty room is already 100% open. 😉", color = Palette.Muted)
            Text("Room use", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LayoutGoal.entries.forEach { g ->
                    FilterChip(
                        selected = room.goal == g, onClick = { vm.setGoal(g) },
                        label = { Text("${g.emoji} ${g.label}") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.Violet.copy(alpha = 0.3f), selectedLabelColor = Color.White),
                    )
                }
            }
            Text(room.goal.blurb, color = Palette.Muted)
        }
    }
}

@Composable
private fun GoalTile(g: SpaceGoal, selected: Boolean, running: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (selected) Palette.Violet.copy(alpha = 0.25f) else Palette.SurfaceHi)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(g.emoji, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(g.label, style = MaterialTheme.typography.titleMedium, color = if (enabled || selected) Palette.Text else Palette.Muted)
            Text(g.blurb, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        when {
            running -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            selected -> Icon(Icons.Default.CheckCircle, null, tint = Palette.Mint)
            else -> Icon(Icons.Default.ChevronRight, null, tint = if (enabled) Palette.Mint else Palette.Muted)
        }
    }
}

// ------------------------------------------------------------------ before / after

@Composable
private fun DiffCard(res: OptimizeResult, steps: List<MoveStep>, designBefore: Int, designAfter: Int) {
    Card(Modifier.padding(16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.Surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Before → After · ${res.spaceGoal.emoji} ${res.spaceGoal.label}", style = MaterialTheme.typography.titleMedium)
            val d = res.space
            if (d != null) {
                val ub = d.before.usableArea; val ua = d.after.usableArea
                val top = maxOf(ub, ua, 0.01f)
                BarRow("Usable open floor", Units.area(ub), Units.area(ua), Units.areaGain(d.usableGain), d.usableGain >= -0.005f, ub / top, ua / top)
                val zb = d.before.zone; val za = d.after.zone
                val zTop = maxOf(zb?.area ?: 0f, za?.area ?: 0f, 0.01f)
                BarRow(
                    "Biggest open zone", zb?.let { Units.dims(it.long, it.short) } ?: "none", za?.let { Units.dims(it.long, it.short) } ?: "none",
                    Units.areaGain(d.zoneGain), d.zoneGain >= -0.005f, (zb?.area ?: 0f) / zTop, (za?.area ?: 0f) / zTop,
                )
                val nb = d.before.narrowest; val na = d.after.narrowest
                if (nb != null && na != null) BarRow(
                    "Narrowest walkway", Units.width(nb), Units.width(na), (if (na >= nb) "+" else "−") + Units.width(abs(na - nb)), na >= nb - 0.005f,
                    (nb / Lanes.GENEROUS).coerceAtMost(1f), (na / Lanes.GENEROUS).coerceAtMost(1f),
                )
                Row {
                    Text("Walkways under ${Units.width(Lanes.CLEAR)}", Modifier.weight(1f))
                    Text("${d.before.tightLanes} → ", color = Palette.Muted)
                    Text("${d.after.tightLanes}", color = if (d.after.tightLanes < d.before.tightLanes) Palette.Mint else Palette.Text, fontWeight = FontWeight.Bold)
                }
                val total = d.after.corridors.size
                if (total > 0 && d.before.lanes.size != d.after.lanes.size) Row {
                    Text("Places you can walk to", Modifier.weight(1f))
                    Text("${d.before.lanes.size} → ", color = Palette.Muted)
                    Text("${d.after.lanes.size} of $total", color = if (d.after.lanes.size > d.before.lanes.size) Palette.Mint else Palette.Coral, fontWeight = FontWeight.Bold)
                }
                if (d.widened.isNotEmpty()) Column {
                    Text("Walkways widened")
                    d.widened.take(3).forEach { (name, a, b) ->
                        Text("to ${name.removePrefix("the ")}: ${Units.width(a)} → ${Units.width(b)}", color = Palette.Mint, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Row {
                Text("Openness score", Modifier.weight(1f))
                Text("${res.before.score} → ", color = Palette.Muted)
                Text("${res.after.score}", color = if (res.after.score > res.before.score) Palette.Mint else Palette.Text, fontWeight = FontWeight.Bold)
            }
            Row {
                Text("Design guidelines", Modifier.weight(1f))
                Text("$designBefore → ", color = Palette.Muted)
                Text("$designAfter", color = if (designAfter > designBefore) Palette.Mint else Palette.Text, fontWeight = FontWeight.Bold)
            }
            val real = steps.filter { !it.temporary }
            val micro = real.count { MovePlanner.isMicro(it) }
            val big = real.size - micro
            Text(
                when {
                    real.isEmpty() -> "No moves needed."
                    big == 0 -> "$micro micro-nudge${if (micro == 1) "" else "s"}. Nothing heavy."
                    micro == 0 -> "$big move${if (big == 1) "" else "s"}."
                    else -> "$micro micro-nudge${if (micro == 1) "" else "s"} + $big bigger move${if (big == 1) "" else "s"}."
                },
                color = Palette.Muted,
            )
        }
    }
}

@Composable
private fun BarRow(label: String, a: String, b: String, delta: String, good: Boolean, fa: Float, fb: Float) {
    val anim by animateFloatAsState(fb, tween(1000), label = label)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Text(delta, color = if (good) Palette.Mint else Palette.Coral, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        }
        Row {
            Text("$a → ", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            Text(b, color = Palette.Text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.SurfaceHi)) {
            Box(Modifier.fillMaxWidth(anim.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Mint.copy(alpha = 0.8f)))
            Box(Modifier.fillMaxWidth(fa.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Muted.copy(alpha = 0.6f)))
        }
    }
}

@Composable
private fun StepCard(s: MoveStep, done: Boolean, onToggle: () -> Unit) {
    Card(
        onClick = onToggle,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (done) Palette.Mint.copy(alpha = 0.12f) else Palette.Surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                tint = if (done) Palette.Mint else Palette.Muted, modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${s.index}. ${s.title}", style = MaterialTheme.typography.titleMedium, color = if (s.temporary) Palette.Amber else Palette.Text)
                Text(s.detail, color = Palette.Muted)
            }
            Text(s.furniture.type.emoji, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

// ------------------------------------------------------------------ what's limiting the space

@Composable
private fun NotesCard(notes: List<SpaceNote>, onPick: (String?) -> Unit) {
    Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.Surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("What's limiting the space", style = MaterialTheme.typography.titleMedium)
            if (notes.isEmpty()) Text("✓ Nothing is pinching the flow. Every walkway is clear and every piece is in reach.", color = Palette.Mint)
            notes.take(6).forEach { n ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(n.furnitureId) }.padding(vertical = 2.dp)) {
                    Text(n.icon, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(n.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(n.body, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ editing

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemEditor(f: Furniture, vm: AppViewModel, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.SurfaceHi).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${f.type.emoji} ${f.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.updateFurniture(f.id, tag = null) { it.withPose(it.center, it.rotationDeg + 90f) } }) { Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate") }
            IconButton(onClick = { vm.updateFurniture(f.id, tag = null) { it.copy(locked = !it.locked) } }) {
                Icon(if (f.locked) Icons.Default.Lock else Icons.Default.LockOpen, if (f.locked) "Unlock" else "Lock in place", tint = if (f.locked) Palette.Amber else Palette.Muted)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = Palette.Coral) }
        }
        if (f.locked) Text("Locked: nothing will move it (built-ins, radiators, the cat's spot).", color = Palette.Amber, style = MaterialTheme.typography.bodyMedium)
        f.seenAs?.let { Text("📷 Camera saw: $it" + if (f.color != null) " · colour taken from the photo" else "", color = Palette.Sky, style = MaterialTheme.typography.bodyMedium) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FurnitureType.entries.forEach { t ->
                FilterChip(
                    selected = f.type == t,
                    onClick = {
                        vm.updateFurniture(f.id, tag = null) {
                            // Keep measured sizes for scanned pieces, but adopt the new type's identity.
                            it.copy(type = t, name = if (it.name == it.type.label || it.name.startsWith(it.type.label)) t.label else it.name, height = if (it.height == it.type.defH) t.defH else it.height)
                        }
                    },
                    label = { Text("${t.emoji} ${t.label}") },
                )
            }
        }
        DimSlider("Width", f.width, 0.3f, 3.5f) { v -> vm.updateFurniture(f.id, tag = "w:${f.id}") { it.copy(width = v) } }
        DimSlider("Depth", f.depth, 0.3f, 2.5f) { v -> vm.updateFurniture(f.id, tag = "d:${f.id}") { it.copy(depth = v) } }
        DimSlider("Height", f.height, 0.2f, 2.4f) { v -> vm.updateFurniture(f.id, tag = "h:${f.id}") { it.copy(height = v) } }
    }
}

@Composable
private fun DimSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(56.dp), color = Palette.Muted)
        Slider(value, { onChange(Units.snap(it).coerceIn(min, max)) }, valueRange = min..max, modifier = Modifier.weight(1f))
        Text(Units.len(value), Modifier.width(64.dp))
    }
}

/** Tap on the plan near a wall → a door or window centered there. */
private fun addOpeningAt(room: Room, p: Vec2, type: OpeningType): Opening? {
    val walls = room.walls()
    val wi = walls.indices.minByOrNull { walls[it].distanceTo(p) } ?: return null
    if (walls[wi].distanceTo(p) > 0.6f) return null
    val width = if (type == OpeningType.DOOR) 0.85f else 1.2f
    val w = walls[wi]
    val half = width / 2f / w.length
    val t = w.closestT(p).coerceIn(half, 1f - half)
    return Opening(type = type, wallIndex = wi, t = t, width = width.coerceAtMost(w.length))
}

@Composable
private fun DeclutterCard(idea: DeclutterIdea, busy: Boolean, onApply: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(idea.icon, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(idea.title, style = MaterialTheme.typography.titleMedium)
            Text(idea.body, color = Palette.Muted)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("+${idea.gain}", color = Palette.Mint, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            TextButton(onClick = onApply, enabled = !busy) { Text("Apply") }
        }
    }
}
