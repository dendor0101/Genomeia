package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * ПЛОЩАДЬ ТРЕУГОЛЬНИКА: ограничение C = S₂ − S₂покоя, где S₂ — удвоенная площадь со знаком.
 *
 *  - Поправка каждой вершины идёт вдоль градиента S₂ по её координатам; градиент сверяется с
 *    конечными разностями самой площади, а не с формулой из решателя.
 *  - Множитель у всех трёх вершин один, вершина сдвигается пропорционально своей обратной массе.
 *  - Треугольник в позе покоя не трогается; вывернутый возвращается на положительную сторону.
 *  - Длина поправки за проход ограничена потолком AREA_MAX_STEP.
 */
class AreaAnalyticTest {
    private fun bothKernels(body: (Kernel) -> Unit) { for (kernel in AREA_KERNELS) try { body(kernel) } catch (e: AssertionError) { throw AssertionError("ядро $kernel: ${e.message}", e) } }

    /** Один треугольник: три клетки, три связи. Возвращает мир и номера его вершин. */
    private fun triangle(): Pair<PhysicsWorld, IntArray> {
        val w = gameWorld().vacuum()
        val l = T_LINK.toFloat()
        val h = (T_LINK * sqrt(3.0) / 2).toFloat()
        val x = floatArrayOf(1f, 1f + l, 1f + l / 2); val y = floatArrayOf(1f, 1f, 1f + h)
        val area2 = (x[1] - x[0]) * (y[2] - y[0]) - (y[1] - y[0]) * (x[2] - x[0])
        val def = BodyDefinition(x, y, FloatArray(3) { T_RADIUS.toFloat() }, BooleanArray(3), BooleanArray(3),
            intArrayOf(0, 1, 0), intArrayOf(1, 2, 2), intArrayOf(0), intArrayOf(1), intArrayOf(2), floatArrayOf(area2),
            emptyArray(), emptyArray(), 1f)
        val first = w.bodyFirst(w.addBody(def))
        return w to intArrayOf(first, first + 1, first + 2)
    }

    private fun area2(x: DoubleArray, y: DoubleArray): Double = (x[1] - x[0]) * (y[2] - y[0]) - (y[1] - y[0]) * (x[2] - x[0])

    private fun read(w: PhysicsWorld, ids: IntArray): Pair<DoubleArray, DoubleArray> =
        DoubleArray(3) { w.state.px[ids[it]] } to DoubleArray(3) { w.state.py[ids[it]] }

    /** Градиент удвоенной площади по координатам вершины [v] — центральной разностью. */
    private fun gradient(x: DoubleArray, y: DoubleArray, v: Int): DoubleArray {
        val e = 1e-7
        val xp = x.copyOf(); val xm = x.copyOf(); xp[v] += e; xm[v] -= e
        val yp = y.copyOf(); val ym = y.copyOf(); yp[v] += e; ym[v] -= e
        return doubleArrayOf((area2(xp, y) - area2(xm, y)) / (2 * e), (area2(x, yp) - area2(x, ym)) / (2 * e))
    }

    @Test fun `треугольник в позе покоя площадь не трогает`() = bothKernels { kernel ->
        val (w, ids) = triangle()
        val (x0, y0) = read(w, ids)
        kernel.run(w.state, DT / SUBSTEPS)
        val (x1, y1) = read(w, ids)
        // Площадь покоя хранится во float, координаты — в double: остаток порядка 1e-12 единицы мира.
        for (k in 0 until 3) { assertEquals(x0[k], x1[k], 1e-9); assertEquals(y0[k], y1[k], 1e-9) }
    }

    @Test fun `поправка каждой вершины идёт вдоль градиента площади - сверка с конечными разностями`() = bothKernels { kernel ->
        val (w, ids) = triangle()
        val st = w.state
        // Раздули треугольник: вершину 2 увели вверх и вбок.
        st.px[ids[2]] += 0.02 * T_LINK; st.py[ids[2]] += 0.04 * T_LINK
        val (x0, y0) = read(w, ids)
        kernel.run(st, DT / SUBSTEPS)
        val (x1, y1) = read(w, ids)
        var lambda = Double.NaN
        for (v in 0 until 3) {
            val g = gradient(x0, y0, v)
            val dx = x1[v] - x0[v]; val dy = y1[v] - y0[v]
            // Вдоль градиента: векторное произведение поправки и градиента — ноль.
            assertEquals("вершина $v: поправка не вдоль градиента", 0.0, (dx * g[1] - dy * g[0]) / (hypot(dx, dy) * hypot(g[0], g[1])), 1e-6)
            // Один множитель на все вершины (обратные массы равны): Δp = λ·w·g.
            val l = (dx * g[0] + dy * g[1]) / (g[0] * g[0] + g[1] * g[1]) / st.invMass[ids[v]]
            if (lambda.isNaN()) lambda = l else assertEquals("вершина $v: множитель другой", lambda, l, 1e-6 * abs(lambda))
        }
        assertTrue("раздутый треугольник сжимается: множитель отрицательный ($lambda)", lambda < 0.0)
    }

    @Test fun `за проход убирается доля отклонения площади по формуле XPBD`() = bothKernels { kernel ->
        val (w, ids) = triangle()
        val st = w.state
        val h = DT / SUBSTEPS
        st.px[ids[2]] += 0.01 * T_LINK; st.py[ids[2]] += 0.01 * T_LINK
        val (x0, y0) = read(w, ids)
        val rest = st.triRestArea2[0].toDouble()
        val c0 = area2(x0, y0) - rest
        var denom = 0.0
        for (v in 0 until 3) { val g = gradient(x0, y0, v); denom += st.invMass[ids[v]] * (g[0] * g[0] + g[1] * g[1]) }
        val alpha = AREA_COMPLIANCE / (h * h)
        kernel.run(st, h)
        val (x1, y1) = read(w, ids)
        val c1 = area2(x1, y1) - rest
        // Линейная оценка: C₁ ≈ C₀ · α̃ / (Σ w|g|² + α̃). Площадь квадратична по координатам — допуск на вторую степень.
        assertEquals(c0 * alpha / (denom + alpha), c1, 0.02 * abs(c0))
        assertTrue("отклонение площади уменьшилось", abs(c1) < abs(c0))
    }

    @Test fun `закреплённая вершина не двигается, остальные берут поправку на себя`() = bothKernels { kernel ->
        val (w, ids) = triangle()
        val st = w.state
        st.invMass[ids[0]] = 0.0
        st.py[ids[2]] += 0.05 * T_LINK
        val (x0, y0) = read(w, ids)
        kernel.run(st, DT / SUBSTEPS)
        assertEquals(x0[0], st.px[ids[0]], 0.0); assertEquals(y0[0], st.py[ids[0]], 0.0)
        assertTrue(st.py[ids[2]] < y0[2])
    }

    @Test fun `вывернутый треугольник возвращается на положительную сторону`() = bothKernels { kernel ->
        val (w, ids) = triangle()
        val st = w.state
        // Вершину 2 перенесли через основание: площадь стала отрицательной.
        st.py[ids[2]] -= 1.2 * T_LINK * sqrt(3.0) / 2
        val (x0, y0) = read(w, ids)
        assertTrue(area2(x0, y0) < 0.0)
        repeat(40) { kernel.run(st, DT / SUBSTEPS) }
        val (x1, y1) = read(w, ids)
        assertTrue("после сорока проходов площадь снова положительна: ${area2(x1, y1)}", area2(x1, y1) > 0.0)
    }

    @Test fun `длина поправки площади за проход не больше потолка`() = bothKernels { kernel ->
        val (w, ids) = triangle()
        val st = w.state
        st.px[ids[2]] += 6.0 * T_LINK; st.py[ids[2]] += 9.0 * T_LINK
        val (x0, y0) = read(w, ids)
        kernel.run(st, DT / SUBSTEPS)
        val (x1, y1) = read(w, ids)
        // Потолок — на длину всей поправки в метрике обратных масс: √(Σ |Δp|² / w).
        var sum = 0.0
        for (v in 0 until 3) sum += ((x1[v] - x0[v]) * (x1[v] - x0[v]) + (y1[v] - y0[v]) * (y1[v] - y0[v])) / st.invMass[ids[v]]
        assertEquals(AREA_MAX_STEP * T_LINK, sqrt(sum), 1e-6 * T_LINK)
    }

    @Test fun `погибший треугольник площадь не держит`() = bothKernels { kernel ->
        val (w, ids) = triangle()
        val st = w.state
        w.kill(ids[0])
        st.py[ids[2]] += 0.05 * T_LINK
        val (x0, y0) = read(w, ids)
        kernel.run(st, DT / SUBSTEPS)
        val (x1, y1) = read(w, ids)
        for (k in 0 until 3) { assertEquals(x0[k], x1[k], 0.0); assertEquals(y0[k], y1[k], 0.0) }
    }
}
