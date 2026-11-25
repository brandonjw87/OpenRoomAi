package com.openroomai.app.engine

import com.openroomai.app.data.RoomRepository
import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Room
import com.openroomai.app.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {

    @Test
    fun emptyRoomIsVeryOpen() {
        val room = Room.rectangle("r", 4f, 4f)
        val rep = OpennessAnalyzer.analyze(room, emptyList())
        assertTrue("score ${rep.score}", rep.score >= 90)
        assertTrue(rep.clearDiameter > 3.5f)
    }

    @Test
    fun clutterInTheMiddleHurts() {
        val room = Room.rectangle("r", 4f, 4f)
        val middle = listOf(Furniture(type = FurnitureType.BOOKSHELF, center = Vec2(2f, 2f)))
        val edge = listOf(Furniture(type = FurnitureType.BOOKSHELF, center = Vec2(2f, 3.8f), rotationDeg = 180f))
        val a = OpennessAnalyzer.analyze(room, middle)
        val b = OpennessAnalyzer.analyze(room, edge)
        assertTrue("middle ${a.score} edge ${b.score}", b.score > a.score)
    }

    @Test
    fun placeAgainstWallFacesInward() {
        val room = Room.rectangle("r", 4f, 3f)
        val opt = LayoutOptimizer(room)
        val sofa = Furniture(type = FurnitureType.SOFA, center = Vec2(2f, 1.5f))
        for (w in 0 until 4) {
            val placed = opt.placeAgainstWall(sofa, w, 0.5f)
            val n = room.inwardNormal(w)
            assertEquals(1f, placed.front() dot n, 1e-3f)
            assertTrue(placed.corners().all { Geo.pointInPolygon(it, room.outline) })
        }
    }

    @Test
    fun optimizerImprovesDemoRoomAndIsValid() {
        val room = RoomRepository.demoRoom()
        val t0 = System.currentTimeMillis()
        val res = LayoutOptimizer(room, SpaceGoal.WIDEST_FLOW).optimize(room.furniture)
        val ms = System.currentTimeMillis() - t0
        println("blocked: " + res.after.blockedPieces.map { it.name } + " clear=" + res.after.clearDiameter + " vis=" + res.after.visibilityPct + " reach=" + res.after.reachablePct + " light=" + res.after.lightPct)
        println("demo: ${res.before.score} -> ${res.after.score} in ${ms}ms, violations=${res.violations}")
        assertTrue(res.after.score > res.before.score)
        assertTrue(res.violations.toString(), res.fits)
        val steps = MovePlanner.plan(room, room.furniture, res.layout)
        steps.forEach { println("${it.index}. ${it.title}: ${it.detail}") }
        assertTrue(steps.isNotEmpty())
        Insights.explain(room, res.before, res.after, res.layout).forEach { println("${it.icon} ${it.title}") }
    }

    @Test
    fun bedroomOptimizes() {
        val room = Room.rectangle("bed", 3.4f, 3.6f).copy(
            openings = listOf(
                com.openroomai.app.model.Opening(type = com.openroomai.app.model.OpeningType.DOOR, wallIndex = 3, t = 0.15f, width = 0.8f),
                com.openroomai.app.model.Opening(type = com.openroomai.app.model.OpeningType.WINDOW, wallIndex = 1, t = 0.5f, width = 1.2f),
            ),
            furniture = listOf(
                Furniture(type = FurnitureType.BED, center = Vec2(1.7f, 1.8f)),
                Furniture(type = FurnitureType.NIGHTSTAND, center = Vec2(0.4f, 0.4f)),
                Furniture(type = FurnitureType.WARDROBE, center = Vec2(3.0f, 1.8f), rotationDeg = 270f),
                Furniture(type = FurnitureType.DESK, center = Vec2(1.7f, 3.2f), rotationDeg = 180f),
            ),
        )
        val res = LayoutOptimizer(room, SpaceGoal.MAX_OPEN_CENTER).optimize(room.furniture)
        println("bedroom: ${res.before.score} -> ${res.after.score}, violations=${res.violations}")
        assertTrue(res.after.score >= res.before.score)
    }

    @Test
    fun movePlansStayShortAcrossSeeds() {
        val room = RoomRepository.demoRoom()
        val scores = ArrayList<Int>()
        for (seed in 1L..6L) {
            val res = LayoutOptimizer(room, SpaceGoal.WIDEST_FLOW, seed).optimize(room.furniture)
            val steps = MovePlanner.plan(room, room.furniture, res.layout)
            scores += res.after.score
            assertTrue("seed $seed: ${steps.size} steps", steps.size <= room.furniture.size * 2)
            assertTrue(res.violations.toString(), res.fits)
        }
        println("seed scores: $scores")
        assertTrue(scores.min() >= 65)
    }

    @Test
    fun goalsShapeTheLayout() {
        val room = RoomRepository.demoRoom()
        for (g in LayoutGoal.entries) {
            val res = LayoutOptimizer(room, SpaceGoal.WIDEST_FLOW, 7L, g).optimize(room.furniture)
            println("goal ${g.name}: ${res.before.score} -> ${res.after.score} clear=${"%.2f".format(res.after.clearDiameter)} fits=${res.fits}")
            assertTrue("${g.name} ${res.violations}", res.fits)
            assertTrue(g.name, res.after.score >= res.before.score)
        }
        val party = LayoutOptimizer(room, SpaceGoal.MAX_OPEN_CENTER, 7L, LayoutGoal.PARTY).optimize(room.furniture)
        assertTrue("entertaining clear ${party.after.clearDiameter}", party.after.clearDiameter >= 2.0f)
    }

    @Test
    fun declutterCoachRanksRealGains() {
        val room = RoomRepository.demoRoom()
        val ideas = DeclutterCoach.suggest(room, room.furniture)
        ideas.forEach { println("declutter +${it.gain}: ${it.title} (${it.name})") }
        assertTrue(ideas.isNotEmpty())
        assertTrue(ideas.zipWithNext().all { (a, b) -> a.gain >= b.gain })
        assertEquals(ideas.size, ideas.map { it.furnitureId }.distinct().size)
        // Applying the top idea really does raise the score by at least the promised gain.
        val top = ideas.first()
        val changed = room.furniture.mapNotNull { if (it.id == top.furnitureId) DeclutterCoach.apply(it, top.action) else it }
        val before = OpennessAnalyzer.analyze(room, room.furniture, 0.05f).score
        val after = OpennessAnalyzer.analyze(room, changed, 0.05f).score
        assertEquals(top.gain, after - before)
    }

    @Test
    fun designGuidelinesImproveWithOptimization() {
        val room = RoomRepository.demoRoom()
        val before = DesignGuidelines.check(room, room.furniture)
        val res = LayoutOptimizer(room, SpaceGoal.WIDEST_FLOW, 11L).optimize(room.furniture)
        val after = DesignGuidelines.check(room, res.layout)
        println("design check: ${before.score} -> ${after.score} (conv ${before.conversation}->${after.conversation}, balance ${before.imbalance}->${after.imbalance}, align ${before.alignment}->${after.alignment}, emph ${before.emphasis}->${after.emphasis})")
        assertTrue(after.score > before.score)
        assertTrue("angled pieces get squared up", after.alignment > before.alignment)
        // A lopsided room: everything heavy against one wall.
        val lop = com.openroomai.app.model.Room.rectangle("lop", 5f, 4f).copy(furniture = listOf(
            Furniture(type = FurnitureType.WARDROBE, center = Vec2(0.35f, 1f), rotationDeg = 90f),
            Furniture(type = FurnitureType.BOOKSHELF, center = Vec2(0.2f, 2.5f), rotationDeg = 90f),
            Furniture(type = FurnitureType.SOFA, center = Vec2(0.5f, 3.4f), rotationDeg = 90f),
        ))
        val ck = DesignGuidelines.check(lop, lop.furniture)
        assertEquals("heavy side is Wall D (x = 0)", 3, ck.heavyWall)
    }
}
