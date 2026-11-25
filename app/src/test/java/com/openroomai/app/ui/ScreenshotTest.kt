package com.openroomai.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.openroomai.app.data.RoomRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders real screens to PNGs (app/build/screens) so the UI can be eyeballed without a device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2600dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun pump(ms: Long = 50) {
        shadowOf(Looper.getMainLooper()).idle()
        rule.mainClock.advanceTimeBy(ms)
        // Let delay()-based coroutines on the main dispatcher fire, too.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timeout" }
            pump(); Thread.sleep(20)
        }
    }

    private fun snap(name: String) {
        repeat(5) { pump(400) }
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val out = File(System.getProperty("user.dir"), "build/screens").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun home() {
        val room = RoomRepository.demoRoom()
        rule.setContent { OpenRoomTheme { HomeScreen(listOf(room), true, {}, { _, _, _ -> }, {}, {}, {}) } }
        snap("home")
    }

    @Test
    fun planBeforeAndAfter() {
        val vm = AppViewModel(rule.activity.application)
        rule.mainClock.autoAdvance = false
        rule.setContent { OpenRoomTheme { PlanScreen(vm, true, {}, {}) } }
        vm.openDemo()
        waitFor(30_000) { vm.report != null }
        snap("plan_now")
        vm.optimize()
        waitFor(180_000) { vm.result != null && vm.progress == null }
        snap("plan_optimized")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun threeD() {
        val room = RoomRepository.demoRoom()
        val res = com.openroomai.app.engine.LayoutOptimizer(room, com.openroomai.app.engine.Effort.BALANCED, 3L).optimize(room.furniture)
        var view by androidx.compose.runtime.mutableStateOf<Viewpoint>(Viewpoint.Dollhouse)
        var after by androidx.compose.runtime.mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            OpenRoomTheme {
                androidx.compose.runtime.key(view, after) {
                    Room3DScreen(room, room.furniture, res.layout, res.before, res.after, {}, initialView = view, initialAfter = after)
                }
            }
        }
        snap("3d_dollhouse_now")
        after = true; snap("3d_dollhouse_optimized")
        view = Viewpoint.Door; after = false; snap("3d_door_now")
        after = true; snap("3d_door_optimized")
        val sofa = res.layout.first { it.type == com.openroomai.app.model.FurnitureType.SOFA }
        view = Viewpoint.Seat(sofa.id, "From your sofa"); snap("3d_sofa_optimized")
    }

    @Test
    fun glowUpGif() {
        val room = RoomRepository.demoRoom()
        val res = com.openroomai.app.engine.LayoutOptimizer(room, com.openroomai.app.engine.Effort.BALANCED, 3L).optimize(room.furniture)
        val t0 = System.currentTimeMillis()
        val f = GlowUp.render(rule.activity, room, room.furniture, res.layout, res.before, res.after)
        println("GLOWUP ${f.length() / 1024} KB in ${System.currentTimeMillis() - t0} ms")
        val out = File(System.getProperty("user.dir"), "build/screens").apply { mkdirs() }
        f.copyTo(File(out, "glowup.gif"), overwrite = true)
    }

    @Test
    fun planWithSunlight() {
        val vm = AppViewModel(rule.activity.application)
        rule.mainClock.autoAdvance = false
        rule.setContent { OpenRoomTheme { PlanScreen(vm, true, {}, {}) } }
        vm.openDemo()
        waitFor(30_000) { vm.report != null }
        vm.sunSeason = com.openroomai.app.engine.Season.WINTER
        vm.sunHour = 11f
        rule.onNodeWithText("☀️ Sunlight").performClick()
        waitFor(60_000) { vm.sunReport != null }
        snap("plan_sunlight")
    }

    @Test
    fun openLookStudioAndMirrorIn3D() {
        val vm = AppViewModel(rule.activity.application)
        rule.mainClock.autoAdvance = false
        var show3d by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent {
            OpenRoomTheme {
                val r = vm.room
                if (show3d && r != null) Room3DScreen(r, r.furniture, null, vm.report, null, {})
                else PlanScreen(vm, true, {}, {})
            }
        }
        vm.openDemo()
        waitFor(30_000) { vm.report != null && vm.mirrorSpots.isNotEmpty() }
        vm.addMirror(vm.mirrorSpots.first())
        vm.setPaint(com.openroomai.app.engine.OpenLook.PAINTS[3])
        waitFor(10_000) { vm.room?.mirrors?.isNotEmpty() == true }
        rule.onNodeWithText("🪞 Open-Look Studio").performScrollTo()
        snap("openlook_card")
        show3d = true
        snap("openlook_3d")
    }

    @Test
    @Config(qualifiers = "w411dp-h3200dp-xxhdpi")
    fun movingDay() {
        val vm = AppViewModel(rule.activity.application)
        rule.mainClock.autoAdvance = false
        rule.setContent { OpenRoomTheme { if (vm.room != null) MovingScreen(vm) {} } }
        vm.openDemo()
        waitFor(10_000) { vm.room != null }
        vm.createSketch("New flat", 4.2f, 3.6f)
        vm.refresh()
        waitFor(10_000) { vm.rooms.value.size >= 2 && vm.room?.name == "New flat" }
        rule.onNodeWithText("All").performClick()
        rule.onNodeWithText("Plan the move-in", substring = true).performClick()
        waitFor(120_000) { vm.arrivalPlan != null }
        snap("moving_day")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun mirrorAndPaintIn3D() {
        val base = RoomRepository.demoRoom()
        val spot = com.openroomai.app.engine.OpenLook.mirrorSpots(base, base.furniture).first()
        val room = base.copy(mirrors = listOf(spot.mirror), paint = com.openroomai.app.engine.OpenLook.PAINTS[3])
        var view by androidx.compose.runtime.mutableStateOf<Viewpoint>(Viewpoint.Door)
        rule.mainClock.autoAdvance = false
        rule.setContent { OpenRoomTheme { androidx.compose.runtime.key(view) { Room3DScreen(room, room.furniture, null, null, null, {}, initialView = view, initialYaw = 1.45f) } } }
        snap("openlook_3d_door")
        view = Viewpoint.Dollhouse
        snap("openlook_3d_mirror")
    }
}
