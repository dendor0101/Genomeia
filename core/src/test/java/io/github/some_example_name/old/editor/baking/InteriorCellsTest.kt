package io.github.some_example_name.old.editor.baking

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Внутренняя клетка — та, рядом с которой редактор не нашёл бы места для новой. */
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

    @Test
    fun centreOfDenseBodyIsInteriorAndRimIsNot() {
        val (xs, ys) = hexagon(step = 0.6f, rings = 3)
        val interior = InteriorCells.find(xs, ys)
        assertTrue(interior[0], "центр плотного тела")
        // Самая дальняя клетка — на краю.
        val far = xs.indices.maxBy { xs[it] * xs[it] + ys[it] * ys[it] }
        assertFalse(interior[far], "клетка на краю")
    }

    @Test
    fun sparseBodyHasRoomEverywhere() {
        // Шаг 1.6 — между соседями помещается новая клетка.
        val (xs, ys) = hexagon(step = 1.6f, rings = 2)
        assertTrue(InteriorCells.find(xs, ys).none { it })
    }

    @Test
    fun loneCellIsBoundary() {
        assertFalse(InteriorCells.find(floatArrayOf(0f), floatArrayOf(0f))[0])
    }
}
