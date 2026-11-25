package com.openroomai.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openroomai.app.engine.OpennessAnalyzer
import com.openroomai.app.model.Room

@Composable
fun HomeScreen(
    rooms: List<Room>,
    arSupported: Boolean?,
    onScan: () -> Unit,
    onSketch: (String, Float, Float) -> Unit,
    onDemo: () -> Unit,
    onOpen: (Room) -> Unit,
    onDelete: (Room) -> Unit,
    bigText: Boolean = false,
    onToggleBigText: () -> Unit = {},
    onDeviceCheck: () -> Unit = {},
) {
    var sketchDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Room?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().background(Palette.Bg).statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("OpenRoom AI", style = MaterialTheme.typography.displaySmall, color = Palette.Text, modifier = Modifier.weight(1f))
                    // Bigger type for grandparents (and anyone without their glasses).
                    OutlinedButton(onClick = onToggleBigText) { Text(if (bigText) "Aa ✓" else "Aa") }
                }
                Text("Same room. Same stuff. Way more space.", color = Palette.Muted)
            }
        }
        item {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF1B3A4B), Color(0xFF26215C))))
                    .clickable(enabled = arSupported != false, onClick = onScan)
                    .padding(22.dp),
            ) {
                Column {
                    Icon(Icons.Default.ViewInAr, null, tint = Palette.Mint, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Scan a room", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Text(
                        when (arSupported) {
                            false -> "This phone doesn't support ARCore — sketch your room instead, everything else still works."
                            else -> "Walk the room with your camera. We map the walls, doors and furniture, then rearrange it for maximum open space."
                        },
                        color = Color.White.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onScan, enabled = arSupported != false) {
                        Icon(Icons.Default.ViewInAr, null); Spacer(Modifier.width(8.dp)); Text("Start AR scan")
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { sketchDialog = true }, modifier = Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Default.Draw, null); Spacer(Modifier.width(6.dp)); Text("Sketch by size")
                }
                OutlinedButton(onClick = onDemo, modifier = Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Demo room")
                }
            }
        }
        if (rooms.isNotEmpty()) item { Text("Your rooms", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
        else item {
            Text(
                "No rooms yet. Scan one, or open the demo room to see how a cluttered living room goes from \"where do I walk\" to \"wow\".",
                color = Palette.Muted,
            )
        }
        items(rooms, key = { it.id }) { r -> RoomCard(r, { onOpen(r) }, { confirmDelete = r }) }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = onDeviceCheck) { Text("📲 Device check — test what this phone can do") }
                // Version 1.67. Six… seven. 🤲 (It means nothing. That's the joke.)
                Text("v1.67 · Open Area Edition · six… seven 🤲", color = Palette.Muted.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }

    if (sketchDialog) SketchDialog(onDismiss = { sketchDialog = false }) { n, w, l -> sketchDialog = false; onSketch(n, w, l) }
    confirmDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${r.name}?") },
            text = { Text("The furniture stays in your actual room. Probably.") },
            confirmButton = { TextButton(onClick = { onDelete(r); confirmDelete = null }) { Text("Delete", color = Palette.Coral) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun RoomCard(r: Room, onOpen: () -> Unit, onDelete: () -> Unit) {
    val rep = remember(r.id, r.updatedAt) { OpennessAnalyzer.analyze(r, r.furniture, 0.1f, withFlows = false) }
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = Palette.Surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Bg)) {
                PlanCanvas(r, r.furniture, null, Modifier.fillMaxSize(), layers = Layers(heatmap = false, openCircle = false, labels = false), compact = true)
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(r.name, style = MaterialTheme.typography.titleMedium)
                Text("${"%.1f".format(r.area)} m² · ${r.furniture.size} pieces" + if (r.optimized != null) " · plan ready" else "", color = Palette.Muted)
                Spacer(Modifier.height(6.dp))
                ScoreChip(rep.score)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = Palette.Muted) }
        }
    }
}

@Composable
fun ScoreChip(score: Int) {
    val c = scoreColor(score)
    Box(Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text("Openness $score", color = c, style = MaterialTheme.typography.labelLarge)
    }
}

fun scoreColor(score: Int) = when {
    score >= 70 -> Palette.Mint
    score >= 50 -> Palette.Amber
    else -> Palette.Coral
}

@Composable
private fun SketchDialog(onDismiss: () -> Unit, onCreate: (String, Float, Float) -> Unit) {
    var name by remember { mutableStateOf("Living room") }
    var w by remember { mutableStateOf("4.0") }
    var l by remember { mutableStateOf("3.5") }
    val wf = w.replace(',', '.').toFloatOrNull()
    val lf = l.replace(',', '.').toFloatOrNull()
    val ok = wf != null && lf != null && wf in 1.5f..20f && lf in 1.5f..20f
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sketch a room") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(w, { w = it }, label = { Text("Width (m)") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(l, { l = it }, label = { Text("Length (m)") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Text("You'll add doors, windows and furniture on the plan next.", color = Palette.Muted)
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onCreate(name, wf!!, lf!!) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
