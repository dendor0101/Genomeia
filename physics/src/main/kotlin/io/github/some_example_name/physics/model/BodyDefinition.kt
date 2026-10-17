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

    fun describe(): String = String.format(
        Locale.ROOT,
        "cells=%d links=%d triangles=%d (%.2f per cell)  bone=%d in %d clusters  muscle=%d in %d clusters  meanLink=%.4f",
        count, linkCount, triCount, triCount.toFloat() / count.coerceAtLeast(1),
        isBone.count { it }, boneClusters.size,
        isMuscle.count { it }, muscleClusters.size,
        meanLinkLength
    )

}
