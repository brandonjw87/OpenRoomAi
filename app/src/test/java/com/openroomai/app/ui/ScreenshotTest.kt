package com.openroomai.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.openroomai.app.data.RoomRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders real screens to PNGs (build/screens) so the UI can be eyeballed without a device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun snap(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
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
        val vm = AppViewModel(ApplicationProvider.getApplicationContext())
        rule.mainClock.autoAdvance = true
        rule.setContent { OpenRoomTheme { PlanScreen(vm, true, {}, {}) } }
        rule.runOnIdle { vm.openDemo() }
        rule.waitUntil(20_000) { vm.report != null }
        snap("plan_now")
        rule.runOnIdle { vm.optimize() }
        rule.waitUntil(120_000) { vm.result != null && vm.progress == null }
        rule.mainClock.advanceTimeBy(2000)
        snap("plan_optimized")
    }
}
