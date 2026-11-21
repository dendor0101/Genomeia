package io.github.some_example_name.physics

import java.util.Locale

/** Исходная топология. Массивы принадлежат описанию и после загрузки не изменяются;
 * рабочая топология и пластика живут в BodyState в собственных массивах. */
class BodyDefinition(
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

    /**
     * ВРЕМЕННЫЕ СВЯЗИ РОСТА (topology/TempLinks.kt): у связи l — пары s из linkRetireStart[l] until
     * linkRetireStart[l + 1]: клетка linkRetireCells[2s] и вторая клетка linkRetireCells[2s + 1]. Связь снимается,
     * когда рождена клетка пары (вторая −1 — клетка лежит на связи) или обе клетки пары (связь между ними пересекает
     * эту). Пустой промежуток — связь постоянная; пустой [linkRetireStart] — временных связей у описания нет.
     */
    val linkRetireStart: IntArray = IntArray(0),
    val linkRetireCells: IntArray = IntArray(0),
) {
    val count: Int get() = x.size
    val linkCount: Int get() = linkA.size
    val triCount: Int get() = triA.size

    /** Есть ли у описания временные связи роста. */
    val hasTempLinks: Boolean get() = linkRetireStart.isNotEmpty()

    /** Временная ли связь [l] (снимается рождением клетки, которая на неё ложится). */
    fun isTempLink(l: Int): Boolean = linkRetireStart.isNotEmpty() && linkRetireStart[l + 1] > linkRetireStart[l]

    /**
     * Описание для мира, где рождены клетки [born] (null — все): временная связь, которую рождённая клетка уже сняла
     * бы, в мир не идёт вовсе. У взрослого тела временных связей нет, и оно побитово то же, что без них. Описание без
     * временных связей, которые надо выбросить, возвращается как есть.
     */
    fun forBorn(born: BooleanArray?): BodyDefinition {
        if (!hasTempLinks) return this
        val keep = BooleanArray(linkCount) { l ->
            var blocked = false
            for (s in linkRetireStart[l] until linkRetireStart[l + 1]) {
                val c = linkRetireCells[2 * s]; val p = linkRetireCells[2 * s + 1]
                if (born == null || (born[c] && (p < 0 || born[p]))) { blocked = true; break }
            }
            !blocked
        }
        var kept = 0; var temp = 0
        for (l in 0 until linkCount) if (keep[l]) { kept++; if (isTempLink(l)) temp++ }
        if (kept == linkCount) return this
        val la = IntArray(kept); val lb = IntArray(kept)
        val start = if (temp > 0) IntArray(kept + 1) else IntArray(0)
        val cells = ArrayList<Int>()
        var m = 0
        for (l in 0 until linkCount) {
            if (!keep[l]) continue
            la[m] = linkA[l]; lb[m] = linkB[l]
            if (temp > 0) {
                for (s in linkRetireStart[l] until linkRetireStart[l + 1]) { cells.add(linkRetireCells[2 * s]); cells.add(linkRetireCells[2 * s + 1]) }
                start[m + 1] = cells.size / 2
            }
            m++
        }
        return BodyDefinition(x, y, radius, isBone, isMuscle, la, lb, triA, triB, triC, triRestArea2, boneClusters, muscleClusters, scale,
            start, cells.toIntArray())
    }

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

    /**
     * Этот набор тел и [other] после него: номера частиц other сдвинуты на [count], его
     * связи, треугольники и кластеры — следом за своими. Так мир дописывает тело
     * (BodyState.appendBody): поза покоя мира — склейка поз покоя тел.
     */
    fun concat(other: BodyDefinition): BodyDefinition {
        val off = count
        fun cat(a: FloatArray, b: FloatArray) = a.copyOf(a.size + b.size).also { System.arraycopy(b, 0, it, a.size, b.size) }
        fun cat(a: BooleanArray, b: BooleanArray) = a.copyOf(a.size + b.size).also { System.arraycopy(b, 0, it, a.size, b.size) }
        fun catShift(a: IntArray, b: IntArray) = a.copyOf(a.size + b.size).also { for (k in b.indices) it[a.size + k] = b[k] + off }
        fun clusters(a: Array<IntArray>, b: Array<IntArray>) = Array(a.size + b.size) { k ->
            if (k < a.size) a[k] else IntArray(b[k - a.size].size) { i -> b[k - a.size][i] + off }
        }
        // Временные связи роста: промежутки обоих описаний подряд, клетки второго — со сдвигом.
        var retireStart = IntArray(0); var retireCells = IntArray(0)
        if (hasTempLinks || other.hasTempLinks) {
            retireStart = IntArray(linkCount + other.linkCount + 1)
            retireCells = IntArray(linkRetireCells.size + other.linkRetireCells.size)
            var m = 0
            for (l in 0 until linkCount) {
                if (hasTempLinks) for (s in linkRetireStart[l] until linkRetireStart[l + 1]) {
                    retireCells[m++] = linkRetireCells[2 * s]; retireCells[m++] = linkRetireCells[2 * s + 1]
                }
                retireStart[l + 1] = m / 2
            }
            for (l in 0 until other.linkCount) {
                if (other.hasTempLinks) for (s in other.linkRetireStart[l] until other.linkRetireStart[l + 1]) {
                    val p = other.linkRetireCells[2 * s + 1]
                    retireCells[m++] = other.linkRetireCells[2 * s] + off; retireCells[m++] = if (p < 0) p else p + off
                }
                retireStart[linkCount + l + 1] = m / 2
            }
        }
        return BodyDefinition(
            x = cat(x, other.x), y = cat(y, other.y), radius = cat(radius, other.radius),
            isBone = cat(isBone, other.isBone), isMuscle = cat(isMuscle, other.isMuscle),
            linkA = catShift(linkA, other.linkA), linkB = catShift(linkB, other.linkB),
            triA = catShift(triA, other.triA), triB = catShift(triB, other.triB), triC = catShift(triC, other.triC),
            triRestArea2 = cat(triRestArea2, other.triRestArea2),
            boneClusters = clusters(boneClusters, other.boneClusters),
            muscleClusters = clusters(muscleClusters, other.muscleClusters),
            scale = scale,
            linkRetireStart = retireStart, linkRetireCells = retireCells,
        )
    }

    fun describe(): String = String.format(
        Locale.ROOT,
        "cells=%d links=%d triangles=%d (%.2f per cell)  bone=%d in %d clusters  muscle=%d in %d clusters  meanLink=%.4f",
        count, linkCount, triCount, triCount.toFloat() / count.coerceAtLeast(1),
        isBone.count { it }, boneClusters.size,
        isMuscle.count { it }, muscleClusters.size,
        meanLinkLength
    )

}
