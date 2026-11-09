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
        return BodyDefinition(
            x = cat(x, other.x), y = cat(y, other.y), radius = cat(radius, other.radius),
            isBone = cat(isBone, other.isBone), isMuscle = cat(isMuscle, other.isMuscle),
            linkA = catShift(linkA, other.linkA), linkB = catShift(linkB, other.linkB),
            triA = catShift(triA, other.triA), triB = catShift(triB, other.triB), triC = catShift(triC, other.triC),
            triRestArea2 = cat(triRestArea2, other.triRestArea2),
            boneClusters = clusters(boneClusters, other.boneClusters),
            muscleClusters = clusters(muscleClusters, other.muscleClusters),
            scale = scale,
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
