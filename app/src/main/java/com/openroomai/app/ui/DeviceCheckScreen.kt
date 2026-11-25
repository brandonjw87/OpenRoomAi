package com.openroomai.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.openroomai.app.ar.VisionDetector
import com.openroomai.app.data.RoomRepository
import com.openroomai.app.engine.Effort
import com.openroomai.app.engine.FallSafety
import com.openroomai.app.engine.LayoutOptimizer
import com.openroomai.app.engine.OpenLook
import com.openroomai.app.engine.Season
import com.openroomai.app.engine.SunModel
import com.openroomai.app.sensors.Compass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Device Check: measures what this phone can do (AR depth, camera recognition speed, engine
 * timings) and produces a shareable report — real-device data to tune the app with.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceCheckScreen(onBack: () -> Unit, probeAr: Boolean = true) {
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var running by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val out = ArrayList<Pair<String, String>>()
        fun add(k: String, v: String) { out += k to v; rows = out.toList() }
        add("App", "OpenRoom AI ${appVersion(ctx)}")
        add("Phone", "${Build.MANUFACTURER} ${Build.MODEL}")
        add("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        add("CPU", "${Runtime.getRuntime().availableProcessors()} cores · ${Build.SUPPORTED_ABIS.joinToString()}")
        add("Memory page size", "${Os.sysconf(OsConstants._SC_PAGESIZE) / 1024} KB")
        add("App heap limit", "${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB")

        // ARCore.
        fun check() = if (!probeAr) ArCoreApk.Availability.UNKNOWN_ERROR else try { ArCoreApk.getInstance().checkAvailability(ctx) } catch (_: Throwable) { ArCoreApk.Availability.UNKNOWN_ERROR }
        var avail = check()
        repeat(10) { if (avail.isTransient) { delay(200); avail = check() } }
        add("ARCore", avail.name.lowercase().replace('_', ' '))
        val camOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (avail == ArCoreApk.Availability.SUPPORTED_INSTALLED && camOk) {
            try {
                val s = Session(ctx)
                add("Depth (smoothed)", yes(s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)))
                add("Raw depth", yes(s.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)))
                val configs = s.getSupportedCameraConfigs(CameraConfigFilter(s))
                add("Camera configs", "${configs.size}" + if (configs.any { it.depthSensorUsage == CameraConfig.DepthSensorUsage.REQUIRE_AND_USE }) " · has a depth sensor" else " · depth from motion")
                s.close()
            } catch (t: Throwable) {
                add("AR session", "couldn't open: ${t.javaClass.simpleName}")
            }
        } else if (!camOk) add("Depth", "start one AR scan first (needs camera permission), then run this again")

        add("Compass", yes(Compass(ctx).available))

        // Camera recognition speed.
        withContext(Dispatchers.Default) {
            val det = VisionDetector.create(ctx)
            if (det == null) add("Camera recognition", "not available on this device")
            else {
                val img = ByteArray(VisionDetector.SIZE * VisionDetector.SIZE * 3) { i -> ((i / 3) % 255).toByte() }
                det.detect(img) // warm-up
                var total = 0L
                repeat(3) { det.detect(img); total += det.lastMillis }
                add("Camera recognition", "${total / 3} ms per frame (EfficientDet-Lite0, LiteRT, CPU)")
                det.close()
            }

            // Engine timings on the demo room.
            val room = RoomRepository.demoRoom()
            fun <T> timed(block: () -> T): Pair<T, Long> { val t0 = SystemClock.elapsedRealtime(); val r = block(); return r to SystemClock.elapsedRealtime() - t0 }
            val (res, optMs) = timed { LayoutOptimizer(room, Effort.BALANCED, 7L).optimize(room.furniture) }
            add("Optimizer (demo, balanced)", "$optMs ms · openness ${res.before.score} → ${res.after.score}")
            val (fs, fsMs) = timed { FallSafety.audit(room, res.layout) }
            add("Easy-living check", "$fsMs ms · score ${fs.score}")
            val (_, sunMs) = timed { SunModel.simulate(room, res.layout, Season.TODAY, 14f) }
            add("Sunlight day simulation", "$sunMs ms")
            val (_, mirMs) = timed { OpenLook.mirrorSpots(room, res.layout) }
            add("Mirror finder", "$mirMs ms")
        }
        running = false
    }

    val report = remember(rows) { "OpenRoom AI device check\n" + rows.joinToString("\n") { (k, v) -> "$k: $v" } }

    LazyColumn(Modifier.fillMaxSize().background(Palette.Bg), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            TopAppBar(
                title = { Text("📲 Device check") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg),
            )
        }
        item {
            Text(
                "Measures what this phone can do so the app can be tuned for it. Share the report with whoever is improving the app.",
                color = Palette.Muted, modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        }
        items(rows) { (k, v) ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Surface).padding(12.dp)) {
                Text(k, Modifier.width(150.dp), color = Palette.Muted)
                Text(v, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("OpenRoom device check", report))
                }, enabled = !running, modifier = Modifier.weight(1f).height(50.dp)) { Text("Copy report") }
                Button(onClick = {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report) }, "Share device check"))
                }, enabled = !running, modifier = Modifier.weight(1f).height(50.dp)) { Text("Share report") }
            }
        }
    }
}

private fun yes(b: Boolean) = if (b) "yes ✓" else "no"

@Suppress("DEPRECATION")
private fun appVersion(ctx: Context): String = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
} catch (_: Exception) { "?" }
