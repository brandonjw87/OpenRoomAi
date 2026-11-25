package com.openroomai.app.engine

import com.openroomai.app.data.RoomRepository
import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Opening
import com.openroomai.app.model.OpeningType
import com.openroomai.app.model.Room
import com.openroomai.app.model.Vec2
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Each one-tap goal must move the number it promises, on the awkward demo room and a bedroom. */
class SpaceGoalTest {
    private val savedUnits = Units.system

    @Before fun imperial() { Units.system = Units.System.IMPERIAL }
    @After fun restoreUnits() { Units.system = savedUnits }

    private fun bedroom() = Room.rectangle("bed", 3.4f, 3.6f).copy(
        openings = listOf(
            Opening(type = OpeningType.DOOR, wallIndex = 3, t = 0.15f, width = 0.8f),
            Opening(type = OpeningType.WINDOW, wallIndex = 1, t = 0.5f, width = 1.2f),
        ),
        furniture = listOf(
            Furniture(type = FurnitureType.BED, center = Vec2(1.7f, 1.8f)),
            Furniture(type = FurnitureType.NIGHTSTAND, center = Vec2(0.4f, 0.4f)),
            Furniture(type = FurnitureType.WARDROBE, center = Vec2(3.0f, 1.8f), rotationDeg = 270f),
            Furniture(type = FurnitureType.DESK, center = Vec2(1.7f, 3.2f), rotationDeg = 180f),
        ),
    )

    private fun describe(tag: String, r: OptimizeResult, ms: Long) {
        val d = r.space!!
        println(
            "$tag [${ms} ms]: usable ${Units.area(d.before.usableArea)} -> ${Units.area(d.after.usableArea)} (${Units.areaGain(d.usableGain)}), " +
                "zone ${d.before.zone?.let { Units.dims(it.long, it.short) }} -> ${d.after.zone?.let { Units.dims(it.long, it.short) }} (${Units.areaGain(d.zoneGain)}), " +
                "narrowest ${d.before.narrowest?.let { Units.len(it) }} -> ${d.after.narrowest?.let { Units.len(it) }}, " +
                "reach ${d.before.lanes.size}->${d.after.lanes.size}/${d.after.corridors.size}, tight ${d.before.tightLanes}->${d.after.tightLanes}, " +
                "openness ${r.before.score}->${r.after.score}, nudges ${r.nudges.size}, violations ${r.violations}",
        )
    }

    private fun run(room: Room, goal: SpaceGoal, seed: Long = 9L): OptimizeResult {
        val t0 = System.currentTimeMillis()
        val r = LayoutOptimizer(room, goal, seed).optimize(room.furniture)
        describe("${room.name} ${goal.name}", r, System.currentTimeMillis() - t0)
        return r
    }

    @Test
    fun maxOpenCenterConsolidatesTheFloor() {
        for (room in listOf(RoomRepository.demoRoom(), bedroom())) {
            val r = run(room, SpaceGoal.MAX_OPEN_CENTER)
            val d = r.space!!
            assertTrue(r.violations.toString(), r.fits)
            assertTrue("zone ${d.zoneGain}", d.zoneGain > 0.3f)
            assertTrue("usable ${d.usableGain}", d.usableGain > 0f)
        }
    }

    @Test
    fun widestFlowWidensTheWalkways() {
        for (room in listOf(RoomRepository.demoRoom(), bedroom())) {
            val r = run(room, SpaceGoal.WIDEST_FLOW)
            val d = r.space!!
            assertTrue(r.violations.toString(), r.fits)
            assertTrue("reach ${d.before.lanes.size} -> ${d.after.lanes.size}", d.after.lanes.size >= d.before.lanes.size)
            assertTrue("narrowest ${d.before.narrowest} -> ${d.after.narrowest}", (d.after.narrowest ?: 0f) >= (d.before.narrowest ?: 0f))
            assertTrue("tight ${d.before.tightLanes} -> ${d.after.tightLanes}", d.after.tightLanes <= d.before.tightLanes || d.after.lanes.size > d.before.lanes.size)
        }
        // On the demo room every destination becomes reachable through a real walkway.
        val demo = run(RoomRepository.demoRoom(), SpaceGoal.WIDEST_FLOW, 4L).space!!
        assertTrue(demo.after.blocked.isEmpty())
        assertTrue((demo.after.narrowest ?: 0f) >= Lanes.PINCH)
    }

    @Test
    fun goalsDifferWhereTheyShould() {
        val room = RoomRepository.demoRoom()
        val open = run(room, SpaceGoal.MAX_OPEN_CENTER, 2L).space!!
        val flow = run(room, SpaceGoal.WIDEST_FLOW, 2L).space!!
        val quick = run(room, SpaceGoal.QUICK_WINS, 2L)
        // Quick wins never moves anything more than a foot.
        assertTrue(MovePlanner.plan(room, room.furniture, quick.layout).all { it.distance <= 0.61f })
        // The open-center goal gets the bigger zone, or at least a comparable one.
        assertTrue("zone open ${open.after.zone?.area} vs flow ${flow.after.zone?.area}", (open.after.zone?.area ?: 0f) >= (flow.after.zone?.area ?: 0f) * 0.9f)
    }
}
