package io.github.some_example_name.old.editor.geometry

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CellPlacementTest {

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    /** Точка соблюдает все расстояния — с допуском на округление float. */
    private fun assertValid(
        p: Pair<Float, Float>, px: Float, py: Float, pr: Float, cr: Float,
        nx: FloatArray, ny: FloatArray, nr: FloatArray
    ) {
        val d = dist(p.first, p.second, px, py)
        assertTrue(d >= CellPlacement.CENTER_MIN * (pr + cr) - 1e-4, "до родителя $d")
        assertTrue(d <= CellPlacement.CENTER_MAX * (pr + cr) + 1e-4, "до родителя $d")
        for (k in nx.indices) {
            val dk = dist(p.first, p.second, nx[k], ny[k])
            assertTrue(dk >= CellPlacement.KEEP * (cr + nr[k]) - 1e-4, "до соседа $k: $dk")
        }
    }

    /** Шесть соседей радиуса 0.5 вплотную вокруг родителя на расстоянии [d]. */
    private fun ring(d: Float): Triple<FloatArray, FloatArray, FloatArray> {
        val nx = FloatArray(6) { (d * cos(it * PI / 3)).toFloat() }
        val ny = FloatArray(6) { (d * sin(it * PI / 3)).toFloat() }
        return Triple(nx, ny, FloatArray(6) { 0.5f })
    }

    /** Крупной дочери в кольце тесно, мелкая проходит в щель между соседями. */
    @Test
    fun smallChildFitsWhereBigDoesNot() {
        val (nx, ny, nr) = ring(0.9f)
        assertNull(CellPlacement.findPosition(0f, 0f, 0.5f, 0.5f, nx, ny, nr, 6, 10f, 0f))
        val small = assertNotNull(CellPlacement.findPosition(0f, 0f, 0.5f, 0.2f, nx, ny, nr, 6, 10f, 0f))
        assertValid(small, 0f, 0f, 0.5f, 0.2f, nx, ny, nr)
    }

    /** Мелкая дочь ставится вплотную к родителю, а не на расстоянии крупной. */
    @Test
    fun distanceScalesWithRadii() {
        val p = assertNotNull(
            CellPlacement.findPosition(0f, 0f, 0.2f, 0.2f, FloatArray(0), FloatArray(0), FloatArray(0), 0, 0f, 0f)
        )
        assertEquals(0.24f, dist(p.first, p.second, 0f, 0f), 1e-4f)
    }

    /** Зигота в центре: первая дочь — на ближней окружности по углу 0, как и раньше. */
    @Test
    fun firstDivisionOfZygote() {
        val p = assertNotNull(
            CellPlacement.findPosition(64f, 64f, 0.5f, 0.5f, FloatArray(0), FloatArray(0), FloatArray(0), 0, 64f, 64f)
        )
        assertEquals(64.6f, p.first, 1e-4f)
        assertEquals(64f, p.second, 1e-4f)
    }

    /**
     * Со всеми радиусами 0.5 — то же, что прежний перебор 101 × 360 с числами 0.6/0.9/0.6:
     * место есть там же, где было, и не дальше от цели (новый поиск точный, а не до градуса).
     */
    @Test
    fun matchesOldSearchForDefaultRadius() {
        val random = Random(7)
        repeat(200) {
            val n = random.nextInt(0, 9)
            val nx = FloatArray(n) { random.nextFloat() * 3f - 1.5f }
            val ny = FloatArray(n) { random.nextFloat() * 3f - 1.5f }
            val nr = FloatArray(n) { 0.5f }
            val tx = random.nextFloat() * 6f - 3f
            val ty = random.nextFloat() * 6f - 3f
            val old = oldSearch(0f, 0f, nx, ny, tx, ty)
            val new = CellPlacement.findPosition(0f, 0f, 0.5f, 0.5f, nx, ny, nr, n, tx, ty)
            if (old == null) return@repeat
            assertNotNull(new, "прежний поиск место нашёл")
            assertValid(new, 0f, 0f, 0.5f, 0.5f, nx, ny, nr)
            assertTrue(
                dist(new.first, new.second, tx, ty) <= dist(old.first, old.second, tx, ty) + 0.01f,
                "новое место не дальше от цели"
            )
        }
    }

    /** Любые радиусы: найденное место соблюдает все расстояния. */
    @Test
    fun resultIsValidForAnyRadii() {
        val random = Random(11)
        repeat(300) {
            val n = random.nextInt(0, 12)
            val pr = 0.2f + random.nextFloat() * 0.3f
            val cr = CellPlacement.childRadius(random.nextInt(CellPlacement.CHILD_RADIUS_COUNT))
            val nx = FloatArray(n) { random.nextFloat() * 2.4f - 1.2f }
            val ny = FloatArray(n) { random.nextFloat() * 2.4f - 1.2f }
            val nr = FloatArray(n) { 0.2f + random.nextFloat() * 0.3f }
            val p = CellPlacement.findPosition(0f, 0f, pr, cr, nx, ny, nr, n, 5f, 3f) ?: return@repeat
            assertValid(p, 0f, 0f, pr, cr, nx, ny, nr)
        }
    }

    @Test
    fun childRadiusSteps() {
        assertEquals(31, CellPlacement.CHILD_RADIUS_COUNT)
        assertEquals(0.2f, CellPlacement.childRadius(0))
        assertEquals(0.5f, CellPlacement.childRadius(30))
        assertEquals(0.37f, CellPlacement.childRadius(17))
        assertEquals(17, CellPlacement.childRadiusStep(0.37f))
    }

    /** Прежний поиск (editorUtils.findNewOptimalCellPosition до радиусов), для сверки. */
    private fun oldSearch(x: Float, y: Float, xs: FloatArray, ys: FloatArray, tx: Float, ty: Float): Pair<Float, Float>? {
        fun valid(px: Float, py: Float) = xs.indices.all { dist(px, py, xs[it], ys[it]) >= 0.6f }
        val dx = tx - x
        val dy = ty - y
        val d = sqrt(dx * dx + dy * dy)
        if (d > 0f) {
            val a = kotlin.math.atan2(dy, dx)
            val r = d.coerceIn(0.6f, 0.9f)
            val px = x + r * cos(a)
            val py = y + r * sin(a)
            if (valid(px, py)) return px to py
        }
        var best: Pair<Float, Float>? = null
        var bestD = Float.MAX_VALUE
        for (i in 0 until 101) {
            val r = 0.6f + i * (0.3f / 100)
            for (j in 0 until 360) {
                val a = j * (2 * PI.toFloat() / 360)
                val px = x + r * cos(a)
                val py = y + r * sin(a)
                if (!valid(px, py)) continue
                val dt = dist(px, py, tx, ty)
                if (dt < bestD) { bestD = dt; best = px to py }
            }
        }
        return best
    }
}
