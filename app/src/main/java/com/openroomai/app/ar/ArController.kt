package com.openroomai.app.ar

import android.app.Activity
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Pose
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.openroomai.app.engine.CameraSnapshot
import com.openroomai.app.engine.Detection
import com.openroomai.app.engine.VisionFusion
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class ScanStep { FLOOR, OUTLINE, OPENINGS, FURNITURE, PREVIEW, ALIGN, PLACE }

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
    /** Camera recognition: summary like ["couch", "chair ×2"]. */
    val recognized: List<String> = emptyList(),
    val visionReady: Boolean = false,
    val visionMs: Long = 0,
    /** Real objects hide virtual ones (depth occlusion active). */
    val occlusion: Boolean = false,
    // Place-in-AR
    val placing: String? = null,
    val placed: Boolean = false,
    val placeOk: Boolean? = null,
    val placeStatus: String = "",
    val placeRotation: Float = 0f,
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

    /** Set by the scan screen; lets us record which way the room faces while you scan. */
    @Volatile var compass: com.openroomai.app.sensors.Compass? = null
    private var northSin = 0.0
    private var northCos = 0.0
    private var northSamples = 0
    @Volatile private var geometryDirty = true

    // Preview model.
    private var previewRoom: Room? = null
    private var previewTargets: List<Furniture> = emptyList()
    /** world = R(theta) * plan + t (on the floor plane). */
    private var alignTheta = 0f
    private var alignT = Vec2.ZERO
    private val alignTaps = ArrayList<Vec2>()
    private var aligned = false

    // Anchors keep content glued to the real world as ARCore refines its map (Google: "always use anchors").
    private val cornerAnchors = ArrayList<Anchor?>()
    private val alignAnchors = ArrayList<Anchor>()
    /** Plan points matched to [alignAnchors] / [cornerAnchors] for the live rigid fit. */
    private var alignSrc: List<Vec2> = emptyList()
    private var alignFromCorners = false
    private var afterAlign = ScanStep.PREVIEW

    // Camera recognition (runs off the GL thread).
    @Volatile private var detector: VisionDetector? = null
    private val detectorLoading = AtomicBoolean(false)
    private val visionBusy = AtomicBoolean(false)
    private val visionExec = Executors.newSingleThreadExecutor { r -> Thread(r, "vision").apply { isDaemon = true } }
    private val visionResults = ConcurrentLinkedQueue<Pair<CameraSnapshot, List<Detection>>>()
    private val votes = VisionFusion.VoteBook()
    private var sensorOrientation = -1

    // Depth occlusion for virtual furniture.
    private val depthTex = DepthTexture()

    // Place-in-AR.
    private var placing: Furniture? = null
    private var placeAnchor: Anchor? = null
    private var placeRotDeg = 0f
    /** Last placement in plan coordinates (center, rotation), readable from the UI thread. */
    @Volatile var placedInPlan: Pair<Vec2, Float>? = null
        private set

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
        if (detector == null && detectorLoading.compareAndSet(false, true)) {
            val ctx = act.applicationContext
            visionExec.execute { detector = VisionDetector.create(ctx) }
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
        visionExec.shutdownNow()
        detector?.close()
        detector = null
    }

    private fun newAnchor(p: Vec2, y: Float = floorY): Anchor? = try {
        session?.createAnchor(Pose.makeTranslation(p.x, if (y.isNaN()) 0f else y, p.y))
    } catch (_: Exception) { null }

    private fun detachAll(list: MutableList<out Anchor?>) { list.forEach { it?.detach() }; list.clear() }

    // ------------------------------------------------------------------ commands from UI

    fun startNewScan() = commands.add {
        step = ScanStep.FLOOR
        corners.clear(); closed = false; openings.clear(); manual.clear(); detected = emptyList()
        accumulator.clear(); pending = null
        northSin = 0.0; northCos = 0.0; northSamples = 0
        detachAll(cornerAnchors); detachAll(alignAnchors); votes.clear()
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
            ScanStep.OUTLINE -> if (closed) closed = false else if (corners.isNotEmpty()) {
                corners.removeAt(corners.size - 1)
                cornerAnchors.removeLastOrNull()?.detach()
            }
            ScanStep.OPENINGS -> if (pending != null) pending = null else if (openings.isNotEmpty()) openings.removeAt(openings.size - 1)
            ScanStep.FURNITURE -> if (pending != null) pending = null else if (manual.isNotEmpty()) manual.removeAt(manual.size - 1)
            ScanStep.ALIGN -> if (alignTaps.isNotEmpty()) {
                alignTaps.removeAt(alignTaps.size - 1)
                alignAnchors.removeLastOrNull()?.detach()
            }
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
        beginAlignment(room, ScanStep.PREVIEW)
        publish()
    }

    /**
     * Same session: glue the plan to the scan's corner anchors (drift-corrected every frame).
     * Otherwise ask for two taps (corners A and B), which become anchors too.
     */
    private fun beginAlignment(room: Room, then: ScanStep) {
        alignTaps.clear(); detachAll(alignAnchors)
        afterAlign = then
        val sameScan = room.scannedInAr && room.arSessionToken == sessionToken &&
            cornerAnchors.size == room.outline.size && cornerAnchors.all { it != null }
        if (sameScan) {
            alignSrc = room.outline; alignFromCorners = true
            alignTheta = 0f; alignT = Vec2.ZERO; aligned = true; step = then
        } else if (room.scannedInAr && room.arSessionToken == sessionToken) {
            alignSrc = emptyList(); alignFromCorners = false
            alignTheta = 0f; alignT = Vec2.ZERO; aligned = true; step = then
        } else {
            alignSrc = emptyList(); alignFromCorners = false
            aligned = false; step = ScanStep.ALIGN
        }
    }

    /** Re-fit plan → world from the live anchor poses (call every tracked frame). */
    private fun refreshAlignment() {
        val anchors: List<Anchor?> = if (alignFromCorners) cornerAnchors else alignAnchors
        if (alignSrc.isEmpty() || anchors.size != alignSrc.size) return
        val dst = ArrayList<Vec2>(anchors.size)
        for (a in anchors) {
            if (a == null || a.trackingState != TrackingState.TRACKING) return
            dst += Vec2(a.pose.tx(), a.pose.tz())
        }
        val (th, t) = Geo.fitRigid(alignSrc, dst)
        alignTheta = th; alignT = t
    }

    // ------------------------------------------------------------------ place it in AR

    /** Walk a piece (e.g. one you're thinking of buying) around the real room at true size. */
    fun startPlacement(item: Furniture, room: Room?) = commands.add {
        placing = item
        placeAnchor?.detach(); placeAnchor = null
        placeRotDeg = 0f
        placedInPlan = null
        previewRoom = room
        previewTargets = room?.furniture ?: emptyList()
        if (room != null) beginAlignment(room, ScanStep.PLACE) else { aligned = false; step = ScanStep.PLACE }
        publish()
    }

    fun placeAtReticle() = commands.add {
        val r = reticle ?: return@add
        placeAnchor?.detach()
        placeAnchor = newAnchor(r)
        publish()
    }

    fun rotatePlacement(deltaDeg: Float) = commands.add { placeRotDeg = Geo.normalizeDeg(placeRotDeg + deltaDeg); publish() }

    fun realign() = commands.add { alignTaps.clear(); aligned = false; step = ScanStep.ALIGN; publish() }

    /** Builds the scanned room. Call from UI thread; reads a consistent snapshot via the state. */
    fun buildRoom(name: String): Room? {
        val st = _state.value
        val outline = st.outline
        if (outline.size < 3) return null
        val center = Geo.centroid(outline)
        val furniture = ArrayList<Furniture>()
        st.detected.forEach { d ->
            furniture += orient(Furniture(
                type = d.guess, center = d.center, width = d.width, depth = d.depth, height = d.height,
                rotationDeg = Math.toDegrees(d.angleRad.toDouble()).toFloat(), name = d.name ?: d.guess.label,
                color = d.color, seenAs = d.seenAs,
            ), center)
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
            northDeg = if (northSamples > 30) ((Math.toDegrees(kotlin.math.atan2(northSin, northCos)) + 360.0) % 360.0).toFloat() else null,
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
                else { corners.add(p); cornerAnchors.add(newAnchor(p)) }
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
                newAnchor(p)?.let { alignAnchors += it }
                if (alignTaps.size == 2) computeAlignment()
            }
            ScanStep.PLACE -> {
                placeAnchor?.detach()
                placeAnchor = newAnchor(p)
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
        // Keep following the two tap anchors as tracking improves.
        if (alignAnchors.size == 2) { alignSrc = listOf(pa, pb); alignFromCorners = false }
        step = afterAlign
    }

    private fun toPlan(w: Vec2): Vec2 = (w - alignT).rotated(-alignTheta)

    private fun toWorld(p: Vec2): Vec2 = p.rotated(alignTheta) + alignT

    // ------------------------------------------------------------------ GLSurfaceView.Renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.06f, 0.08f, 1f)
        background.create()
        prims.create()
        depthTex.create()
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
            sampleNorth(frame)
            // Anchored corners follow ARCore's refined map.
            for (i in corners.indices) {
                val a = cornerAnchors.getOrNull(i) ?: continue
                if (a.trackingState == TrackingState.TRACKING) corners[i] = Vec2(a.pose.tx(), a.pose.tz())
            }
            if (aligned) refreshAlignment()
            if (!floorY.isNaN()) {
                reticle = floorHit(frame, viewW / 2f, viewH / 2f)
                if (step == ScanStep.FLOOR) step = ScanStep.OUTLINE
                if (step == ScanStep.FURNITURE || step == ScanStep.OPENINGS || step == ScanStep.OUTLINE) accumulate(frame)
                if (step == ScanStep.FURNITURE) {
                    if (frameNo % 24 == 0L) captureForVision(frame)
                    fuseVision()
                    if (frameNo % 20 == 0L) detected = withVision(accumulator.detect(if (closed) corners else null))
                }
            }
            if ((step == ScanStep.PREVIEW || step == ScanStep.PLACE) && depthOn) depthTex.update(frame)

            drawScene()
            if (frameNo % 6 == 0L) publish()
        } catch (e: CameraNotAvailableException) {
            _state.value = _state.value.copy(error = "Camera not available")
        } catch (t: Throwable) {
            Log.e("OpenRoomAR", "frame", t)
        }
    }

    /**
     * Pair the compass bearing of the camera with the camera's heading in AR space; their
     * difference is the compass bearing of the plan's "up" (−Z) axis. Averaged on the circle.
     */
    private fun sampleNorth(frame: Frame) {
        val bearing = compass?.cameraBearing ?: return
        val z = frame.camera.pose.zAxis
        val fx = -z[0]; val fz = -z[2]
        if (kotlin.math.hypot(fx, fz) < 0.5f) return
        val rel = Math.toDegrees(kotlin.math.atan2(fx, -fz).toDouble())
        val north = Math.toRadians(bearing - rel)
        northSin += kotlin.math.sin(north); northCos += kotlin.math.cos(north); northSamples++
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

        if (!depthOn || frameNo % 2 != 0L) return
        // Prefer Raw Depth + its confidence image (as in Google's ARCore Raw Depth codelab):
        // fewer points, but each one is trustworthy. Smoothed depth is used where raw isn't offered.
        val ok = rawDepthOk && try {
            accumulateDepth(frame, raw = true)
        } catch (_: NotYetAvailableException) {
            true
        } catch (e: Exception) {
            rawDepthOk = false // this device/config has no raw depth: stop asking every frame
            false
        }
        if (!ok) try {
            accumulateDepth(frame, raw = false)
        } catch (_: NotYetAvailableException) {
        } catch (e: Exception) {
            Log.w("OpenRoomAR", "depth", e)
        }
    }

    private var lastDepthTimestamp = 0L
    private var rawDepthOk = true

    /** @return true if a depth image was consumed (or was a duplicate). */
    private fun accumulateDepth(frame: Frame, raw: Boolean): Boolean {
        val img = if (raw) frame.acquireRawDepthImage16Bits() else frame.acquireDepthImage16Bits()
        img.use { depth ->
            // Depth updates slower than the camera: don't count the same depth frame twice.
            if (depth.timestamp == lastDepthTimestamp) return true
            lastDepthTimestamp = depth.timestamp
            val conf = if (raw) frame.acquireRawDepthConfidenceImage() else null
            try {
                val plane = depth.planes[0]
                val buf = plane.buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val rowStride = plane.rowStride
                val pxStride = plane.pixelStride
                val cPlane = conf?.planes?.get(0)
                val cBuf = cPlane?.buffer
                val w = depth.width; val h = depth.height
                val intr = frame.camera.textureIntrinsics
                val dims = intr.imageDimensions
                val f = intr.focalLength; val pp = intr.principalPoint
                val sx = w.toFloat() / dims[0]; val sy = h.toFloat() / dims[1]
                val fx = f[0] * sx; val fy = f[1] * sy; val cx = pp[0] * sx; val cy = pp[1] * sy
                val pose = frame.camera.pose
                val walls = wallPlanes()
                val local = FloatArray(3)
                val world = FloatArray(3)
                val maxMm = if (raw) 3500 else 4500 // raw depth gets noisy with distance
                val step = if (raw) 2 else 3
                var v = 0
                while (v < h) {
                    var u = 0
                    while (u < w) {
                        val mm = buf.getShort(v * rowStride + u * pxStride).toInt() and 0xFFFF
                        val c = if (cBuf != null) (cBuf.get(v * cPlane.rowStride + u * cPlane.pixelStride).toInt() and 0xFF) else 255
                        if (mm in 300..maxMm && c >= MIN_CONFIDENCE) {
                            val d = mm / 1000f
                            // Camera space: +X right, +Y up, -Z forward; image v grows downward.
                            local[0] = (u - cx) * d / fx
                            local[1] = -(v - cy) * d / fy
                            local[2] = -d
                            pose.transformPoint(local, 0, world, 0)
                            if (!onWall(world, walls)) accumulator.add(world[0], world[1], world[2], floorY)
                        }
                        u += step
                    }
                    v += step
                }
            } finally {
                conf?.close()
            }
        }
        return true
    }

    private class WallPlane(val plane: Plane, val c: FloatArray, val n: FloatArray)

    private fun wallPlanes(): List<WallPlane> {
        val sess = session ?: return emptyList()
        return sess.getAllTrackables(Plane::class.java)
            .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null && it.type == Plane.Type.VERTICAL }
            .map { p ->
                val pose = p.centerPose
                val n = FloatArray(3)
                pose.getTransformedAxis(1, 1f, n, 0) // plane normal = local +Y
                WallPlane(p, floatArrayOf(pose.tx(), pose.ty(), pose.tz()), n)
            }
    }

    /** Points within 3 cm of a detected wall — inside its polygon, not the infinite plane — are wall, not furniture. */
    private fun onWall(p: FloatArray, walls: List<WallPlane>): Boolean {
        for (w in walls) {
            val d = (p[0] - w.c[0]) * w.n[0] + (p[1] - w.c[1]) * w.n[1] + (p[2] - w.c[2]) * w.n[2]
            if (kotlin.math.abs(d) < WALL_BAND && w.plane.isPoseInPolygon(com.google.ar.core.Pose.makeTranslation(p[0], p[1], p[2]))) return true
        }
        return false
    }

    private companion object {
        /** Raw-depth confidence 0–255; the codelab keeps ≥ 0.3 (≈77). */
        const val MIN_CONFIDENCE = 77
        const val WALL_BAND = 0.03f
    }

    // ------------------------------------------------------------------ camera recognition

    /** Grab the CPU camera image, convert it, and run the detector on the background thread. */
    private fun captureForVision(frame: Frame) {
        val det = detector ?: return
        if (!visionBusy.compareAndSet(false, true)) return
        try {
            val cam = frame.camera
            val intr = cam.imageIntrinsics
            val m = FloatArray(16)
            cam.pose.toMatrix(m, 0)
            val dims = intr.imageDimensions
            val snap = CameraSnapshot(m, intr.focalLength[0], intr.focalLength[1], intr.principalPoint[0], intr.principalPoint[1], dims[0], dims[1], imageRotation())
            val rgb = ByteArray(VisionDetector.SIZE * VisionDetector.SIZE * 3)
            frame.acquireCameraImage().use { img -> VisionDetector.yuvToUprightRgb(img, snap.rotationDeg, rgb) }
            visionExec.execute {
                try { visionResults.add(snap to det.detect(rgb)) } catch (t: Throwable) { Log.w("OpenRoomAR", "vision", t) } finally { visionBusy.set(false) }
            }
        } catch (_: NotYetAvailableException) {
            visionBusy.set(false)
        } catch (t: Throwable) {
            visionBusy.set(false)
            Log.w("OpenRoomAR", "vision capture", t)
        }
    }

    /** Clockwise rotation from the sensor image to upright on screen (back camera). */
    private fun imageRotation(): Int {
        if (sensorOrientation < 0) {
            sensorOrientation = try {
                val a = activity ?: return 90
                val id = session?.cameraConfig?.cameraId ?: return 90
                val cm = a.getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            } catch (_: Exception) { 90 }
        }
        val display = when (displayRotation()) {
            Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
        }
        return (sensorOrientation - display + 360) % 360
    }

    /** Merge finished detections into the vote book (GL thread). */
    private fun fuseVision() {
        while (true) {
            val (cam, dets) = visionResults.poll() ?: break
            val clusters = detected.filter { !it.fromCamera }.map { VisionFusion.Cluster(it.center, it.corners(), it.height) }
            val (matched, unmatched) = VisionFusion.match(cam, dets, clusters, floorY)
            matched.forEach { (c, d) -> votes.vote(c.key, d, cameraOnly = false) }
            // Strong sightings the depth scan missed (thin chair legs, dark sofas) become camera-only pieces.
            for (d in unmatched) {
                if (d.label !in VisionFusion.CAMERA_ONLY || d.score < 0.6f) continue
                val sb = VisionFusion.boxToSensor(d.box, cam.rotationDeg)
                // The bottom-centre of the box in the upright image touches the floor.
                val bottom = VisionFusion.uprightToSensor((d.box.l + d.box.r) / 2f, d.box.b, cam.rotationDeg)
                val p = VisionFusion.floorPoint(cam, bottom.x, bottom.y, floorY) ?: continue
                if (closed && corners.size >= 3 && !Geo.pointInPolygon(p, corners)) continue
                if (sb.area > 0f) votes.vote(p, d, cameraOnly = true)
            }
        }
    }

    /** Depth clusters, relabelled and coloured by the camera's votes, plus camera-only finds. */
    private fun withVision(geo: List<DetectedObject>): List<DetectedObject> {
        val refined = geo.map { d ->
            val e = votes.near(d.center)
            val (t, seen) = VisionFusion.refine(d.guess, e, d.width, d.depth, d.height)
            d.copy(guess = t, seenAs = seen, color = e?.color, name = e?.best()?.first?.let { VisionFusion.nameFor(it) })
        }
        var id = (geo.maxOfOrNull { it.id } ?: 0) + 1
        val extra = votes.entries.filter { e ->
            e.cameraOnly && e.sightings >= 2 && (e.best()?.second ?: 0f) >= 1.1f && geo.none { it.center.dist(e.at) < 0.6f }
        }.mapNotNull { e ->
            val (label, total) = e.best() ?: return@mapNotNull null
            val t = when (label) {
                "couch" -> FurnitureType.SOFA; "chair" -> FurnitureType.CHAIR; "bed" -> FurnitureType.BED
                "dining table" -> FurnitureType.DINING_TABLE; "potted plant" -> FurnitureType.PLANT
                else -> return@mapNotNull null
            }
            // Square it to the nearest wall; the measured size is unknown, so use typical dimensions.
            val ang = if (corners.size >= 2) Geo.edges(corners).minByOrNull { it.distanceTo(e.at) }!!.dir.angle() else 0f
            DetectedObject(id++, e.at, t.defW, t.defD, ang, t.defH, t, 0.4f,
                seenAs = "$label ${(100 * total / e.sightings).toInt().coerceAtMost(99)}%", color = e.color, fromCamera = true)
        }
        return refined + extra
    }

    private fun recognizedSummary(): List<String> = detected.mapNotNull { it.seenAs?.substringBeforeLast(' ') }
        .groupingBy { it }.eachCount().map { (k, n) -> if (n > 1) "$k ×$n" else k }

    // ------------------------------------------------------------------ drawing

    private fun drawScene() {
        val fy = floorY
        if (fy.isNaN()) return
        lines.clear(); tris.clear(); pts.clear()

        when (step) {
            ScanStep.PREVIEW, ScanStep.ALIGN -> drawPreview(fy)
            ScanStep.PLACE -> drawPlacement(fy)
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

        // Furniture: depth-detected (amber, mint once the camera confirms it), camera-only (sky), manual (pink).
        for (d in detected.filter { it.seenAs == null && !it.fromCamera }) box(lines, d.corners(), fy, fy + d.height)
        prims.lines(vp, lines.data, lines.vertices, 0xFFFFC94D, 6f)
        lines.clear()
        for (d in detected.filter { it.seenAs != null && !it.fromCamera }) box(lines, d.corners(), fy, fy + d.height)
        prims.lines(vp, lines.data, lines.vertices, 0xFF2EE6A8, 6f)
        lines.clear()
        for (d in detected.filter { it.fromCamera }) box(lines, d.corners(), fy, fy + d.height)
        prims.lines(vp, lines.data, lines.vertices, 0xFF64B5F6, 6f)
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
        // Ghosts hide behind real furniture (depth occlusion) so they read as really "there".
        val occ = if (depthOn) depthTex else null
        prims.occluded(GLES20.GL_TRIANGLES, vp, view, tris.data, tris.vertices, 0x402EE6A8, occ, viewW, viewH)
        prims.occluded(GLES20.GL_LINES, vp, view, lines.data, lines.vertices, 0xFF2EE6A8, occ, viewW, viewH, 7f)
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

    /** The piece being placed, coloured by whether it fits where it stands. */
    private fun drawPlacement(fy: Float) {
        val item = placing ?: return
        val a = placeAnchor
        val at = if (a != null && a.trackingState == TrackingState.TRACKING) Vec2(a.pose.tx(), a.pose.tz()) else reticle ?: return
        val rot = Math.toRadians(placeRotDeg.toDouble()).toFloat()
        val ghost = Geo.rectCorners(at, item.width, item.depth, rot)
        val (ok, _) = placementVerdict(ghost)
        val col = when (ok) { true -> 0xFF2EE6A8; false -> 0xFFFF5370; null -> 0xFFFFC94D }
        // Room outline + existing pieces, for context.
        previewRoom?.takeIf { aligned }?.let { room ->
            val ol = room.outline.map { toWorld(it) }
            for (i in ol.indices) seg(lines, ol[i], ol[(i + 1) % ol.size], fy + 0.01f)
            for (f in room.furniture) { val c = f.corners().map { toWorld(it) }; for (i in 0 until 4) seg(lines, c[i], c[(i + 1) % 4], fy + 0.01f) }
            prims.lines(vp, lines.data, lines.vertices, 0x66FFFFFF, 3f)
            lines.clear()
        }
        val top = fy + item.height
        for (i in 0 until 4) quadV(tris, ghost[i], ghost[(i + 1) % 4], fy, top)
        topQuad(tris, ghost, top)
        box(lines, ghost, fy, top)
        // Front edge marker: which way it faces.
        val front = Vec2(0f, 1f).rotated(rot)
        seg(lines, at, at + front * (item.depth / 2f + 0.25f), fy + 0.02f)
        val occ = if (depthOn) depthTex else null
        prims.occluded(GLES20.GL_TRIANGLES, vp, view, tris.data, tris.vertices, (0x55L shl 24) or (col and 0xFFFFFF), occ, viewW, viewH)
        prims.occluded(GLES20.GL_LINES, vp, view, lines.data, lines.vertices, col, occ, viewW, viewH, 7f)
        tris.clear(); lines.clear()
        placedInPlan = if (aligned && previewRoom != null) toPlan(at) to Geo.normalizeDeg(placeRotDeg - Math.toDegrees(alignTheta.toDouble()).toFloat()) else null
    }

    /** true = fits, false = bumps into something / pokes through a wall, null = no room to check against. */
    private fun placementVerdict(ghost: List<Vec2>): Pair<Boolean?, String> {
        val room = previewRoom
        if (room == null || !aligned) return null to "No room plan to check against — this just shows the size."
        val outline = room.outline.map { toWorld(it) }
        if (ghost.any { !Geo.pointInPolygon(it, outline) }) return false to "Pokes through a wall"
        val hit = room.furniture.firstOrNull { f -> Geo.convexOverlap(ghost, f.corners().map { toWorld(it) }) }
        if (hit != null) return false to "Bumps into the ${hit.name}"
        val wallGap = Geo.edges(outline).minOf { w -> ghost.minOf { w.distanceTo(it) } }
        return true to "Fits ✓ — ${(wallGap * 100).toInt()} cm from the nearest wall"
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
            ScanStep.PLACE -> if (placeAnchor == null) "Aim the ring where it should go, then tap \"Put it here\"" else "Walk around it. Rotate, or tap \"Move here\" to shift it."
        }
        _state.value = _state.value.copy(
            step = step, tracking = true, message = msg, floorFound = !floorY.isNaN(),
            reticleOnFloor = reticle != null, corners = corners.size, outline = corners.toList(), outlineClosed = closed,
            perimeter = per, area = if (corners.size >= 3) Geo.absArea(corners) else 0f,
            openings = openings.toList(), openingType = openingType, pendingPoint = pending != null,
            detected = detected, manual = manual.toList(), coverage = accumulator.coveredArea(),
            alignPoints = alignTaps.size, aligned = aligned, error = null,
            recognized = recognizedSummary(), visionReady = detector != null, visionMs = detector?.lastMillis ?: 0,
            occlusion = depthOn && depthTex.ready && (step == ScanStep.PREVIEW || step == ScanStep.PLACE),
            placing = placing?.name, placed = placeAnchor != null,
            placeOk = placing?.let { item ->
                val at = placeAnchor?.let { Vec2(it.pose.tx(), it.pose.tz()) } ?: reticle
                at?.let { placementVerdict(Geo.rectCorners(it, item.width, item.depth, Math.toRadians(placeRotDeg.toDouble()).toFloat())).first }
            },
            placeStatus = placing?.let { item ->
                val at = placeAnchor?.let { Vec2(it.pose.tx(), it.pose.tz()) } ?: reticle
                at?.let { placementVerdict(Geo.rectCorners(it, item.width, item.depth, Math.toRadians(placeRotDeg.toDouble()).toFloat())).second } ?: "Find the floor first"
            } ?: "",
            placeRotation = placeRotDeg,
        )
    }
}
