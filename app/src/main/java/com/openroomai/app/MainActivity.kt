package com.openroomai.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ar.core.ArCoreApk
import com.openroomai.app.ar.ArController
import com.openroomai.app.ui.AppViewModel
import com.openroomai.app.ui.HomeScreen
import com.openroomai.app.ui.OpenRoomTheme
import com.openroomai.app.ui.Palette
import com.openroomai.app.ui.PlanScreen
import com.openroomai.app.ui.DeviceCheckScreen
import com.openroomai.app.ui.MovingScreen
import com.openroomai.app.ui.PlaceScreen
import com.openroomai.app.ui.PreviewScreen
import com.openroomai.app.ui.Room3DScreen
import com.openroomai.app.ui.ScanScreen
import com.openroomai.app.ui.Screen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val ar = ArController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OpenRoomTheme { App(vm, ar) } }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) ar.destroy()
    }
}

@Composable
private fun App(vm: AppViewModel, ar: ArController) {
    val ctx = LocalContext.current
    val rooms by vm.rooms.collectAsStateWithLifecycle()
    var pendingAr by remember { mutableStateOf<Screen?>(null) }
    val prefs = remember { ctx.getSharedPreferences("openroom", android.content.Context.MODE_PRIVATE) }
    // Big text for older eyes: scales every text in the app by 30 %.
    var bigText by remember { mutableStateOf(prefs.getBoolean("big_text", false)) }
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current

    // ARCore availability can be "checking" for a moment on first launch.
    val arSupported by produceState<Boolean?>(null) {
        repeat(10) {
            val a = ArCoreApk.getInstance().checkAvailability(ctx)
            if (!a.isTransient) { value = a.isSupported; return@produceState }
            delay(250)
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val target = pendingAr
        pendingAr = null
        if (granted && target != null) vm.go(target)
        else if (!granted) Toast.makeText(ctx, "Camera access is needed to scan. Sketch mode works without it.", Toast.LENGTH_LONG).show()
    }

    fun withCamera(target: Screen) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) vm.go(target)
        else { pendingAr = target; permission.launch(Manifest.permission.CAMERA) }
    }

    BackHandler(enabled = vm.screen != Screen.Home) { vm.back() }

    LaunchedEffect(vm.toast) {
        vm.toast?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.toast = null }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(baseDensity.density, baseDensity.fontScale * if (bigText) 1.3f else 1f),
    ) {
    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        AnimatedContent(vm.screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screens") { s ->
            when (s) {
                Screen.Home -> HomeScreen(
                    rooms = rooms,
                    arSupported = arSupported,
                    onScan = { withCamera(Screen.Scan) },
                    onSketch = { n, w, l -> vm.createSketch(n, w, l) },
                    onDemo = { vm.openDemo() },
                    onOpen = { vm.open(it) },
                    onDelete = { vm.delete(it) },
                    bigText = bigText,
                    onToggleBigText = { bigText = !bigText; prefs.edit().putBoolean("big_text", bigText).apply() },
                    onDeviceCheck = { vm.go(Screen.DeviceCheck) },
                )
                Screen.Scan -> ScanScreen(ar, onDone = { vm.saveScanned(it) }, onBack = { vm.back() })
                Screen.Plan -> PlanScreen(vm, arSupported, onBack = { vm.back() }, onPreview = { withCamera(Screen.Preview) }, onOpen3D = { vm.go(Screen.ThreeD) }, onOpenMoving = { vm.refresh(); vm.go(Screen.Moving) },
                    onPlaceInAr = { item -> vm.startPlacement(item); withCamera(Screen.Place) })
                Screen.Place -> vm.placingItem?.let { item ->
                    PlaceScreen(ar, item, vm.room, onAdd = { c, r -> vm.addPlaced(c, r) }, onBack = { vm.back() })
                } ?: LaunchedEffect(Unit) { vm.back() }
                Screen.DeviceCheck -> DeviceCheckScreen(onBack = { vm.back() })
                Screen.Moving -> MovingScreen(vm, onBack = { vm.back() })
                Screen.ThreeD -> vm.room?.let { r ->
                    Room3DScreen(r, r.furniture, vm.result?.layout, vm.report, vm.result?.after, onBack = { vm.back() })
                } ?: LaunchedEffect(Unit) { vm.back() }
                Screen.Preview -> {
                    val room = vm.room
                    val res = vm.result
                    if (room != null && res != null) PreviewScreen(ar, room, res.layout, vm.steps, vm.doneSteps, { vm.toggleStep(it) }, onBack = { vm.back() })
                    else LaunchedEffect(Unit) { vm.back() }
                }
            }
        }
    }
}
}
