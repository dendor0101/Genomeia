package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Мягкость ткани по клетке (PhysicsWorld.setSoftness, BodyState.cellSoftness). */
class SoftnessTest {
    /** Масштаб мира игры: связь 0.6 и радиус 0.5 клетки игры при 0.048 единицы физики на клетку. */
    private val link = 0.0288
    private val r = 0.024
    private fun world() = PhysicsWorld(PhysicsConfig(), workers = null,
        scale = WorldScale(meanLinkLength = link.toFloat(), meanCellArea = r * r)).apply { state.quiet = true }

    /** Цепочка из [count] клеток вдоль x без треугольников. */
    private fun chain(count: Int) = BodyDefinition(
        FloatArray(count) { (1.0 + it * link).toFloat() }, FloatArray(count) { 1f },
        FloatArray(count) { r.toFloat() }, BooleanArray(count), BooleanArray(count),
        IntArray(count - 1) { it }, IntArray(count - 1) { it + 1 },
        IntArray(0), IntArray(0), IntArray(0), FloatArray(0), emptyArray(), emptyArray(), 1f,
    )

    /** Квадрат 2×2 клетки из двух треугольников. */
    private fun square() = BodyDefinition(
        floatArrayOf(1f, (1 + link).toFloat(), 1f, (1 + link).toFloat()),
        floatArrayOf(1f, 1f, (1 + link).toFloat(), (1 + link).toFloat()),
        FloatArray(4) { r.toFloat() }, BooleanArray(4), BooleanArray(4),
        intArrayOf(0, 1, 0, 1, 2), intArrayOf(1, 2, 2, 3, 3),
        intArrayOf(0, 1), intArrayOf(1, 3), intArrayOf(2, 2),
        floatArrayOf((link * link).toFloat(), (link * link).toFloat()), emptyArray(), emptyArray(), 1f,
    )

    private fun pin(w: PhysicsWorld, i: Int) { w.state.restInvMass[i] = 0.0; w.state.invMass[i] = 0.0 }

    /**
     * Цепочка A–B–C–D растянута между закреплёнными концами; A и B — жир (2.5). Натяжение в
     * равновесии одно, поэтому растяжения связей идут как их действующие податливости:
     * AB (жир–жир) мягче всех, BC (жир–обычная, среднее 1.75) посередине, CD — обычная.
     */
    @Test fun softLinksStretchMoreUnderTheSameTension() {
        val w = world()
        val first = w.bodyFirst(w.addBody(chain(4)))
        val a = first; val b = first + 1; val c = first + 2; val d = first + 3
        w.setSoftness(a, 2.5f); w.setSoftness(b, 2.5f)
        pin(w, a); pin(w, d)
        val stretch = 0.03 * 3 * link
        w.state.px[d] += stretch; w.state.prevX[d] += stretch
        repeat(400) { w.step() }
        fun ext(i: Int, j: Int) = (w.x[j] - w.x[i]) - link
        val ab = ext(a, b); val bc = ext(b, c); val cd = ext(c, d)
        assertEquals("растяжение сохранилось целиком", stretch, ab + bc + cd, 1e-7)
        println("мягкость: AB/CD = ${"%.3f".format(ab / cd)}, BC/CD = ${"%.3f".format(bc / cd)}")
        assertTrue("жир–жир мягче обычной: AB/CD = ${ab / cd}", ab / cd > 2.3)
        assertTrue("жир–обычная между ними: ${ab / cd} > ${bc / cd} > 1", ab > bc && bc > cd)
    }

    /** Значения связей и треугольников — средние концов; кольцо клетки совпадает с полным пересчётом. */
    @Test fun ringRefreshMatchesFullRecompute() {
        val w = world()
        val first = w.bodyFirst(w.addBody(square()))
        w.setSoftness(first, 2.5f)
        w.step()
        w.setSoftness(first + 3, 2.5f)
        w.step()
        assertEquals(0, w.cellScaleMismatches())
        val st = w.state
        for (k in 0 until st.conCount) {
            val expected = (st.cellSoftness[st.conA[k]] + st.cellSoftness[st.conB[k]]) * 0.5f
            assertEquals(expected, st.conSoftness[k], 0f)
        }
        // Обратная мутация: всё снова обычное.
        w.setSoftness(first, 1f); w.setSoftness(first + 3, 1f)
        w.step()
        assertEquals(0, w.cellScaleMismatches())
        for (k in 0 until st.conCount) assertEquals(1f, st.conSoftness[k], 0f)
        for (t in 0 until st.triCount) assertEquals(1f, st.triSoftness[t], 0f)
    }
}
