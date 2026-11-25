package com.openroomai.app.engine

import com.openroomai.app.data.RoomRepository
import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Opening
import com.openroomai.app.model.OpeningType
import com.openroomai.app.model.Room
import com.openroomai.app.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class SunAndGifTest {
    private fun deg(r: Float) = Math.toDegrees(r.toDouble()).toFloat()

    @Test
    fun sunPositionMatchesKnownValues() {
        val noonEq = SunModel.position(40f, 80, 12f)
        assertEquals(50f, deg(noonEq.altitude), 1.5f)
        assertEquals(180f, deg(noonEq.azimuth), 1f)
        assertEquals(73.4f, deg(SunModel.position(40f, 172, 12f).altitude), 1f)
        assertEquals(26.6f, deg(SunModel.position(40f, 355, 12f).altitude), 1f)
        val morning = SunModel.position(40f, 80, 6.2f)
        assertEquals(90f, deg(morning.azimuth), 6f) // equinox sunrise ≈ due east
        val (rise, set) = SunModel.daylight(40f, 80)!!
        assertEquals(6f, rise, 0.25f); assertEquals(18f, set, 0.25f)
        // Southern hemisphere: noon sun is in the north.
        assertEquals(0f, deg(SunModel.position(-33f, 80, 12f).azimuth) % 360f, 1f)
    }

    private fun southRoom(north: Float) = Room.rectangle("r", 4f, 4f).copy(
        // Wall 2 is the bottom edge (y = 4); with north = 0 it faces south.
        openings = listOf(Opening(type = OpeningType.WINDOW, wallIndex = 2, t = 0.5f, width = 1.6f)),
        northDeg = north, latitude = 40f,
    )

    @Test
    fun windowsFacingTheSunLetItIn() {
        val room = southRoom(0f)
        assertEquals("S", SunModel.compassName(SunModel.windowFacingDeg(room, 2, 0f)))
        val winter = SunModel.simulate(room, emptyList(), Season.WINTER, 12f)
        val summer = SunModel.simulate(room, emptyList(), Season.SUMMER, 12f)
        val w = winter.litNow.count { it }; val s = summer.litNow.count { it }
        println("noon sun cells: winter=$w summer=$s")
        assertTrue("low winter sun reaches deeper ($w vs $s)", w > s && s > 0)
        // Same room turned around: window faces north, no noon sun at 40°N.
        val northFacing = SunModel.simulate(southRoom(180f), emptyList(), Season.SUMMER, 12f)
        assertEquals(0, northFacing.litNow.count { it })
    }

    @Test
    fun tallFurnitureCastsShadows() {
        val room = southRoom(0f)
        val shelf = Furniture(type = FurnitureType.WARDROBE, center = Vec2(2f, 3.3f), width = 2.2f, depth = 0.6f, height = 2f)
        val open = SunModel.simulate(room, emptyList(), Season.WINTER, 12f).litNow.count { it }
        val shaded = SunModel.simulate(room, listOf(shelf), Season.WINTER, 12f).litNow.count { it }
        assertTrue("shadow: $open → $shaded", shaded < open / 3)
    }

    @Test
    fun demoRoomSunInsightsAndOptimizerUsesSun() {
        val room = RoomRepository.demoRoom()
        val rep = SunModel.simulate(room, room.furniture, Season.EQUINOX, 14f)
        rep.insights.forEach { println("${it.icon} ${it.title} — ${it.body}") }
        assertTrue(rep.maxHours > 0f)
        assertTrue(rep.insights.any { it.icon == "☀️" })
        // With the sun known, the optimized plant keeps a healthy dose (the reward caps at 4 h).
        val res = LayoutOptimizer(room, Effort.BALANCED, 5L).optimize(room.furniture)
        val plant = room.furniture.first { it.type == FurnitureType.PLANT }
        val today = SunModel.simulate(room, room.furniture, Season.TODAY, 12f).pieceHours[plant.id]!!
        val after = SunModel.simulate(room, res.layout, Season.TODAY, 12f).pieceHours[plant.id]!!
        println("plant sun hours today: $today -> $after")
        assertTrue(after >= minOf(today, 4f) - 0.5f)
    }

    @Test
    fun gifEncoderRoundTripsThroughImageIO() {
        val w = 64; val h = 48
        val frames = (0 until 5).map { k ->
            IntArray(w * h) { i ->
                val x = i % w; val y = i / w
                if ((x / 8 + y / 8 + k) % 2 == 0) 0xFF2EE6A8.toInt() else (0xFF000000.toInt() or ((x * 4) shl 16) or ((y * 5) shl 8) or 0x40)
            }
        }
        val bos = ByteArrayOutputStream()
        val enc = GifEncoder(w, h, bos)
        enc.buildPalette(frames)
        frames.forEach { enc.addFrame(it, 10) }
        enc.finish()
        val decoded = TinyGifDecoder.decode(bos.toByteArray())
        assertEquals(5, decoded.frames.size)
        assertEquals(w, decoded.width)
        // Every pixel of every frame decodes to a colour close to the original.
        for ((k, f) in frames.withIndex()) {
            val d = decoded.frames[k]
            var worst = 0
            for (i in f.indices) {
                val a = f[i]; val b = d[i]
                val diff = maxOf(kotlin.math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)), kotlin.math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)), kotlin.math.abs((a and 0xFF) - (b and 0xFF)))
                if (diff > worst) worst = diff
            }
            // Smooth gradients lose a little to the 256-colour palette; flat colours stay exact.
            assertTrue("frame $k worst channel error $worst", worst <= 32)
            val mint = f.indices.first { f[it] == 0xFF2EE6A8.toInt() }
            assertEquals(0x2EE6A8, d[mint] and 0xFFFFFF)
        }
    }
}

/** Independent minimal GIF decoder used only to verify the encoder. */
object TinyGifDecoder {
    class Result(val width: Int, val height: Int, val frames: List<IntArray>)

    fun decode(b: ByteArray): Result {
        var p = 0
        fun u8() = b[p++].toInt() and 0xFF
        fun u16() = u8() or (u8() shl 8)
        check(String(b, 0, 6) == "GIF89a"); p = 6
        val w = u16(); val h = u16()
        val flags = u8(); u8(); u8()
        val gctSize = 1 shl ((flags and 7) + 1)
        val pal = IntArray(gctSize) { (u8() shl 16) or (u8() shl 8) or u8() }
        val frames = ArrayList<IntArray>()
        loop@ while (p < b.size) {
            when (u8()) {
                0x21 -> { u8(); while (true) { val n = u8(); if (n == 0) break; p += n } }
                0x2C -> {
                    u16(); u16(); val fw = u16(); val fh = u16(); u8()
                    val minCode = u8()
                    val data = java.io.ByteArrayOutputStream()
                    while (true) { val n = u8(); if (n == 0) break; data.write(b, p, n); p += n }
                    frames += lzw(data.toByteArray(), minCode, fw * fh).let { idx -> IntArray(idx.size) { pal[idx[it]] } }
                }
                0x3B -> break@loop
                else -> error("bad block")
            }
        }
        return Result(w, h, frames)
    }

    private fun lzw(d: ByteArray, minCode: Int, n: Int): IntArray {
        val out = IntArray(n); var o = 0
        val clear = 1 shl minCode; val eoi = clear + 1
        val prefix = IntArray(4096); val suffix = IntArray(4096); val len = IntArray(4096)
        var size = minCode + 1; var next = eoi + 1; var prev = -1
        var bits = 0; var acc = 0; var i = 0
        val tmp = IntArray(4096)
        fun first(c: Int): Int { var x = c; while (x >= clear + 2) x = prefix[x]; return x }
        fun emit(c: Int) { var x = c; var k = 0; while (x >= clear + 2) { tmp[k++] = suffix[x]; x = prefix[x] }; tmp[k++] = x; while (k > 0 && o < n) out[o++] = tmp[--k] }
        while (true) {
            while (bits < size) { if (i >= d.size) return out; acc = acc or ((d[i++].toInt() and 0xFF) shl bits); bits += 8 }
            val code = acc and ((1 shl size) - 1); acc = acc ushr size; bits -= size
            if (code == clear) { size = minCode + 1; next = eoi + 1; prev = -1; continue }
            if (code == eoi) return out
            if (prev == -1) { emit(code); prev = code; continue }
            val f = if (code < next) first(code) else first(prev)
            if (next < 4096) { prefix[next] = prev; suffix[next] = f; len[next] = 0; next++ }
            emit(code)
            if (next == (1 shl size) && size < 12) size++
            prev = code
        }
    }
}
