package com.openroomai.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import com.openroomai.app.engine.MoveStep
import com.openroomai.app.engine.SpaceDiff
import com.openroomai.app.engine.Units
import com.openroomai.app.model.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Shares the plan image plus a text moving checklist (great for roommates doing the lifting). */
suspend fun sharePlan(ctx: Context, room: Room, image: ImageBitmap, steps: List<MoveStep>, before: Int?, after: Int?, space: SpaceDiff? = null) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "open-area-${room.name.replace(Regex("[^A-Za-z0-9]+"), "-").lowercase()}.png")
        f.outputStream().use { image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false).compress(Bitmap.CompressFormat.PNG, 100, it) }
        f
    }
    val text = buildString {
        append("🏠 ${room.name} — ${Units.area(room.area)}\n")
        if (before != null && after != null) append("Openness score: $before → $after\n")
        else if (before != null) append("Openness score: $before\n")
        if (space != null) {
            append("Usable open floor: ${Units.area(space.before.usableArea)} → ${Units.area(space.after.usableArea)} (${Units.areaGain(space.usableGain)})\n")
            val a = space.before.narrowest; val b = space.after.narrowest
            if (a != null && b != null) append("Narrowest walkway: ${Units.width(a)} → ${Units.width(b)}\n")
            space.after.zone?.let { append("Biggest open zone: ${Units.dims(it.long, it.short)} (${Units.area(it.area)})\n") }
        }
        if (steps.isNotEmpty()) {
            append("\nMoving plan:\n")
            steps.forEach { append("${it.index}. ${it.title} — ${it.detail}\n") }
        }
        append("\nPlanned with Open Area AI")
    }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, "${room.name} layout plan")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Share plan"))
}
