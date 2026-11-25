package com.openroomai.app.engine

import com.openroomai.app.data.RoomRepository
import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Opening
import com.openroomai.app.model.OpeningType
import com.openroomai.app.model.Room
import com.openroomai.app.model.Vec2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

class OpenSpaceTest {
    private val savedUnits = Units.system

    @After fun restoreUnits() { Units.system = savedUnits }

    /** 4 × 3 m room, door on the left wall (D), desk against the right wall facing the door. */
    private fun corridorRoom(gap: Float): Pair<Room, Furniture> {
        val base = Room.rectangle("lane", 4f, 3f).copy(openings = listOf(Opening(type = OpeningType.DOOR, wallIndex = 3, t = 0.5f, width = 0.8f)))
        val desk = Furniture(type = FurnitureType.DESK, center = Vec2(3.7f, 1.5f), rotationDeg = 90f, name = "Desk")
        // Two partitions across the room leave exactly [gap] between them at y = 1.5.
        val half = (3f - gap) / 2f
        val a = Furniture(type = FurnitureType.OTHER, center = Vec2(2f, half / 2f), width = 0.2f, depth = half, name = "Shelf A")
        val b = Furniture(type = FurnitureType.OTHER, center = Vec2(2f, 3f - half / 2f), width = 0.2f, depth = half, name = "Shelf B")
        return base.copy(furniture = listOf(desk, a, b)) to desk
    }

    @Test
    fun edtMatchesBruteForce() {
        val room = Room.rectangle("g", 1.5f, 1.1f)
        val g = SpaceGrid(room, 0.05f)
        val rnd = Random(3)
        repeat(6) { k ->
            val border = k % 2 == 0
            val src = BooleanArray(g.size) { rnd.nextFloat() < 0.04f }
            val d2 = g.edt(src, borderIsSource = border)
            for (i in 0 until g.size) {
                val x = i % g.w; val y = i / g.w
                // With a border source, the ring one cell outside the grid counts too.
                var best = if (border) minOf(x + 1, g.w - x, y + 1, g.h - y).let { (it * it).toFloat() } else Float.MAX_VALUE
                for (j in 0 until g.size) if (src[j]) {
                    val dx = (j % g.w - x).toFloat(); val dy = (j / g.w - y).toFloat()
                    best = minOf(best, dx * dx + dy * dy)
                }
                if (best == Float.MAX_VALUE) assertTrue(d2[i] >= 1e19f) else assertEquals("cell $x,$y border=$border", best, d2[i], 1e-3f)
            }
        }
    }

    @Test
    fun fastLaneWidthsMatchTheExactSearch() {
        val room = RoomRepository.demoRoom()
        val rnd = Random(11)
        for (cell in listOf(OpenSpace.FINE, OpenSpace.FAST, 0.1f)) {
            val m = OpenSpace.Meter(room, cell)
            repeat(25) {
                val layout = room.furniture.map { f ->
                    f.withPose(f.center + Vec2(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f) * 0.8f, f.rotationDeg + if (rnd.nextBoolean()) 90f else 0f)
                }
                val fast = m.measure(layout, routes = false).corridors.associate { it.targetId to it.width }
                val exact = m.measure(layout, routes = true).corridors.associate { it.targetId to it.width }
                assertEquals(exact.keys, fast.keys)
                for ((k, v) in exact) assertEquals("cell $cell $k", v, fast[k]!!, 0.002f)
            }
        }
    }

    @Test
    fun emptyRoomIsOneBigZone() {
        val room = Room.rectangle("empty", 4f, 3f)
        val rep = OpenSpace.analyze(room, emptyList())
        val z = rep.zone!!
        assertEquals(4f, z.long, 0.03f)
        assertEquals(3f, z.short, 0.03f)
        assertEquals(12f, rep.freeArea, 0.05f)
        assertEquals(12f, rep.usableArea, 0.1f)
    }

    @Test
    fun corridorWidthIsMeasuredToTheInch() {
        for (gap in listOf(0.6f, 0.8f, 1.0f, 1.2f)) {
            val (room, desk) = corridorRoom(gap)
            val rep = OpenSpace.analyze(room, room.furniture)
            val lane = rep.corridors.first { it.targetId == desk.id }
            println("gap ${"%.2f".format(gap)} m -> lane ${"%.3f".format(lane.width)} m, pinch at ${lane.pinch}")
            assertTrue(lane.reachable)
            assertEquals("gap $gap", gap, lane.width, OpenSpace.FINE * 1.6f)
            // The pinch is in the gap between the partitions.
            val p = lane.pinch!!
            assertEquals(2f, p.x, 0.15f)
            assertEquals(1.5f, p.y, gap / 2f)
            assertTrue(lane.path.size >= 2 && lane.widths.size == lane.path.size)
        }
        val (room, _) = corridorRoom(0.6f)
        val rep = OpenSpace.analyze(room, room.furniture)
        val pinch = rep.pinches.first()
        assertTrue(pinch.between, pinch.between.contains("Shelf"))
        assertTrue(rep.notes.any { it.title.contains("narrows to") })
    }

    @Test
    fun blockedLaneIsReported() {
        val (room, desk) = corridorRoom(0.6f)
        // Close the gap completely.
        val plug = Furniture(type = FurnitureType.OTHER, center = Vec2(2f, 1.5f), width = 0.2f, depth = 0.7f, name = "Plug")
        val rep = OpenSpace.analyze(room, room.furniture + plug)
        assertTrue(rep.blocked.any { it.targetId == desk.id })
        assertTrue(rep.notes.any { it.title.startsWith("No clear way") })
        assertTrue(rep.narrowest == null || rep.lanes.none { it.targetId == desk.id })
    }

    @Test
    fun sliversDontCountAsUsableFloor() {
        val room = Room.rectangle("sofa", 4.5f, 3.5f)
        // Sofa 10″ off the bottom wall, facing up into the room.
        val off = Furniture(type = FurnitureType.SOFA, center = Vec2(2.25f, 3.5f - 0.25f - 0.45f), rotationDeg = 180f)
        val flush = off.withPose(Vec2(2.25f, 3.5f - 0.015f - 0.45f), 180f)
        val a = OpenSpace.analyze(room, listOf(off))
        val b = OpenSpace.analyze(room, listOf(flush))
        println("usable off-wall ${a.usableArea} vs flush ${b.usableArea}; free ${a.freeArea} vs ${b.freeArea}")
        // The 10″ gap behind is free floor, but not usable floor.
        assertTrue(a.freeArea - a.usableArea >= 0.25f * 2f * 0.85f)
        // Pushing flush turns that dead strip into real floor in front: ~0.235 m × 2 m.
        assertEquals(0.235f * 2f, b.usableArea - a.usableArea, 0.08f)
    }

    @Test
    fun zoneStaysOutOfDoorSwings() {
        val room = RoomRepository.demoRoom()
        val rep = OpenSpace.analyze(room, room.furniture)
        val z = rep.zone!!
        for (d in room.doors()) {
            val swing = room.doorSwing(d)
            // Shrink a hair so touching edges don't count.
            val inner = Geo.rectCorners(z.center, z.width - 0.03f, z.depth - 0.03f, z.angleRad)
            assertFalse(Geo.convexOverlap(inner, swing))
        }
        // The zone is open floor: no furniture inside it.
        val inner = Geo.rectCorners(z.center, z.width - 0.03f, z.depth - 0.03f, z.angleRad)
        assertTrue(room.furniture.none { Geo.convexOverlap(it.corners(), inner) })
        assertTrue(z.short >= Lanes.MIN_ZONE_SIDE - 1e-3f)
    }

    @Test
    fun rotatedRoomMeasuresTheSame() {
        // Same room, scanned at a 23° angle in AR world space: numbers must match.
        val room = RoomRepository.demoRoom()
        val pivot = Vec2(1f, -2f)
        val rad = Math.toRadians(23.0).toFloat()
        fun rot(p: Vec2) = (p - pivot).rotated(rad) + pivot
        val turned = room.copy(
            outline = room.outline.map(::rot),
            furniture = room.furniture.map { it.withPose(rot(it.center), it.rotationDeg + 23f) },
        )
        val a = OpenSpace.analyze(room, room.furniture)
        val b = OpenSpace.analyze(turned, turned.furniture)
        println("usable ${a.usableArea} vs ${b.usableArea}; zone ${a.zone?.area} vs ${b.zone?.area}; narrowest ${a.narrowest} vs ${b.narrowest}")
        assertEquals(a.usableArea, b.usableArea, 0.12f)
        assertEquals(a.zone!!.area, b.zone!!.area, 0.15f)
        assertEquals(a.narrowest!!, b.narrowest!!, 0.06f)
    }

    @Test
    fun lShapedRoomCatchesPiecesAcrossTheInnerCorner() {
        // L-shape: the notch is x > 2, y > 2.
        val outline = listOf(Vec2(0f, 0f), Vec2(4f, 0f), Vec2(4f, 2f), Vec2(2f, 2f), Vec2(2f, 4f), Vec2(0f, 4f))
        val room = Room(name = "L", outline = outline)
        // A long bench laid diagonally across the inner corner: all four corners are inside the room…
        val bench = Furniture(type = FurnitureType.OTHER, center = Vec2(2f, 2f), width = 2.26f, depth = 0.3f, rotationDeg = -45f, name = "Bench")
        assertTrue("all corners inside", bench.corners().all { Geo.pointInPolygon(it, outline) })
        // …but the wall corner pokes right through it.
        assertTrue(Geo.outsideAmount(bench.corners(), outline) > 0f)
        assertTrue(Constraints(room).violations(listOf(bench)).any { it.contains("sticks through a wall") })
        // A piece fully inside one arm is fine; one poking into the notch is not.
        val ok = Furniture(type = FurnitureType.OTHER, center = Vec2(1f, 3f), width = 1f, depth = 0.6f)
        assertEquals(0f, Geo.outsideAmount(ok.corners(), outline), 0f)
        val poke = Furniture(type = FurnitureType.OTHER, center = Vec2(2.2f, 1.7f), width = 1f, depth = 1f)
        assertTrue(Geo.outsideAmount(poke.corners(), outline) > 0f)
    }

    @Test
    fun optimizerKeepsLShapedRoomsLegal() {
        val outline = listOf(Vec2(0f, 0f), Vec2(5f, 0f), Vec2(5f, 2.5f), Vec2(2.5f, 2.5f), Vec2(2.5f, 5f), Vec2(0f, 5f))
        val room = Room(
            name = "L", outline = outline,
            openings = listOf(Opening(type = OpeningType.DOOR, wallIndex = 0, t = 0.2f, width = 0.85f)),
            furniture = listOf(
                Furniture(type = FurnitureType.SOFA, center = Vec2(1.25f, 3.5f)),
                Furniture(type = FurnitureType.BOOKSHELF, center = Vec2(3.5f, 1.2f), rotationDeg = 30f),
                Furniture(type = FurnitureType.ARMCHAIR, center = Vec2(2.3f, 2.3f)),
                Furniture(type = FurnitureType.COFFEE_TABLE, center = Vec2(1.2f, 2.2f)),
            ),
        )
        val res = LayoutOptimizer(room, SpaceGoal.MAX_OPEN_CENTER, 5L).optimize(room.furniture)
        println("L room: violations=${res.violations} usable ${res.space?.before?.usableArea} -> ${res.space?.after?.usableArea}")
        assertTrue(res.violations.toString(), res.fits)
        for (f in res.layout) assertEquals(f.name, 0f, Geo.outsideAmount(f.corners(), outline), 0f)
    }

    @Test
    fun savedRoomsWithRetiredGoalsStillLoad() {
        val json = RoomRepository.JSON
        val room = RoomRepository.demoRoom().copy(goal = LayoutGoal.MOVIE_NIGHT)
        val text = json.encodeToString(Room.serializer(), room)
        for (old in listOf("RETIRED_GOAL", "FROM_A_FUTURE_VERSION")) {
            val legacy = text.replace("\"MOVIE_NIGHT\"", "\"$old\"")
            assertTrue(legacy.contains(old))
            val back = json.decodeFromString(Room.serializer(), legacy)
            assertEquals(LayoutGoal.EVERYDAY, back.goal)
            assertEquals(room.furniture, back.furniture)
        }
        // Rooms from before space goals existed get the default.
        assertEquals(SpaceGoal.MAX_OPEN_CENTER, json.decodeFromString(Room.serializer(), text).space)
    }

    @Test
    fun unitsFormatBothSystems() {
        assertEquals(Units.System.IMPERIAL, Units.defaultFor(Locale.US))
        assertEquals(Units.System.METRIC, Units.defaultFor(Locale.GERMANY))
        assertEquals(Units.System.METRIC, Units.defaultFor(Locale.UK))
        Units.system = Units.System.IMPERIAL
        assertEquals("3″", Units.len(3 * Units.INCH))
        assertEquals("7′ 4″", Units.len(88 * Units.INCH))
        assertEquals("6′", Units.len(72 * Units.INCH))
        assertEquals("½″", Units.nudge(0.5f * Units.INCH))
        assertEquals("3½″", Units.nudge(3.5f * Units.INCH))
        assertEquals("+8.5 sq ft", Units.areaGain(8.5f * Units.SQFT).replace(',', '.'))
        assertEquals(0.0254f, Units.snap(0.03f), 1e-6f)
        assertEquals(listOf(1, 2, 3, 4, 6, 9, 12), Units.nudgeSteps.map { (it / Units.INCH + 0.5f).toInt() })
        Units.system = Units.System.METRIC
        assertEquals("35 cm", Units.len(0.35f))
        assertEquals("2.4 m", Units.len(2.4f).replace(',', '.'))
        assertEquals("4.2 m²", Units.area(4.2f).replace(',', '.'))
        assertEquals("+0.35 m²", Units.areaGain(0.35f).replace(',', '.'))
        assertEquals(0.03f, Units.snap(0.0304f), 1e-6f)
    }

    @Test
    fun demoRoomMeasuresFast() {
        val room = RoomRepository.demoRoom()
        OpenSpace.analyze(room, room.furniture) // warm-up
        val t0 = System.nanoTime()
        repeat(10) { OpenSpace.analyze(room, room.furniture) }
        val fine = (System.nanoTime() - t0) / 10_000_000.0
        val m = OpenSpace.Meter(room, OpenSpace.FAST)
        val t1 = System.nanoTime()
        repeat(50) { m.measure(room.furniture) }
        val fast = (System.nanoTime() - t1) / 50_000_000.0
        println("open-space analysis: fine ${"%.1f".format(fine)} ms, fast screen ${"%.2f".format(fast)} ms")
        assertTrue(fine < 400)
    }

    @Test
    fun demoRoomNumbersAreSane() {
        val room = RoomRepository.demoRoom()
        val rep = OpenSpace.analyze(room, room.furniture)
        println("demo: usable ${Units.area(rep.usableArea)} of ${Units.area(rep.floorArea)}, zone ${rep.zone?.let { Units.dims(it.long, it.short) }}, narrowest ${rep.narrowest?.let { Units.len(it) }}, lanes ${rep.corridors.map { it.targetName to "%.2f".format(it.width) }}")
        rep.notes.forEach { println("  ${it.icon} ${it.title} — ${it.body}") }
        assertTrue(rep.usableArea in 3f..rep.floorArea)
        assertTrue(rep.freeArea >= rep.usableArea)
        // The demo room is deliberately awkward: the coffee table sits in the door swing.
        assertTrue(rep.notes.any { it.icon == "🚪" && it.title.contains("Coffee table") })
        assertTrue(rep.pinches.isNotEmpty())
    }
}
