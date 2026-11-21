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

        // Расслабление (s = 1.5) длин покоя не меняет, а делает ткань мягче на растяжение (ConstraintSolver.
        // relaxedLinkCorrection; до слияния 21.11 в этой физике s > 1 удлиняло ткань, и здесь ждали «вдоль > 1.35»):
        // без нагрузки розетка держит размер в обе стороны. Что мягче только вдоль волокна — в тесте множителей ниже.
        val (stretched, side) = squeezed(1f, 0f, 1.5f)
        println("волокно x, s=1.5: вдоль %.3f, поперёк %.3f".format(stretched, side))
        assertEquals("вдоль волокна длина покоя та же", 1.0, stretched, 0.06)
        assertEquals("поперёк размер держится", 1.0, side, 0.06)
    }

    @Test fun withoutFiberMuscleContractsEverywhere() {
        val (along, across) = squeezed(0f, 0f)
        println("без волокна, s=0.5: вдоль %.3f, поперёк %.3f".format(along, across))
        assertTrue("вдоль сжимается: $along", along < 0.66)
        assertTrue("поперёк сжимается так же: $along / $across", kotlin.math.abs(along - across) < 0.03)
    }

    /**
     * Цели одноосного закона (k = 0, мир по умолчанию): длина связи под сжатием — |F·e| (MuscleFiber.limitShrink, по
     * cos² связи к волокну conFiberC2), площадь треугольника — × s (fiberAreaFactor); при s = 1 — ровно 1; расслабление —
     * мягкость связи |F·e| (вдоль волокна s, поперёк 1; fiberRelaxedSoftness), а команда связи — целиком s: предел длины
     * у всей расслабленной мышцы (находка 34). До слияния 21.11 мотонейрон сворачивал и сжатие, и расслабление в
     * conCellScale/triCellScale; теперь их ведут ядра, а числа закона те же.
     */
    @Test fun linkAndTriangleTargetsFollowUniaxialStretch() {
        val w = world()
        val first = w.bodyFirst(w.addBody(rosette()))
        for (i in 0 until 7) w.setCellFiber(first + i, 1f, 0f)
        w.setCellScale(first, 1f)
        w.step()
        val st = w.state
        assertEquals("закон мира по умолчанию — одноосный", 0.0, st.fiberThicken, 0.0)
        for (c in 0 until st.conCount) assertEquals("при s = 1 волокно ничего не меняет", 1f, st.conCellScale[c], 0f)
        fun cos2(c: Int): Float {
            val a = st.conA[c]; val b = st.conB[c]
            val dx = st.body.x[b] - st.body.x[a]; val dy = st.body.y[b] - st.body.y[a]
            return dx * dx / (dx * dx + dy * dy)
        }
        for (i in 0 until 7) w.setCellScale(first + i, 0.5f)
        repeat(300) { w.step() }
        for (c in 0 until st.conCount) {
            val a = st.conA[c] - first; val b = st.conB[c] - first
            assertEquals("cos² связи $a–$b к волокну", cos2(c), st.conFiberC2[c], 1e-6f)
            // Связь вдоль x (спицы 0–1, 0–4) — 0.5; под 60° — sqrt(1 − 0.75·0.25).
            assertEquals("связь $a–$b", kotlin.math.sqrt(1f - 0.75f * cos2(c)).toDouble(), st.limitShrink(c, st.conCellScale[c].toDouble()), 1e-6)
        }
        for (t in 0 until st.triCount) {
            assertTrue("треугольник $t сжимается по волокну", st.triFiberById[st.triId[t]])
            assertEquals(0.5, fiberAreaFactor(st.triCellScale[t].toDouble(), st.fiberThicken), 1e-6)
        }
        for (i in 0 until 7) w.setCellScale(first + i, 1.5f)
        repeat(300) { w.step() }
        for (c in 0 until st.conCount) {
            val a = st.conA[c] - first; val b = st.conB[c] - first
            assertEquals("команда расслабления связи $a–$b — целиком", 1.5f, st.conCellScale[c], 0f)
            assertEquals("мягкость связи $a–$b — только вдоль волокна", kotlin.math.sqrt(1.0 + 1.25 * cos2(c)),
                fiberRelaxedSoftness(1.5, st.conFiberC2[c].toDouble()), 1e-6)
        }
    }

    /**
     * Расслабленная связь поперёк волокна (находка 34): мягкость обычной ткани, а предел длины и разрыва — расслабленной
     * мышцы. Без запаса на разрыв поперёк волокна расслабленная мышца, которую тянут вбок, рвалась на 5%, как обычная
     * ткань: на стендах это 86% лишних разрывов варианта «всё только вдоль».
     */
    @Test fun relaxedAcrossFiberKeepsTearReserve() {
        val inner = booleanArrayOf(false)
        val w = 2.0; val alpha = 3.0
        val across = fiberRelaxedSoftness(1.5, 0.0)
        assertEquals("поперёк волокна мягче не становится", 1.0, across, 0.0)
        assertEquals("податливость обычной ткани", -0.2 / (w + alpha), relaxedLinkCorrection(1.2, 1.0, 1.5, w, alpha, inner, 0, across), 1e-15)
        assertTrue("предел длины — у всей расслабленной мышцы: 1.2 покоя ещё не предел", linkMaxLength(1.0, 1.5) > 1.2)
        assertEquals("вдоль волокна — как расслабленная мышца без волокна",
            relaxedLinkCorrection(1.2, 1.0, 1.5, w, alpha, inner, 0),
            relaxedLinkCorrection(1.2, 1.0, 1.5, w, alpha, inner, 0, fiberRelaxedSoftness(1.5, 1.0)), 1e-15)
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
