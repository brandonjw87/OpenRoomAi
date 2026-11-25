package com.openroomai.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.openroomai.app.engine.GifEncoder
import com.openroomai.app.engine.OpennessReport
import com.openroomai.app.model.Furniture
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Room
import java.io.File
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Feature: "Glow-up" GIF. Renders an orbiting 3D before → after transformation offscreen and
 * encodes it as a looping GIF that drops straight into any chat or social app.
 */
object GlowUp {
    const val SIZE = 400
    private const val FPS_DELAY_CS = 8 // 12.5 fps

    private data class Shot(val layout: List<Furniture>, val report: OpennessReport?, val yaw: Float, val caption: String, val score: Int, val accent: Color, val delayCs: Int)

    fun render(
        context: Context,
        room: Room,
        before: List<Furniture>,
        after: List<Furniture>,
        beforeReport: OpennessReport,
        afterReport: OpennessReport,
        onProgress: (Float) -> Unit = {},
    ): File {
        val shots = script(before, after, beforeReport, afterReport)
        val density = Density(1.6f)
        val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)
        val bmp = ImageBitmap(SIZE, SIZE)
        val canvas = Canvas(bmp)
        val scope = CanvasDrawScope()
        val b = Geo.bounds(room.outline)
        val target = Geo.centroid(room.outline).at(0.45f)
        val dist = maxOf(b.width, b.height) * 1.25f + 1.4f

        fun frame(s: Shot): IntArray {
            scope.draw(density, LayoutDirection.Ltr, canvas, Size(SIZE.toFloat(), SIZE.toFloat())) {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF1B2433), Palette.Bg)))
                val cam = Camera3D.orbit(target, s.yaw, 0.78f, dist, size.width, size.height)
                drawRoom3D(cam, room, s.layout, s.report, dollhouse = true)
                // Caption pill.
                val t = measurer.measure(s.caption, TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                val pad = 10f
                val w = t.size.width + pad * 2
                drawRoundRect(Color.Black.copy(alpha = 0.55f), Offset((size.width - w) / 2f, 14f), Size(w, t.size.height + pad), CornerRadius(30f))
                drawText(t, topLeft = Offset((size.width - t.size.width) / 2f, 14f + pad / 2f))
                // Score badge.
                val sc = measurer.measure("${s.score}", TextStyle(color = s.accent, fontSize = 22.sp, fontWeight = FontWeight.Black))
                val lbl = measurer.measure("openness", TextStyle(color = Palette.Muted, fontSize = 8.sp))
                drawText(sc, topLeft = Offset(size.width - sc.size.width - 16f, size.height - sc.size.height - lbl.size.height - 10f))
                drawText(lbl, topLeft = Offset(size.width - lbl.size.width - 16f, size.height - lbl.size.height - 8f))
                val wm = measurer.measure("OpenRoom AI", TextStyle(color = Palette.Mint.copy(alpha = 0.85f), fontSize = 9.sp, fontWeight = FontWeight.Bold))
                drawText(wm, topLeft = Offset(14f, size.height - wm.size.height - 10f))
            }
            val px = IntArray(SIZE * SIZE)
            bmp.readPixels(px)
            return px
        }

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "glowup-${room.name.replace(Regex("[^A-Za-z0-9]+"), "-").lowercase()}.gif")
        file.outputStream().buffered().use { os ->
            val enc = GifEncoder(SIZE, SIZE, os)
            // Palette from the first and last frames covers both looks.
            enc.buildPalette(listOf(frame(shots.first()), frame(shots[shots.size / 2]), frame(shots.last())))
            shots.forEachIndexed { i, s ->
                enc.addFrame(frame(s), s.delayCs)
                onProgress((i + 1f) / shots.size)
            }
            enc.finish()
        }
        return file
    }

    private fun script(before: List<Furniture>, after: List<Furniture>, rb: OpennessReport, ra: OpennessReport): List<Shot> {
        val out = ArrayList<Shot>()
        val y0 = 0.35f
        var yaw = y0
        val spin = 0.02f
        val bColor = scoreColor(rb.score)
        val aColor = scoreColor(ra.score)
        repeat(24) { k -> out += Shot(before, rb, yaw, "Before", rb.score, bColor, if (k == 0) 60 else FPS_DELAY_CS); yaw += spin }
        val morph = 20
        for (k in 1..morph) {
            val t = k / morph.toFloat()
            val e = (1 - cos(t * Math.PI.toFloat())) / 2f
            val score = (rb.score + (ra.score - rb.score) * e).roundToInt()
            out += Shot(lerpLayout(before, after, t), null, yaw, "✨ Rearranging…", score, scoreColor(score), FPS_DELAY_CS)
            yaw += spin * 1.5f
        }
        val delta = ra.score - rb.score
        repeat(28) { k -> out += Shot(after, ra, yaw, "After  (+$delta openness)", ra.score, aColor, if (k == 27) 150 else FPS_DELAY_CS); yaw += spin }
        return out
    }

    fun share(context: Context, file: File, room: Room, before: Int, after: Int) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/gif"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "My ${room.name} glow-up: openness $before → $after with zero new furniture 🛋️✨ (OpenRoom AI)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share your glow-up"))
    }
}
