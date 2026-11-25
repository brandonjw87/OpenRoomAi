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

class MovingAndOpenLookTest {

    @Test
    fun cornerTurnMatchesClosedForm() {
        // Thin rod around a 1 m × 1 m corner: (a^(2/3) + b^(2/3))^(3/2) = 2√2.
        assertEquals(2.828f, Delivery.maxLengthAroundCorner(1f, 1f, 0f), 0.01f)
        // Symmetric corridors: the binding angle is 45°, so L = 2√2 − 2t.
        assertEquals(2.828f - 1f, Delivery.maxLengthAroundCorner(1f, 1f, 0.5f), 0.01f)
        assertTrue(Delivery.maxLengthAroundCorner(1.2f, 0.9f, 0.4f) < Delivery.maxLengthAroundCorner(1.2f, 1.2f, 0.4f))
    }

    @Test
    fun doorChecksFollowTheMoversPlaybook() {
        val shelf = Furniture(type = FurnitureType.BOOKSHELF, center = Vec2.ZERO)
        assertEquals(DeliveryMethod.STRAIGHT, Delivery.check(shelf, 0.8f).method)
        val sofa = Furniture(type = FurnitureType.SOFA, center = Vec2.ZERO) // 2.0 × 0.9 × 0.85
        val tightDoor = Delivery.check(sofa, 0.8f)
        println("sofa / 80 cm door: ${tightDoor.method} — ${tightDoor.detail}")
        assertTrue(tightDoor.method in setOf(DeliveryMethod.TILT, DeliveryMethod.DOOR_OFF, DeliveryMethod.LEGS_OFF))
        assertTrue(Delivery.check(sofa, 0.92f).method in setOf(DeliveryMethod.STRAIGHT, DeliveryMethod.TILT))
        assertEquals(DeliveryMethod.NO_FIT, Delivery.check(sofa, 0.6f).method)
        // 85 cm armchair, 80 cm door: needs both tricks.
        val chair = Furniture(type = FurnitureType.ARMCHAIR, center = Vec2.ZERO)
        assertEquals(DeliveryMethod.DOOR_AND_LEGS, Delivery.check(chair, 0.8f).method)
        val wardrobe = Furniture(type = FurnitureType.WARDROBE, center = Vec2.ZERO, width = 1.6f, depth = 0.9f, height = 2.2f)
        assertEquals(DeliveryMethod.DISASSEMBLE, Delivery.check(wardrobe, 0.8f, doorHeight = 2.0f).method)
        // A "diagonal depth" style sofa profile is never wider than its bounding box.
        assertTrue(Delivery.profileMinWidth(sofa) <= minOf(sofa.depth, sofa.height) + 1e-4f)
        // Hallway corner: a 2 m sofa in a 0.9 m hall needs tilting, in a 1.4 m hall it just turns.
        val narrow = Delivery.check(sofa, 0.92f, hallWidth = 0.9f, ceiling = 2.45f)
        val wide = Delivery.check(sofa, 0.92f, hallWidth = 1.4f)
        println("corner 0.9 m: ok=${narrow.cornerOk} tilt=${narrow.cornerNeedsTilt}; 1.4 m: ok=${wide.cornerOk}")
        assertEquals(true, wide.cornerOk)
        assertTrue(narrow.cornerOk == false || narrow.cornerNeedsTilt)
        // A 0.7 m hall with a low ceiling defeats even standing it on end.
        assertEquals(false, Delivery.check(sofa, 0.92f, hallWidth = 0.7f, ceiling = 1.9f).cornerOk)
    }

    @Test
    fun mirrorFinderSeesTheWindowFromTheDoor() {
        val room = Room.rectangle("m", 4f, 4f).copy(
            openings = listOf(
                Opening(type = OpeningType.WINDOW, wallIndex = 2, t = 0.5f, width = 1.6f),
                Opening(type = OpeningType.DOOR, wallIndex = 3, t = 0.5f, width = 0.85f),
            ),
        )
        val spots = OpenLook.mirrorSpots(room, emptyList())
        spots.forEach { println("mirror wall ${room.wallLabel(it.mirror.wallIndex)} t=${"%.2f".format(it.mirror.t)} score=${"%.2f".format(it.score)} — ${it.reason}") }
        assertTrue(spots.isNotEmpty())
        val top = spots.first()
        assertTrue(top.reflectsWindow)
        assertTrue("wall ${top.mirror.wallIndex}", top.mirror.wallIndex in setOf(0, 1))
        assertTrue(spots.zipWithNext().all { (a, b) -> a.score >= b.score })
        // Never on top of a door or window.
        for (s in spots) for (o in room.openings) if (o.wallIndex == s.mirror.wallIndex) {
            val ms = room.mirrorSegment(s.mirror); val os = room.openingSegment(o)
            assertTrue(ms.distanceTo(os.pointAt(0.5f)) > 0.3f)
        }
    }

    @Test
    fun mirrorFinderAvoidsTallFurnitureAndWorksOnDemo() {
        val room = RoomRepository.demoRoom()
        val spots = OpenLook.mirrorSpots(room, room.furniture)
        spots.forEach { println("demo mirror wall ${room.wallLabel(it.mirror.wallIndex)} t=${"%.2f".format(it.mirror.t)} — ${it.reason}") }
        assertTrue(spots.isNotEmpty())
        val tall = room.furniture.filter { it.height >= 1.3f }
        for (s in spots) {
            val seg = room.mirrorSegment(s.mirror)
            assertTrue(tall.none { f -> f.corners().any { seg.distanceTo(it) < 0.3f } })
            assertTrue(s.mirror.bottom >= 0.7f && s.mirror.top > s.mirror.bottom)
        }
        assertTrue(OpenLook.paintVerdict(OpenLook.PAINTS.first()).contains("bigger"))
    }
}
