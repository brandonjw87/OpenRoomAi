package com.openroomai.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openroomai.app.engine.DEFAULT_LATITUDE
import com.openroomai.app.engine.Season
import com.openroomai.app.engine.SunModel
import com.openroomai.app.model.Room
import com.openroomai.app.sensors.Compass
import com.openroomai.app.sensors.Places
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.roundToInt

/** Feature: sunlight simulator controls + findings. */
@Composable
fun SunCard(vm: AppViewModel, room: Room, hoursMode: Boolean, onHoursMode: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var calibrating by remember { mutableStateOf(false) }
    val sun = vm.sunReport
    val locate = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val loc = if (granted) Places.lastKnown(ctx) else null
        if (loc != null) vm.setLatitude(loc.latitude.toFloat())
        else vm.toast = if (granted) "No recent location fix — set your latitude with the slider." else "No problem — set your latitude with the slider."
    }

    Card(Modifier.padding(16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.Surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("☀️ Sunlight", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(selected = !hoursMode, onClick = { onHoursMode(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Moment") }
                    SegmentedButton(selected = hoursMode, onClick = { onHoursMode(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("All day") }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Season.entries.forEach { s ->
                    FilterChip(
                        selected = vm.sunSeason == s, onClick = { vm.sunSeason = s }, label = { Text(s.label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.Amber.copy(alpha = 0.25f), selectedLabelColor = Palette.Amber),
                    )
                }
            }
            if (!hoursMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(SunModel.fmtHour(vm.sunHour), Modifier.width(64.dp), style = MaterialTheme.typography.titleMedium)
                    Slider(vm.sunHour, { vm.sunHour = (it * 4).roundToInt() / 4f }, valueRange = 5f..20f, modifier = Modifier.weight(1f))
                }
                sun?.sunNow?.let { sp ->
                    Text(
                        if (!sp.up) "🌙 The sun is down at this hour."
                        else "Sun ${Math.toDegrees(sp.altitude.toDouble()).roundToInt()}° above the horizon, in the ${SunModel.compassName(Math.toDegrees(sp.azimuth.toDouble()).toFloat())} (solar time)",
                        color = Palette.Muted,
                    )
                }
            } else sun?.let {
                Text(
                    if (it.maxHours <= 0f) "No direct sun reaches the floor this day." else "Brighter yellow = more hours of direct sun (up to ${"%.1f".format(it.maxHours)} h).",
                    color = Palette.Muted,
                )
            }

            // Orientation + latitude.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    room.northDeg?.let { "🧭 Top of plan faces ${SunModel.compassName(it)} (${it.roundToInt()}°)" } ?: "🧭 Direction not set",
                    Modifier.weight(1f), color = if (room.northDeg == null) Palette.Amber else Palette.Text,
                )
                TextButton(onClick = { calibrating = true }) { Text(if (room.northDeg == null) "Set" else "Recalibrate") }
            }
            val lat = room.latitude ?: DEFAULT_LATITUDE
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📍 Latitude ${lat.roundToInt()}°${if (room.latitude == null) " (default)" else ""}", Modifier.weight(1f))
                TextButton(onClick = {
                    if (Places.hasPermission(ctx)) Places.lastKnown(ctx)?.let { vm.setLatitude(it.latitude.toFloat()) } ?: run { vm.toast = "No recent location fix — use the slider." }
                    else locate.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }) { Text("Use my location") }
            }
            Slider(lat, { vm.setLatitude(it.roundToInt().toFloat()) }, valueRange = -60f..66f)

            sun?.insights?.forEach { ins ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHi).padding(10.dp)) {
                    Text(ins.icon, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(ins.title, style = MaterialTheme.typography.titleMedium)
                        Text(ins.body, color = Palette.Muted)
                    }
                }
            }
        }
    }
    if (calibrating) CompassDialog(room, onSet = { vm.setNorth(it); calibrating = false }, onDismiss = { calibrating = false })
}

/** Point the camera at Wall A → we know which way the whole room faces. */
@Composable
fun CompassDialog(room: Room, onSet: (Float) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val compass = remember { Compass(ctx) }
    DisposableEffect(Unit) { compass.start(); onDispose { compass.stop() } }
    var bearing by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) { while (true) { bearing = compass.cameraBearing; delay(120) } }
    var manual by remember { mutableFloatStateOf(room.northDeg ?: 0f) }

    // Direction from the room toward Wall A, as a plan bearing relative to the plan's top.
    val toWallA = -room.inwardNormal(0)
    val relA = Math.toDegrees(atan2(toWallA.x, -toWallA.y).toDouble()).toFloat()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which way is north?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (compass.available) {
                    Text("Stand in the room, hold your phone upright and point the camera straight at Wall A.")
                    Text(
                        bearing?.let { "Camera points ${it.roundToInt()}° (${SunModel.compassName(it)})" } ?: "Hold the phone upright…",
                        style = MaterialTheme.typography.titleMedium, color = Palette.Mint,
                    )
                    Button(onClick = { bearing?.let { onSet(it - relA) } }, enabled = bearing != null, modifier = Modifier.fillMaxWidth()) {
                        Text("Wall A is in front of me")
                    }
                    Text("Tip: rooms scanned in AR set this automatically.", color = Palette.Muted)
                } else Text("This phone has no compass sensor — set it by hand.", color = Palette.Amber)
                Text("By hand: top of the plan faces ${manual.roundToInt()}° (${SunModel.compassName(manual)})")
                Slider(manual, { manual = it.roundToInt().toFloat() }, valueRange = 0f..359f)
            }
        },
        confirmButton = { TextButton(onClick = { onSet(manual) }) { Text("Use by-hand value") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
