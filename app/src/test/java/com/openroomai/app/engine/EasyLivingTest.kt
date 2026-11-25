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

class EasyLivingTest {

    private fun livingRoom(coffee: Vec2) = Room.rectangle("lr", 5f, 4f).copy(
        openings = listOf(Opening(type = OpeningType.DOOR, wallIndex = 3, t = 0.5f, width = 0.9f)), // door on the left wall
        furniture = listOf(
            Furniture(type = FurnitureType.SOFA, center = Vec2(4.5f, 2f), rotationDeg = 90f, name = "Sofa"), // against right wall, facing the door
            Furniture(type = FurnitureType.COFFEE_TABLE, center = coffee, rotationDeg = 90f, name = "Coffee table"),
        ),
    )

    @Test
    fun coffeeTableInTheWalkwayIsATripHazard() {
        // Door at (0, 2); sofa at x ≈ 4.5: the route runs straight along y = 2.
        val inRoute = FallSafety.audit(livingRoom(Vec2(2.2f, 2f)), livingRoom(Vec2(2.2f, 2f)).furniture)
        val aside = FallSafety.audit(livingRoom(Vec2(3.4f, 0.6f)), livingRoom(Vec2(3.4f, 0.6f)).furniture)
        println("in route: ${inRoute.tripPieces} score=${inRoute.score}; aside: ${aside.tripPieces} score=${aside.score}")
        assertTrue(inRoute.tripPieces.toString(), "Coffee table" in inRoute.tripPieces)
        assertTrue(inRoute.items.any { it.severity == Severity.FIX_FIRST && it.title.contains("Coffee table") })
        assertTrue("Coffee table" !in aside.tripPieces)
    }

    @Test
    fun bedNeedsAClearSideAndANightPath() {
        val room = Room.rectangle("bed", 3.6f, 3.4f).copy(openings = listOf(Opening(type = OpeningType.DOOR, wallIndex = 0, t = 0.85f, width = 0.85f)))
        val bed = Furniture(type = FurnitureType.BED, center = Vec2(1.8f, 2.4f), rotationDeg = 180f) // headboard on the bottom wall
        val open = FallSafety.audit(room, listOf(bed))
        assertTrue(open.good.any { it.contains("get out of bed") })
        assertTrue("night path reaches the door", open.nightPath.size >= 2 && open.nightPath.last().dist(room.entryPoint()) < 0.5f)
        assertTrue(open.nightLights.size >= 2)
        // Wardrobe on one side, desk on the other: no way out.
        val boxedIn = listOf(
            bed,
            Furniture(type = FurnitureType.WARDROBE, center = Vec2(0.4f, 2.2f), width = 0.7f, depth = 1.6f, height = 2f),
            Furniture(type = FurnitureType.DESK, center = Vec2(3.2f, 2.2f), width = 0.7f, depth = 1.6f),
        )
        val blocked = FallSafety.audit(room, boxedIn)
        assertTrue(blocked.items.any { it.severity == Severity.FIX_FIRST && it.title.contains("out of bed") })
        assertTrue(FallSafety.bedExitObstruction(room, bed, boxedIn) > 0.35f)
        assertTrue(blocked.score < open.score)
    }

    @Test
    fun easyLivingGoalMakesTheDemoSaferToWalk() {
        val room = RoomRepository.demoRoom()
        val before = FallSafety.audit(room, room.furniture)
        val res = LayoutOptimizer(room, Effort.BALANCED, 21L, LayoutGoal.EASY_LIVING).optimize(room.furniture)
        val after = FallSafety.audit(room, res.layout)
        println("fall-safety ${before.score} -> ${after.score}; trip ${before.tripPieces} -> ${after.tripPieces}; good=${after.good}")
        after.items.filter { it.severity != Severity.TIP }.forEach { println("  [${it.severity.label}] ${it.title}") }
        assertTrue(res.fits)
        assertTrue(after.score > before.score)
        assertTrue(after.items.count { it.severity == Severity.FIX_FIRST } <= before.items.count { it.severity == Severity.FIX_FIRST })
        assertTrue("no trip pieces left: ${after.tripPieces}", after.tripPieces.isEmpty())
    }

    @Test
    fun sixSevenIsPerfectlyMid() {
        val rep = OpennessAnalyzer.analyze(RoomRepository.demoRoom(), emptyList())
        assertEquals("Six… seven 🤲", rep.copy(score = 67).label)
        assertTrue(rep.copy(score = 68).label != rep.copy(score = 67).label)
    }
}
