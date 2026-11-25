package com.openroomai.app.engine

import com.openroomai.app.model.Furniture
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Room
import com.openroomai.app.model.Vec2

/**
 * A coordinate frame aligned with the room's longest wall. AR-scanned rooms sit at arbitrary
 * angles in world space; rectangles that should "hug" the room (open zones, rugs) are searched
 * in this frame and rotated back. Openings and mirrors reference walls by index, so rotated
 * copies of a room stay valid.
 */
class RoomFrame(val angle: Float, val pivot: Vec2) {
    val angleDeg: Float get() = Math.toDegrees(angle.toDouble()).toFloat()
    fun toLocal(p: Vec2): Vec2 = (p - pivot).rotated(-angle)
    fun toWorld(p: Vec2): Vec2 = p.rotated(angle) + pivot
    fun toLocal(room: Room): Room = room.copy(outline = room.outline.map(::toLocal))
    fun toLocal(f: Furniture): Furniture = f.withPose(toLocal(f.center), f.rotationDeg - angleDeg)

    companion object {
        fun of(room: Room): RoomFrame {
            val walls = room.walls()
            val longest = walls.maxByOrNull { it.length } ?: return RoomFrame(0f, Vec2.ZERO)
            return RoomFrame(longest.dir.angle(), Geo.centroid(room.outline))
        }
    }
}
