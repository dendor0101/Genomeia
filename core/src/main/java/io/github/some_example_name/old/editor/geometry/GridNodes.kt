package io.github.some_example_name.old.editor.geometry

/**
 * Выбор узла сетки симметрии (квадратной, треугольной) для дочери при делении.
 *
 * ЗАНЯТ ЛИ УЗЕЛ
 * -------------
 * Раньше узел считался занятым, только если центр соседа лежит в нём с точностью 0.001.
 * Но клетки стоят в узлах не точно: первое деление игрок мог сделать ещё без сетки, а
 * сетку включить потом. Отчёт 17.11 (SM-S938B): дочь первого деления встала в
 * (64.3023, 63.4791), в 0.0023 от узла (64.3, 63.4804) сетки triangle(0.6). Её узел
 * для соседей выглядел свободным, а узлы, которые считались от неё, сдвигались на те же
 * 0.0023 и мимо клетки (64, 64). В итоге из шести повторов деления (правым касанием) три
 * поставили клетку поверх уже существующей.
 *
 * Теперь узел занят, если ближе [OWN_FRACTION] шага к нему стоит центр любой клетки.
 * Соседние узлы отстоят друг от друга на шаг, поэтому клетка в узле или рядом с ним
 * занимает только свой узел. Клетка между узлами, в полушаге от обоих, не занимает ни один.
 */
object GridNodes {

    /** Доля шага сетки: центр клетки ближе этого к узлу — узел её. */
    const val OWN_FRACTION = 0.5f

    /**
     * Свободный узел из [candidates] (плоский массив x0, y0, x1, y1, …), ближайший к цели
     * ([targetX], [targetY]), или null, если заняты все. Соседи — первые [count] из [nx], [ny].
     */
    fun closestFree(
        candidates: FloatArray,
        step: Float,
        nx: FloatArray,
        ny: FloatArray,
        count: Int,
        targetX: Float,
        targetY: Float
    ): Pair<Float, Float>? {
        val own = step * OWN_FRACTION
        val ownSq = own * own
        var best = -1
        var bestDistSq = Float.MAX_VALUE
        var c = 0
        while (c < candidates.size) {
            val x = candidates[c]
            val y = candidates[c + 1]
            if (!isOccupied(x, y, ownSq, nx, ny, count)) {
                val dx = x - targetX
                val dy = y - targetY
                val distSq = dx * dx + dy * dy
                if (distSq < bestDistSq) {
                    bestDistSq = distSq
                    best = c
                }
            }
            c += 2
        }
        return if (best == -1) null else Pair(candidates[best], candidates[best + 1])
    }

    private fun isOccupied(x: Float, y: Float, ownSq: Float, nx: FloatArray, ny: FloatArray, count: Int): Boolean {
        for (i in 0 until count) {
            val dx = nx[i] - x
            val dy = ny[i] - y
            if (dx * dx + dy * dy < ownSq) return true
        }
        return false
    }
}
