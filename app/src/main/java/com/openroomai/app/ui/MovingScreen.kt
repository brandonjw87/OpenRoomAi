package com.openroomai.app.ui

import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openroomai.app.engine.Delivery
import com.openroomai.app.engine.DeliveryMethod
import com.openroomai.app.engine.MovePlanner
import com.openroomai.app.model.Furniture
import kotlin.math.roundToInt

/**
 * Feature: Moving Day. Bring furniture from your old room(s), let the optimizer place it, check
 * every piece gets through the door and round the hallway corner, and hand movers a checklist.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MovingScreen(vm: AppViewModel, onBack: () -> Unit) {
    val room = vm.room ?: return
    val ctx = LocalContext.current
    val rooms by vm.rooms.collectAsStateWithLifecycle()
    val others = remember(rooms, room.id) { rooms.filter { it.id != room.id && it.furniture.isNotEmpty() } }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val pool = others.flatMap { it.furniture }
    val picked = pool.filter { it.id in selected }

    var doorW by remember { mutableFloatStateOf(room.doors().minOfOrNull { it.width } ?: 0.8f) }
    var doorH by remember { mutableFloatStateOf(2.0f) }
    var hallOn by remember { mutableStateOf(true) }
    var hallW by remember { mutableFloatStateOf(1.0f) }

    LazyColumn(Modifier.fillMaxSize().background(Palette.Bg), contentPadding = PaddingValues(bottom = 48.dp)) {
        item {
            TopAppBar(
                title = { Column { Text("🚚 Moving Day"); Text(room.name, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg),
            )
        }

        // ---- 1. Bring furniture in
        item { SectionTitle("1 · Bring furniture in", "Pick pieces from your other rooms. They're copied here — the old room stays as it is.") }
        if (others.isEmpty()) item {
            Text("No other rooms with furniture yet. Scan or sketch your old room first and its furniture shows up here.",
                color = Palette.Muted, modifier = Modifier.padding(horizontal = 16.dp))
        }
        items(others, key = { it.id }) { other ->
            Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.Surface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(other.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        val all = other.furniture.all { it.id in selected }
                        OutlinedButton(onClick = {
                            selected = if (all) selected - other.furniture.map { it.id }.toSet() else selected + other.furniture.map { it.id }
                        }) { Text(if (all) "None" else "All") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        other.furniture.forEach { f ->
                            FilterChip(
                                selected = f.id in selected,
                                onClick = { selected = if (f.id in selected) selected - f.id else selected + f.id },
                                label = { Text("${f.type.emoji} ${f.name}") },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.Mint.copy(alpha = 0.2f), selectedLabelColor = Palette.Mint),
                            )
                        }
                    }
                }
            }
        }
        if (others.isNotEmpty()) item {
            val p = vm.arrivalProgress
            Column(Modifier.padding(16.dp)) {
                if (p != null) {
                    LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth())
                    Text("Finding a home for everything…", color = Palette.Muted)
                } else Button(onClick = { vm.planArrivals(picked) }, enabled = picked.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Plan the move-in (${picked.size} piece${if (picked.size == 1) "" else "s"})")
                }
            }
        }
        vm.arrivalPlan?.let { plan ->
            item {
                val res = plan.result
                val stuck = res.after.blockedPieces.filter { it.id in plan.itemIds }
                val ok = res.fits && stuck.isEmpty()
                Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.Surface), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (ok) "Everything fits 🎉" else "Tight squeeze 😬", style = MaterialTheme.typography.headlineSmall, color = if (ok) Palette.Mint else Palette.Amber)
                        Text("Openness with everything in: ${res.after.score} (room now: ${res.before.score}). ${plan.steps.size} step${if (plan.steps.size == 1) "" else "s"} to set it up.", color = Palette.Muted)
                        if (!ok) Text((res.violations + stuck.map { "${it.name} can't be reached" }).joinToString("; ") + ". Try leaving one piece behind.", color = Palette.Amber)
                        val noDoor = picked.filter { Delivery.check(it, doorW, doorH).method == DeliveryMethod.NO_FIT }
                        if (noDoor.isNotEmpty()) Text("⚠️ It fits in the room, but ${noDoor.joinToString { it.name }} won't get through a ${MovePlanner.fmt(doorW)} door — see the delivery check below.", color = Palette.Amber)
                        Box(Modifier.fillMaxWidth().aspectRatio(1.15f).clip(RoundedCornerShape(16.dp)).background(Palette.Bg)) {
                            PlanCanvas(room, res.layout, res.after, Modifier.fillMaxSize(), layers = Layers(flows = false), space = res.space?.after)
                        }
                        Text("Dashed amber = moving in.", color = Palette.Muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { vm.dismissArrivals() }, modifier = Modifier.weight(1f)) { Text("Discard") }
                            Button(onClick = { vm.acceptArrivals(); selected = emptySet() }, enabled = res.fits, modifier = Modifier.weight(1f)) { Text("Move them in") }
                        }
                    }
                }
            }
        }

        // ---- 2. Delivery check
        item { SectionTitle("2 · Will it get through the door?", "Checks each piece's real side profile, tilted the way movers do it, and the hallway corner.") }
        item {
            Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.Surface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(12.dp)) {
                    MeasureSlider("Door width", doorW, 0.6f..1.3f) { doorW = it }
                    MeasureSlider("Door height", doorH, 1.8f..2.6f) { doorH = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Hallway corner on the way in", Modifier.weight(1f))
                        Switch(hallOn, { hallOn = it })
                    }
                    if (hallOn) {
                        MeasureSlider("Hall width", hallW, 0.7f..2.0f) { hallW = it }
                        MeasureSlider("Ceiling", room.ceilingHeight, 2.0f..3.5f) { vm.setCeiling(it) }
                    }
                }
            }
        }
        val checkList = (room.furniture + picked).distinctBy { it.id }
        items(checkList, key = { "d" + it.id }) { f ->
            val v = remember(f, doorW, doorH, hallOn, hallW, room.ceilingHeight) {
                Delivery.check(f, doorW, doorH, if (hallOn) hallW else null, room.ceilingHeight)
            }
            DeliveryRow(f, v.method, v.detail, v.cornerOk, f.id in selected)
        }

        // ---- 3. Labels for movers
        item { SectionTitle("3 · Labels for the movers", "Stick a number on each piece; the list tells them where it goes.") }
        item {
            if (vm.steps.isEmpty()) Text("Tap \"Maximize my space\" or plan a move-in first — the moving checklist appears here.",
                color = Palette.Muted, modifier = Modifier.padding(horizontal = 16.dp))
            else Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.steps.forEach { s ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Surface).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("#${s.index}", style = MaterialTheme.typography.titleLarge, color = Palette.Amber, fontWeight = FontWeight.Black, modifier = Modifier.width(48.dp))
                        Column {
                            Text("${s.furniture.type.emoji} ${s.furniture.name}", style = MaterialTheme.typography.titleMedium)
                            Text(s.detail, color = Palette.Muted)
                        }
                    }
                }
                Button(onClick = {
                    val text = buildString {
                        append("🚚 Moving plan for ${room.name}\n\n")
                        vm.steps.forEach { append("#${it.index} ${it.furniture.name}: ${it.title}. ${it.detail}\n") }
                        append("\nPlanned with OpenRoom AI")
                    }
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Share moving checklist"))
                }, modifier = Modifier.fillMaxWidth()) { Text("Share the checklist") }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, sub: String) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(sub, color = Palette.Muted)
    }
}

@Composable
private fun MeasureSlider(label: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), color = Palette.Muted)
        Slider(v, { onChange((it * 100).roundToInt() / 100f) }, valueRange = range, modifier = Modifier.weight(1f))
        Text(MovePlanner.fmt(v), Modifier.width(60.dp))
    }
}

@Composable
private fun DeliveryRow(f: Furniture, method: DeliveryMethod, detail: String, cornerOk: Boolean?, incoming: Boolean) {
    val col = when (method) {
        DeliveryMethod.STRAIGHT, DeliveryMethod.TILT -> Palette.Mint
        DeliveryMethod.DOOR_OFF, DeliveryMethod.LEGS_OFF, DeliveryMethod.DOOR_AND_LEGS, DeliveryMethod.DISASSEMBLE -> Palette.Amber
        DeliveryMethod.NO_FIT -> Palette.Coral
    }
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Surface).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(f.type.emoji, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name + if (incoming) "  · moving in" else "", style = MaterialTheme.typography.titleMedium)
            Text("${method.icon} ${method.label}" + if (cornerOk == false) "  · ❌ corner" else "", color = col, style = MaterialTheme.typography.labelLarge)
            Text(detail, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
