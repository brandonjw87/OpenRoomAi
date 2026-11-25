package com.openroomai.app.engine

import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionFusionTest {
    /** Camera at (0, 1.2, 3) looking along −Z (identity rotation), 640×480, f = 500. */
    private fun cam(rot: Int = 0) = CameraSnapshot(
        floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1.2f, 3f, 1f),
        500f, 500f, 320f, 240f, 640, 480, rot,
    )

    @Test
    fun projectionAndFloorRaysAreInverse() {
        val c = cam()
        val center = VisionFusion.project(c, 0f, 1.2f, 1f)!!
        assertEquals(0.5f, center.x, 1e-4f); assertEquals(0.5f, center.y, 1e-4f)
        assertNull("behind the camera", VisionFusion.project(c, 0f, 1f, 4f))
        // A floor point projects to a pixel that rays back onto the same floor point.
        val px = VisionFusion.project(c, 0.7f, 0f, 0.5f)!!
        val back = VisionFusion.floorPoint(c, px.x, px.y, 0f)!!
        assertEquals(0.7f, back.x, 1e-3f); assertEquals(0.5f, back.y, 1e-3f)
    }

    @Test
    fun uprightSensorRotationsRoundTrip() {
        for (rot in listOf(0, 90, 180, 270)) {
            val s = VisionFusion.uprightToSensor(0.2f, 0.7f, rot)
            val u = VisionFusion.sensorToUpright(s.x, s.y, rot)
            assertEquals(0.2f, u.x, 1e-6f); assertEquals(0.7f, u.y, 1e-6f)
        }
        // Back camera, portrait (90°): upright top-left is the sensor's bottom-left.
        val tl = VisionFusion.uprightToSensor(0f, 0f, 90)
        assertEquals(0f, tl.x, 1e-6f); assertEquals(1f, tl.y, 1e-6f)
    }

    @Test
    fun detectionsVoteForTheRightClusterAndSizeGatesTheLabel() {
        val c = cam()
        val sofa = VisionFusion.Cluster(Vec2(0f, 0f), Geo.rectCorners(Vec2(0f, 0f), 2f, 0.9f, 0f), 0.85f)
        val dresser = VisionFusion.Cluster(Vec2(1.6f, -0.5f), Geo.rectCorners(Vec2(1.6f, -0.5f), 1.0f, 0.5f, 0f), 0.9f)
        val sofaBox = VisionFusion.projectBox(c, sofa.corners, 0f, 0.85f)!!
        val dresserBox = VisionFusion.projectBox(c, dresser.corners, 0f, 0.9f)!!
        val dets = listOf(
            Detection("couch", 0.71f, sofaBox),
            Detection("refrigerator", 0.5f, dresserBox), // COCO has no dresser class
            Detection("bench", 0.43f, NBox(0f, 0f, 0.2f, 0.2f)), // below its threshold: ignored
        )
        val (matched, unmatched) = VisionFusion.match(c, dets, listOf(sofa, dresser), 0f)
        assertEquals(2, matched.size)
        assertTrue(unmatched.isEmpty())
        val book = VisionFusion.VoteBook()
        repeat(2) { matched.forEach { (cl, d) -> book.vote(cl.key, d, cameraOnly = false) } }
        val (sofaType, seen) = VisionFusion.refine(FurnitureType.OTHER, book.near(sofa.key), 2f, 0.9f, 0.85f)
        assertEquals(FurnitureType.SOFA, sofaType)
        assertTrue(seen!!.startsWith("couch"))
        assertEquals(FurnitureType.DRESSER, VisionFusion.refine(FurnitureType.OTHER, book.near(dresser.key), 1f, 0.5f, 0.9f).first)
        // One weak sighting isn't enough to override geometry.
        val weak = VisionFusion.VoteBook().apply { vote(Vec2.ZERO, Detection("couch", 0.46f, sofaBox), false) }
        assertEquals(FurnitureType.DESK, VisionFusion.refine(FurnitureType.DESK, weak.near(Vec2.ZERO), 1.2f, 0.6f, 0.75f).first)
    }

    @Test
    fun rigidFitRecoversRotationAndShift() {
        val src = listOf(Vec2(0f, 0f), Vec2(4f, 0f), Vec2(4f, 3f), Vec2(0f, 3f))
        val theta = 0.4f; val t = Vec2(1.5f, -2f)
        val dst = src.map { it.rotated(theta) + t + Vec2(0.01f, -0.01f) } // a little drift noise
        val (th, tt) = Geo.fitRigid(src, dst)
        assertEquals(theta, th, 1e-3f)
        assertEquals(t.x + 0.01f, tt.x, 1e-3f); assertEquals(t.y - 0.01f, tt.y, 1e-3f)
        assertNotNull(tt)
    }
}
