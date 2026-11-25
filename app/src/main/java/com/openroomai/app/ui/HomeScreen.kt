package com.openroomai.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openroomai.app.engine.Units
import com.openroomai.app.model.Room

const val APP_NAME = "Open Area AI"
const val APP_VERSION = "2.0"

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
    stats: Map<String, RoomStat> = emptyMap(),
    imperial: Boolean = Units.imperial,
    onToggleUnits: () -> Unit = {},
) {
    var sketchDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Room?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().background(Palette.Bg).statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.padding(bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onToggleUnits, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(if (imperial) "ft · in" else "m · cm") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = onToggleBigText, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(if (bigText) "Aa ✓" else "Aa") }
                }
                Text(APP_NAME, style = MaterialTheme.typography.displaySmall, color = Palette.Text, fontWeight = FontWeight.Black)
                Text("Same room. Same stuff. Way more open floor.", color = Palette.Muted)
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
                            false -> "This phone doesn't support ARCore. Sketch your room instead; everything else works."
                            else -> "Walk the room with your camera. We map walls, doors and furniture, then find you more open floor."
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
                OutlinedButton(onClick = { sketchDialog = true }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Icon(Icons.Default.Draw, null); Spacer(Modifier.width(6.dp)); Text("Sketch by size")
                }
                OutlinedButton(onClick = onDemo, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Demo room")
                }
            }
        }
        if (rooms.isEmpty()) item { HowItWorks() }
        else item { Text("Your rooms", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
        items(rooms, key = { it.id }) { r -> RoomCard(r, stats[r.id], { onOpen(r) }, { confirmDelete = r }) }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = onDeviceCheck) { Text("📲 Device check: test what this phone can do") }
                Text("$APP_NAME · v$APP_VERSION", color = Palette.Muted.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }

    if (sketchDialog) SketchDialog(imperial, onDismiss = { sketchDialog = false }) { n, w, l -> sketchDialog = false; onSketch(n, w, l) }
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

/** First-run: three steps, no lecture. */
@Composable
private fun HowItWorks() {
    Card(colors = CardDefaults.cardColors(containerColor = Palette.Surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("How it works", style = MaterialTheme.typography.titleLarge)
            HowStep(1, "Map the room", "Scan with AR or sketch it by size. Doors, windows and furniture go on the plan.")
            HowStep(2, "See the open floor", "Your biggest open zone, usable square footage and every walkway's real width.")
            HowStep(3, "Free up space", "Apply 3-inch micro-nudges, or one tap for Max open center, Widest flow paths or Quick wins.")
            Text("No room handy? Open the demo room and watch a cluttered living room open up.", color = Palette.Muted)
        }
    }
}

@Composable
private fun HowStep(n: Int, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Palette.Mint.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Text("$n", color = Palette.Mint, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, color = Palette.Muted)
        }
    }
}

@Composable
private fun RoomCard(r: Room, stat: RoomStat?, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = Palette.Surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Bg)) {
                PlanCanvas(r, r.furniture, null, Modifier.fillMaxSize(), layers = Layers(heatmap = false, flows = false, openZone = false, labels = false), compact = true)
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(r.name, style = MaterialTheme.typography.titleMedium)
                Text("${Units.area(r.area)} · ${r.furniture.size} pieces" + if (r.optimized != null) " · plan ready" else "", color = Palette.Muted)
                if (stat != null) {
                    Text("${Units.area(stat.usableArea)} usable open floor", color = Palette.Mint, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    ScoreChip(stat.score)
                }
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

/** Room size in the user's units; the plan is always stored in meters. */
@Composable
private fun SketchDialog(imperial: Boolean, onDismiss: () -> Unit, onCreate: (String, Float, Float) -> Unit) {
    val unit = if (imperial) "ft" else "m"
    val toMeters = if (imperial) Units.FOOT else 1f
    var name by remember { mutableStateOf("Living room") }
    var w by remember { mutableStateOf(if (imperial) "13" else "4.0") }
    var l by remember { mutableStateOf(if (imperial) "11.5" else "3.5") }
    val wf = w.replace(',', '.').toFloatOrNull()?.times(toMeters)
    val lf = l.replace(',', '.').toFloatOrNull()?.times(toMeters)
    val ok = wf != null && lf != null && wf in 1.5f..20f && lf in 1.5f..20f
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sketch a room") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(w, { w = it }, label = { Text("Width ($unit)") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(l, { l = it }, label = { Text("Length ($unit)") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Text(
                    if (ok || (w.isBlank() && l.isBlank())) "You'll add doors, windows and furniture on the plan next."
                    else "Each side needs to be between ${Units.len(1.5f)} and ${Units.len(20f)}.",
                    color = if (ok) Palette.Muted else Palette.Amber,
                )
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onCreate(name, wf!!, lf!!) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
