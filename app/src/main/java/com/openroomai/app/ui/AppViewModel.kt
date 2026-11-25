package com.openroomai.app.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openroomai.app.data.RoomRepository
import com.openroomai.app.engine.Constraints
import com.openroomai.app.engine.DeclutterAction
import com.openroomai.app.engine.DeclutterCoach
import com.openroomai.app.engine.DeclutterIdea
import com.openroomai.app.engine.Insight
import com.openroomai.app.engine.Insights
import com.openroomai.app.engine.LayoutGoal
import com.openroomai.app.engine.LayoutOptimizer
import com.openroomai.app.engine.MicroNudge
import com.openroomai.app.engine.MirrorSpot
import com.openroomai.app.engine.MovePlanner
import com.openroomai.app.engine.MoveStep
import com.openroomai.app.engine.Nudge
import com.openroomai.app.engine.OpenLook
import com.openroomai.app.engine.OpenSpace
import com.openroomai.app.engine.OpenSpaceReport
import com.openroomai.app.engine.OpennessAnalyzer
import com.openroomai.app.engine.OpennessReport
import com.openroomai.app.engine.OptimizeResult
import com.openroomai.app.engine.Season
import com.openroomai.app.engine.SpaceGoal
import com.openroomai.app.engine.SunModel
import com.openroomai.app.engine.SunReport
import com.openroomai.app.engine.Units
import com.openroomai.app.model.Furniture
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Opening
import com.openroomai.app.model.Paint
import com.openroomai.app.model.Room
import com.openroomai.app.model.Vec2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

sealed interface Screen {
    data object Home : Screen
    data object Scan : Screen
    data object Plan : Screen
    data object Preview : Screen
    data object ThreeD : Screen
    data object Moving : Screen
    data object Place : Screen
    data object DeviceCheck : Screen
}

/** Moving Day: pieces brought in from other rooms, placed by the optimizer. */
data class ArrivalPlan(val itemIds: Set<String>, val result: OptimizeResult, val steps: List<MoveStep>)

data class FitCheck(val item: Furniture, val result: OptimizeResult, val steps: List<MoveStep>)

/** What the home screen shows per room, computed off the main thread. */
data class RoomStat(val score: Int, val usableArea: Float)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = RoomRepository(app)
    private val prefs = app.getSharedPreferences("openroom", Context.MODE_PRIVATE)

    private val _rooms = MutableStateFlow<List<Room>>(emptyList())
    val rooms: StateFlow<List<Room>> = _rooms
    private val _stats = MutableStateFlow<Map<String, RoomStat>>(emptyMap())
    val stats: StateFlow<Map<String, RoomStat>> = _stats
    private val statCache = ConcurrentHashMap<Pair<String, Long>, RoomStat>()

    /** Inches & feet or centimeters & meters; read by screens so they re-render on change. */
    var units by mutableStateOf(loadUnits())
        private set

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set

    var room by mutableStateOf<Room?>(null)
        private set
    var report by mutableStateOf<OpennessReport?>(null)
        private set
    /** Open zone, usable floor and walkway widths for the room as it is now (1″ grid). */
    var space by mutableStateOf<OpenSpaceReport?>(null)
        private set
    var result by mutableStateOf<OptimizeResult?>(null)
        private set
    var steps by mutableStateOf<List<MoveStep>>(emptyList())
        private set
    var insights by mutableStateOf<List<Insight>>(emptyList())
        private set
    var doneSteps by mutableStateOf<Set<Int>>(emptySet())
        private set
    var progress by mutableStateOf<Float?>(null)
        private set
    /** The space goal being planned right now (for the progress line). */
    var runningGoal by mutableStateOf<SpaceGoal?>(null)
        private set
    var fitCheck by mutableStateOf<FitCheck?>(null)
        private set
    var fitProgress by mutableStateOf<Float?>(null)
        private set
    var toast by mutableStateOf<String?>(null)

    // ---- Micro-nudges
    var nudges by mutableStateOf<List<Nudge>>(emptyList())
        private set
    var nudging by mutableStateOf(false)
        private set
    var previewNudge by mutableStateOf<Nudge?>(null)
        private set
    var previewSpace by mutableStateOf<OpenSpaceReport?>(null)
        private set
    private var nudgeJob: Job? = null
    private var previewJob: Job? = null

    // ---- Undo / redo (per room, layout edits only)
    private val undoStack = ArrayDeque<Room>()
    private val redoStack = ArrayDeque<Room>()
    private var lastTag: String? = null
    private var lastTagAt = 0L
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    // ---- Sunlight simulator
    var sunSeason by mutableStateOf(Season.TODAY)
    var sunHour by mutableStateOf(14f)
    var sunReport by mutableStateOf<SunReport?>(null)
        private set
    private var sunJob: Job? = null
    private var sunKey: Any? = null

    // ---- Glow-up GIF
    var glowUpProgress by mutableStateOf<Float?>(null)
        private set
    var glowUpFile by mutableStateOf<File?>(null)

    var declutter by mutableStateOf<List<DeclutterIdea>>(emptyList())
        private set
    private var declutterJob: Job? = null

    private var analyzeJob: Job? = null

    init { refresh() }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        val list = repo.list()
        _rooms.value = list
        _stats.value = withContext(Dispatchers.Default) {
            list.associate { r -> r.id to statCache.getOrPut(r.id to r.updatedAt) { statOf(r) } }
        }
    }

    private fun statOf(r: Room) = RoomStat(
        OpennessAnalyzer.analyze(r, r.furniture, 0.1f, withFlows = false).score,
        OpenSpace.Meter(r, OpenSpace.FAST).measure(r.furniture).usableArea,
    )

    fun go(s: Screen) { screen = s }

    fun back(): Boolean = when (screen) {
        Screen.Home -> false
        Screen.Preview, Screen.ThreeD, Screen.Moving, Screen.Place -> { screen = Screen.Plan; true }
        Screen.DeviceCheck -> { screen = Screen.Home; true }
        else -> { screen = Screen.Home; refresh(); true }
    }

    // ------------------------------------------------------------ units

    private fun loadUnits(): Units.System {
        val saved = prefs.getString("units", null)?.let { runCatching { Units.System.valueOf(it) }.getOrNull() }
        val s = saved ?: Units.defaultFor(Locale.getDefault())
        Units.system = s
        return s
    }

    fun changeUnits(s: Units.System) {
        if (s == units) return
        Units.system = s
        units = s
        prefs.edit().putString("units", s.name).apply()
        // Text with measurements baked in gets rebuilt.
        val r = room ?: return
        result?.let { applyResult(r, it) }
        reanalyze(immediate = true)
    }

    // ------------------------------------------------------------ room lifecycle

    fun open(r: Room) {
        room = r
        result = null; steps = emptyList(); insights = emptyList(); doneSteps = emptySet(); fitCheck = null; declutter = emptyList()
        nudges = emptyList(); previewNudge = null; previewSpace = null; space = null
        undoStack.clear(); redoStack.clear(); lastTag = null; syncHistory()
        reanalyze(immediate = true)
        // Restore a previously computed plan.
        r.optimized?.let { opt -> restorePlan(r, opt) }
        screen = Screen.Plan
    }

    fun openDemo() = open(repo.save(RoomRepository.demoRoom()))

    fun createSketch(name: String, w: Float, l: Float) = open(repo.save(Room.rectangle(name.ifBlank { "My room" }, w, l)))

    fun saveScanned(r: Room) = open(repo.save(r))

    fun delete(r: Room) = viewModelScope.launch(Dispatchers.IO) {
        repo.delete(r.id)
        refresh()
    }

    fun rename(name: String) = update(invalidate = false, record = false) { it.copy(name = name) }

    /**
     * Every change to the room goes through here. Layout edits invalidate the plan and land on
     * the undo stack; edits with the same [tag] in quick succession (a drag, a slider) count as one.
     */
    fun update(invalidate: Boolean = true, tag: String? = null, record: Boolean = true, f: (Room) -> Room) {
        val r = room ?: return
        val n = f(r).let { if (invalidate) it.copy(optimized = null) else it }
        if (n == r) return
        if (record) pushHistory(r, tag)
        room = n
        if (invalidate) { result = null; steps = emptyList(); insights = emptyList(); doneSteps = emptySet() }
        if (invalidate || n.furniture != r.furniture || n.openings != r.openings || n.outline != r.outline) reanalyze()
        persist(n)
    }

    fun updateFurniture(id: String, tag: String? = "edit:$id", f: (Furniture) -> Furniture) =
        update(tag = tag) { r -> r.copy(furniture = r.furniture.map { if (it.id == id) f(it) else it }) }
    fun moveFurniture(id: String, p: Vec2) = updateFurniture(id, tag = "move:$id") { it.withPose(p, it.rotationDeg) }
    fun addFurniture(item: Furniture) = update { r -> r.copy(furniture = r.furniture + item) }
    fun removeFurniture(id: String) = update { r -> r.copy(furniture = r.furniture.filterNot { it.id == id }) }
    fun addOpening(o: Opening) = update { r -> r.copy(openings = r.openings + o) }
    fun removeOpening(id: String) = update { r -> r.copy(openings = r.openings.filterNot { it.id == id }) }

    private fun pushHistory(prev: Room, tag: String?) {
        val now = System.currentTimeMillis()
        val coalesce = tag != null && tag == lastTag && now - lastTagAt < 1500
        lastTag = tag; lastTagAt = now
        if (!coalesce) {
            undoStack.addLast(prev)
            while (undoStack.size > 60) undoStack.removeFirst()
        }
        redoStack.clear()
        syncHistory()
    }

    private fun syncHistory() { canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty() }

    fun undo() {
        val cur = room ?: return
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(cur)
        restore(prev)
        toast = "Undone"
    }

    fun redo() {
        val cur = room ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(cur)
        restore(next)
        toast = "Redone"
    }

    private fun restore(r: Room) {
        lastTag = null
        room = r
        result = null; steps = emptyList(); insights = emptyList(); doneSteps = emptySet()
        reanalyze()
        persist(r)
        r.optimized?.let { restorePlan(r, it) }
        syncHistory()
    }

    private var saveJob: Job? = null

    /** Debounced: dragging a sofa shouldn't rewrite the file 60 times a second. */
    private fun persist(r: Room) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) { delay(400); repo.save(r) }
    }

    private fun reanalyze(immediate: Boolean = false) {
        val r = room ?: return
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            if (!immediate) delay(120)
            val (rep, sp) = withContext(Dispatchers.Default) {
                OpennessAnalyzer.analyze(r, r.furniture, 0.05f, walkRadius = r.goal.walkRadius) to OpenSpace.analyze(r, r.furniture)
            }
            report = rep
            space = sp
            if (result == null) refreshDeclutter(r, r.furniture)
            refreshNudges(r)
        }
    }

    /** Two rooms describe the same layout problem (so a plan for one still fits the other). */
    private fun sameLayout(a: Room?, b: Room) =
        a != null && a.id == b.id && a.furniture == b.furniture && a.outline == b.outline && a.openings == b.openings && a.goal == b.goal

    // ------------------------------------------------------------ micro-nudges

    private fun refreshNudges(r: Room) {
        nudgeJob?.cancel()
        previewJob?.cancel()
        previewNudge = null; previewSpace = null
        if (r.furniture.isEmpty()) { nudges = emptyList(); nudging = false; return }
        nudging = true
        nudgeJob = viewModelScope.launch {
            delay(250)
            val list = withContext(Dispatchers.Default) { MicroNudge.suggest(r, r.furniture, SpaceGoal.QUICK_WINS, max = 5) }
            if (sameLayout(room, r)) { nudges = list; nudging = false }
        }
    }

    /** Show a nudge on the plan (tap again to hide it). */
    fun togglePreview(n: Nudge) {
        if (previewNudge?.key == n.key) { previewNudge = null; previewSpace = null; return }
        val r = room ?: return
        previewNudge = n
        previewSpace = null
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val sp = withContext(Dispatchers.Default) { OpenSpace.analyze(r, n.applyTo(r.furniture), withNotes = false) }
            if (previewNudge?.key == n.key && sameLayout(room, r)) previewSpace = sp
        }
    }

    fun applyNudge(n: Nudge) {
        val r = room ?: return
        if (n.poses.any { p -> r.furniture.none { it.id == p.id } }) return
        update(tag = null) { it.copy(furniture = n.applyTo(it.furniture)) }
        toast = "Nudged ✓ ${n.detail}"
    }

    // ------------------------------------------------------------ optimize

    /** One tap: plan the room for [goal]. */
    fun optimize(goal: SpaceGoal = room?.space ?: SpaceGoal.MAX_OPEN_CENTER) {
        val r0 = room ?: return
        if (progress != null) return
        if (r0.space != goal) { room = r0.copy(space = goal); persist(room!!) }
        val r = room!!
        progress = 0f
        runningGoal = goal
        viewModelScope.launch {
            val res = withContext(Dispatchers.Default) {
                LayoutOptimizer(r, goal, seed = System.nanoTime(), goal = r.goal).optimize(r.furniture) { p -> progress = p }
            }
            progress = null
            runningGoal = null
            val cur = room
            // Edited while we were thinking? That plan is for a room that no longer exists.
            if (cur == null || !sameLayout(cur, r)) {
                toast = "The room changed while I was planning, so I set that plan aside. Tap a goal to re-plan."
                return@launch
            }
            applyResult(cur, res)
            room = cur.copy(optimized = res.layout)
            persist(room!!)
            toast = resultToast(res)
        }
    }

    private fun resultToast(res: OptimizeResult): String {
        val d = res.space ?: return "Plan ready."
        val gain = d.usableGain
        val w = d.widened.firstOrNull()
        return when {
            gain / Units.SQFT >= 10f -> "Boom. ${Units.areaGain(gain)} of usable floor back. The room just exhaled."
            gain / Units.SQFT >= 1f -> "${Units.areaGain(gain)} usable floor. Small moves, real breathing room."
            w != null -> "${d.widened.size} walkway${if (d.widened.size == 1) "" else "s"} wider: ${w.first.removePrefix("the ")} ${Units.width(w.second)} → ${Units.width(w.third)}."
            d.improved -> "A little more open. Every inch counts."
            else -> "This layout is already about as open as it gets. Nothing worth moving."
        }
    }

    private fun applyResult(r: Room, res: OptimizeResult) {
        result = res
        steps = MovePlanner.plan(r, r.furniture, res.layout)
        insights = Insights.explain(r, res.before, res.after, res.layout, res.space)
        doneSteps = emptySet()
        refreshDeclutter(r, res.layout)
    }

    private fun refreshDeclutter(r: Room, layout: List<Furniture>) {
        declutterJob?.cancel()
        declutterJob = viewModelScope.launch {
            declutter = withContext(Dispatchers.Default) { DeclutterCoach.suggest(r, layout, r.goal) }
        }
    }

    /** Declutter coach: apply a suggestion to the real room and re-plan. */
    fun applyIdea(idea: DeclutterIdea) {
        val r = room ?: return
        val f = r.furniture.firstOrNull { it.id == idea.furnitureId } ?: return
        val changed = DeclutterCoach.apply(f, idea.action)
        update { room -> room.copy(furniture = room.furniture.mapNotNull { if (it.id == f.id) changed else it }) }
        toast = when (idea.action) {
            DeclutterAction.Remove -> "Bye-bye ${f.name} 👋 Re-planning…"
            else -> "Done. Re-planning around the slimmer ${f.name.lowercase()}…"
        }
        optimize()
    }

    fun setGoal(g: LayoutGoal) {
        if (room?.goal == g) return
        update(tag = "goal") { it.copy(goal = g) }
    }

    private fun restorePlan(r: Room, opt: List<Furniture>) = viewModelScope.launch {
        val res = withContext(Dispatchers.Default) {
            val before = OpennessAnalyzer.analyze(r, r.furniture, 0.05f, walkRadius = r.goal.walkRadius)
            val after = OpennessAnalyzer.analyze(r, opt, 0.05f, walkRadius = r.goal.walkRadius)
            OptimizeResult(opt, before, after, Constraints(r).violations(opt), OpenSpace.diff(r, r.furniture, opt), r.space)
        }
        if (sameLayout(room, r) && room?.optimized == opt) applyResult(r, res)
    }

    fun toggleStep(i: Int) { doneSteps = if (i in doneSteps) doneSteps - i else doneSteps + i }

    /** "I moved everything": the planned layout becomes the room's real layout. */
    fun acceptLayout() {
        val res = result ?: return
        update(tag = "accept") { it.copy(furniture = res.layout) }
        toast = "Layout saved as your room. Enjoy the space 🧘"
    }

    // ------------------------------------------------------------ will it fit?

    fun checkFit(item: Furniture) {
        val r = room ?: return
        if (fitProgress != null) return
        fitProgress = 0f
        fitCheck = null
        viewModelScope.launch {
            val placed = item.copy(center = Geo.centroid(r.outline), isNew = true)
            val res = withContext(Dispatchers.Default) {
                LayoutOptimizer(r, SpaceGoal.WIDEST_FLOW, seed = System.nanoTime(), goal = r.goal)
                    .optimize(start = r.furniture + placed, reference = r.furniture) { p -> fitProgress = p }
            }
            val st = MovePlanner.plan(r, r.furniture, res.layout)
            fitCheck = FitCheck(placed, res, st)
            fitProgress = null
        }
    }

    fun dismissFit() { fitCheck = null }

    /** Commit a fit check: keep the new piece and show its plan as the optimized layout. */
    fun acceptFit() {
        val fc = fitCheck ?: return
        val r = room ?: return
        val item = fc.result.layout.first { it.id == fc.item.id }
        pushHistory(r, null)
        val withItem = r.copy(furniture = r.furniture + item.copy(center = item.center), optimized = fc.result.layout)
        room = withItem
        persist(withItem)
        // Existing pieces still need to move to the plan; the new one is already "placed".
        val res = fc.result
        result = res
        steps = fc.steps
        insights = Insights.explain(withItem, res.before, res.after, res.layout, res.space)
        doneSteps = emptySet()
        fitCheck = null
        reanalyze()
        toast = "${item.name} added to the plan 🛒"
    }

    // ------------------------------------------------------------ sunlight

    /** Full-day simulation for [layout] (cached), then a cheap re-trace for the selected hour. */
    fun refreshSun(layout: List<Furniture>) {
        val r = room ?: return
        val key = listOf(layout, sunSeason, r.northDeg, r.latitude, r.outline, r.openings)
        val season = sunSeason
        val hour = sunHour
        sunJob?.cancel()
        sunJob = viewModelScope.launch {
            delay(40)
            val rep = withContext(Dispatchers.Default) {
                val base = sunReport
                if (key == sunKey && base != null) SunModel.atHour(r, layout, base, season, hour)
                else SunModel.simulate(r, layout, season, hour)
            }
            sunKey = key
            sunReport = rep
        }
    }

    /** Orientation/latitude don't move furniture, so the current plan stays valid. */
    fun setNorth(deg: Float) = update(invalidate = false, record = false) { it.copy(northDeg = Geo.normalizeDeg(deg)) }
    fun setLatitude(lat: Float) = update(invalidate = false, record = false) { it.copy(latitude = lat.coerceIn(-66f, 66f)) }

    // ------------------------------------------------------------ glow-up GIF

    fun makeGlowUp() {
        val r = room ?: return
        val res = result ?: return
        if (glowUpProgress != null) return
        glowUpProgress = 0f
        viewModelScope.launch {
            val file = withContext(Dispatchers.Default) {
                runCatching {
                    GlowUp.render(getApplication(), r, r.furniture, res.layout, res.before, res.after) { glowUpProgress = it }
                }.getOrNull()
            }
            glowUpProgress = null
            if (file == null) toast = "Couldn't render the GIF. Try again?" else glowUpFile = file
        }
    }

    // ------------------------------------------------------------ Open-Look Studio

    var mirrorSpots by mutableStateOf<List<MirrorSpot>>(emptyList())
        private set
    private var openLookJob: Job? = null

    fun refreshOpenLook(layout: List<Furniture>) {
        val r = room ?: return
        openLookJob?.cancel()
        openLookJob = viewModelScope.launch {
            delay(60)
            mirrorSpots = withContext(Dispatchers.Default) { OpenLook.mirrorSpots(r, layout) }
        }
    }

    /** Mirrors and paint change the look, not the layout: keep the current plan. */
    fun addMirror(spot: MirrorSpot) {
        update(invalidate = false, tag = "look") { it.copy(mirrors = it.mirrors + spot.mirror) }
        toast = "Mirror pinned to Wall ${room?.wallLabel(spot.mirror.wallIndex)} 🪞 Check it in 3D."
    }

    fun removeMirror(id: String) = update(invalidate = false, tag = "look") { it.copy(mirrors = it.mirrors.filterNot { m -> m.id == id }) }
    fun setPaint(p: Paint?) = update(invalidate = false, tag = "look") { it.copy(paint = p) }
    fun setCeiling(h: Float) = update(invalidate = false, record = false) { it.copy(ceilingHeight = h.coerceIn(2.0f, 4.0f)) }

    // ------------------------------------------------------------ Moving Day

    var arrivalPlan by mutableStateOf<ArrivalPlan?>(null)
        private set
    var arrivalProgress by mutableStateOf<Float?>(null)
        private set

    /** Plan where furniture from other rooms should go here; nothing changes until accepted. */
    fun planArrivals(items: List<Furniture>) {
        val r = room ?: return
        if (items.isEmpty() || arrivalProgress != null) return
        arrivalProgress = 0f
        arrivalPlan = null
        viewModelScope.launch {
            val center = Geo.centroid(r.outline)
            val placed = items.map { it.copy(id = java.util.UUID.randomUUID().toString(), center = center, isNew = true, locked = false) }
            val res = withContext(Dispatchers.Default) {
                LayoutOptimizer(r, SpaceGoal.WIDEST_FLOW, seed = System.nanoTime(), goal = r.goal)
                    .optimize(start = r.furniture + placed, reference = r.furniture) { p -> arrivalProgress = p }
            }
            arrivalPlan = ArrivalPlan(placed.map { it.id }.toSet(), res, MovePlanner.plan(r, r.furniture, res.layout))
            arrivalProgress = null
        }
    }

    fun dismissArrivals() { arrivalPlan = null }

    /** Keep the arrivals (copied: the source rooms stay untouched) and adopt the plan. */
    fun acceptArrivals() {
        val plan = arrivalPlan ?: return
        val r = room ?: return
        pushHistory(r, null)
        val arrived = plan.result.layout.filter { it.id in plan.itemIds }.map { it.copy(isNew = false) }
        val withItems = r.copy(furniture = r.furniture + arrived, optimized = plan.result.layout.map { if (it.id in plan.itemIds) it.copy(isNew = false) else it })
        room = withItems
        persist(withItems)
        result = plan.result.copy(layout = withItems.optimized!!)
        steps = plan.steps
        insights = Insights.explain(withItems, plan.result.before, plan.result.after, withItems.optimized!!, plan.result.space)
        doneSteps = emptySet()
        arrivalPlan = null
        reanalyze()
        toast = "${arrived.size} piece(s) moved in 🚚 Your move-in plan is ready."
    }

    fun otherRooms(): List<Room> = rooms.value.filter { it.id != room?.id && it.furniture.isNotEmpty() }

    // ------------------------------------------------------------ place it in AR

    var placingItem by mutableStateOf<Furniture?>(null)
        private set

    fun startPlacement(item: Furniture) { placingItem = item }

    /** Drop the AR-placed piece into the plan where it was standing. */
    fun addPlaced(center: Vec2, rotationDeg: Float) {
        val item = placingItem ?: return
        addFurniture(item.copy(center = center, rotationDeg = rotationDeg, isNew = true))
        toast = "${item.name} added where you put it in AR 📱"
        placingItem = null
        screen = Screen.Plan
    }
}
