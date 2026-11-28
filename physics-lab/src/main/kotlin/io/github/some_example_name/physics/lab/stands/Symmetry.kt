package io.github.some_example_name.physics.lab.stands

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/*
 * ПРОВЕРКА СИММЕТРИИ СЦЕНЫ (план стабильности, этап 1, пункт 3): gradlew :physics-lab:standsCheck -Psymmetry=1
 *
 * Сцена прогоняется как написана, затем ещё раз в каждой системе отсчёта (Frame.kt): сдвинутой,
 * повёрнутой, отражённой и с перенумерованными частицами. Итог в координатах сцены обязан совпасть
 * в пределах допуска.
 *
 * ЧТО СВЕРЯЕТСЯ. Крупные величины, а не биты: биты другими и должны быть — другая арифметика
 * округления. По ходу сцены восемь раз и в конце снимается «набросок»: центр масс каждого тела,
 * куда оно повёрнуто, положения свободных частиц. Расхождение набросков — в связях (поворот тела
 * переведён в сдвиг его самой дальней клетки). Отдельно сверяются счёт разрывов и погибших клеток
 * и вердикт правил.
 *
 * ХАОТИЧНУЮ СЦЕНУ ПО ОДНОМУ ПРОГОНУ НЕ СУДЯТ. Толпа из тридцати тел или таран разносят ошибку
 * округления в связи за сотню тиков сами, и расхождение повёрнутой сцены с исходной там ничего не
 * значит. Поэтому сначала идёт контрольный прогон с ничтожным сдвигом (Frame.NUDGE). Сцена, которая
 * его повторила, — спокойная, и от неё требуется повторить и остальные системы отсчёта. Сцена,
 * которая не повторила даже его, — хаотичная: она в общий счёт идёт только суммами (сколько всего
 * порвано и погибло в каждой системе отсчёта) — перекос решателя виден и по ним.
 *
 * ПОРОГИ записаны рядом и взяты из прогона 16.11.2026 (docs/physics/stands.md, «Симметрия»).
 */

/** Спокойная сцена: контрольный прогон с малым сдвигом разошёлся с исходным не больше чем на столько связей. */
const val SYMMETRY_CALM = 0.02
/** Допуск спокойной сцены: расхождение с любой системой отсчёта, связей, — плюс доля пройденного пути. */
const val SYMMETRY_TIGHT = 0.25
/**
 * Доля пути, которую можно разойтись. Тело, улетевшее после удара на сотни связей, уносит с собой и
 * ничтожную разницу в скорости: за 400 тиков в пустоте она вырастает в доли связи, и судить её по
 * одному только числу связей значило бы судить длину сцены. Путь — центра масс тела; у поворота к
 * нему прибавляется путь самой дальней клетки вокруг центра.
 *
 * ЧТО ЭТОТ ДОПУСК ЗАВЕДОМО ПРОПУСКАЕТ (замер 16.11.2026). Связи решаются по очереди (Гаусс — Зейдель),
 * и удар закручивает тело в сторону, заданную порядком клеток в памяти: два одинаковых мягких
 * квадрата 8×8, удар в лоб 2 связи за тик — направление разошлось на 0.97 радиана за 120 тиков между
 * прямым и обратным порядком клеток, то есть скорость края около 1% скорости удара. Чередование
 * направления обхода (SWEEP_FLIP) гасит перекос в среднем, а удар длится один-три подшага. Отражение
 * этот перекос не ловит (вместе с геометрией отражается и порядок), перенумерация ловит. Два
 * процента пути — выше этого уровня: красным остаётся то, что заметно сильнее.
 */
const val SYMMETRY_SHARE = 0.02
/** Сколько раз за сцену снимается набросок (плюс конец сцены). */
private const val SKETCH_POINTS = 8

/** Набросок сцены в координатах сцены по контрольным тикам — то, что сверяется между системами отсчёта. */
class Sketch(val frame: Frame, private val scenarioTicks: Int) {
    /** На контрольный тик: по телу — x, y центра масс, направление, радиус (все в связях и радианах); затем x, y свободных частиц. */
    val points = ArrayList<DoubleArray>()
    /** На контрольный тик: по телу — путь центра масс и путь самой дальней клетки вокруг центра с начала сцены, связей. */
    val travel = ArrayList<DoubleArray>()
    val pointTick = ArrayList<Int>()
    var bodies = 0
        private set
    var torn = 0; var died = 0; var passed = false; var failed = ""; var crash = ""; var ticks = 0
    var peakEnergy = 0.0
    /** Прогон эталоном: сколько ударов его шаг проскочил (ReferenceSolver.missedHits). У быстрого решателя — ноль. */
    var missedHits = 0
    private val every = maxOf(1, scenarioTicks / SKETCH_POINTS)

    /** Самая дальняя от центра фигуры клетка — по ней видно, куда тело повёрнуто. */
    private var far = IntArray(0)

    fun begin(run: StandRun) {
        val w = run.w
        bodies = w.bodies.size
        far = IntArray(bodies) { i ->
            val s = w.bodies[i].shape
            var best = 0; var bestD = -1.0
            for (k in 0 until s.count) { val d = s.x[k] * s.x[k] + s.y[k] * s.y[k]; if (d > bestD) { bestD = d; best = k } }
            best
        }
        // Набросок начала сцены: от него копится путь тел, по которому считается допуск.
        take(run)
    }

    fun after(run: StandRun) {
        if (run.tick % every == 0 || run.done) take(run)
        if (run.done) finish(run)
    }

    private fun take(run: StandRun) {
        val w = run.w
        val st = w.world.state
        val out = DoubleArray(4 * bodies + 2 * w.balls.size)
        for (i in 0 until bodies) {
            val b = w.bodies[i]
            val c = w.centerOf(b)
            out[4 * i] = c[0] / GAME_LINK; out[4 * i + 1] = c[1] / GAME_LINK
            val p = b.particle(far[i])
            val rx = w.gameX(p) - c[0]; val ry = w.gameY(p) - c[1]
            val alive = !st.cellDead[p] && !st.cellUnborn[p] && hypot(rx, ry) > 1e-9
            out[4 * i + 2] = if (alive) atan2(ry, rx) else Double.NaN
            out[4 * i + 3] = if (alive) hypot(rx, ry) / GAME_LINK else 0.0
        }
        for ((k, id) in w.balls.withIndex()) {
            val alive = w.world.isFreeAlive(id)
            val p = if (alive) w.world.freeParticle(id) else -1
            out[4 * bodies + 2 * k] = if (alive) w.gameX(p) / GAME_LINK else Double.NaN
            out[4 * bodies + 2 * k + 1] = if (alive) w.gameY(p) / GAME_LINK else Double.NaN
        }
        val path = DoubleArray(2 * bodies)
        val last = points.lastOrNull(); val lastPath = travel.lastOrNull()
        if (last != null && lastPath != null) for (i in 0 until bodies) {
            path[2 * i] = lastPath[2 * i] + hypot(out[4 * i] - last[4 * i], out[4 * i + 1] - last[4 * i + 1])
            val turned = if (out[4 * i + 2].isNaN() || last[4 * i + 2].isNaN()) 0.0 else abs(wrap(out[4 * i + 2] - last[4 * i + 2])) * out[4 * i + 3]
            path[2 * i + 1] = lastPath[2 * i + 1] + turned
        }
        points.add(out); travel.add(path); pointTick.add(run.tick)
    }

    private fun finish(run: StandRun) {
        val m = run.monitor
        torn = m.torn; died = m.died; ticks = run.tick; peakEnergy = m.peakEnergy
        passed = run.passed
        failed = m.results.values.filter { it.failed }.joinToString(",") { it.rule.name }
        crash = run.crash?.toString().orEmpty()
    }
}

/** Расхождение двух набросков. */
class Deviation(val frame: Frame) {
    /** Расхождение положений, связей, там, где оно больше всего превысило допуск; тик и что разошлось. */
    var score = 0.0
    var tick = -1
    var what = ""
    /** Допуск в этой точке, связей, и во сколько раз расхождение его превысило (больше единицы — нарушение). */
    var allowed = SYMMETRY_TIGHT
    var excess = 0.0
    var tornBase = 0; var tornOther = 0; var diedBase = 0; var diedOther = 0
    var verdictBase = ""; var verdictOther = ""
    var crash = ""
    val sameCounts: Boolean get() = tornBase == tornOther && diedBase == diedOther
    val sameVerdict: Boolean get() = verdictBase == verdictOther
    val clean: Boolean get() = crash.isEmpty() && sameCounts && sameVerdict
    /** Положения сошлись в пределах допуска. */
    val close: Boolean get() = excess <= 1.0

    fun describe(): String {
        if (crash.isNotEmpty()) return "прогон упал: $crash"
        val parts = ArrayList<String>()
        if (score > 0.0) parts.add(String.format(Locale.ROOT, "%s разошлось на %.2f связи к тику %d при допуске %.2f", what, score, tick, allowed))
        if (tornBase != tornOther) parts.add("порвано связей $tornOther против $tornBase")
        if (diedBase != diedOther) parts.add("погибло клеток $diedOther против $diedBase")
        if (!sameVerdict) parts.add("вердикт правил «${verdictOther.ifEmpty { "прошла" }}» против «${verdictBase.ifEmpty { "прошла" }}»")
        return parts.joinToString("; ")
    }
}

private fun wrap(a: Double): Double { var x = a; while (x > Math.PI) x -= 2 * Math.PI; while (x < -Math.PI) x += 2 * Math.PI; return x }

/** Сверить набросок [other] с наброском исходной сцены [base]: допуск — [tight] связей плюс доля [share] пути. */
fun compareSketches(base: Sketch, other: Sketch, tight: Double = SYMMETRY_TIGHT, share: Double = SYMMETRY_SHARE): Deviation {
    val d = Deviation(other.frame)
    d.tornBase = base.torn; d.tornOther = other.torn; d.diedBase = base.died; d.diedOther = other.died
    d.verdictBase = base.failed; d.verdictOther = other.failed
    d.crash = other.crash
    val count = minOf(base.points.size, other.points.size)
    fun note(shift: Double, path: Double, c: Int, what: String) {
        val allowed = tight + share * path
        if (shift / allowed > d.excess) { d.excess = shift / allowed; d.score = shift; d.allowed = allowed; d.tick = base.pointTick[c]; d.what = what }
    }
    for (c in 0 until count) {
        val a = base.points[c]; val b = other.points[c]; val path = base.travel[c]
        for (i in 0 until base.bodies) {
            note(hypot(a[4 * i] - b[4 * i], a[4 * i + 1] - b[4 * i + 1]), path[2 * i], c, "центр тела ${i + 1}")
            // Поворот — сдвигом самой дальней клетки тела: так у всех расхождений одна мера.
            if (!a[4 * i + 2].isNaN() && !b[4 * i + 2].isNaN())
                note(abs(wrap(a[4 * i + 2] - b[4 * i + 2])) * a[4 * i + 3], path[2 * i] + path[2 * i + 1], c, "поворот тела ${i + 1}")
        }
        // Свободная частица: её путь не копится, допуск — от её удаления от места на первом контрольном тике.
        var k = 4 * base.bodies
        val first = base.points[0]
        while (k + 1 < minOf(a.size, b.size)) {
            if (!a[k].isNaN() && !b[k].isNaN()) {
                val gone = if (k + 1 < first.size && !first[k].isNaN()) hypot(a[k] - first[k], a[k + 1] - first[k + 1]) else 0.0
                note(hypot(a[k] - b[k], a[k + 1] - b[k + 1]), gone, c, "частица ${(k - 4 * base.bodies) / 2 + 1}")
            }
            k += 2
        }
    }
    return d
}

/**
 * Исходный прогон сцены с набросками и контрольный — с малым сдвигом. Общие для проверки симметрии и
 * сверки с эталоном: сцена, которая не повторила даже свой сдвиг на треть клетки, хаотична, и судить
 * её по одному прогону нельзя ни там, ни там.
 */
class Control(private val s: Scenario) {
    val base: Sketch = sketchedRun(s, Frame.SCENE)
    val nudge: Deviation = compareSketches(base, sketchedRun(s, Frame.NUDGE))
    val calm: Boolean = base.crash.isEmpty() && nudge.clean && nudge.score <= SYMMETRY_CALM

    // Ключ — сама система отсчёта, а не её имя: у поворота на 37° и на четверть оборота имя одно («turn»).
    private val sketches = java.util.IdentityHashMap<Frame, Sketch>()
    /** Набросок сцены в системе отсчёта [frame]: прогоняется один раз, сколько бы проверок его ни спросили. */
    fun sketch(frame: Frame): Sketch = sketches.getOrPut(frame) { sketchedRun(s, frame) }

    /**
     * Прогон с малым разнобоем тел (Frame.PHASE) против исходного, с допуском сверки с эталоном: сцена,
     * которая разошлась уже от этого, чувствительна к фазе касания. Считается, только если спросят.
     */
    val phase: Deviation by lazy { compareSketches(base, sketch(Frame.PHASE), REFERENCE_TIGHT, REFERENCE_SHARE) }
    /**
     * Прогон с клетками в обратном порядке (Frame.ORDER) против исходного, с тем же допуском: сцена, чей
     * итог зависит от порядка клеток в памяти, зависит от порядка обхода ограничений — единственного
     * ответа у неё нет, и другой решатель в неё не попадёт. Саму зависимость ловит правило симметрии.
     */
    val order: Deviation by lazy { compareSketches(base, sketch(Frame.ORDER), REFERENCE_TIGHT, REFERENCE_SHARE) }
}

/** Итог проверки симметрии одной сцены. */
class SymmetryResult {
    /** Сцена повторила контрольный прогон с малым сдвигом: её можно судить по одному прогону. */
    var calm = false
    var nudge: Deviation? = null
    val checks = ArrayList<Deviation>()
    /** Что нарушено у спокойной сцены; пусто — симметрия держится (или сцена хаотична и не судится). */
    val problems = ArrayList<String>()
    val failed: Boolean get() = problems.isNotEmpty()
}

/** Прогон сцены в системе отсчёта [frame] с набросками; [reference] > 0 — эталонным решателем, до тика [stopAt]. */
internal fun sketchedRun(s: Scenario, frame: Frame, reference: Int = 0, stopAt: Int = Int.MAX_VALUE): Sketch {
    val run = StandRun(s, frame = frame, reference = reference, stopAt = stopAt)
    val sketch = Sketch(frame, s.ticks)
    run.inVariation { sketch.begin(run) }
    while (!run.done) { run.step(); run.inVariation { sketch.after(run) } }
    sketch.missedHits = run.w.world.reference?.missedHits ?: 0
    return sketch
}

/**
 * Проверка симметрии сцены [s]: исходный прогон, контрольный с малым сдвигом и по прогону на каждую
 * систему отсчёта из [frames]. Сцена с рамкой мира поворачивается на четверть оборота вместо 37°.
 */
fun checkSymmetry(s: Scenario, frames: List<Frame>, control: Control = Control(s)): SymmetryResult {
    val result = SymmetryResult()
    val base = control.base
    result.nudge = control.nudge
    result.calm = control.calm
    for (frame in frames) {
        val other = try { control.sketch(frame) } catch (e: FrameUnfit) {
            if (frame.id != Frame.TURN.id) throw e
            control.sketch(Frame.QUARTER)
        }
        val d = compareSketches(base, other)
        result.checks.add(d)
        if (result.calm && (!d.clean || !d.close)) result.problems.add("${d.frame.title}: ${d.describe()}")
    }
    return result
}

// --- отчёт пакетного прогона ---

private class FrameTotals(val frame: Frame) {
    var judged = 0; var failed = 0; var chaotic = 0
    var worst = 0.0; var worstId = ""
    var sum = 0.0
    var tornBase = 0L; var tornOther = 0L; var diedBase = 0L; var diedOther = 0L
    var verdictChanged = 0; var passedBase = 0; var passedOther = 0; var all = 0
}

private fun totals(outcomes: List<Outcome>, frames: List<Frame>): List<FrameTotals> = frames.mapIndexed { k, frame ->
    val t = FrameTotals(frame)
    for (o in outcomes) {
        val sym = o.symmetry ?: continue
        val d = sym.checks.getOrNull(k) ?: continue
        t.all++
        t.tornBase += d.tornBase; t.tornOther += d.tornOther; t.diedBase += d.diedBase; t.diedOther += d.diedOther
        if (!d.sameVerdict) t.verdictChanged++
        if (d.verdictBase.isEmpty()) t.passedBase++
        if (d.verdictOther.isEmpty()) t.passedOther++
        if (!sym.calm) { t.chaotic++; continue }
        t.judged++
        t.sum += d.score
        if (d.score > t.worst) { t.worst = d.score; t.worstId = o.scenario.id }
        if (!d.clean || !d.close) t.failed++
    }
    t
}

/** Короткая сводка для консоли. */
fun symmetrySummary(outcomes: List<Outcome>, frames: List<Frame>): String {
    val checked = outcomes.count { it.symmetry != null }
    val calm = outcomes.count { it.symmetry?.calm == true }
    val sb = StringBuilder()
    sb.append("СИММЕТРИЯ: проверено сцен $checked, спокойных (судятся) $calm, хаотичных (только суммы) ${checked - calm}, нарушений ${outcomes.count { it.symmetry?.failed == true }}\n")
    for (t in totals(outcomes, frames)) sb.append(String.format(Locale.ROOT,
        "  %-32s нарушений %4d из %5d; худшее расхождение %.3f связи; порвано %d против %d, погибло %d против %d, вердикт сменился у %d\n",
        t.frame.title, t.failed, t.judged, t.worst, t.tornOther, t.tornBase, t.diedOther, t.diedBase, t.verdictChanged))
    return sb.toString().trimEnd()
}

/** Отчёт: сводка по системам отсчёта, нарушения спокойных сцен, самые чувствительные сцены. */
fun symmetryReport(outcomes: List<Outcome>, frames: List<Frame>): String {
    val sb = StringBuilder()
    val checked = outcomes.filter { it.symmetry != null }
    val calm = checked.filter { it.symmetry!!.calm }
    sb.append("# Стенды физики: проверка симметрии\n\n")
    sb.append("Сцена прогоняется как написана и ещё раз в каждой системе отсчёта; итог в координатах сцены обязан совпасть. ")
    sb.append("Сначала — контрольный прогон с малым сдвигом: сцена, которая не повторила даже его (расхождение больше ")
    sb.append("$SYMMETRY_CALM связи, другой счёт разрывов или другой вердикт), хаотична и по одному прогону не судится.\n\n")
    sb.append("Проверено сцен ${checked.size}: спокойных ${calm.size}, хаотичных ${checked.size - calm.size}. ")
    sb.append("Допуск спокойной сцены — $SYMMETRY_TIGHT связи плюс ${(SYMMETRY_SHARE * 100).toInt()}% пройденного телом пути, тот же счёт разрывов и погибших, тот же вердикт правил.\n\n")
    sb.append("## По системам отсчёта\n\n")
    sb.append("| Система отсчёта | Судится сцен | Нарушений | Среднее расхождение, связи | Худшее, связи | Худшая сцена |\n|---|---:|---:|---:|---:|---|\n")
    val all = totals(outcomes, frames)
    for (t in all) sb.append(String.format(Locale.ROOT, "| %s | %d | %d | %.4f | %.3f | `%s` |\n",
        t.frame.title, t.judged, t.failed, if (t.judged > 0) t.sum / t.judged else 0.0, t.worst, t.worstId))
    sb.append("\n## Суммы по всем сценам, включая хаотичные\n\n")
    sb.append("Перекос решателя — «лево и право считаются по-разному» — виден и там, где отдельную сцену судить нельзя: ")
    sb.append("в отражённых или перенумерованных сценах в сумме рвётся и гибнет заметно больше или меньше, чем в исходных.\n\n")
    sb.append("| Система отсчёта | Сцен | Порвано связей: исходные | в этой системе | Погибло клеток: исходные | в этой системе | Прошло правила: исходные | в этой системе | Вердикт сменился |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|\n")
    for (t in all) sb.append("| ${t.frame.title} | ${t.all} | ${t.tornBase} | ${t.tornOther} | ${t.diedBase} | ${t.diedOther} | ${t.passedBase} | ${t.passedOther} | ${t.verdictChanged} |\n")

    val failed = checked.filter { it.symmetry!!.failed }
    sb.append("\n## Нарушения симметрии у спокойных сцен: ${failed.size}\n\n")
    for ((family, group) in failed.groupBy { it.scenario.family }) {
        sb.append("### $family\n\n")
        for (o in group) {
            sb.append("- `${o.scenario.id}` — ${o.scenario.title}\n")
            for (p in o.symmetry!!.problems) sb.append("  - $p\n")
        }
        sb.append('\n')
    }
    sb.append("## Хаотичные сцены по семействам\n\n| Семейство | Сцен | Хаотичных |\n|---|---:|---:|\n")
    for ((family, group) in checked.groupBy { it.scenario.family })
        sb.append("| $family | ${group.size} | ${group.count { !it.symmetry!!.calm }} |\n")
    return sb.toString()
}
