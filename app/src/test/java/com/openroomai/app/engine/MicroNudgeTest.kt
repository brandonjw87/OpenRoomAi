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
import org.junit.Before
import org.junit.Test

class MicroNudgeTest {
    private val savedUnits = Units.system

    @Before fun imperial() { Units.system = Units.System.IMPERIAL }
    @After fun restoreUnits() { Units.system = savedUnits }

    private fun sofaRoom(gap: Float): Pair<Room, Furniture> {
        val room = Room.rectangle("sofa", 4.5f, 3.5f).copy(openings = listOf(Opening(type = OpeningType.DOOR, wallIndex = 0, t = 0.15f, width = 0.85f)))
        // Sofa [gap] off the bottom wall (C), facing up into the room.
        val sofa = Furniture(type = FurnitureType.SOFA, center = Vec2(2.4f, 3.5f - gap - 0.45f), rotationDeg = 180f, name = "Sofa")
        return room.copy(furniture = listOf(sofa)) to sofa
    }

    @Test
    fun pushesASofaFlushAgainstTheWall() {
        val (room, sofa) = sofaRoom(0.25f)
        val nudges = MicroNudge.suggest(room, room.furniture)
        nudges.forEach { println("${it.title} — ${it.detail} (score ${"%.1f".format(it.score)})") }
        val best = nudges.first()
        assertEquals(sofa.id, best.pieceId)
        assertTrue(best.kind == NudgeKind.FLUSH || best.kind == NudgeKind.SLIDE)
        assertTrue(best.title, best.title.contains("Wall C"))
        // ~0.235 m × 2 m of dead strip behind becomes usable floor in front.
        assertTrue("gain ${best.usableGain}", best.usableGain > 0.3f)
        assertTrue(best.detail, best.detail.contains("sq ft open floor"))
        // Now flush: nothing left to push back.
        val after = best.applyTo(room.furniture)
        val back = after.first().backCenter()
        assertTrue(room.walls()[2].distanceTo(back) < 0.05f)
    }

    @Test
    fun nudgesKeepEveryRuleAndReportHonestNumbers() {
        val room = RoomRepository.demoRoom()
        val cons = Constraints(room)
        val baseHard = cons.hardCost(room.furniture)
        val base = OpenSpace.analyze(room, room.furniture, withNotes = false)
        val t0 = System.nanoTime()
        val nudges = MicroNudge.suggest(room, room.furniture)
        println("demo: ${nudges.size} nudges in ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertTrue(nudges.isNotEmpty())
        assertEquals("one per piece", nudges.size, nudges.map { it.pieceId }.distinct().size)
        assertTrue("ranked", nudges.zipWithNext().all { (a, b) -> a.score >= b.score })
        for (n in nudges) {
            println("  ${n.kind} ${n.title} — ${n.detail}")
            val after = n.applyTo(room.furniture)
            assertTrue(n.title, cons.hardCost(after) <= baseHard + 1e-4f)
            assertTrue(n.title, cons.violations(after).size <= cons.violations(room.furniture).size)
            for (d in room.doors()) for (p in n.poses) {
                val was = room.furniture.first { it.id == p.id }
                if (!Geo.convexOverlap(was.corners(), room.doorSwing(d))) assertFalse("${n.title} blocks a door", Geo.convexOverlap(p.corners(), room.doorSwing(d)))
            }
            // Small means small.
            assertTrue(n.title, n.travel <= 0.61f)
            // The numbers shown are the numbers you get.
            val rep = OpenSpace.analyze(room, after, withNotes = false)
            assertEquals(n.title, rep.usableArea - base.usableArea, n.usableGain, 1e-3f)
            // Walkways that were clear stay clear.
            for (c in rep.corridors) {
                val b = base.corridors.first { it.targetId == c.targetId }
                if (b.width >= Lanes.CLEAR) assertTrue("${n.title}: ${c.targetName}", c.width >= Lanes.CLEAR - OpenSpace.FINE)
                if (b.reachable) assertTrue(c.reachable)
            }
        }
    }

    @Test
    fun widensATightWalkway() {
        // Armchair squeezing the walkway from the door to the desk: 24″ lane.
        val room = Room.rectangle("study", 4f, 3.2f).copy(openings = listOf(Opening(type = OpeningType.DOOR, wallIndex = 3, t = 0.5f, width = 0.8f)))
        val desk = Furniture(type = FurnitureType.DESK, center = Vec2(3.7f, 1.6f), rotationDeg = 90f, name = "Desk")
        val shelf = Furniture(type = FurnitureType.BOOKSHELF, center = Vec2(1.8f, 0.175f), name = "Bookshelf")
        val chair = Furniture(type = FurnitureType.ARMCHAIR, center = Vec2(1.8f, 2.2f), name = "Armchair")
        val layout = listOf(desk, shelf, chair)
        val r = room.copy(furniture = layout)
        val base = OpenSpace.analyze(r, layout, withNotes = false)
        val nudges = MicroNudge.suggest(r, layout, SpaceGoal.WIDEST_FLOW)
        nudges.forEach { println("${it.title} — ${it.detail}") }
        val lanes = nudges.mapNotNull { it.lane }
        assertTrue("some nudge widens a lane", lanes.any { (_, a, b) -> b > a + Units.INCH })
        val best = nudges.first()
        val after = OpenSpace.analyze(r, best.applyTo(layout), withNotes = false)
        assertTrue("narrowest ${base.narrowest} -> ${after.narrowest}", after.narrowest!! >= base.narrowest!!)
    }

    @Test
    fun quickWinsChainOnlyEverHelps() {
        val room = RoomRepository.demoRoom()
        val chain = MicroNudge.chain(room, room.furniture)
        chain.forEach { println("${it.title} — ${it.detail}") }
        assertTrue(chain.size in 1..4)
        val moved = chain.flatMap { it.ids }
        assertEquals("each piece moves once", moved.size, moved.distinct().size)
        val cons = Constraints(room)
        var layout = room.furniture
        val start = OpenSpace.analyze(room, layout, withNotes = false)
        for (n in chain) {
            assertTrue(n.title, n.score > 0f)
            val next = n.applyTo(layout)
            // No step ever makes a single rule worse, so clashes never multiply.
            assertTrue(n.title, cons.noWorse(layout, next, n.poses))
            assertTrue(n.title, cons.violations(layout).containsAll(cons.violations(next)))
            layout = next
        }
        val end = OpenSpace.analyze(room, layout, withNotes = false)
        println("chain: usable ${start.usableArea} -> ${end.usableArea}, narrowest ${start.narrowest} -> ${end.narrowest}, zone ${start.zone?.area} -> ${end.zone?.area}")
        val d = SpaceDiff(start, end)
        println("widened: ${d.widened}")
        assertTrue(end.usableArea >= start.usableArea - 0.1f)
        assertTrue(d.improved)
    }

    @Test
    fun quickWinsGoalIsAllMicroMoves() {
        val room = RoomRepository.demoRoom()
        val res = LayoutOptimizer(room, SpaceGoal.QUICK_WINS, 3L).optimize(room.furniture)
        val steps = MovePlanner.plan(room, room.furniture, res.layout)
        steps.forEach { println("${it.index}. ${it.title}: ${it.detail}") }
        // The demo room starts out with clashes; small moves may fix some, never add any.
        val before = Constraints(room).violations(room.furniture)
        println("violations: $before -> ${res.violations}")
        assertTrue(res.violations.toString(), before.containsAll(res.violations))
        assertTrue(steps.isNotEmpty())
        assertTrue(steps.size <= 8)
        // Rotations of a table count; otherwise every move is a foot or less.
        assertTrue(steps.all { it.distance <= 0.61f })
        val d = res.space!!
        println("quick wins: usable ${Units.areaGain(d.usableGain)}, narrowest ${d.before.narrowest} -> ${d.after.narrowest}, zone ${Units.areaGain(d.zoneGain)}")
        assertTrue(d.improved)
        assertEquals(res.nudges.size, res.nudges.map { it.pieceId }.distinct().size)
    }

    @Test
    fun deskChairTravelsWithTheDesk() {
        val room = Room.rectangle("office", 3.6f, 3f).copy(openings = listOf(Opening(type = OpeningType.DOOR, wallIndex = 0, t = 0.8f, width = 0.8f)))
        val desk = Furniture(type = FurnitureType.DESK, center = Vec2(1.5f, 3f - 0.3f - 0.2f), rotationDeg = 180f, name = "Desk")
        val chair = Furniture(type = FurnitureType.CHAIR, center = Vec2(1.5f, 3f - 0.6f - 0.2f - 0.3f), rotationDeg = 0f, name = "Desk chair")
        val layout = listOf(desk, chair)
        assertEquals(listOf(chair.id), MicroNudge.companions(desk, layout).map { it.id })
        val nudges = MicroNudge.suggest(room.copy(furniture = layout), layout)
        for (n in nudges.filter { it.pieceId == desk.id }) {
            assertEquals(n.title, setOf(desk.id, chair.id), n.ids)
            assertTrue(n.title, n.title.contains("bring the Desk chair along"))
        }
    }
}
