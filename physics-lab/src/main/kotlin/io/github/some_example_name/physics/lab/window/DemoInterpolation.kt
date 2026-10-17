package io.github.some_example_name.physics.lab.window

import io.github.some_example_name.physics.SUBSTEPS
import io.github.some_example_name.physics.BodyState
import io.github.some_example_name.physics.StepObserver

/** История принадлежит окну, а не физике. Без окна этот объект вообще не создаётся.
 * Две оси double × два снимка = 32 байта/частицу; подробный режим — 272 байта.
 * На 102400 частиц это 3.28 / 27.85 МБ: больше L2 и заметная часть L3, поэтому
 * 17 снимков выделяются только после запроса режима 2 и копируются только в нём.
 * Возврат в режим 1 сохраняет ёмкость, чтобы переключение не вызывало GC.
 */
internal class DemoInterpolation(private val state: BodyState) : StepObserver {
    var mode = 1
    private var historyX = emptyArray<DoubleArray>()
    private var historyY = emptyArray<DoubleArray>()
    private var count = 0
    private var hasSubsteps = false
    private var lo = -1
    private var hi = 0
    private var u = 0.0
    internal val allocatedSlots: Int get() = historyX.size

    override fun beginTick() {
        if (mode == 0) { invalidate(); return }
        val slots = if (mode == 2) SUBSTEPS + 1 else 2
        if (historyX.size < slots || historyX.firstOrNull()?.size != state.n) {
            historyX = Array(slots) { DoubleArray(state.n) }
            historyY = Array(slots) { DoubleArray(state.n) }
        }
        count = slots
        hasSubsteps = mode == 2
        snapshot(0)
    }

    override fun afterSubstep(step: Int) {
        if (count == 0) return
        if (hasSubsteps) snapshot(step + 1)
        else if (step == SUBSTEPS - 1) snapshot(1)
    }

    private fun snapshot(slot: Int) {
        val x = historyX[slot]; val y = historyY[slot]
        for (i in 0 until state.n) { x[i] = state.px[i]; y[i] = state.py[i] }
    }

    fun invalidate() { count = 0; lo = -1 }

    fun prepare(alpha: Double) {
        if (mode == 0 || count < 2) { lo = -1; return }
        if (mode == 1 || !hasSubsteps) {
            lo = 0; hi = count - 1; u = alpha
            return
        }
        val f = alpha * (count - 1)
        lo = f.toInt().coerceIn(0, count - 2)
        hi = lo + 1
        u = (f - lo).coerceIn(0.0, 1.0)
    }

    fun x(i: Int): Float = if (lo < 0) state.px[i].toFloat()
        else (historyX[lo][i] + (historyX[hi][i] - historyX[lo][i]) * u).toFloat()
    fun y(i: Int): Float = if (lo < 0) state.py[i].toFloat()
        else (historyY[lo][i] + (historyY[hi][i] - historyY[lo][i]) * u).toFloat()
}
