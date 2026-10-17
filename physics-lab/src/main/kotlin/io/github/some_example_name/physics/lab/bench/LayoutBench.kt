package io.github.some_example_name.physics.lab.bench

import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.SUBSTEPS
import io.github.some_example_name.physics.lab.inspection.Probe
import io.github.some_example_name.physics.solveAreas
import io.github.some_example_name.physics.solveConstraints
import java.util.Locale
import java.util.Random

/**
 * Цена раскладки в НАСТОЯЩИХ solveConstraints/solveAreas, без копии их формул.
 * Меняются только номера частиц и соответствующие ссылки; порядок ограничений,
 * аргументов треугольника и арифметики остаётся прежним. После каждого блока
 * позиции сравниваются побитово с исходной раскладкой в старых номерах.
 * Случайная перестановка — контроль плохой локальности, не предложение для игры.
 * Здесь нельзя вызывать simulate(): остальные стадии не перенумерованы.
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val path = args.getOrNull(0) ?: "body-export.txt"
    val copies = args.getOrNull(1)?.toIntOrNull() ?: 108
    val p = Probe
    p.boot(path, copies, 0)
    p.demo.state.quiet = true
    p.resetState()
    val n = p.n
    val sourceX = p.px.copyOf(); val sourceY = p.py.copyOf(); val mass = p.invMass.copyOf()
    val a = p.conA.copyOf(); val b = p.conB.copyOf()
    val ta = p.triA.copyOf(); val tb = p.triB.copyOf(); val tc = p.triC.copyOf()
    val h = p.const("DT") / p.constInt("SUBSTEPS")
    for (i in 0 until n) {
        sourceX[i] += ((i * 17 % 13) - 6) * p.body.meanLinkLength * 0.001
        sourceY[i] += ((i * 23 % 11) - 5) * p.body.meanLinkLength * 0.001
    }
    val original = IntArray(n) { it }
    val rcm = reverseCuthillMcKee(n, p.body.linkA, p.body.linkB)
    val random = original.copyOf()
    val rng = Random(571L)
    for (i in random.lastIndex downTo 1) {
        val j = rng.nextInt(i + 1); val tmp = random[i]; random[i] = random[j]; random[j] = tmp
    }
    val names = arrayOf("original", "rcm", "random")
    val orders = arrayOf(original, rcm, random)
    val blocks = 9
    val repeats = 32
    data class Layout(val oldToNew: IntArray,
        val x: DoubleArray, val y: DoubleArray, val mass: DoubleArray,
        val a: IntArray, val b: IntArray, val ta: IntArray, val tb: IntArray, val tc: IntArray)
    val layouts = orders.map { order ->
        val oldToNew = IntArray(n)
        for (i in order.indices) oldToNew[order[i]] = i
        fun mapped(src: IntArray) = IntArray(src.size) { oldToNew[src[it]] }
        Layout(oldToNew, DoubleArray(n) { sourceX[order[it]] },
            DoubleArray(n) { sourceY[order[it]] }, DoubleArray(n) { mass[order[it]] },
            mapped(a), mapped(b), mapped(ta), mapped(tb), mapped(tc))
    }
    fun install(layout: Layout) {
        p.setField("px", layout.x); p.setField("py", layout.y); p.setField("invMass", layout.mass)
        p.setField("conA", layout.a); p.setField("conB", layout.b)
        p.setField("triA", layout.ta); p.setField("triB", layout.tb); p.setField("triC", layout.tc)
        p.attach(p.demo)
    }
    // Прогрев обеих функций до таймера; setup восстанавливает одинаковую сцену.
    repeat(256) { p.solveConstraints(h); p.solveAreas(h) }
    for (layout in layouts) {
        install(layout)
        repeat(96) { p.solveConstraints(h); p.solveAreas(h) }
    }
    val linkMs = Array(3) { DoubleArray(blocks) }; val areaMs = Array(3) { DoubleArray(blocks) }
    // Чередование вариантов и обратный порядок через серию уменьшают перекос
    // от прогрева/температуры/посторонней нагрузки между длинными отдельными прогонами.
    repeat(blocks) { block ->
        for (v in names.indices) {
            val variant = if (block % 2 == 0) v else names.lastIndex - v
            install(layouts[variant])
            var linkNs = 0L; var areaNs = 0L
            repeat(repeats) {
                val start = System.nanoTime()
                p.solveConstraints(h)
                val between = System.nanoTime()
                p.solveAreas(h)
                val end = System.nanoTime()
                linkNs += between - start; areaNs += end - between
            }
            linkMs[variant][block] = linkNs / 1e6 / repeats
            areaMs[variant][block] = areaNs / 1e6 / repeats
        }
        // Сверка после всех трёх вариантов, вне измеряемого участка.
        for (variant in 1 until layouts.size) for (old in 0 until n) {
            val layout = layouts[variant]; val i = layout.oldToNew[old]
            check(layouts[0].x[old].toBits() == layout.x[i].toBits() &&
                layouts[0].y[old].toBits() == layout.y[i].toBits()) {
                "layout=${names[variant]} block=$block particle=$old diverged"
            }
        }
    }
    for (variant in names.indices) {
        val links = linkMs[variant]; val areas = areaMs[variant]
        links.sort(); areas.sort()
        println("LAYOUT path=$path copies=$copies n=$n mode=${names[variant]}" +
            " links_ms=%.5f areas_ms=%.5f links_p25=%.5f links_p75=%.5f areas_p25=%.5f areas_p75=%.5f checks=%d".format(
                links[blocks / 2], areas[blocks / 2], links[blocks / 4], links[blocks * 3 / 4],
                areas[blocks / 4], areas[blocks * 3 / 4], if (variant == 0) 0 else blocks * n * 2))
        println("LOCAL ${linkLocality(layouts[variant].a, layouts[variant].b, p.conCount)}")
    }
}

/** Детерминированная загрузочная RCM: минимальная степень, равенство — старый ID. */
private fun reverseCuthillMcKee(n: Int, a: IntArray, b: IntArray): IntArray {
    val degree = IntArray(n)
    for (k in a.indices) { degree[a[k]]++; degree[b[k]]++ }
    val start = IntArray(n + 1)
    for (i in 0 until n) start[i + 1] = start[i] + degree[i]
    val cursor = start.copyOf()
    val neighbors = IntArray(start[n])
    for (k in a.indices) { neighbors[cursor[a[k]]++] = b[k]; neighbors[cursor[b[k]]++] = a[k] }
    val visited = BooleanArray(n)
    val queue = IntArray(n)
    val result = IntArray(n)
    // Один порядок стартов: повторный поиск минимальной степени по всем вершинам
    // превратился бы в O(n²) на множестве отдельных клеток.
    val seeds = LongArray(n) { (degree[it].toLong() shl 32) or it.toLong() }
    seeds.sort()
    var count = 0
    for (key in seeds) {
        val seed = key.toInt()
        if (visited[seed]) continue
        var head = 0; var tail = 1
        queue[0] = seed; visited[seed] = true
        while (head < tail) {
            val v = queue[head++]
            val next = neighbors.copyOfRange(start[v], start[v + 1]).toTypedArray()
            next.sortWith(compareBy<Int> { degree[it] }.thenBy { it })
            for (u in next) if (!visited[u]) { visited[u] = true; queue[tail++] = u }
        }
        for (k in tail - 1 downTo 0) result[count++] = queue[k]
    }
    return result
}
