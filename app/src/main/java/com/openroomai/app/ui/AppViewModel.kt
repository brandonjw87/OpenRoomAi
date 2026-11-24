package com.openroomai.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openroomai.app.data.RoomRepository
import com.openroomai.app.engine.Effort
import com.openroomai.app.engine.Insight
import com.openroomai.app.engine.Insights
import com.openroomai.app.engine.LayoutOptimizer
import com.openroomai.app.engine.MovePlanner
import com.openroomai.app.engine.MoveStep
import com.openroomai.app.engine.OpennessAnalyzer
import com.openroomai.app.engine.OpennessReport
import com.openroomai.app.engine.OptimizeResult
import com.openroomai.app.model.Furniture
import com.openroomai.app.model.Geo
import com.openroomai.app.model.Opening
import com.openroomai.app.model.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object Scan : Screen
    data object Plan : Screen
    data object Preview : Screen
}

data class FitCheck(val item: Furniture, val result: OptimizeResult, val steps: List<MoveStep>)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = RoomRepository(app)

    private val _rooms = MutableStateFlow<List<Room>>(emptyList())
    val rooms: StateFlow<List<Room>> = _rooms

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set

    var room by mutableStateOf<Room?>(null)
        private set
    var report by mutableStateOf<OpennessReport?>(null)
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
    var effort by mutableStateOf(Effort.BALANCED)
    var fitCheck by mutableStateOf<FitCheck?>(null)
        private set
    var fitProgress by mutableStateOf<Float?>(null)
        private set
    var toast by mutableStateOf<String?>(null)

    private var analyzeJob: Job? = null

    init { refresh() }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) { _rooms.value = repo.list() }

    fun go(s: Screen) { screen = s }

    fun back(): Boolean = when (screen) {
        Screen.Home -> false
        Screen.Preview -> { screen = Screen.Plan; true }
        else -> { screen = Screen.Home; refresh(); true }
    }

    // ------------------------------------------------------------ room lifecycle

    fun open(r: Room) {
        room = r
        result = null; steps = emptyList(); insights = emptyList(); doneSteps = emptySet(); fitCheck = null
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
        _rooms.value = repo.list()
    }

    fun rename(name: String) = update { it.copy(name = name) }

    /** Any edit to the current layout invalidates the optimized plan. */
    fun update(invalidate: Boolean = true, f: (Room) -> Room) {
        val r = room ?: return
        val n = f(r).let { if (invalidate) it.copy(optimized = null) else it }
        room = n
        if (invalidate) { result = null; steps = emptyList(); insights = emptyList(); doneSteps = emptySet() }
        reanalyze()
        persist(n)
    }

    fun updateFurniture(id: String, f: (Furniture) -> Furniture) = update { r -> r.copy(furniture = r.furniture.map { if (it.id == id) f(it) else it }) }
    fun addFurniture(item: Furniture) = update { r -> r.copy(furniture = r.furniture + item) }
    fun removeFurniture(id: String) = update { r -> r.copy(furniture = r.furniture.filterNot { it.id == id }) }
    fun addOpening(o: Opening) = update { r -> r.copy(openings = r.openings + o) }
    fun removeOpening(id: String) = update { r -> r.copy(openings = r.openings.filterNot { it.id == id }) }

    private fun persist(r: Room) = viewModelScope.launch(Dispatchers.IO) { repo.save(r) }

    private fun reanalyze(immediate: Boolean = false) {
        val r = room ?: return
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            if (!immediate) delay(120)
            val rep = withContext(Dispatchers.Default) { OpennessAnalyzer.analyze(r, r.furniture, 0.05f) }
            report = rep
        }
    }

    // ------------------------------------------------------------ optimize

    fun optimize() {
        val r = room ?: return
        if (progress != null) return
        progress = 0f
        viewModelScope.launch {
            val res = withContext(Dispatchers.Default) {
                LayoutOptimizer(r, effort, seed = System.nanoTime()).optimize(r.furniture) { p -> progress = p }
            }
            applyResult(r, res)
            progress = null
            room = r.copy(optimized = res.layout)
            persist(room!!)
            val delta = res.after.score - res.before.score
            toast = when {
                delta >= 15 -> "Boom. +$delta openness. Your room just exhaled."
                delta > 0 -> "+$delta openness. Small moves, big vibes."
                else -> "Honestly? This layout is already great. Nothing to move."
            }
        }
    }

    private fun applyResult(r: Room, res: OptimizeResult) {
        result = res
        steps = MovePlanner.plan(r, r.furniture, res.layout)
        insights = Insights.explain(r, res.before, res.after, res.layout)
        doneSteps = emptySet()
    }

    private fun restorePlan(r: Room, opt: List<Furniture>) = viewModelScope.launch {
        val res = withContext(Dispatchers.Default) {
            val opz = LayoutOptimizer(r, effort)
            val before = OpennessAnalyzer.analyze(r, r.furniture, 0.05f)
            val after = OpennessAnalyzer.analyze(r, opt, 0.05f)
            OptimizeResult(opt, before, after, opz.violations(opt))
        }
        applyResult(r, res)
    }

    fun toggleStep(i: Int) { doneSteps = if (i in doneSteps) doneSteps - i else doneSteps + i }

    /** "I moved everything": the optimized layout becomes the room's real layout. */
    fun acceptLayout() {
        val res = result ?: return
        update { it.copy(furniture = res.layout) }
        toast = "Layout saved as your room. Enjoy the space! 🧘"
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
                LayoutOptimizer(r, Effort.BALANCED, seed = System.nanoTime())
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
        val withItem = r.copy(furniture = r.furniture + item.copy(center = item.center), optimized = fc.result.layout)
        room = withItem
        persist(withItem)
        // Existing pieces still need to move to the plan; the new one is already "placed".
        val res = fc.result
        result = res
        steps = fc.steps
        insights = Insights.explain(withItem, res.before, res.after, res.layout)
        doneSteps = emptySet()
        fitCheck = null
        reanalyze()
        toast = "${item.name} added to the plan 🛒"
    }
}
