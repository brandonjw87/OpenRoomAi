package com.openroomai.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openroomai.app.engine.MovePlanner
import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Geo
import kotlin.math.roundToInt

/**
 * Feature #3: "Will it fit?" — try a piece before you buy it. The optimizer re-plans the whole room
 * around the newcomer and tells you whether it fits, what it costs in openness, and what to move.
 * Also doubles as the "add furniture" picker for manual room editing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogDialog(vm: AppViewModel, onDismiss: () -> Unit, onPlaceInAr: ((Furniture) -> Unit)? = null) {
    val room = vm.room ?: return
    var type by remember { mutableStateOf(FurnitureType.SOFA) }
    var name by remember { mutableStateOf(type.label) }
    var w by remember { mutableStateOf(type.defW) }
    var d by remember { mutableStateOf(type.defD) }
    var h by remember { mutableStateOf(type.defH) }

    fun item() = Furniture(type = type, center = Geo.centroid(room.outline), width = w, depth = d, height = h, name = name.ifBlank { type.label })

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Palette.Bg).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Furniture", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
            }
            val fc = vm.fitCheck
            val fp = vm.fitProgress
            when {
                fp != null -> {
                    Text("Re-planning the whole room around your ${name.ifBlank { type.label }}…")
                    LinearProgressIndicator(progress = { fp }, Modifier.fillMaxWidth())
                }
                fc != null -> {
                    val newItem = fc.result.layout.first { it.id == fc.item.id }
                    val reachable = fc.result.after.blockedPieces.none { it.id == newItem.id }
                    val delta = fc.result.after.score - fc.result.before.score
                    val (verdict, color) = when {
                        !fc.result.fits || !reachable -> "Doesn't fit 😬" to Palette.Coral
                        delta < -12 -> "Fits, but it'll feel cramped" to Palette.Amber
                        else -> "It fits! 🎉" to Palette.Mint
                    }
                    Text(verdict, style = MaterialTheme.typography.displaySmall, color = color)
                    Text(
                        "Openness ${fc.result.before.score} → ${fc.result.after.score} (${if (delta >= 0) "+" else ""}$delta). " +
                            (if (fc.steps.size > 1) "${fc.steps.size - 1} existing piece(s) shift to make room." else "Nothing else needs to move."),
                        color = Palette.Muted,
                    )
                    if (fc.result.violations.isNotEmpty()) Text(fc.result.violations.joinToString("\n"), color = Palette.Coral)
                    Box(Modifier.fillMaxWidth().aspectRatio(1.1f).clip(RoundedCornerShape(20.dp)).background(Palette.Surface)) {
                        PlanCanvas(room, fc.result.layout, fc.result.after, Modifier.fillMaxSize(), layers = Layers(heatmap = true, openCircle = true), ghost = room.furniture, highlightId = newItem.id)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { vm.dismissFit() }, modifier = Modifier.weight(1f)) { Text("Try another size") }
                        Button(onClick = { vm.acceptFit(); onDismiss() }, enabled = fc.result.fits, modifier = Modifier.weight(1f)) { Text("Add to plan") }
                    }
                }
                else -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FurnitureType.entries.forEach { t ->
                            FilterChip(
                                selected = type == t,
                                onClick = { type = t; name = t.label; w = t.defW; d = t.defD; h = t.defH },
                                label = { Text("${t.emoji} ${t.label}") },
                            )
                        }
                    }
                    OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. \"IKEA Kivik 3-seat\")") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Size from the product page", style = MaterialTheme.typography.titleMedium)
                    Dim("Width", w, 0.3f, 3.5f) { w = it }
                    Dim("Depth", d, 0.3f, 2.5f) { d = it }
                    Dim("Height", h, 0.2f, 2.4f) { h = it }
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = { vm.checkFit(item()) }, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                        Icon(Icons.Default.ShoppingCart, null); Spacer(Modifier.width(8.dp)); Text("Will it fit? (I'm thinking of buying it)")
                    }
                    OutlinedButton(onClick = { vm.addFurniture(item()); onDismiss() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add to room (I already own it)")
                    }
                    if (onPlaceInAr != null) OutlinedButton(onClick = { onPlaceInAr(item()) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Icon(Icons.Default.ViewInAr, null); Spacer(Modifier.width(8.dp)); Text("📱 See it in AR at real size")
                    }
                    Text(
                        "Tip: measure the box it ships in too — doorways are often the real bottleneck. Your narrowest door here is " +
                            (room.doors().minOfOrNull { it.width }?.let { MovePlanner.fmt(it) } ?: "not marked yet") + ".",
                        color = Palette.Muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun Dim(label: String, v: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(60.dp), color = Palette.Muted)
        Slider(v, { onChange((it * 20).roundToInt() / 20f) }, valueRange = min..max, modifier = Modifier.weight(1f))
        Text(MovePlanner.fmt(v), Modifier.width(60.dp))
    }
}
