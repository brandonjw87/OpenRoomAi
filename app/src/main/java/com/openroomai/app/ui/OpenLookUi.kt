package com.openroomai.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openroomai.app.engine.DesignGuidelines
import com.openroomai.app.engine.OpenLook
import com.openroomai.app.model.Furniture
import com.openroomai.app.model.Room
import kotlin.math.roundToInt

/**
 * Feature: Open-Look Studio — make it *look* bigger without moving a thing: ray-traced mirror
 * spots, light-reflective paint, and a design check (conversation, balance, alignment, focus).
 */
@Composable
fun OpenLookCard(
    vm: AppViewModel,
    room: Room,
    layout: List<Furniture>,
    showSpots: Boolean,
    onShowSpots: (Boolean) -> Unit,
    onOpen3D: () -> Unit,
) {
    val design = remember(room.outline, room.openings, layout) { DesignGuidelines.check(room, layout) }
    Card(Modifier.padding(16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.Surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🪞 Open-Look Studio", style = MaterialTheme.typography.titleMedium)
            Text("Designer tricks that make it look bigger without moving anything.", color = Palette.Muted)

            // Mirrors.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mirror finder", style = MaterialTheme.typography.titleMedium)
                    Text("Traced from the doorway and your sofa.", color = Palette.Muted)
                }
                Switch(showSpots, onShowSpots)
            }
            vm.mirrorSpots.forEachIndexed { i, sp ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHi).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(Palette.Sky), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = Color.Black, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Wall ${room.wallLabel(sp.mirror.wallIndex)} · ${com.openroomai.app.engine.Units.len(sp.mirror.width)} wide, ${com.openroomai.app.engine.Units.len(sp.mirror.bottom)}–${com.openroomai.app.engine.Units.len(sp.mirror.top)} up", style = MaterialTheme.typography.labelLarge)
                        Text(sp.reason, color = Palette.Muted)
                    }
                    TextButton(onClick = { vm.addMirror(sp) }) { Text("Hang") }
                }
            }
            if (vm.mirrorSpots.isEmpty()) Text("No good mirror spot yet — mark a door and a window on the plan.", color = Palette.Muted)
            if (room.mirrors.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                room.mirrors.forEach { m ->
                    FilterChip(selected = true, onClick = { vm.removeMirror(m.id) }, label = { Text("🪞 Wall ${room.wallLabel(m.wallIndex)} ✕") })
                }
            }

            // Paint.
            Text("Wall colour", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OpenLook.PAINTS.forEach { p ->
                    val sel = room.paint?.name == p.name
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { vm.setPaint(if (sel) null else p) }) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(Color(p.argb))
                                .border(if (sel) 3.dp else 1.dp, if (sel) Palette.Mint else Palette.Muted.copy(alpha = 0.4f), CircleShape),
                        )
                        Text("LRV ${p.lrv}", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            room.paint?.let { Text("${it.name}: ${OpenLook.paintVerdict(it)}", color = if (it.lrv >= 50) Palette.Text else Palette.Amber) }
                ?: Text("Tap a colour to try it on the 3D walls (and your glow-up GIF).", color = Palette.Muted)

            // Design check.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Design check", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${design.score}/100", color = scoreColor(design.score), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            }
            MiniBar("Conversation", design.conversation)
            MiniBar(if (design.heavyWall >= 0) "Balance (heavy toward Wall ${room.wallLabel(design.heavyWall)})" else "Visual balance", 1f - design.imbalance)
            MiniBar("Squared to the walls", design.alignment)
            MiniBar("Facing ${design.focalName ?: "a focal point"}", design.emphasis)
            TextButton(onClick = onOpen3D) { Text("See it in 3D →") }
        }
    }
}

@Composable
private fun MiniBar(label: String, v: Float) {
    Column {
        Row { Text(label, Modifier.weight(1f), color = Palette.Muted); Text("${(v * 100).roundToInt()}%", color = Palette.Text) }
        Spacer(Modifier.height(3.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.SurfaceHi)) {
            Box(Modifier.fillMaxWidth(v.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (v >= 0.7f) Palette.Mint else Palette.Amber))
        }
    }
}
