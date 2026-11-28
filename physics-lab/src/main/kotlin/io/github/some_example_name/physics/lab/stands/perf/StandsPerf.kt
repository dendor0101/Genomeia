package io.github.some_example_name.physics.lab.stands.perf

import io.github.some_example_name.physics.PhysicsWorkers
import io.github.some_example_name.physics.SUBSTEPS
import io.github.some_example_name.physics.lab.io.PlayerLog
import io.github.some_example_name.physics.lab.stands.StandWorld
import java.io.File
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

/**
 * ЗАМЕР ПРОИЗВОДИТЕЛЬНОСТИ НА СЦЕНАХ СТЕНДОВ: время шага мира физики и то, как оно падает с
 * числом воркеров.
 *
 *   gradlew :physics-lab:standsPerf                         все сцены, воркеров 1, 2, 4, 8
 *   gradlew :physics-lab:standsPerf -Pworkers=1,4           другой набор воркеров
 *   gradlew :physics-lab:standsPerf -PperfScale=4           вчетверо больше частиц (единица — около 20 тысяч на сцену)
 *   gradlew :physics-lab:standsPerf -Ponly=small,gas        часть сцен
 *   gradlew :physics-lab:standsPerf -PperfTicks=300 -PperfWarm=100 -PperfRuns=5
 *   gradlew :physics-lab:standsPerf -Psubsteps=4            с другим числом подшагов
 *
 * КАК МЕРЯЕТСЯ. Сцена собирается заново для каждого числа воркеров и каждого повтора; первые
 * perfWarm тиков не в счёт (за них JIT доводит код, а тела успевают сойтись), затем perfTicks
 * тиков меряется время одного только шага мира (StandWorld.lastStepNanos: без действий сценария
 * и без монитора правил). Из тиков берётся медиана — она не боится отдельной паузы сборщика
 * мусора; из повторов — лучший (наименьшая медиана): шум системы время только прибавляет.
 * Перед всеми замерами каждая сцена прогоняется вхолостую, чтобы первая не платила за прогрев
 * всех остальных.
 *
 * ЧТО ПЕЧАТАЕТСЯ. По сцене: частиц, время тика при каждом числе воркеров, ускорение к одному
 * потоку и эффективность (ускорение, делённое на число воркеров: единица — идеал). Сводка —
 * среднее геометрическое по «обычной игре» и по «тяжёлым краям». Отдельный проход в один поток
 * с замером по стадиям показывает, на что уходит тик в каждой сцене.
 *
 * Заодно сверяется детерминизм: состояние мира после замера обязано совпасть побитово при
 * любом числе воркеров — здесь сцены настоящего размера, в десятки блоков решателя.
 *
 * Отчёт: physics-lab/build/reports/stands/perf.md.
 */
private class Timing(val median: Double, val mean: Double, val p95: Double, val worst: Double, val hash: Long, val particles: Int, val bodies: Int)

private fun measure(scene: PerfScene, scale: Double, workers: Int, warm: Int, ticks: Int, stages: LongArray? = null, names: Array<String>? = null): Timing {
    val pool = if (workers > 1) PhysicsWorkers.shared(workers) else null
    val w = StandWorld(pool)
    scene.setup(w, scale)
    repeat(warm) { w.step() }
    val st = w.world.state
    if (stages != null) { java.util.Arrays.fill(st.dbgTimeNs, 0L); st.dbgTimeOn = true }
    val times = DoubleArray(ticks)
    for (t in 0 until ticks) { w.step(); times[t] = w.lastStepNanos / 1e6 }
    if (stages != null) { st.dbgTimeOn = false; System.arraycopy(st.dbgTimeNs, 0, stages, 0, minOf(stages.size, st.dbgTimeNs.size)) }
    names?.let { System.arraycopy(st.dbgTimeNames, 0, it, 0, minOf(it.size, st.dbgTimeNames.size)) }
    val sorted = times.sortedArray()
    return Timing(sorted[ticks / 2], times.average(), sorted[(ticks * 95) / 100], sorted[ticks - 1],
        PlayerLog.stateHash(st.n, st.px, st.py, st.vx, st.vy), w.world.particleCount, w.bodies.size)
}

private fun geoMean(values: List<Double>): Double = if (values.isEmpty()) 0.0 else exp(values.sumOf { ln(it) } / values.size)

fun main() {
    Locale.setDefault(Locale.ROOT)
    val scale = System.getProperty("stands.perfScale")?.toDoubleOrNull() ?: 1.0
    val ticks = System.getProperty("stands.perfTicks")?.toIntOrNull() ?: 150
    val warm = System.getProperty("stands.perfWarm")?.toIntOrNull() ?: 60
    val runs = System.getProperty("stands.perfRuns")?.toIntOrNull() ?: 3
    val cores = Runtime.getRuntime().availableProcessors()
    val workerList = (System.getProperty("stands.workers") ?: "1,2,4,8").split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it >= 1 }.distinct()
    val only = System.getProperty("stands.only").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val scenes = PERF_SCENES.filter { s -> only.isEmpty() || only.any { s.id.contains(it) } }
    require(scenes.isNotEmpty()) { "нет таких сцен замера: $only" }

    println("ЗАМЕР: сцен ${scenes.size}, размер ×$scale, подшагов $SUBSTEPS, воркеров $workerList (потоков у машины $cores), прогрев $warm тиков, замер $ticks тиков, повторов $runs")
    println("прогрев кода: каждая сцена вхолостую…")
    for (s in scenes) measure(s, minOf(scale, 0.5), 1, 20, 40)
    if (workerList.any { it > 1 }) for (s in scenes.take(3)) measure(s, minOf(scale, 0.5), workerList.max(), 20, 40)

    class Row(val scene: PerfScene, val particles: Int, val bodies: Int, val ms: DoubleArray, val p95: DoubleArray, val same: Boolean, val stageText: String)
    val rows = ArrayList<Row>()
    val t0 = System.nanoTime()
    for (s in scenes) {
        val ms = DoubleArray(workerList.size) { Double.MAX_VALUE }
        val p95 = DoubleArray(workerList.size)
        val hashes = LongArray(workerList.size)
        var particles = 0; var bodies = 0
        for (run in 0 until runs) for (k in workerList.indices) {
            // Порядок чисел воркеров от повтора к повтору сдвигается: разогрев процессора и соседние задачи не достаются одному и тому же.
            val i = (k + run) % workerList.size
            val t = measure(s, scale, workerList[i], warm, ticks)
            if (t.median < ms[i]) { ms[i] = t.median; p95[i] = t.p95 }
            hashes[i] = t.hash; particles = t.particles; bodies = t.bodies
        }
        // На что уходит тик: один поток, со счётчиками стадий (сами счётчики тик немного замедляют, поэтому отдельным проходом).
        val stageNs = LongArray(32); val stageNames = Array(32) { "" }
        measure(s, scale, 1, warm, maxOf(40, ticks / 2), stageNs, stageNames)
        val total = stageNs.sum().coerceAtLeast(1L)
        val top = stageNs.indices.filter { stageNs[it] > 0 }.sortedByDescending { stageNs[it] }.take(5)
            .joinToString(", ") { "${stageNames[it]} ${Math.round(100.0 * stageNs[it] / total)}%" }
        val same = hashes.all { it == hashes[0] }
        rows.add(Row(s, particles, bodies, ms, p95, same, top))
        val line = StringBuilder(String.format("  %-9s частиц %6d тел %4d |", s.id, particles, bodies))
        for (k in workerList.indices) line.append(String.format(" %dв %7.2f мс", workerList[k], ms[k]))
        if (workerList.size > 1) line.append(String.format(" | ускорение ×%.2f", ms[0] / ms[workerList.size - 1]))
        line.append(if (same) " | побитово" else " | РАЗОШЛОСЬ ПО ВОРКЕРАМ")
        println(line)
    }
    val seconds = (System.nanoTime() - t0) / 1e9

    val sb = StringBuilder()
    sb.append("# Стенды физики: замер производительности\n\n")
    sb.append("Размер сцен ×$scale, подшагов $SUBSTEPS, прогрев $warm тиков, замер $ticks тиков, лучший из $runs повторов. Потоков у машины $cores. Замер занял ${Math.round(seconds)} с.\n\n")
    sb.append("Время — медиана шага мира физики за тик, миллисекунды (без действий сценария и без монитора правил). Бюджет тика игры при 30 тиках в секунду — 33 мс.\n\n")
    sb.append("## Время тика по числу воркеров\n\n| Сцена | Что в ней | Частиц | Тел |")
    for (wk in workerList) sb.append(" $wk в., мс |")
    for (wk in workerList.drop(1)) sb.append(" ускорение ×$wk в. | эффективность |")
    sb.append(" Один поток = воркеры |\n|---|---|---:|---:|").append("---:|".repeat(workerList.size)).append("---:|---:|".repeat(workerList.size - 1)).append("---|\n")
    for (r in rows) {
        sb.append("| `${r.scene.id}`${if (r.scene.standard) "" else " ⚑"} | ${r.scene.title} | ${r.particles} | ${r.bodies} |")
        for (k in workerList.indices) sb.append(String.format(" %.2f |", r.ms[k]))
        for (k in 1 until workerList.size) sb.append(String.format(" %.2f | %.0f%% |", r.ms[0] / r.ms[k], 100.0 * r.ms[0] / r.ms[k] / workerList[k]))
        sb.append(if (r.same) " побитово |\n" else " **разошлось** |\n")
    }
    sb.append("\n⚑ — «тяжёлый край»: сцена, где дорожает одна стадия решателя; в среднее по обычной игре не входит.\n\n")

    sb.append("## Сводка\n\n| Группа | Сцен |")
    for (wk in workerList) sb.append(" $wk в., мс |")
    for (wk in workerList.drop(1)) sb.append(" ускорение ×$wk в. | эффективность |")
    sb.append(" мкс на частицу, 1 в. |\n|---|---:|").append("---:|".repeat(workerList.size)).append("---:|---:|".repeat(workerList.size - 1)).append("---:|\n")
    println()
    for ((name, group) in listOf("обычная игра" to rows.filter { it.scene.standard }, "тяжёлые края" to rows.filter { !it.scene.standard }, "все сцены" to rows)) {
        if (group.isEmpty()) continue
        sb.append("| $name | ${group.size} |")
        val means = DoubleArray(workerList.size) { k -> geoMean(group.map { it.ms[k] }) }
        for (k in workerList.indices) sb.append(String.format(" %.2f |", means[k]))
        val line = StringBuilder(String.format("  %-13s (%2d сцен): ", name, group.size))
        for (k in workerList.indices) line.append(String.format("%dв %.2f мс  ", workerList[k], means[k]))
        for (k in 1 until workerList.size) {
            val speedup = geoMean(group.map { it.ms[0] / it.ms[k] })
            sb.append(String.format(" %.2f | %.0f%% |", speedup, 100.0 * speedup / workerList[k]))
            line.append(String.format("×%d: %.2f (%.0f%%)  ", workerList[k], speedup, 100.0 * speedup / workerList[k]))
        }
        sb.append(String.format(" %.2f |\n", geoMean(group.map { 1000.0 * it.ms[0] / it.particles })))
        println(line)
    }
    sb.append("\nСреднее — геометрическое по сценам группы. Эффективность — ускорение, делённое на число воркеров: 100% значило бы, что работа делится без потерь.\n\n")

    sb.append("## Хвосты: 95-й процентиль времени тика, мс\n\n| Сцена |")
    for (wk in workerList) sb.append(" $wk в. |")
    sb.append("\n|---|").append("---:|".repeat(workerList.size)).append('\n')
    for (r in rows) { sb.append("| `${r.scene.id}` |"); for (k in workerList.indices) sb.append(String.format(" %.2f |", r.p95[k])); sb.append('\n') }

    sb.append("\n## На что уходит тик (один поток)\n\n| Сцена | Пять самых дорогих стадий |\n|---|---|\n")
    for (r in rows) sb.append("| `${r.scene.id}` | ${r.stageText} |\n")

    val diverged = rows.filter { !it.same }
    sb.append("\n## Детерминизм\n\n")
    sb.append(if (diverged.isEmpty()) "Состояние мира после замера совпало побитово при всех числах воркеров во всех ${rows.size} сценах.\n"
    else "РАЗОШЛОСЬ ПО ВОРКЕРАМ в сценах: ${diverged.joinToString(", ") { it.scene.id }}.\n")

    val dir = File("physics-lab/build/reports/stands").also { it.mkdirs() }
    File(dir, "perf.md").writeText(sb.toString())
    println("замер занял ${Math.round(seconds)} с; отчёт: ${File(dir, "perf.md").path}" + if (diverged.isEmpty()) "; детерминизм: все сцены побитово" else "; РАЗОШЛОСЬ: ${diverged.size}")
    System.exit(0)
}
