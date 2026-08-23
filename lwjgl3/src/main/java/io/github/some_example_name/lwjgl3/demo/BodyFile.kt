package io.github.some_example_name.lwjgl3.demo

import java.io.File
import java.util.Locale

/**
 * Загрузка тела, выгруженного из редактора генома (см. BodyExport в модуле core).
 *
 * Файл содержит ТОЛЬКО вершины и связи. Всё остальное — треугольники знаковой площади,
 * кластеры костей и мышц — выводится здесь, из топологии. Так и задумано: если бы
 * структуру считал экспортёр, стенд проверял бы его работу, а не геном.
 *
 * ФОРМАТ
 *   P <genomeId> <x> <y> <radius> <cellType> <bone> <muscle>
 *   L <genomeIdA> <genomeIdB>
 * Строки с '#' и пустые игнорируются.
 */
class BodyFile private constructor(
    /** Позиции после нормировки в мировые координаты стенда. */
    val x: FloatArray,
    val y: FloatArray,
    val radius: FloatArray,
    val isBone: BooleanArray,
    val isMuscle: BooleanArray,

    /** Связи: пара индексов на связь, подряд. */
    val linkA: IntArray,
    val linkB: IntArray,

    /** Треугольники: тройки индексов, развёрнутые до положительной площади покоя. */
    val triA: IntArray,
    val triB: IntArray,
    val triC: IntArray,
    val triRestArea2: FloatArray,

    /** Связные компоненты костных клеток. Каждая — отдельный кластер shape matching. */
    val boneClusters: Array<IntArray>,

    /** Связные компоненты мышечных клеток. */
    val muscleClusters: Array<IntArray>,

    /** Во сколько раз координаты ужаты при нормировке — нужно, чтобы пересчитать радиусы. */
    val scale: Float,
) {
    val count: Int get() = x.size
    val linkCount: Int get() = linkA.size
    val triCount: Int get() = triA.size

    /** Средняя длина связи. Опорный размер: от него считается число подшагов и толщина линий. */
    val meanLinkLength: Float by lazy {
        if (linkCount == 0) return@lazy 1f
        var s = 0f
        for (i in 0 until linkCount) {
            val dx = x[linkA[i]] - x[linkB[i]]
            val dy = y[linkA[i]] - y[linkB[i]]
            s += kotlin.math.sqrt(dx * dx + dy * dy)
        }
        s / linkCount
    }

    fun describe(): String = String.format(
        Locale.ROOT,
        "cells=%d links=%d triangles=%d (%.2f per cell)  bone=%d in %d clusters  muscle=%d in %d clusters  meanLink=%.4f",
        count, linkCount, triCount, triCount.toFloat() / count.coerceAtLeast(1),
        isBone.count { it }, boneClusters.size,
        isMuscle.count { it }, muscleClusters.size,
        meanLinkLength
    )

    companion object {

        /**
         * Читает файл и достраивает структуру.
         *
         * [targetWidth] — в какую ширину вписать тело по горизонтали, [centerX]/[centerY] —
         * куда поставить его центр. Координаты в движке измеряются клетками мира и имеют
         * совсем другой масштаб, поэтому без нормировки тело либо не влезет в кадр,
         * либо окажется точкой.
         */
        fun load(
            path: String,
            targetWidth: Float = 2.6f,
            targetHeight: Float = 1.7f,
            centerX: Float = 1.55f,
            centerY: Float = 0.85f,
            /** Сколько независимых организмов создать из одного файла. */
            copies: Int = 1,
            /** Зазор между копиями в мировых единицах. */
            gap: Float = 0.35f,
        ): BodyFile {
            val file = File(path)
            require(file.isFile) { "нет файла тела: $path" }

            // --- разбор ---
            val ids = ArrayList<Int>()
            val rawX = ArrayList<Float>()
            val rawY = ArrayList<Float>()
            val rawR = ArrayList<Float>()
            val bone = ArrayList<Boolean>()
            val muscle = ArrayList<Boolean>()
            val rawLinks = ArrayList<IntArray>()

            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val p = line.split(Regex("\\s+"))
                when (p[0]) {
                    "P" -> {
                        require(p.size >= 8) { "короткая строка P: $line" }
                        ids.add(p[1].toInt())
                        rawX.add(p[2].toFloat()); rawY.add(p[3].toFloat())
                        rawR.add(p[4].toFloat())
                        bone.add(p[6] == "1"); muscle.add(p[7] == "1")
                    }
                    "L" -> {
                        require(p.size >= 3) { "короткая строка L: $line" }
                        rawLinks.add(intArrayOf(p[1].toInt(), p[2].toInt()))
                    }
                }
            }
            require(ids.isNotEmpty()) { "в файле нет ни одной вершины: $path" }

            // genomeId разрежен (дырки от удалённых клеток), а массивам нужен 0..n-1.
            val indexOfId = HashMap<Int, Int>(ids.size * 2)
            ids.forEachIndexed { i, id -> indexOfId[id] = i }

            val n = ids.size

            // --- нормировка ---
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (i in 0 until n) {
                if (rawX[i] < minX) minX = rawX[i]; if (rawX[i] > maxX) maxX = rawX[i]
                if (rawY[i] < minY) minY = rawY[i]; if (rawY[i] > maxY) maxY = rawY[i]
            }
            // Вписываем по БОЛЬШЕЙ стороне, а не по ширине: реальные организмы бывают
            // сильно вытянуты по вертикали (у тестового тела 2.6 в ширину против 3.84
            // в высоту), и подгонка только по ширине выносит их за кадр.
            val spanX = maxOf(maxX - minX, 1e-6f)
            val spanY = maxOf(maxY - minY, 1e-6f)
            val scale = targetWidth / maxOf(spanX, spanY * targetWidth / targetHeight)
            val srcCx = (minX + maxX) * 0.5f
            val srcCy = (minY + maxY) * 0.5f

            // Позиции ОДНОЙ копии. Ниже они размножаются со сдвигом, см. РАЗМНОЖЕНИЕ ТЕЛА.
            val x0 = FloatArray(n) { centerX + (rawX[it] - srcCx) * scale }
            val y0 = FloatArray(n) { centerY + (rawY[it] - srcCy) * scale }
            val r0 = FloatArray(n) { rawR[it] * scale }

            // --- связи: без дублей, без ссылок в никуда ---
            val seen = HashSet<Long>(rawLinks.size * 2)
            val la0 = ArrayList<Int>(rawLinks.size)
            val lb0 = ArrayList<Int>(rawLinks.size)
            for (l in rawLinks) {
                val a = indexOfId[l[0]] ?: continue
                val b = indexOfId[l[1]] ?: continue
                if (a == b) continue
                val key = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()
                if (!seen.add(key)) continue            // уже была — дубль
                la0.add(a); lb0.add(b)
            }

            // --- РАЗМНОЖЕНИЕ ТЕЛА ---
            //
            // Копии делаются ПОСЛЕ нормировки и ДО построения смежности. Это важно:
            //   * после нормировки — иначе общая рамка охватила бы обе копии, масштаб
            //     упал бы вдвое, и вместе с ним поехали бы meanLinkLength и все
            //     подобранные под него константы. Каждая копия обязана быть ровно того
            //     же размера, что одиночное тело;
            //   * до смежности, треугольников и компонент — тогда вся производная
            //     структура выводится из графа сама и для копий получается правильной,
            //     а связей между копиями не возникает: индексы сдвинуты, общих рёбер нет.
            //
            // Копии — это отдельные организмы, а не одно тело из двух кусков: связные
            // компоненты костей и мышц у каждой свои, и решатель обходится с ними как
            // с независимыми телами. Ровно то, что нужно для проверки межорганизменных
            // контактов.
            val nOne = n
            val total = nOne * copies
            val x = FloatArray(total)
            val y = FloatArray(total)
            val r = FloatArray(total)
            val isBone = BooleanArray(total)
            val isMuscle = BooleanArray(total)
            for (c in 0 until copies) {
                val off = c * nOne
                val shiftX = (spanX * scale + gap) * c
                for (i in 0 until nOne) {
                    x[off + i] = x0[i] + shiftX
                    y[off + i] = y0[i]
                    r[off + i] = r0[i]
                    isBone[off + i] = bone[i]
                    isMuscle[off + i] = muscle[i]
                }
            }

            val adjacency = Array(total) { sortedSetOf<Int>() }
            val la = ArrayList<Int>(la0.size * copies)
            val lb = ArrayList<Int>(lb0.size * copies)
            for (c in 0 until copies) {
                val off = c * nOne
                for (k in la0.indices) {
                    val a = la0[k] + off
                    val b = lb0[k] + off
                    adjacency[a].add(b); adjacency[b].add(a)
                    la.add(a); lb.add(b)
                }
            }

            // --- треугольники: тройки взаимно связанных клеток ---
            //
            // Та же логика, что в RCMSort.collectTriangles: канонический порядок v < a < b,
            // чтобы каждый треугольник встретился ровно один раз, а не трижды.
            val ta = ArrayList<Int>(); val tb = ArrayList<Int>(); val tc = ArrayList<Int>()
            val area = ArrayList<Float>()
            // По ВСЕМ частицам, включая копии: n это размер одного тела.
            for (v in 0 until total) {
                val nb = adjacency[v].toIntArray()
                for (i in nb.indices) {
                    val a = nb[i]
                    if (a <= v) continue
                    for (j in i + 1 until nb.size) {
                        val b = nb[j]
                        if (b <= a) continue
                        if (!adjacency[a].contains(b)) continue

                        var i1 = a; var i2 = b
                        var area2 = signedArea2(x, y, v, i1, i2)
                        if (area2 < 0f) {           // разворачиваем до положительной площади,
                            val t = i1; i1 = i2; i2 = t   // иначе «вывернулся» неотличимо
                            area2 = -area2                // от «так и было»
                        }
                        if (area2 < 1e-9f) continue // три клетки на одной прямой
                        ta.add(v); tb.add(i1); tc.add(i2); area.add(area2)
                    }
                }
            }

            return BodyFile(
                x = x, y = y, radius = r, isBone = isBone, isMuscle = isMuscle,
                linkA = la.toIntArray(), linkB = lb.toIntArray(),
                triA = ta.toIntArray(), triB = tb.toIntArray(), triC = tc.toIntArray(),
                triRestArea2 = area.toFloatArray(),
                boneClusters = components(adjacency, isBone),
                muscleClusters = components(adjacency, isMuscle),
                scale = scale
            )
        }

        private fun signedArea2(x: FloatArray, y: FloatArray, i0: Int, i1: Int, i2: Int): Float =
            (x[i1] - x[i0]) * (y[i2] - y[i0]) - (y[i1] - y[i0]) * (x[i2] - x[i0])

        /**
         * Связные компоненты по вершинам, у которых [flag] == true, обходом в ширину
         * по тем же связям.
         *
         * Почему именно так, а не прямоугольниками: кость — это то, что СОЕДИНЕНО.
         * Два отдельных костных отростка обязаны быть двумя жёсткими телами, иначе
         * проекция свяжет их в одно и организм не сможет сгибаться в этом месте.
         * Ровно та же причина у мышц: несоединённые мышечные клетки — разные мышцы.
         */
        private fun components(adjacency: Array<out Set<Int>>, flag: BooleanArray): Array<IntArray> {
            val seen = BooleanArray(flag.size)
            val result = ArrayList<IntArray>()
            val queue = ArrayDeque<Int>()

            for (start in flag.indices) {
                if (!flag[start] || seen[start]) continue
                val group = ArrayList<Int>()
                seen[start] = true
                queue.addLast(start)
                while (queue.isNotEmpty()) {
                    val v = queue.removeFirst()
                    group.add(v)
                    for (u in adjacency[v]) {
                        if (!flag[u] || seen[u]) continue
                        seen[u] = true
                        queue.addLast(u)
                    }
                }
                result.add(group.toIntArray())
            }
            return result.toTypedArray()
        }
    }
}
