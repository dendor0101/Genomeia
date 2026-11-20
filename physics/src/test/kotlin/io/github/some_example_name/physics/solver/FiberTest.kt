package io.github.some_example_name.physics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Волокно мышцы клетки (PhysicsWorld.setCellFiber, BodyState.cellFiberX): сжатие только вдоль него. */
class FiberTest {
    /** Масштаб мира игры, как в SoftnessTest: связь 0.0288, радиус 0.024 единицы физики. */
    private val link = 0.0288
    private val r = 0.024
    private fun world() = PhysicsWorld(PhysicsConfig(), workers = null,
        scale = WorldScale(meanLinkLength = link.toFloat(), meanCellArea = r * r)).apply { state.quiet = true }

    /** Розетка: клетка 0 в центре, 1..6 по кругу через 60° начиная с оси x; 6 треугольников. */
    private fun rosette(): BodyDefinition {
        val x = FloatArray(7); val y = FloatArray(7)
        x[0] = 1f; y[0] = 1f
        for (k in 1..6) {
            val a = (k - 1) * Math.PI / 3
            x[k] = (1.0 + link * cos(a)).toFloat(); y[k] = (1.0 + link * sin(a)).toFloat()
        }
        val la = IntArray(12); val lb = IntArray(12)
        for (k in 1..6) { la[k - 1] = 0; lb[k - 1] = k; la[5 + k] = k; lb[5 + k] = k % 6 + 1 }
        val ta = IntArray(6); val tb = IntArray(6); val tc = IntArray(6); val area = FloatArray(6)
        for (k in 1..6) {
            val b = k; val c = k % 6 + 1
            ta[k - 1] = 0; tb[k - 1] = b; tc[k - 1] = c
            area[k - 1] = (x[b] - x[0]) * (y[c] - y[0]) - (y[b] - y[0]) * (x[c] - x[0])
        }
        return BodyDefinition(x, y, FloatArray(7) { r.toFloat() }, BooleanArray(7), BooleanArray(7),
            la, lb, ta, tb, tc, area, emptyArray(), emptyArray(), 1f)
    }

    /** Сжатая розетка: доли длины покоя вдоль оси x (клетки 1–4) и поперёк (середины 2–3 и 5–6). */
    private fun squeezed(fiberX: Float, fiberY: Float, scale: Float = 0.5f): Pair<Double, Double> {
        val w = world()
        val first = w.bodyFirst(w.addBody(rosette()))
        for (i in 0 until 7) { w.setCellFiber(first + i, fiberX, fiberY); w.setCellScale(first + i, scale) }
        repeat(600) { w.step() }
        assertEquals("кольца совпадают с полным пересчётом", 0, w.cellScaleMismatches())
        fun p(i: Int) = doubleArrayOf(w.x[first + i], w.y[first + i])
        val along = hypot(p(1)[0] - p(4)[0], p(1)[1] - p(4)[1]) / (2 * link)
        val topX = (p(2)[0] + p(3)[0]) / 2; val topY = (p(2)[1] + p(3)[1]) / 2
        val botX = (p(5)[0] + p(6)[0]) / 2; val botY = (p(5)[1] + p(6)[1]) / 2
        val across = hypot(topX - botX, topY - botY) / (2 * link * sin(Math.PI / 3))
        return along to across
    }

    /**
     * До самого множителя 0.5 розетку не дожимают касания несвязанных клеток: противоположные
     * клетки кольца сходятся ближе двух радиусов (замер 20.11: без волокна 0.61 в обе стороны).
     * Поэтому мерится то, ради чего волокно: вдоль сжатие как без него, поперёк размер держится.
     */
    @Test fun fiberContractsOnlyAlongItself() {
        val (along, across) = squeezed(1f, 0f)
        println("волокно x, s=0.5: вдоль %.3f, поперёк %.3f".format(along, across))
        assertTrue("вдоль волокна сжимается: $along", along < 0.62)
        assertTrue("поперёк размер держится: $across", across > 0.95)

        // Волокно по y — то же, но повёрнутое.
        val (alongY, acrossY) = squeezed(0f, 1f)
        println("волокно y, s=0.5: ось x %.3f, ось y %.3f".format(alongY, acrossY))
        assertTrue("поперёк волокна держится: $alongY", alongY > 0.92)
        assertTrue("вдоль волокна сжимается: $acrossY", acrossY < 0.66)

        // Растяжение (s = 1.5) — тоже только вдоль.
        val (stretched, side) = squeezed(1f, 0f, 1.5f)
        println("волокно x, s=1.5: вдоль %.3f, поперёк %.3f".format(stretched, side))
        assertTrue("вдоль волокна растягивается: $stretched", stretched > 1.35)
        assertEquals("поперёк размер держится", 1.0, side, 0.06)
    }

    @Test fun withoutFiberMuscleContractsEverywhere() {
        val (along, across) = squeezed(0f, 0f)
        println("без волокна, s=0.5: вдоль %.3f, поперёк %.3f".format(along, across))
        assertTrue("вдоль сжимается: $along", along < 0.66)
        assertTrue("поперёк сжимается так же: $along / $across", kotlin.math.abs(along - across) < 0.03)
    }

    /** Множители связей — |F·e| одноосного растяжения, треугольников — sqrt(s); при s = 1 — ровно 1. */
    @Test fun linkAndTriangleMultipliersFollowUniaxialStretch() {
        val w = world()
        val first = w.bodyFirst(w.addBody(rosette()))
        for (i in 0 until 7) w.setCellFiber(first + i, 1f, 0f)
        w.setCellScale(first, 1f)
        w.step()
        val st = w.state
        for (c in 0 until st.conCount) assertEquals("при s = 1 волокно ничего не меняет", 1f, st.conCellScale[c], 0f)
        for (i in 0 until 7) w.setCellScale(first + i, 0.5f)
        repeat(300) { w.step() }
        for (c in 0 until st.conCount) {
            val a = st.conA[c] - first; val b = st.conB[c] - first
            // Связь вдоль x (спицы 0–1, 0–4) — 0.5; под 60° — sqrt(1 − 0.75·0.25).
            val dx = st.body.x[b + first] - st.body.x[a + first]; val dy = st.body.y[b + first] - st.body.y[a + first]
            val cos2 = dx * dx / (dx * dx + dy * dy)
            assertEquals("связь $a–$b", kotlin.math.sqrt(1f - 0.75f * cos2), st.conCellScale[c], 1e-6f)
        }
        for (t in 0 until st.triCount) assertEquals(kotlin.math.sqrt(0.5f), st.triCellScale[t], 1e-6f)
    }

    /** Мир из снимка с волокнами идёт побитово так же, как мир, который его записал. */
    @Test fun snapshotKeepsFibers() {
        val a = world()
        val first = a.bodyFirst(a.addBody(rosette()))
        for (i in 0 until 7) { a.setCellFiber(first + i, 0f, 1f); a.setCellScale(first + i, 0.6f) }
        repeat(20) { a.step() }
        val bytes = java.io.ByteArrayOutputStream()
        a.write(java.io.DataOutputStream(bytes))
        val b = world()
        b.read(java.io.DataInputStream(java.io.ByteArrayInputStream(bytes.toByteArray())))
        assertArrayEquals(a.state.cellFiberY, b.state.cellFiberY, 0f)
        assertTrue(b.state.fibersOn)
        repeat(100) { a.step(); b.step() }
        for (i in 0 until a.particleCount) {
            assertEquals("x[$i]", a.x[i].toRawBits(), b.x[i].toRawBits())
            assertEquals("y[$i]", a.y[i].toRawBits(), b.y[i].toRawBits())
        }
    }
}
