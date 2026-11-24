package com.openroomai.app.ar

import android.app.Activity
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Opening
import com.openroomai.app.model.OpeningType
import com.openroomai.app.model.Room
import com.openroomai.app.model.Segment
import com.openroomai.app.model.Vec2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class ScanStep { FLOOR, OUTLINE, OPENINGS, FURNITURE, PREVIEW, ALIGN }

data class ManualBox(val corners: List<Vec2>, val center: Vec2, val width: Float, val depth: Float, val angleRad: Float)

data class ArUiState(
    val step: ScanStep = ScanStep.FLOOR,
    val tracking: Boolean = false,
    val message: String = "Starting camera…",
    val floorFound: Boolean = false,
    val reticleOnFloor: Boolean = false,
    val corners: Int = 0,
    val outline: List<Vec2> = emptyList(),
    val outlineClosed: Boolean = false,
    val perimeter: Float = 0f,
    val area: Float = 0f,
    val openings: List<Opening> = emptyList(),
    val openingType: OpeningType = OpeningType.DOOR,
    val pendingPoint: Boolean = false,
    val detected: List<DetectedObject> = emptyList(),
    val manual: List<ManualBox> = emptyList(),
    val depthSupported: Boolean = false,
    val coverage: Float = 0f,
    val alignPoints: Int = 0,
    val aligned: Boolean = false,
    val error: String? = null,
)

/**
 * Owns the ARCore session and renders the scan / preview overlays.
 * UI talks to it through [state] (read) and the command methods (write, queued to the GL thread).
 */
class ArController : GLSurfaceView.Renderer {

    private var session: Session? = null
    private var installRequested = false
    private var activity: Activity? = null
    /** Changes whenever a fresh ARCore session (= fresh world origin) is created. */
    var sessionToken: String = ""
        private set

    private val _state = MutableStateFlow(ArUiState())
    val state: StateFlow<ArUiState> = _state

    private val background = BackgroundRenderer()
    private val prims = PrimitiveRenderer()
    private val accumulator = ScanAccumulator()
    private val commands = ConcurrentLinkedQueue<(Frame) -> Unit>()

    private var viewW = 1
    private var viewH = 1
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val invVp = FloatArray(16)

    // Scan model (GL thread owned).
    private var step = ScanStep.FLOOR
    private var floorY = Float.NaN
    private val corners = ArrayList<Vec2>()
    private var closed = false
    private val openings = ArrayList<Opening>()
    private var openingType = OpeningType.DOOR
    private var pending: Vec2? = null
    private val manual = ArrayList<ManualBox>()
    private var detected: List<DetectedObject> = emptyList()
    private var reticle: Vec2? = null
    private var frameNo = 0L
    private var depthOn = false
    @Volatile private var geometryDirty = true

    // Preview model.
    private var previewRoom: Room? = null
    private var previewTargets: List<Furniture> = emptyList()
    /** world = R(theta) * plan + t (on the floor plane). */
    private var alignTheta = 0f
    private var alignT = Vec2.ZERO
    private val alignTaps = ArrayList<Vec2>()
    private var aligned = false

    private val lines = FloatList()
    private val tris = FloatList()
    private val pts = FloatList()

    // ------------------------------------------------------------------ lifecycle

    /** @return null on success, or a human readable reason AR can't run. */
    fun resume(act: Activity): String? {
        activity = act
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(act, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return "Installing Google Play Services for AR…" }
                    ArCoreApk.InstallStatus.INSTALLED -> {}
                    null -> {}
                }
                val s = Session(act)
                val cfg = Config(s).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                    depthMode = if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                }
                depthOn = cfg.depthMode == Config.DepthMode.AUTOMATIC
                s.configure(cfg)
                session = s
                sessionToken = UUID.randomUUID().toString()
                geometryDirty = true
                _state.value = _state.value.copy(depthSupported = depthOn)
            } catch (e: UnavailableException) {
                return "AR isn't available on this device (${e.javaClass.simpleName}). Use manual sketch instead."
            } catch (e: Exception) {
                return "Couldn't start AR: ${e.message}"
            }
        }
        return try {
            session?.resume(); null
        } catch (e: CameraNotAvailableException) {
            session = null
            "Camera is busy. Close other camera apps and try again."
        }
    }

    fun pause() {
        session?.pause()
    }

    fun destroy() {
        session?.close()
        session = null
    }

    // ------------------------------------------------------------------ commands from UI

    fun startNewScan() = commands.add {
        step = ScanStep.FLOOR
        corners.clear(); closed = false; openings.clear(); manual.clear(); detected = emptyList()
        accumulator.clear(); pending = null
        if (!floorY.isNaN()) step = ScanStep.OUTLINE
        publish()
    }

    fun setStep(s: ScanStep) = commands.add { step = s; pending = null; publish() }
    fun setOpeningType(t: OpeningType) = commands.add { openingType = t; pending = null; publish() }

    /** Tap on screen (pixels). Uses the floor ray hit. */
    fun tap(x: Float, y: Float) = commands.add { f -> floorHit(f, x, y)?.let { handlePoint(it) } }

    /** "Add point" button: uses the center reticle. */
    fun addAtReticle() = commands.add { reticle?.let { handlePoint(it) } }

    fun undo() = commands.add {
        when (step) {
            ScanStep.OUTLINE -> if (closed) closed = false else if (corners.isNotEmpty()) corners.removeAt(corners.size - 1)
            ScanStep.OPENINGS -> if (pending != null) pending = null else if (openings.isNotEmpty()) openings.removeAt(openings.size - 1)
            ScanStep.FURNITURE -> if (pending != null) pending = null else if (manual.isNotEmpty()) manual.removeAt(manual.size - 1)
            ScanStep.ALIGN -> if (alignTaps.isNotEmpty()) alignTaps.removeAt(alignTaps.size - 1)
            else -> {}
        }
        publish()
    }

    fun closeOutline() = commands.add {
        if (corners.size >= 3) { closed = true; step = ScanStep.OPENINGS }
        publish()
    }

    fun rescanFurniture() = commands.add { accumulator.clear(); detected = emptyList(); publish() }

    /** Show [targets] for [room] in AR. If the room came from this very session, no alignment needed. */
    fun startPreview(room: Room, targets: List<Furniture>) = commands.add {
        previewRoom = room
        previewTargets = targets
        alignTaps.clear()
        if (room.scannedInAr && room.arSessionToken == sessionToken) {
            alignTheta = 0f; alignT = Vec2.ZERO; aligned = true; step = ScanStep.PREVIEW
        } else {
            aligned = false; step = ScanStep.ALIGN
        }
        publish()
    }

    fun realign() = commands.add { alignTaps.clear(); aligned = false; step = ScanStep.ALIGN; publish() }

    /** Builds the scanned room. Call from UI thread; reads a consistent snapshot via the state. */
    fun buildRoom(name: String): Room? {
        val st = _state.value
        val outline = st.outline
        if (outline.size < 3) return null
        val center = Geo.centroid(outline)
        val furniture = ArrayList<Furniture>()
        st.detected.forEach { d ->
            furniture += orient(Furniture(type = d.guess, center = d.center, width = d.width, depth = d.depth, height = d.height, rotationDeg = Math.toDegrees(d.angleRad.toDouble()).toFloat(), name = d.guess.label), center)
        }
        st.manual.forEach { m ->
            furniture += orient(Furniture(type = FurnitureType.OTHER, center = m.center, width = m.width, depth = m.depth, rotationDeg = Math.toDegrees(m.angleRad.toDouble()).toFloat(), name = "Item"), center)
        }
        // Number duplicates: "Chair 1", "Chair 2"…
        val counts = furniture.groupingBy { it.type }.eachCount()
        val seen = HashMap<FurnitureType, Int>()
        val named = furniture.map { f ->
            if ((counts[f.type] ?: 0) > 1) { val n = (seen[f.type] ?: 0) + 1; seen[f.type] = n; f.copy(name = "${f.name} $n") } else f
        }
        return Room(
            name = name, outline = outline, furniture = named, openings = st.openings,
            floorY = if (floorY.isNaN()) 0f else floorY, scannedInAr = true, arSessionToken = sessionToken,
        )
    }

    /** Point the usable side of a detected piece toward the room center (backs to walls). */
    private fun orient(f: Furniture, roomCenter: Vec2): Furniture {
        val toCenter = (roomCenter - f.center).normalized()
        return if ((f.front() dot toCenter) < 0) f.withPose(f.center, f.rotationDeg + 180f) else f.withPose(f.center, f.rotationDeg)
    }

    // ------------------------------------------------------------------ point handling (GL thread)

    private fun handlePoint(p: Vec2) {
        when (step) {
            ScanStep.OUTLINE -> {
                if (closed) return
                // Tapping near the first corner closes the room.
                if (corners.size >= 3 && p.dist(corners[0]) < 0.25f) { closed = true; step = ScanStep.OPENINGS }
                else corners.add(p)
            }
            ScanStep.OPENINGS -> {
                val a = pending
                if (a == null) pending = p else {
                    addOpening(a, p); pending = null
                }
            }
            ScanStep.FURNITURE -> {
                val a = pending
                if (a == null) pending = p else {
                    manual += boxFromDiagonal(a, p); pending = null
                }
            }
            ScanStep.ALIGN -> {
                alignTaps += p
                if (alignTaps.size == 2) computeAlignment()
            }
            else -> {}
        }
        publish()
    }

    private fun addOpening(a: Vec2, b: Vec2) {
        if (corners.size < 3) return
        val walls = Geo.edges(corners)
        val mid = (a + b) / 2f
        val wi = walls.indices.minByOrNull { walls[it].distanceTo(mid) } ?: return
        val w = walls[wi]
        val ta = w.closestT(a); val tb = w.closestT(b)
        val width = abs(tb - ta) * w.length
        if (width < 0.3f) return
        openings += Opening(type = openingType, wallIndex = wi, t = (ta + tb) / 2f, width = width)
    }

    private fun boxFromDiagonal(a: Vec2, b: Vec2): ManualBox {
        // Align the box with the closest wall direction (or wall A) so it looks tidy.
        val ref = if (corners.size >= 2) Geo.edges(corners).minByOrNull { it.distanceTo((a + b) / 2f) }!!.dir else Vec2(1f, 0f)
        val ang = ref.angle()
        val la = a.rotated(-ang); val lb = b.rotated(-ang)
        val w = abs(lb.x - la.x).coerceAtLeast(0.2f)
        val d = abs(lb.y - la.y).coerceAtLeast(0.2f)
        val c = ((la + lb) / 2f).rotated(ang)
        return ManualBox(Geo.rectCorners(c, w, d, ang), c, w, d, ang)
    }

    private fun computeAlignment() {
        val room = previewRoom ?: return
        // Taps are the real-world positions of plan corners A (0) and B (1).
        val pa = room.outline[0]; val pb = room.outline[1 % room.outline.size]
        val wa = alignTaps[0]; val wb = alignTaps[1]
        val planAng = (pb - pa).angle()
        val worldAng = (wb - wa).angle()
        alignTheta = worldAng - planAng
        alignT = wa - pa.rotated(alignTheta)
        aligned = true
        step = ScanStep.PREVIEW
    }

    private fun toWorld(p: Vec2): Vec2 = p.rotated(alignTheta) + alignT

    // ------------------------------------------------------------------ GLSurfaceView.Renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.06f, 0.08f, 1f)
        background.create()
        prims.create()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width; viewH = height
        GLES20.glViewport(0, 0, width, height)
        geometryDirty = true
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int {
        val a = activity ?: return Surface.ROTATION_0
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) a.display?.rotation ?: Surface.ROTATION_0
        else (a.getSystemService(Activity.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (geometryDirty && viewW > 1) { s.setDisplayGeometry(displayRotation(), viewW, viewH); geometryDirty = false }
        try {
            s.setCameraTextureName(background.textureId)
            val frame = s.update()
            background.draw(frame)
            val cam = frame.camera
            frameNo++

            while (true) { val c = commands.poll() ?: break; c(frame) }

            if (cam.trackingState != TrackingState.TRACKING) {
                val why = when (cam.trackingFailureReason) {
                    TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark — turn on a light"
                    TrackingFailureReason.EXCESSIVE_MOTION -> "Slow down a little"
                    TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point at something with more texture"
                    TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable"
                    else -> "Move your phone slowly to start tracking"
                }
                _state.value = _state.value.copy(tracking = false, message = why)
                return
            }

            cam.getProjectionMatrix(proj, 0, 0.05f, 50f)
            cam.getViewMatrix(view, 0)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
            Matrix.invertM(invVp, 0, vp, 0)

            updateFloor(frame)
            if (!floorY.isNaN()) {
                reticle = floorHit(frame, viewW / 2f, viewH / 2f)
                if (step == ScanStep.FLOOR) step = ScanStep.OUTLINE
                if (step == ScanStep.FURNITURE || step == ScanStep.OPENINGS || step == ScanStep.OUTLINE) accumulate(frame)
                if (step == ScanStep.FURNITURE && frameNo % 20 == 0L) detected = accumulator.detect(if (closed) corners else null)
            }

            drawScene()
            if (frameNo % 6 == 0L) publish()
        } catch (e: CameraNotAvailableException) {
            _state.value = _state.value.copy(error = "Camera not available")
        } catch (t: Throwable) {
            Log.e("OpenRoomAR", "frame", t)
        }
    }

    private fun updateFloor(frame: Frame) {
        val sess = session ?: return
        val camY = frame.camera.pose.ty()
        var best = Float.NaN
        for (p in sess.getAllTrackables(Plane::class.java)) {
            if (p.trackingState != TrackingState.TRACKING || p.subsumedBy != null) continue
            if (p.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            if (p.extentX * p.extentZ < 0.25f) continue
            val y = p.centerPose.ty()
            if (camY - y < 0.6f) continue // table tops are not the floor
            if (best.isNaN() || y < best) best = y
        }
        if (!best.isNaN()) floorY = if (floorY.isNaN()) best else floorY * 0.9f + best * 0.1f
    }

    private fun floorHit(frame: Frame, sx: Float, sy: Float): Vec2? {
        if (floorY.isNaN()) return null
        val nx = 2f * sx / viewW - 1f
        val ny = 1f - 2f * sy / viewH
        val near = unproject(nx, ny, -1f)
        val far = unproject(nx, ny, 1f)
        val dy = far[1] - near[1]
        if (abs(dy) < 1e-6f) return null
        val t = (floorY - near[1]) / dy
        if (t < 0f || t > 1f) return null
        val x = near[0] + (far[0] - near[0]) * t
        val z = near[2] + (far[2] - near[2]) * t
        val cam = frame.camera.pose
        if (Vec2(x, z).dist(Vec2(cam.tx(), cam.tz())) > 8f) return null
        return Vec2(x, z)
    }

    private fun unproject(x: Float, y: Float, z: Float): FloatArray {
        val inp = floatArrayOf(x, y, z, 1f)
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, invVp, 0, inp, 0)
        return floatArrayOf(out[0] / out[3], out[1] / out[3], out[2] / out[3])
    }

    private fun accumulate(frame: Frame) {
        // Sparse feature points every frame.
        try {
            frame.acquirePointCloud().use { pc ->
                val b = pc.points
                while (b.remaining() >= 4) {
                    val x = b.get(); val y = b.get(); val z = b.get(); val c = b.get()
                    if (c > 0.3f) accumulator.add(x, y, z, floorY)
                }
            }
        } catch (_: Exception) {}

        if (!depthOn || frameNo % 3 != 0L) return
        try {
            frame.acquireDepthImage16Bits().use { img ->
                val plane = img.planes[0]
                val buf = plane.buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val rowStride = plane.rowStride
                val pxStride = plane.pixelStride
                val w = img.width; val h = img.height
                val intr = frame.camera.textureIntrinsics
                val dims = intr.imageDimensions
                val f = intr.focalLength; val pp = intr.principalPoint
                val sx = w.toFloat() / dims[0]; val sy = h.toFloat() / dims[1]
                val fx = f[0] * sx; val fy = f[1] * sy; val cx = pp[0] * sx; val cy = pp[1] * sy
                val pose = frame.camera.pose
                val local = FloatArray(3)
                val world = FloatArray(3)
                var v = 0
                while (v < h) {
                    var u = 0
                    while (u < w) {
                        val mm = buf.getShort(v * rowStride + u * pxStride).toInt() and 0xFFFF
                        if (mm in 300..4500) {
                            val d = mm / 1000f
                            // Camera space: +X right, +Y up, -Z forward; image v grows downward.
                            local[0] = (u - cx) * d / fx
                            local[1] = -(v - cy) * d / fy
                            local[2] = -d
                            pose.transformPoint(local, 0, world, 0)
                            accumulator.add(world[0], world[1], world[2], floorY)
                        }
                        u += 3
                    }
                    v += 3
                }
            }
        } catch (_: NotYetAvailableException) {
        } catch (e: Exception) {
            Log.w("OpenRoomAR", "depth", e)
        }
    }

    // ------------------------------------------------------------------ drawing

    private fun drawScene() {
        val fy = floorY
        if (fy.isNaN()) return
        lines.clear(); tris.clear(); pts.clear()

        when (step) {
            ScanStep.PREVIEW, ScanStep.ALIGN -> drawPreview(fy)
            else -> drawScan(fy)
        }
        // Reticle.
        reticle?.let { r ->
            val rad = 0.08f
            for (i in 0 until 24) {
                val a0 = (i / 24f) * 6.2832f; val a1 = ((i + 1) / 24f) * 6.2832f
                lines.add(r.x + cos(a0) * rad, fy + 0.005f, r.y + sin(a0) * rad)
                lines.add(r.x + cos(a1) * rad, fy + 0.005f, r.y + sin(a1) * rad)
            }
            pts.add(r.x, fy + 0.005f, r.y)
            prims.lines(vp, lines.data, lines.vertices, 0xFFFFFFFF, 4f)
            prims.points(vp, pts.data, pts.vertices, 0xFFFFFFFF, 14f)
        }
    }

    private fun drawScan(fy: Float) {
        val wallH = 2.4f
        // Room outline + "virtual walls".
        for (i in 0 until corners.size) {
            val a = corners[i]
            val bIdx = i + 1
            if (bIdx >= corners.size && !closed) break
            val b = corners[bIdx % corners.size]
            seg(lines, a, b, fy + 0.01f)
            // translucent wall
            quadV(tris, a, b, fy, fy + wallH)
        }
        for (c in corners) { lines.add(c.x, fy, c.y); lines.add(c.x, fy + wallH, c.y) }
        // Rubber band from last corner to the reticle.
        if (step == ScanStep.OUTLINE && !closed && corners.isNotEmpty()) reticle?.let { seg(lines, corners.last(), it, fy + 0.01f) }
        prims.triangles(vp, tris.data, tris.vertices, 0x262EE6A8)
        prims.lines(vp, lines.data, lines.vertices, 0xFF2EE6A8, 8f)
        tris.clear(); lines.clear()

        // Openings.
        if (corners.size >= 3) {
            val walls = Geo.edges(corners)
            for (o in openings) {
                val w = walls.getOrNull(o.wallIndex) ?: continue
                val half = o.width / 2f / w.length
                val a = w.pointAt((o.t - half).coerceIn(0f, 1f)); val b = w.pointAt((o.t + half).coerceIn(0f, 1f))
                if (o.type == OpeningType.DOOR) quadV(tris, a, b, fy, fy + 2.05f) else quadV(tris, a, b, fy + 0.9f, fy + 2.1f)
                prims.triangles(vp, tris.data, tris.vertices, if (o.type == OpeningType.DOOR) 0x88FFB74D else 0x8864B5F6)
                tris.clear()
            }
        }
        pending?.let { p -> pts.add(p.x, fy + 0.01f, p.y); reticle?.let { r -> seg(lines, p, r, fy + 0.01f) } }

        // Furniture: auto-detected (amber) and manual (pink).
        for (d in detected) box(lines, d.corners(), fy, fy + d.height)
        prims.lines(vp, lines.data, lines.vertices, 0xFFFFC94D, 6f)
        lines.clear()
        for (m in manual) box(lines, m.corners, fy, fy + 0.8f)
        prims.lines(vp, lines.data, lines.vertices, 0xFFFF6FB5, 6f)
        lines.clear()
        prims.points(vp, pts.data, pts.vertices, 0xFFFF6FB5, 22f)
        pts.clear()
    }

    private fun drawPreview(fy: Float) {
        val room = previewRoom ?: return
        if (step == ScanStep.ALIGN) {
            for (p in alignTaps) pts.add(p.x, fy + 0.01f, p.y)
            prims.points(vp, pts.data, pts.vertices, 0xFFFFC94D, 26f)
            pts.clear()
            return
        }
        // Room outline.
        val ol = room.outline.map { toWorld(it) }
        for (i in ol.indices) seg(lines, ol[i], ol[(i + 1) % ol.size], fy + 0.01f)
        prims.lines(vp, lines.data, lines.vertices, 0x992EE6A8, 5f)
        lines.clear()
        val current = room.furniture.associateBy { it.id }
        for (t in previewTargets) {
            val tc = t.corners().map { toWorld(it) }
            val top = fy + t.height
            // Ghost target: translucent box + bright edges.
            for (i in 0 until 4) quadV(tris, tc[i], tc[(i + 1) % 4], fy, top)
            topQuad(tris, tc, top)
            box(lines, tc, fy, top)
            val c = current[t.id]
            if (c != null && (c.center.dist(t.center) > 0.08f || abs(c.rotationDelta(t)) > 5f)) {
                // Arrow from where it is now to where it goes.
                val from = toWorld(c.center); val to = toWorld(t.center)
                arrow(lines, from, to, fy + 0.02f)
            }
        }
        prims.triangles(vp, tris.data, tris.vertices, 0x402EE6A8)
        prims.lines(vp, lines.data, lines.vertices, 0xFF2EE6A8, 7f)
        tris.clear(); lines.clear()
        // Current footprints in red so you can see what moves.
        for (c in room.furniture) {
            val t = previewTargets.firstOrNull { it.id == c.id } ?: continue
            if (c.center.dist(t.center) <= 0.08f && abs(c.rotationDelta(t)) <= 5f) continue
            val cc = c.corners().map { toWorld(it) }
            for (i in 0 until 4) seg(lines, cc[i], cc[(i + 1) % 4], fy + 0.015f)
        }
        prims.lines(vp, lines.data, lines.vertices, 0xFFFF5370, 5f)
        lines.clear()
    }

    private fun seg(l: FloatList, a: Vec2, b: Vec2, y: Float) { l.add(a.x, y, a.y); l.add(b.x, y, b.y) }

    private fun quadV(t: FloatList, a: Vec2, b: Vec2, y0: Float, y1: Float) {
        t.add(a.x, y0, a.y); t.add(b.x, y0, b.y); t.add(b.x, y1, b.y)
        t.add(a.x, y0, a.y); t.add(b.x, y1, b.y); t.add(a.x, y1, a.y)
    }

    private fun topQuad(t: FloatList, c: List<Vec2>, y: Float) {
        t.add(c[0].x, y, c[0].y); t.add(c[1].x, y, c[1].y); t.add(c[2].x, y, c[2].y)
        t.add(c[0].x, y, c[0].y); t.add(c[2].x, y, c[2].y); t.add(c[3].x, y, c[3].y)
    }

    private fun box(l: FloatList, c: List<Vec2>, y0: Float, y1: Float) {
        for (i in 0 until 4) {
            val a = c[i]; val b = c[(i + 1) % 4]
            seg(l, a, b, y0); seg(l, a, b, y1)
            l.add(a.x, y0, a.y); l.add(a.x, y1, a.y)
        }
    }

    private fun arrow(l: FloatList, from: Vec2, to: Vec2, y: Float) {
        seg(l, from, to, y)
        val d = (to - from).normalized()
        val back = to - d * 0.18f
        val side = d.perp() * 0.1f
        seg(l, to, back + side, y); seg(l, to, back - side, y)
    }

    private fun publish() {
        val per = if (corners.size >= 2) {
            val e = if (closed) Geo.edges(corners) else (0 until corners.size - 1).map { Segment(corners[it], corners[it + 1]) }
            e.sumOf { it.length.toDouble() }.toFloat()
        } else 0f
        val msg = when (step) {
            ScanStep.FLOOR -> "Point at the floor and sweep slowly to find it"
            ScanStep.OUTLINE -> when {
                floorY.isNaN() -> "Point at the floor and sweep slowly to find it"
                corners.isEmpty() -> "Aim the ring at a room corner where the floor meets the walls, then tap +"
                corners.size < 3 -> "Next corner… (${corners.size} placed)"
                else -> "Keep adding corners, or tap ✓ to close the room"
            }
            ScanStep.OPENINGS -> if (pending == null) "Mark a ${openingType.name.lowercase()}: aim at one edge on the floor and tap +" else "Now the other edge of the ${openingType.name.lowercase()}"
            ScanStep.FURNITURE -> if (pending == null) "Walk around your furniture slowly — boxes appear as it's detected. Missing one? Tap two opposite corners." else "Tap the opposite corner of the item"
            ScanStep.ALIGN -> if (alignTaps.isEmpty()) "Align: aim at the real corner A of the room and tap +" else "Now aim at corner B and tap +"
            ScanStep.PREVIEW -> "Green ghosts show where each piece goes. Red outlines = where they are now."
        }
        _state.value = _state.value.copy(
            step = step, tracking = true, message = msg, floorFound = !floorY.isNaN(),
            reticleOnFloor = reticle != null, corners = corners.size, outline = corners.toList(), outlineClosed = closed,
            perimeter = per, area = if (corners.size >= 3) Geo.absArea(corners) else 0f,
            openings = openings.toList(), openingType = openingType, pendingPoint = pending != null,
            detected = detected, manual = manual.toList(), coverage = accumulator.coveredArea(),
            alignPoints = alignTaps.size, aligned = aligned, error = null,
        )
    }
}
