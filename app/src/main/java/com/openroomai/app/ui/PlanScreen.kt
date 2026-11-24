package com.openroomai.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Window
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openroomai.app.engine.Effort
import com.openroomai.app.engine.MovePlanner
import com.openroomai.app.engine.OpennessReport
import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Opening
import com.openroomai.app.model.OpeningType
import com.openroomai.app.model.Room
import com.openroomai.app.model.Vec2
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class EditTool { SELECT, DOOR, WINDOW }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlanScreen(vm: AppViewModel, arSupported: Boolean?, onBack: () -> Unit, onPreview: () -> Unit) {
    val room = vm.room ?: return
    val result = vm.result
    var showAfter by remember(room.id) { mutableStateOf(false) }
    var layers by remember { mutableStateOf(Layers()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var tool by remember { mutableStateOf(EditTool.SELECT) }
    var catalog by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()

    LaunchedEffect(result) { if (result != null) showAfter = true }
    val after = showAfter && result != null
    val morph by animateFloatAsState(if (after) 1f else 0f, tween(900), label = "morph")
    val shownLayout = if (result != null) lerpLayout(room.furniture, result.layout, morph) else room.furniture
    val shownReport: OpennessReport? = if (after) result!!.after else vm.report
    val selectedItem = room.furniture.firstOrNull { it.id == selected }

    LazyColumn(Modifier.fillMaxSize().background(Palette.Bg), contentPadding = PaddingValues(bottom = 48.dp)) {
        item {
            TopAppBar(
                title = { Text(room.name, maxLines = 1, modifier = Modifier.clickable { renaming = true }) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rename") }
                    IconButton(onClick = {
                        scope.launch { sharePlan(ctx, room, layer.toImageBitmap(), vm.steps, result?.before?.score ?: vm.report?.score, result?.after?.score) }
                    }) { Icon(Icons.Default.Share, "Share") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg),
            )
        }

        item { ScoreHeader(vm.report, result?.after, after) }

        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = !after, onClick = { showAfter = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Now") }
                    SegmentedButton(selected = after, enabled = result != null, onClick = { showAfter = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Optimized ✨") }
                }
                Spacer(Modifier.height(10.dp))
                val b = Geo.bounds(room.outline)
                val aspect = (b.width / b.height).coerceIn(0.6f, 1.6f)
                Box(
                    Modifier.fillMaxWidth().aspectRatio(aspect).heightIn(max = 520.dp).clip(RoundedCornerShape(24.dp)).background(Palette.Surface)
                        .drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) },
                ) {
                    PlanCanvas(
                        room = room,
                        layout = shownLayout,
                        report = if (morph in 0.01f..0.99f) null else shownReport,
                        modifier = Modifier.fillMaxSize(),
                        layers = layers,
                        ghost = if (after) room.furniture else null,
                        selectedId = selected,
                        interactive = !after,
                        onSelect = { if (tool == EditTool.SELECT) selected = it },
                        onMove = { id, p -> vm.updateFurniture(id) { it.withPose(p, it.rotationDeg) } },
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
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LayerChip("Openness heatmap", layers.heatmap) { layers = layers.copy(heatmap = !layers.heatmap) }
                    LayerChip("Walk paths", layers.flows) { layers = layers.copy(flows = !layers.flows) }
                    LayerChip("View from door", layers.sightlines) { layers = layers.copy(sightlines = !layers.sightlines) }
                    LayerChip("Open circle", layers.openCircle) { layers = layers.copy(openCircle = !layers.openCircle) }
                }
                if (layers.heatmap) Legend()
            }
        }

        // ---- Edit tools (current layout only)
        if (!after) item {
            Card(Modifier.padding(16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Edit the room", style = MaterialTheme.typography.titleMedium)
                    Text("Drag pieces to match reality. Tap a piece to change what it is or its size.", color = Palette.Muted)
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

        // ---- Optimize
        item {
            Card(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("How much heavy lifting?", style = MaterialTheme.typography.titleMedium)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Effort.entries.forEachIndexed { i, e ->
                            SegmentedButton(selected = vm.effort == e, onClick = { vm.effort = e }, shape = SegmentedButtonDefaults.itemShape(i, Effort.entries.size)) {
                                Text(e.label, maxLines = 1)
                            }
                        }
                    }
                    Text(vm.effort.blurb, color = Palette.Muted)
                    val p = vm.progress
                    if (p != null) {
                        LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth())
                        Text(funnyProgress(p), color = Palette.Muted)
                    } else {
                        Button(onClick = { vm.optimize() }, enabled = room.furniture.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(54.dp)) {
                            Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp))
                            Text(if (result == null) "Maximize my space" else "Try another arrangement")
                        }
                    }
                    if (room.furniture.isEmpty()) Text("Add some furniture first — an empty room is already 100% open. 😉", color = Palette.Muted)
                    OutlinedButton(onClick = { catalog = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.ShoppingCart, null); Spacer(Modifier.width(8.dp)); Text("Will it fit? Test a new purchase")
                    }
                }
            }
        }

        if (result != null) {
            item { Breakdown(result.before, result.after) }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onPreview, enabled = arSupported != false && vm.steps.isNotEmpty(), modifier = Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Default.ViewInAr, null); Spacer(Modifier.width(6.dp)); Text("See it in AR")
                    }
                    OutlinedButton(onClick = { vm.acceptLayout(); showAfter = false }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text("I moved it all ✓")
                    }
                }
            }
            if (vm.steps.isNotEmpty()) {
                item {
                    Text(
                        "Your moving plan · ${vm.doneSteps.size}/${vm.steps.size} done",
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                    )
                    Text("Ordered so nothing ends up stuck behind something else.", color = Palette.Muted, modifier = Modifier.padding(horizontal = 16.dp))
                }
                itemsIndexed(vm.steps) { i, s ->
                    val done = i in vm.doneSteps
                    Card(
                        onClick = { vm.toggleStep(i) },
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
            } else item {
                Text("Nothing to move — your layout is already the most open we could find. Try the tips below.", color = Palette.Muted, modifier = Modifier.padding(16.dp))
            }
            if (result.violations.isNotEmpty()) item {
                Text("⚠️ Heads-up: " + result.violations.joinToString("; ") + ". Try removing a piece or \"Full redesign\".", color = Palette.Amber, modifier = Modifier.padding(16.dp))
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
        } else if (vm.report != null) {
            item { CurrentDiagnosis(vm.report!!) }
        }
    }

    if (catalog) CatalogDialog(vm, onDismiss = { catalog = false; vm.dismissFit() })
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

private fun funnyProgress(p: Float) = when {
    p < 0.2f -> "Measuring twice, lifting zero times…"
    p < 0.45f -> "Asking the sofa where it really wants to live…"
    p < 0.7f -> "Negotiating with the bookshelf…"
    p < 0.9f -> "Clearing a path to the fridge…"
    else -> "Fluffing the pillows…"
}

@Composable
private fun LayerChip(label: String, on: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = on, onClick = onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.Mint.copy(alpha = 0.2f), selectedLabelColor = Palette.Mint),
    )
}

@Composable
private fun Legend() {
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(Palette.Coral to "< 60 cm squeeze", Palette.Amber to "60–90 cm tight", Palette.Mint to "open floor").forEach { (c, t) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c))
                Spacer(Modifier.width(4.dp))
                Text(t, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ScoreHeader(now: OpennessReport?, best: OpennessReport?, showingAfter: Boolean) {
    val shown = if (showingAfter && best != null) best else now
    val target = shown?.score ?: 0
    val anim by animateIntAsState(target, tween(900), label = "score")
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            val col = scoreColor(anim)
            Canvas(Modifier.fillMaxSize()) {
                drawArc(Palette.SurfaceHi, 135f, 270f, false, style = Stroke(12f, cap = StrokeCap.Round), topLeft = Offset(8f, 8f), size = Size(size.width - 16f, size.height - 16f))
                drawArc(col, 135f, 270f * anim / 100f, false, style = Stroke(12f, cap = StrokeCap.Round), topLeft = Offset(8f, 8f), size = Size(size.width - 16f, size.height - 16f))
            }
            Text("$anim", style = MaterialTheme.typography.headlineSmall, color = col, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Openness score", color = Palette.Muted)
            Text(shown?.label ?: "Analyzing…", style = MaterialTheme.typography.headlineSmall)
            if (now != null && best != null) {
                val d = best.score - now.score
                Text(
                    if (d > 0) "${now.score} → ${best.score}  (+$d)" else "Already near the best layout",
                    color = if (d > 0) Palette.Mint else Palette.Muted, style = MaterialTheme.typography.titleMedium,
                )
            } else if (shown != null) {
                Text("${(shown.footprintPct * 100).roundToInt()}% of the floor is furniture", color = Palette.Muted)
            }
        }
    }
}

@Composable
private fun Breakdown(before: OpennessReport, after: OpennessReport) {
    Card(Modifier.padding(16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.Surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("What changes", style = MaterialTheme.typography.titleMedium)
            BarRow("Biggest open spot", "${"%.1f".format(before.clearDiameter)} m", "${"%.1f".format(after.clearDiameter)} m", (before.clearDiameter / 4f).coerceAtMost(1f), (after.clearDiameter / 4f).coerceAtMost(1f))
            BarRow("Walkable floor", pct(before.reachablePct), pct(after.reachablePct), before.reachablePct, after.reachablePct)
            BarRow("Seen from the door", pct(before.visibilityPct), pct(after.visibilityPct), before.visibilityPct, after.visibilityPct)
            BarRow("Daylight unblocked", pct(before.lightPct), pct(after.lightPct), before.lightPct, after.lightPct)
            BarRow("Pieces you can reach", pct(before.accessPct), pct(after.accessPct), before.accessPct, after.accessPct)
        }
    }
}

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"

@Composable
private fun BarRow(label: String, a: String, b: String, fa: Float, fb: Float) {
    val anim by animateFloatAsState(fb, tween(1000), label = label)
    Column {
        Row { Text(label, Modifier.weight(1f)); Text("$a → ", color = Palette.Muted); Text(b, color = if (fb > fa + 0.005f) Palette.Mint else Palette.Text, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.SurfaceHi)) {
            Box(Modifier.fillMaxWidth(anim.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Mint.copy(alpha = 0.8f)))
            Box(Modifier.fillMaxWidth(fa.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Muted.copy(alpha = 0.6f)))
        }
    }
}

@Composable
private fun CurrentDiagnosis(r: OpennessReport) {
    Card(Modifier.padding(16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.Surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Diagnosis", style = MaterialTheme.typography.titleMedium)
            Text("• Biggest open spot: ${MovePlanner.fmt(r.clearDiameter)} across", color = Palette.Muted)
            Text("• Walkable floor: ${pct(r.reachablePct)}  · Seen from door: ${pct(r.visibilityPct)}", color = Palette.Muted)
            if (r.blockedPieces.isNotEmpty()) Text("• Hard to reach: " + r.blockedPieces.joinToString { it.name }, color = Palette.Amber)
            if (r.windowBlockers.isNotEmpty()) Text("• Blocking daylight: " + r.windowBlockers.joinToString { it.name }, color = Palette.Amber)
            if (r.tightSpots.isNotEmpty()) Text("• ${r.tightSpots.size} tight squeeze points on your walking paths (turn on \"Walk paths\")", color = Palette.Muted)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemEditor(f: Furniture, vm: AppViewModel, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.SurfaceHi).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${f.type.emoji} ${f.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.updateFurniture(f.id) { it.withPose(it.center, it.rotationDeg + 90f) } }) { Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate") }
            IconButton(onClick = { vm.updateFurniture(f.id) { it.copy(locked = !it.locked) } }) {
                Icon(if (f.locked) Icons.Default.Lock else Icons.Default.LockOpen, if (f.locked) "Unlock" else "Lock in place", tint = if (f.locked) Palette.Amber else Palette.Muted)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = Palette.Coral) }
        }
        if (f.locked) Text("Locked: the optimizer won't move it (built-ins, radiators, the cat's spot).", color = Palette.Amber, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FurnitureType.entries.forEach { t ->
                FilterChip(
                    selected = f.type == t,
                    onClick = {
                        vm.updateFurniture(f.id) {
                            // Keep measured sizes for scanned pieces, but adopt the new type's identity.
                            it.copy(type = t, name = if (it.name == it.type.label || it.name.startsWith(it.type.label)) t.label else it.name, height = if (it.height == it.type.defH) t.defH else it.height)
                        }
                    },
                    label = { Text("${t.emoji} ${t.label}") },
                )
            }
        }
        DimSlider("Width", f.width, 0.3f, 3.5f) { v -> vm.updateFurniture(f.id) { it.copy(width = v) } }
        DimSlider("Depth", f.depth, 0.3f, 2.5f) { v -> vm.updateFurniture(f.id) { it.copy(depth = v) } }
        DimSlider("Height", f.height, 0.2f, 2.4f) { v -> vm.updateFurniture(f.id) { it.copy(height = v) } }
    }
}

@Composable
private fun DimSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(56.dp), color = Palette.Muted)
        Slider(value, { onChange((it * 20).roundToInt() / 20f) }, valueRange = min..max, modifier = Modifier.weight(1f))
        Text(MovePlanner.fmt(value), Modifier.width(58.dp))
    }
}

/** Tap on the plan near a wall → a 0.85 m door or 1.2 m window centered there. */
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
