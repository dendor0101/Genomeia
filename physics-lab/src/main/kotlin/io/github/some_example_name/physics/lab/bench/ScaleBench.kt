package io.github.some_example_name.physics.lab.bench

import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.SUBSTEPS
import io.github.some_example_name.physics.lab.inspection.Probe
import io.github.some_example_name.physics.lab.scenes.slamOrganisms
import io.github.some_example_name.physics.lab.session.replayStateHash
import java.io.File
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.abs

/**
 * Масштаб настоящего simulate(), отдельно одно связное тело и мир из копий.
 * Загрузка, хэш и обход памяти не входят в время тика. Два свежих запуска каждой
 * сцены сверяются на КАЖДОМ тике; таймеры стадий включены только во втором запуске.
 * Это короткий замер CPU, не проверка отсутствия проникновений и не замер FPS.
 * Пример: :lwjgl3:scaleBench -PscaleTicks=48 -PscaleWarm=32.
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val path = args.getOrNull(0) ?: "body-export.txt"
    val ticks = (args.getOrNull(1)?.toIntOrNull() ?: 48).coerceAtLeast(1)
    val warm = (args.getOrNull(2)?.toIntOrNull() ?: 32).coerceAtLeast(1)
    val selection = args.getOrNull(3) ?: "all"
    val dir = File("build/reports/scale-bench").apply { mkdirs() }
    println("SCALE java=${System.getProperty("java.runtime.version")} ticks=$ticks warm=$warm selection=$selection")
    val p = Probe
    // JIT прогревается на том же настоящем теле до первой строки таблицы.
    p.boot(path, 2, 0)
    p.demo.state.quiet = true
    p.resetState()
    val dt = p.const("DT"); val sub = p.constInt("SUBSTEPS")
    repeat(200) { p.frameGait(dt, it) }
    data class Scene(val name: String, val path: String, val copies: Int, val mode: String)
    val scenes = ArrayList<Scene>()
    for (side in intArrayOf(32, 100, 320)) {
        val file = File(dir, "grid-$side.txt")
        writeScaleGrid(file, side)
        scenes.add(Scene("grid-$side", file.path, 1, "rest"))
    }
    for (copies in intArrayOf(2, 12, 108)) for (mode in arrayOf("rest", "gait", "slam")) {
        scenes.add(Scene("world-$copies", path, copies, mode))
    }
    for (scene in scenes) {
        if (selection != "all" && !"${scene.name}:${scene.mode}".contains(selection)) continue
        val hashes = LongArray(warm + ticks)
        var plainMs = 0.0
        repeat(2) { run ->
            p.boot(scene.path, scene.copies, 0)
            p.demo.state.quiet = true
            p.resetState()
            val initialLinks = p.conCount
            val initialTriangles = p.triCount

            val times = DoubleArray(ticks)
            val stages = LongArray(p.demo.state.dbgTimeNs.size)
            for (tick in 0 until warm + ticks) {
                // Как O: бьют два крупнейших тела. На большой сцене остальные — фон.
                if (scene.mode == "slam" && tick == warm) p.demo.slamOrganisms()
                p.demo.state.dbgTimeOn = run == 1 && tick >= warm
                if (tick == warm) p.demo.state.dbgTimeNs.fill(0)
                val start = System.nanoTime()
                if (scene.mode == "gait") p.frameGait(dt, tick)
                else p.frame(dt, sub, contract = false)
                val elapsed = System.nanoTime() - start
                if (tick >= warm) times[tick - warm] = elapsed / 1e6
                val hash = p.demo.replayStateHash()
                if (run == 0) hashes[tick] = hash
                else check(hashes[tick] == hash) { "${scene.name}:${scene.mode} diverged at tick $tick" }
            }
            p.demo.state.dbgTimeNs.copyInto(stages)
            val mean = times.average()
            times.sort()
            if (run == 0) plainMs = mean
            println("SCALE scene=${scene.name}:${scene.mode} run=$run n=${p.n} links=$initialLinks triangles=$initialTriangles" +
                " mean_ms=%.4f median_ms=%.4f p95_ms=%.4f ns_particle=%.1f".format(
                    mean, times[ticks / 2], times[((ticks - 1) * 0.95).toInt()], mean * 1e6 / p.n) +
                " lost_links=${initialLinks - p.conCount} lost_triangles=${initialTriangles - p.triCount}")
            if (run == 1) {
                println("SCALE checksums=${warm + ticks}/${warm + ticks} timer_ratio=%.3f".format(mean / plainMs))
                for (k in stages.indices) if (stages[k] != 0L) {
                    println("STAGE ${p.demo.state.dbgTimeNames[k]} ms_tick=%.4f percent_tick=%.2f".format(
                        stages[k] / 1e6 / ticks, stages[k] / 1e6 / ticks / mean * 100.0))
                }
                // Нижняя оценка: только уникальные примитивные массивы классов стенда.
                // Не включает заголовки, HashMap/fastutil, строки, JVM и временную загрузку.
                println("MEM primitive_payload_bytes=${primitivePayload(p.demo)} history_bytes=0" +
                    " links_areas_working_bytes=${24L * p.n + 21L * initialLinks + 25L * initialTriangles}")
                println("LOCAL links ${linkLocality(p.conA, p.conB, p.conCount)}")
            }
        }
    }
}

/** Порядок строк сохраняет соседство; не имитирует геном и не заменяет живую запись. */
private fun writeScaleGrid(file: File, side: Int) {
    file.bufferedWriter().use { out ->
        out.appendLine("# connected soft grid $side x $side")
        for (y in 0 until side) for (x in 0 until side) {
            out.appendLine("P ${y * side + x} ${x * 0.6} ${y * 0.6} 0.5 0 0 0")
        }
        for (y in 0 until side) for (x in 0 until side) {
            val i = y * side + x
            if (x + 1 < side) out.appendLine("L $i ${i + 1}")
            if (y + 1 < side) out.appendLine("L $i ${i + side}")
            if (x + 1 < side && y + 1 < side) out.appendLine("L $i ${i + side + 1}")
        }
    }
}

/** Размах индексов — подсказка, не аппаратный счётчик промахов кэша. */
internal fun linkLocality(a: IntArray, b: IntArray, count: Int): String {
    if (count == 0) return "empty"
    val spans = IntArray(count) { abs(a[it] - b[it]) }
    spans.sort()
    // Рабочее окно из 64 последовательных связей: только случайно читаемые
    // px/py/invMass, по 8 Double на условную линию. База массива не выровнена по 64.
    val windows = IntArray((count + 63) / 64)
    for (w in windows.indices) {
        val lines = HashSet<Int>()
        for (i in w * 64 until minOf(count, (w + 1) * 64)) {
            lines.add(a[i] / 8); lines.add(b[i] / 8)
        }
        windows[w] = lines.size * 3 * 64
    }
    windows.sort()
    return "span_p50=${spans[count / 2]} span_p95=${spans[(count - 1) * 95 / 100]} span_max=${spans.last()}" +
        " window64_p50_bytes=${windows[windows.size / 2]} window64_max_bytes=${windows.last()}"
}

private fun primitivePayload(root: Any): Long {
    val seen = IdentityHashMap<Any, Boolean>()
    fun visit(value: Any?): Long {
        if (value == null || seen.put(value, true) != null) return 0
        return when (value) {
            is DoubleArray -> value.size * 8L
            is LongArray -> value.size * 8L
            is FloatArray -> value.size * 4L
            is IntArray -> value.size * 4L
            is ShortArray -> value.size * 2L
            is CharArray -> value.size * 2L
            is ByteArray -> value.size.toLong()
            is BooleanArray -> value.size.toLong()
            is Array<*> -> value.sumOf { visit(it) }
            else -> {
                if (!value.javaClass.name.startsWith("io.github.some_example_name.")) 0L
                else value.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
                    .sumOf { it.isAccessible = true; visit(it.get(value)) }
            }
        }
    }
    return visit(root)
}
