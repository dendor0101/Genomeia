package io.github.some_example_name.old.editor.baking

import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Внутренняя клетка — та, что не выходит на внешний контур тела. */
class InteriorCellsTest {

    /** Треугольная решётка с шагом [step], [rings] колец вокруг центра; центр — клетка 0. */
    private fun hexagon(step: Float, rings: Int): Pair<FloatArray, FloatArray> {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val h = step * sqrt(3f) / 2f
        for (row in -rings..rings) for (col in -rings..rings) {
            val x = col * step + row * step / 2f
            val y = row * h
            // Шестиугольник: расстояние в шагах решётки не больше rings.
            val q = col; val r = row; val s = -q - r
            if (maxOf(kotlin.math.abs(q), kotlin.math.abs(r), kotlin.math.abs(s)) > rings) continue
            if (q == 0 && r == 0) { xs.add(0, x); ys.add(0, y) } else { xs.add(x); ys.add(y) }
        }
        return xs.toFloatArray() to ys.toFloatArray()
    }

    private fun radii(n: Int, r: Float) = FloatArray(n) { r }

    @Test
    fun centreOfDenseBodyIsInteriorAndRimIsNot() {
        val (xs, ys) = hexagon(step = 0.6f, rings = 3)
        val interior = InteriorCells.find(xs, ys, radii(xs.size, 0.5f))
        assertTrue(interior[0], "центр плотного тела")
        // Внешнее кольцо (3-е) — на краю, первые два — внутри.
        for (i in xs.indices) {
            val ring = sqrt(xs[i] * xs[i] + ys[i] * ys[i]) / 0.6f
            if (ring > 2.6f) assertFalse(interior[i], "клетка $i на краю")
            if (ring < 2.1f) assertTrue(interior[i], "клетка $i внутри")
        }
    }

    @Test
    fun sparseBodyIsAllBoundary() {
        // Шаг 1.6 при радиусе 0.5 — диски не касаются, каждая клетка открыта.
        val (xs, ys) = hexagon(step = 1.6f, rings = 2)
        assertTrue(InteriorCells.find(xs, ys, radii(xs.size, 0.5f)).none { it })
    }

    @Test
    fun loneCellIsBoundary() {
        assertFalse(InteriorCells.find(floatArrayOf(0f), floatArrayOf(0f), floatArrayOf(0.5f))[0])
    }

    /**
     * Шов: между соседями внешнего ряда щель (диски не перекрываются), и через неё снаружи
     * видна клетка второго ряда. Прежний признак («нет места для новой клетки») щель уже
     * клетки редактора не замечал, и клетка за ней считалась внутренней.
     */
    @Test
    fun cellBehindGapIsBoundary() {
        // Плотная решётка 0.6 из мелких клеток 0.31: соседи перекрываются (0.62 > 0.6)…
        val (xs, ys) = hexagon(step = 0.6f, rings = 3)
        val tight = InteriorCells.find(xs, ys, radii(xs.size, 0.31f))
        assertTrue(tight[0], "при перекрытии центр закрыт")
        // …а при 0.29 между всеми соседями щель, и центр виден снаружи.
        val loose = InteriorCells.find(xs, ys, radii(xs.size, 0.29f))
        assertFalse(loose[0], "сквозь щели центр открыт")
    }

    /** Полость внутри замкнутого кольца снаружи недоступна: клетки вокруг неё внутренние. */
    @Test
    fun cavityDoesNotMakeBoundary() {
        // Шестиугольник 3 колец без центральной клетки: дыра радиусом 0.6 внутри.
        val (xs0, ys0) = hexagon(step = 0.6f, rings = 3)
        val xs = xs0.copyOfRange(1, xs0.size)
        val ys = ys0.copyOfRange(1, ys0.size)
        val interior = InteriorCells.find(xs, ys, radii(xs.size, 0.5f))
        for (i in xs.indices) {
            val ring = sqrt(xs[i] * xs[i] + ys[i] * ys[i]) / 0.6f
            if (ring < 1.1f) assertTrue(interior[i], "клетка $i у полости")
        }
    }

    /** Мелкая клетка, зажатая крупными внутри тела, внутренняя; та же на краю — граничная. */
    @Test
    fun mixedRadii() {
        val (xs, ys) = hexagon(step = 0.6f, rings = 3)
        val rs = radii(xs.size, 0.5f)
        rs[0] = 0.2f
        val far = xs.indices.maxBy { xs[it] * xs[it] + ys[it] * ys[it] }
        rs[far] = 0.2f
        val interior = InteriorCells.find(xs, ys, rs)
        assertTrue(interior[0], "мелкая в центре")
        assertFalse(interior[far], "мелкая на краю")
    }

    /**
     * Главное свойство: снаружи не дотронуться ни до одной внутренней клетки. Проверяется
     * растром — заливкой свободного места снаружи: ни одна залитая точка не должна лежать
     * в диске внутренней клетки ближе шага растра к её краю. На случайных телах с разными
     * радиусами.
     */
    @Test
    fun noInteriorCellIsReachableFromOutside() {
        val random = Random(20261020)
        repeat(30) { attempt ->
            val n = 40 + random.nextInt(80)
            val xs = FloatArray(n)
            val ys = FloatArray(n)
            val rs = FloatArray(n) { 0.2f + random.nextFloat() * 0.3f }
            // Тело растёт делением: каждая новая клетка — у случайной старой, на
            // расстоянии 0.6..0.9 суммы радиусов, как ставит редактор.
            for (i in 1 until n) {
                val p = random.nextInt(i)
                val a = random.nextDouble() * 2 * Math.PI
                val d = (rs[p] + rs[i]) * (0.6f + random.nextFloat() * 0.3f)
                xs[i] = xs[p] + (d * Math.cos(a)).toFloat()
                ys[i] = ys[p] + (d * Math.sin(a)).toFloat()
            }
            val interior = InteriorCells.find(xs, ys, rs)
            val exposed = reachableFromOutside(xs, ys, rs, interior, step = 0.01f)
            for (i in 0 until n) {
                assertFalse(exposed[i], "тело $attempt: внутренняя клетка $i открыта снаружи")
            }
            // И проверка не пустая: внутренние клетки вообще есть.
            assertTrue(interior.any { it }, "тело $attempt без внутренних клеток")
        }
    }

    /**
     * Растр: какие внутренние клетки видны снаружи — на краю тела, видимом из свободного
     * места, связанного с краем картинки, есть точка, не покрытая ни одной граничной.
     */
    private fun reachableFromOutside(
        xs: FloatArray, ys: FloatArray, rs: FloatArray, interior: BooleanArray, step: Float
    ): BooleanArray {
        val minX = xs.indices.minOf { xs[it] - rs[it] } - 4 * step
        val minY = xs.indices.minOf { ys[it] - rs[it] } - 4 * step
        val maxX = xs.indices.maxOf { xs[it] + rs[it] } + 4 * step
        val maxY = xs.indices.maxOf { ys[it] + rs[it] } + 4 * step
        val w = ((maxX - minX) / step).toInt() + 1
        val h = ((maxY - minY) / step).toInt() + 1
        val blocked = BooleanArray(w * h)
        for (i in xs.indices) {
            val r2 = rs[i] * rs[i]
            val x0 = ((xs[i] - rs[i] - minX) / step).toInt().coerceAtLeast(0)
            val x1 = ((xs[i] + rs[i] - minX) / step).toInt().coerceAtMost(w - 1)
            val y0 = ((ys[i] - rs[i] - minY) / step).toInt().coerceAtLeast(0)
            val y1 = ((ys[i] + rs[i] - minY) / step).toInt().coerceAtMost(h - 1)
            for (py in y0..y1) for (px in x0..x1) {
                val dx = minX + px * step - xs[i]
                val dy = minY + py * step - ys[i]
                if (dx * dx + dy * dy < r2) blocked[py * w + px] = true
            }
        }
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        queue[tail++] = 0
        seen[0] = true
        while (head < tail) {
            val k = queue[head++]
            val px = k % w
            val py = k / w
            for ((nx, ny) in arrayOf(px + 1 to py, px - 1 to py, px to py + 1, px to py - 1)) {
                if (nx !in 0 until w || ny !in 0 until h) continue
                val nk = ny * w + nx
                if (seen[nk] || blocked[nk]) continue
                seen[nk] = true
                queue[tail++] = nk
            }
        }
        // Край тела, видимый снаружи, — занятые точки рядом с залитыми. Каждая должна лежать
        // в диске хотя бы одной граничной клетки, иначе внутренняя открыта. Без допусков:
        // точка внутри диска, покрытого соседями целиком, лежит и в диске соседа.
        val exposed = BooleanArray(xs.size)
        for (k in 0 until w * h) {
            if (!blocked[k]) continue
            val px = k % w
            val py = k / w
            val nearOutside = (px > 0 && seen[k - 1]) || (px < w - 1 && seen[k + 1]) ||
                (py > 0 && seen[k - w]) || (py < h - 1 && seen[k + w])
            if (!nearOutside) continue
            val x = minX + px * step
            val y = minY + py * step
            var coveredByBoundary = false
            val owners = ArrayList<Int>()
            for (i in xs.indices) {
                val dx = x - xs[i]
                val dy = y - ys[i]
                if (dx * dx + dy * dy >= rs[i] * rs[i]) continue
                owners.add(i)
                if (!interior[i]) coveredByBoundary = true
            }
            if (!coveredByBoundary) for (i in owners) exposed[i] = true
        }
        return exposed
    }

    @Test
    fun duplicateCellsKeepOneOpen() {
        val interior = InteriorCells.find(floatArrayOf(0f, 0f), floatArrayOf(0f, 0f), floatArrayOf(0.5f, 0.5f))
        assertEquals(listOf(false, true), interior.toList())
    }
}
