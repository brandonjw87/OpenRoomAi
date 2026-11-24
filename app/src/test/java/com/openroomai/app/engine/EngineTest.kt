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
        val res = LayoutOptimizer(room, Effort.BALANCED).optimize(room.furniture)
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
        val res = LayoutOptimizer(room, Effort.REDESIGN).optimize(room.furniture)
        println("bedroom: ${res.before.score} -> ${res.after.score}, violations=${res.violations}")
        assertTrue(res.after.score >= res.before.score)
    }
}
