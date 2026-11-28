package io.github.some_example_name.physics.lab.bench

import io.github.some_example_name.physics.lab.stands.GenomeBodies
import io.github.some_example_name.physics.waveOrder
import java.util.Locale

/**
 * КАК ЛЕЖАТ СВЯЗИ И МЫШЦЫ У НАСТОЯЩИХ ГЕНОМОВ (24.11.2026, находка 40) — разбор библиотеки тел стендов без единого
 * тика: годятся ли им «волны» и «острова».
 *
 * По каждому телу (как его получает физика — ShapeSpec.definition):
 *  - связей решателя (связи внутри одной кости решатель не заводит) и треугольников;
 *  - «цепочка»: доля связей, которые делят частицу с предыдущей по порядку описания, — насколько нынешний порядок
 *    мешал процессору считать связи разом; и то же после перестановки «волнами» (waveOrder в :physics — так тело
 *    теперь и входит в мир);
 *  - волн на участок в 1024 связи и средняя ширина волны;
 *  - мышцы: доля клеток и доля связей с мышечной клеткой; сколько раз на сотню связей по порядку обхода сменяется
 *    вид связи (мышечная — обычная) — по номерам и «волнами»: смена вида — это ошибка предсказания перехода в ядре;
 *  - блоков решателя по тысяче клеток.
 *
 * Запуск: genomeLayoutReport [наименьшее тело=100]
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val minCells = args.getOrNull(0)?.toIntOrNull() ?: 100
    println("GENOMES: тело | клеток | костных | мышечных | связей решателя | треугольников | цепочка по номерам → волнами, % | волн на 1024 связи | ширина волны | " +
        "мышечных связей, % | смен вида на 100 связей: по номерам → волнами | блоков")
    var sumCells = 0L; var sumLinks = 0L; var sumChain = 0L; var sumChainW = 0L; var sumMuscleLinks = 0L; var sumSwitch = 0L; var sumSwitchW = 0L
    var sumWaves = 0L; var sumWindows = 0L; var bodies = 0; var sumMuscle = 0L; var sumBone = 0L; var sumTris = 0L
    val sizes = IntArray(5)
    for (e in GenomeBodies.entries) {
        sizes[when { e.cells < 30 -> 0; e.cells < 100 -> 1; e.cells < 400 -> 2; e.cells < 1500 -> 3; else -> 4 }]++
        val shape = GenomeBodies.shape(e.slug)
        val def = shape.definition(0.0, 0.0)
        val n = def.count
        val boneOf = IntArray(n) { -1 }
        for ((b, cluster) in def.boneClusters.withIndex()) for (c in cluster) boneOf[c] = b
        // Связи решателя — в порядке описания, без временных связей роста и без связей внутри одной кости.
        val a = ArrayList<Int>(); val b = ArrayList<Int>()
        for (l in 0 until def.linkCount) {
            if (def.isTempLink(l)) continue
            val i = def.linkA[l]; val j = def.linkB[l]
            if (boneOf[i] != -1 && boneOf[i] == boneOf[j]) continue
            a.add(i); b.add(j)
        }
        val la = a.toIntArray(); val lb = b.toIntArray(); val m = la.size
        if (m == 0) continue
        val order = waveOrder(n, la, lb, null)
        val wa = IntArray(m) { la[order[it]] }; val wb = IntArray(m) { lb[order[it]] }
        fun chain(x: IntArray, y: IntArray): Int { var c = 0; for (k in 1 until m) if (x[k] == x[k - 1] || x[k] == y[k - 1] || y[k] == x[k - 1] || y[k] == y[k - 1]) c++; return c }
        val muscle = shape.muscle
        fun kind(i: Int, j: Int) = muscle[i] >= 0 || muscle[j] >= 0
        fun switches(x: IntArray, y: IntArray): Int { var c = 0; for (k in 1 until m) if (kind(x[k], y[k]) != kind(x[k - 1], y[k - 1])) c++; return c }
        var muscleLinks = 0
        for (k in 0 until m) if (kind(la[k], lb[k])) muscleLinks++
        // Волны: участками по 1024 связи, как считает waveOrder.
        var waves = 0; var windows = 0
        run {
            val last = IntArray(n); var height = 0; var base = 0; var windowTop = 0
            for (k in 0 until m) {
                if (k % 1024 == 0) { waves += height - base; base = height; windows++; windowTop = height }
                val l = maxOf(base, last[la[k]], last[lb[k]])
                last[la[k]] = l + 1; last[lb[k]] = l + 1
                if (l + 1 > height) height = l + 1
            }
            waves += height - base
        }
        val chainN = chain(la, lb); val chainW = chain(wa, wb)
        val swN = switches(la, lb); val swW = switches(wa, wb)
        bodies++; sumCells += n; sumLinks += m; sumChain += chainN; sumChainW += chainW; sumMuscleLinks += muscleLinks
        sumSwitch += swN; sumSwitchW += swW; sumWaves += waves; sumWindows += windows; sumMuscle += shape.muscleCount; sumBone += shape.boneCount
        sumTris += def.triCount
        if (n >= minCells) println("GENOME %-22s %6d %6d %6d %7d %7d   %3.0f → %3.0f   %5.0f %6.1f   %3.0f   %5.1f → %5.1f   %d".format(
            e.slug, n, shape.boneCount, shape.muscleCount, m, def.triCount, 100.0 * chainN / maxOf(1, m - 1), 100.0 * chainW / maxOf(1, m - 1),
            waves.toDouble() / windows, m.toDouble() / maxOf(1, waves), 100.0 * muscleLinks / m, 100.0 * swN / m, 100.0 * swW / m, (n + 999) / 1000))
    }
    println("ИТОГ: тел $bodies, клеток $sumCells (костных %.0f%%, мышечных %.0f%%), связей решателя $sumLinks, треугольников $sumTris".format(
        100.0 * sumBone / sumCells, 100.0 * sumMuscle / sumCells))
    println("ИТОГ по размерам: до 30 клеток — ${sizes[0]}, 30–99 — ${sizes[1]}, 100–399 — ${sizes[2]}, 400–1499 — ${sizes[3]}, от 1500 — ${sizes[4]}")
    println("ИТОГ по всем связям: цепочка по номерам %.0f%%, волнами %.0f%%; средняя ширина волны %.1f связи; мышечных связей %.0f%%; смен вида на 100 связей — %.1f по номерам, %.1f волнами".format(
        100.0 * sumChain / sumLinks, 100.0 * sumChainW / sumLinks, sumLinks.toDouble() / sumWaves, 100.0 * sumMuscleLinks / sumLinks,
        100.0 * sumSwitch / sumLinks, 100.0 * sumSwitchW / sumLinks))
}
