package com.openroomai.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openroomai.app.engine.FallSafetyReport
import com.openroomai.app.engine.LayoutGoal
import com.openroomai.app.engine.Severity

/**
 * Easy living for older adults (built for an 80-year-old grandparent's home): walker-width
 * paths, trip hazards on the natural walking lines, getting out of bed, a seat by the door,
 * and a lit night path. General guidance — not a substitute for an occupational therapist.
 */
@Composable
fun EasyLivingCard(
    report: FallSafetyReport?,
    goal: LayoutGoal,
    showOnPlan: Boolean,
    onShowOnPlan: (Boolean) -> Unit,
    busy: Boolean,
    onOptimize: () -> Unit,
) {
    Card(Modifier.padding(16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.Surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🧓 Easy living check", style = MaterialTheme.typography.titleMedium)
                    Text("For grandparents — and anyone who'd rather not trip.", color = Palette.Muted)
                }
                report?.let {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${it.score}", style = MaterialTheme.typography.headlineSmall, color = scoreColor(it.score), fontWeight = FontWeight.Black)
                        Text(
                            when {
                                it.score >= 85 -> "Safe & easy"
                                it.score >= 60 -> "A few fixes"
                                else -> "Needs work"
                            },
                            color = Palette.Muted, style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show walking lines & night lights on the plan", Modifier.weight(1f))
                Switch(showOnPlan, onShowOnPlan)
            }
            if (report == null) {
                Text("Checking paths…", color = Palette.Muted)
                return@Column
            }
            report.good.forEach { g -> Text("✓ $g", color = Palette.Mint) }
            report.items.forEach { it ->
                val col = when (it.severity) {
                    Severity.FIX_FIRST -> Palette.Coral
                    Severity.IMPROVE -> Palette.Amber
                    Severity.TIP -> Palette.Sky
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHi).padding(10.dp)) {
                    Text(it.icon, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                Text(it.severity.label, color = col, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(it.title, style = MaterialTheme.typography.titleMedium)
                        Text(it.body, color = Palette.Muted)
                    }
                }
            }
            if (goal != LayoutGoal.EASY_LIVING || report.score < 100) Button(
                onClick = onOptimize, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (goal == LayoutGoal.EASY_LIVING) "Re-plan for easy living" else "🧓 Rearrange for easy living") }
            Text(
                "General guidance based on CDC fall-prevention, accessible-route and CPSC advice — not a substitute for an occupational therapist's home visit.",
                color = Palette.Muted.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Colour for the night path overlay. */
val NightGlow = Color(0xFFFFE082)
