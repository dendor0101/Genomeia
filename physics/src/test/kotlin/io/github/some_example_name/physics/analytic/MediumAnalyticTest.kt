package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * СТАДИИ СКОРОСТЕЙ: интегрирование, вязкость, сопротивление контура, потолок скорости, рамка мира,
 * тяга мышью, хватка и пружина к месту. Ответ каждого теста выписан из определения стадии.
 *
 *   интегрирование  p += v·h, прежняя позиция запоминается; закреплённая частица стоит;
 *   вязкость        скорость сближения концов связи падает на долю k = VISCOSITY·h (не больше 0.5),
 *                   концы делят её по обратным массам, движение целиком не гасится;
 *   контур          ребро тормозит только движение ПОПЕРЁК себя относительно увлечённой среды:
 *                   k = (NORMAL_DRAG + NORMAL_DRAG_QUADRATIC·|vn|)·длина·h, не больше 0.5;
 *   потолок         быстрее MAX_SPEED_CELLS_PER_TICK связей за тик частица не летит, направление то же;
 *   рамка           частица не выходит за рамку мира, скорость наружу гасится;
 *   тяга            рука даёт постоянную силу: импульс за подшаг не больше DRAG_ACCEL·связь/DT²·h;
 *   хватка          скорость × (1 − g) каждый подшаг; пружина к месту убирает долю w/(w + α/h²) смещения.
 */
class MediumAnalyticTest {
    private val h = DT / SUBSTEPS
    private val v = linksPerTick(1.0)

    private fun chainWorld(count: Int, vacuum: Boolean = true): Pair<PhysicsWorld, Int> {
        val w = gameWorld().also { if (vacuum) it.vacuum() }
        return w to w.bodyFirst(w.addBody(chain(count)))
    }

    // --- интегрирование ---

    @Test fun `интегрирование - позиция идёт на скорость за шаг, прежняя запоминается, закреплённая стоит`() {
        val (w, first) = chainWorld(3)
        val st = w.state
        st.vx[first] = 0.7 * v; st.vy[first] = -0.3 * v
        st.vx[first + 1] = 5 * v; st.invMass[first + 1] = 0.0
        val x = DoubleArray(3) { st.px[first + it] }; val y = DoubleArray(3) { st.py[first + it] }
        st.integrate(h)
        assertEquals(x[0] + 0.7 * v * h, st.px[first], 1e-15); assertEquals(y[0] - 0.3 * v * h, st.py[first], 1e-15)
        assertEquals(x[0], st.prevX[first], 0.0); assertEquals(y[0], st.prevY[first], 0.0)
        assertEquals("закреплённая частица стоит", x[1], st.px[first + 1], 0.0); assertEquals(x[1], st.prevX[first + 1], 0.0)
        assertEquals("покоящаяся остаётся на месте", x[2], st.px[first + 2], 0.0)
        assertEquals("тяжести нет: скорость прежняя", -0.3 * v, st.vy[first], 0.0)
        assertFalse(st.inContact[first])
    }

    @Test fun `восстановление скоростей - скорость равна смещению за подшаг на шаг`() {
        val (w, first) = chainWorld(2)
        val st = w.state
        st.prevX[first] = st.px[first] - 0.002; st.prevY[first] = st.py[first] + 0.001
        st.invMass[first + 1] = 0.0; st.vx[first + 1] = 3.0
        st.updateVelocities(h)
        assertEquals(0.002 / h, st.vx[first], 1e-9); assertEquals(-0.001 / h, st.vy[first], 1e-9)
        assertEquals("у закреплённой скорость обнуляется", 0.0, st.vx[first + 1], 0.0)
    }

    // --- вязкость ---

    /** Пара клеток под углом [angle] со скоростями концов; возвращает состояние и первую частицу. */
    private fun pairAt(angle: Double, wi: Double = 1.0, wj: Double = 1.0): Pair<BodyState, Int> {
        val (w, first) = chainWorld(2)
        val st = w.state
        st.invMass[first] = wi; st.invMass[first + 1] = wj
        val len = st.px[first + 1] - st.px[first]
        st.px[first + 1] = st.px[first] + len * cos(angle); st.py[first + 1] = st.py[first] + len * sin(angle)
        return st to first
    }

    @Test fun `вязкость гасит долю скорости сближения концов связи и не трогает движение поперёк и целиком`() {
        val k = minOf(VISCOSITY * DT, 0.5)
        for (angle in doubleArrayOf(0.0, 0.7, 2.4)) for ((wi, wj) in listOf(1.0 to 1.0, 1.0 to 4.0, 0.0 to 1.0)) {
            val (st, a) = pairAt(angle, wi, wj)
            val b = a + 1
            val nx = cos(angle); val ny = sin(angle)
            // Общий снос, расхождение вдоль связи и вращение поперёк неё.
            val along = 0.6 * v; val across = 0.25 * v; val driftX = 0.3 * v; val driftY = -0.2 * v
            st.vx[a] = driftX; st.vy[a] = driftY
            st.vx[b] = driftX + along * nx - across * ny; st.vy[b] = driftY + along * ny + across * nx
            val px0 = if (wi > 0) st.vx[a] / wi + st.vx[b] / wj else 0.0
            st.applyViscosity(DT)
            val rvx = st.vx[b] - st.vx[a]; val rvy = st.vy[b] - st.vy[a]
            assertEquals("угол $angle, массы $wi/$wj: вдоль связи", along * (1 - k), rvx * nx + rvy * ny, 1e-12 * v)
            assertEquals("угол $angle, массы $wi/$wj: поперёк связи", across, -rvx * ny + rvy * nx, 1e-12 * v)
            if (wi > 0) assertEquals("импульс пары", px0, st.vx[a] / wi + st.vx[b] / wj, 1e-12 * v)
            else { assertEquals("закреплённый конец стоит", driftX, st.vx[a], 0.0); assertEquals(driftY, st.vy[a], 0.0) }
        }
        assertEquals("доля за тик", 1.0 / 3, k, 1e-12)
    }

    @Test fun `вязкость не трогает порванную связь и связь с совпавшими концами, а доля не больше половины`() {
        val (st, a) = pairAt(0.0)
        st.vx[a + 1] = v
        st.conDead[st.linkBetween(a, a + 1)] = true
        st.applyViscosity(DT)
        assertEquals(v, st.vx[a + 1], 0.0); assertEquals(0.0, st.vx[a], 0.0)

        val (zero, z) = pairAt(0.0)
        zero.px[z + 1] = zero.px[z]; zero.vx[z + 1] = v
        zero.applyViscosity(DT)
        assertEquals(v, zero.vx[z + 1], 0.0)

        // Шаг в секунду: VISCOSITY·h = 10, доля обрезается на 0.5 — концы сходятся к общей скорости, но не дальше.
        val (big, c) = pairAt(0.0)
        big.vx[c + 1] = v
        big.applyViscosity(1.0)
        assertEquals(0.5 * v, big.vx[c + 1] - big.vx[c], 1e-12 * v)
        // Само ядро на одну связь — тот же ответ.
        val (one, d) = pairAt(0.0)
        one.vx[d + 1] = v
        one.viscousLink(one.linkBetween(d, d + 1), 0.25)
        assertEquals(0.75 * v, one.vx[d + 1] - one.vx[d], 1e-12 * v)
    }

    // --- сопротивление контура ---

    @Test fun `ребро контура тормозит только движение поперёк себя`() {
        for (angle in doubleArrayOf(0.0, 1.1)) {
            val (st, a) = pairAt(angle)
            val b = a + 1
            assertEquals("у пары клеток одно ребро контура", 1, st.boundCount)
            val len = hypot(st.px[b] - st.px[a], st.py[b] - st.py[a])
            val tx = cos(angle); val ty = sin(angle); val nx = -ty; val ny = tx
            val across = 0.5 * v; val along = 0.8 * v
            for (i in intArrayOf(a, b)) { st.vx[i] = across * nx + along * tx; st.vy[i] = across * ny + along * ty }
            st.normalDragEdge(0, DT)
            val k = minOf((NORMAL_DRAG + NORMAL_DRAG_QUADRATIC * across) * len * DT, 0.5)
            for (i in intArrayOf(a, b)) {
                assertEquals("угол $angle: поперёк ребра", across * (1 - k), st.vx[i] * nx + st.vy[i] * ny, 1e-12 * v)
                assertEquals("угол $angle: вдоль ребра", along, st.vx[i] * tx + st.vy[i] * ty, 1e-12 * v)
            }
            assertTrue("сопротивление не упёрлось в потолок доли: $k", k < 0.5)
        }
    }

    @Test fun `ребро тормозится относительно увлечённой среды, а доля торможения не больше половины`() {
        val (st, a) = pairAt(0.0)
        val b = a + 1
        val len = st.px[b] - st.px[a]
        val o = st.organismOf[a]
        // Среда уже едет вместе с ребром: сопротивления нет.
        st.vy[a] = 0.5 * v; st.vy[b] = 0.5 * v; st.flowVY[o] = 0.5 * v
        st.normalDragEdge(0, DT)
        assertEquals(0.5 * v, st.vy[a], 0.0); assertEquals(0.5 * v, st.vy[b], 0.0)
        // Среда едет навстречу: тормозит разность скоростей.
        st.flowVY[o] = -0.5 * v
        st.normalDragEdge(0, DT)
        val k = minOf((NORMAL_DRAG + NORMAL_DRAG_QUADRATIC * v) * len * DT, 0.5)
        assertEquals(0.5 * v - v * k, st.vy[a], 1e-12 * v)
        // Огромный шаг: доля обрезается на половине, скорость через ноль не перелетает.
        st.flowVY[o] = 0.0; st.vy[a] = v; st.vy[b] = v
        st.normalDragEdge(0, 100.0)
        assertEquals(0.5 * v, st.vy[a], 1e-12 * v); assertEquals(0.5 * v, st.vy[b], 1e-12 * v)
        // Вращение ребра вокруг середины (середина стоит) сопротивления не даёт.
        st.vy[a] = -v; st.vy[b] = v
        st.normalDragEdge(0, DT)
        assertEquals(-v, st.vy[a], 0.0); assertEquals(v, st.vy[b], 0.0)
    }

    @Test fun `сопротивление контура за тик - запас среды и ребро вместе, по разностному закону`() {
        val w = gameWorld().apply { state.dbgIsoDragOff = true }
        val a = w.bodyFirst(w.addBody(chain(2)))
        val b = a + 1
        val st = w.state
        val len = st.px[b] - st.px[a]
        val kf = minOf(FLOW_ENTRAIN * DT, 0.9 / (1.0 + FLOW_MASS)); val kd = minOf(FLOW_DECAY * DT, 1.0)
        st.vy[a] = 0.5 * v; st.vy[b] = 0.5 * v
        st.applyNormalDrag(DT)
        // Запас догоняет центр масс, частицы платят за разгон, затем ребро тормозится о запас.
        val gain = 0.5 * v * kf
        val flow = gain * (1 - kd)
        val paid = 0.5 * v - FLOW_MASS * gain
        val vn = paid - flow
        val k = minOf((NORMAL_DRAG + NORMAL_DRAG_QUADRATIC * abs(vn)) * len * DT, 0.5)
        assertEquals("запас среды", flow, st.flowVY[st.organismOf[a]], 1e-12 * v)
        assertEquals(paid - vn * k, st.vy[a], 1e-12 * v); assertEquals(paid - vn * k, st.vy[b], 1e-12 * v)
        assertEquals("вдоль ребра ничего не появилось", 0.0, st.vx[a], 0.0)
        // С выключенным сопротивлением стадия не делает ничего.
        st.dbgDragOff = true
        val before = st.vy[a]
        st.applyNormalDrag(DT)
        assertEquals(before, st.vy[a], 0.0)
    }

    // --- изотропная среда и потолок скорости ---

    @Test fun `изотропная среда отдельной стадией - тот же закон и тот же множитель частицы`() {
        val w = gameWorld()
        val p = w.freeParticle(w.addFree(1.0, 1.0, T_RADIUS, v, -0.5 * v))
        val q = w.freeParticle(w.addFree(2.0, 1.0, T_RADIUS, v, 0.0, drag = 2.0))
        val st = w.state
        st.applyMediumDrag(DT)
        val keep = 1.0 - MEDIUM_DRAG * DT
        assertEquals(v * keep, st.vx[p], 1e-15); assertEquals(-0.5 * v * keep, st.vy[p], 1e-15)
        assertEquals(v * (1.0 - 2 * MEDIUM_DRAG * DT), st.vx[q], 1e-15)
        assertEquals("доля не уходит ниже нуля", 0.0, mediumKeep(1000.0, 1.0), 0.0)
        assertEquals(1.0 - MEDIUM_DRAG * 0.5 * 3, mediumKeep(0.5, 3.0), 1e-15)
        st.dbgIsoDragOff = true
        val before = st.vx[p]
        st.applyMediumDrag(DT)
        assertEquals("выключенная среда не тормозит", before, st.vx[p], 0.0)
    }

    @Test fun `потолок скорости сохраняет направление и не трогает тех, кто медленнее`() {
        val w = gameWorld().vacuum()
        val fast = w.freeParticle(w.addFree(1.0, 1.0, T_RADIUS))
        val slow = w.freeParticle(w.addFree(2.0, 1.0, T_RADIUS))
        val edge = w.freeParticle(w.addFree(3.0, 1.0, T_RADIUS))
        val st = w.state
        val cap = MAX_SPEED_CELLS_PER_TICK * st.linkScale / DT
        st.vx[fast] = 3 * cap * 0.6; st.vy[fast] = -3 * cap * 0.8
        st.vx[slow] = 0.5 * cap; st.vy[slow] = 0.5 * cap
        st.vx[edge] = cap
        val hits = st.speedCapHits
        st.peakSpeed2 = 0.0
        st.clampSpeed()
        assertEquals(cap * 0.6, st.vx[fast], 1e-12 * cap); assertEquals(-cap * 0.8, st.vy[fast], 1e-12 * cap)
        assertEquals(0.5 * cap, st.vx[slow], 0.0); assertEquals(0.5 * cap, st.vy[slow], 0.0)
        assertEquals("ровно на потолке — не нарушение", cap, st.vx[edge], 0.0)
        assertEquals("срезана одна частица", hits + 1, st.speedCapHits)
        assertEquals("пик скорости запомнен до среза", 9 * cap * cap, st.peakSpeed2, 1e-9 * cap * cap)
        // Выключенный потолок не режет.
        st.dbgNoClamp = true
        st.vx[slow] = 5 * cap
        st.clampSpeed()
        assertEquals(5 * cap, st.vx[slow], 0.0)
    }

    // --- рамка мира ---

    @Test fun `рамка мира - частица не выходит дальше своего радиуса от края, скорость наружу гасится`() {
        val w = gameWorld().vacuum()
        w.setBounds(0.5, 0.5, 2.0, 1.5)
        val st = w.state
        val out = w.freeParticle(w.addFree(1.0, 1.0, T_RADIUS))
        val inside = w.freeParticle(w.addFree(1.2, 1.0, T_RADIUS))
        val r = st.radius[out]
        // За левый нижний угол, со скоростью наружу по x и внутрь по y.
        st.px[out] = 0.4; st.py[out] = 0.3; st.vx[out] = -v; st.vy[out] = 0.5 * v
        st.vx[inside] = -v
        st.clampToBounds()
        assertEquals(0.5 + r, st.px[out], 0.0); assertEquals(0.5 + r, st.py[out], 0.0)
        assertEquals("скорость наружу погашена", 0.0, st.vx[out], 0.0)
        assertEquals("скорость внутрь оставлена", 0.5 * v, st.vy[out], 0.0)
        assertEquals("частицу внутри рамка не трогает", 1.2, st.px[inside], 0.0); assertEquals(-v, st.vx[inside], 0.0)
        // За правый верхний угол.
        st.px[out] = 2.5; st.py[out] = 1.9; st.vx[out] = v; st.vy[out] = -0.5 * v
        st.clampToBounds()
        assertEquals(2.0 - r, st.px[out], 0.0); assertEquals(1.5 - r, st.py[out], 0.0)
        assertEquals(0.0, st.vx[out], 0.0); assertEquals(-0.5 * v, st.vy[out], 0.0)
        // Ровно на допустимой границе — не нарушение.
        st.px[out] = 0.5 + r; st.py[out] = 1.0; st.vx[out] = -v
        st.clampToBounds()
        assertEquals(-v, st.vx[out], 0.0)
    }

    @Test fun `интегрирование у рамки - частица упирается в край в тот же подшаг`() {
        val w = gameWorld().vacuum()
        w.setBounds(0.5, 0.5, 2.0, 1.5)
        val st = w.state
        val p = w.freeParticle(w.addFree(0.6, 0.6, T_RADIUS))
        val r = st.radius[p]
        st.vx[p] = -1000 * v; st.vy[p] = -1000 * v
        st.integrate(h)
        assertEquals(0.5 + r, st.px[p], 0.0); assertEquals(0.5 + r, st.py[p], 0.0)
        st.px[p] = 1.9; st.py[p] = 1.4; st.vx[p] = 1000 * v; st.vy[p] = 1000 * v
        st.integrate(h)
        assertEquals(2.0 - r, st.px[p], 0.0); assertEquals(1.5 - r, st.py[p], 0.0)
        // Без рамки та же частица улетает свободно.
        w.clearBounds()
        st.integrate(h)
        assertTrue(st.px[p] > 2.0)
    }

    // --- тяга мышью ---

    @Test fun `тяга одиночной частицы - импульс за подшаг не больше силы руки, у цели скорость падает`() {
        val w = gameWorld().vacuum()
        // Тяжёлая частица (масса 4): сила руки насыщена, а до потолка скорости ещё далеко.
        val p = w.freeParticle(w.addFree(1.0, 1.0, T_RADIUS, mass = 4.0))
        val st = w.state
        val force = st.DRAG_ACCEL * st.linkScale / (DT * DT)
        val vLimit = st.DRAG_SPEED_LIMIT * MAX_SPEED_CELLS_PER_TICK * st.linkScale / DT
        // Далёкая цель по диагонали: скорость растёт на force·h·w вдоль направления к цели.
        st.pullToward(p, 1.0 + 3.0, 1.0 + 4.0, h)
        val w0 = st.invMass[p]
        assertTrue("сила насыщена раньше потолка скорости", force * h * w0 < vLimit)
        assertEquals(force * h * w0 * 0.6, st.vx[p], 1e-9 * v); assertEquals(force * h * w0 * 0.8, st.vy[p], 1e-9 * v)
        // Второй подшаг: ещё столько же.
        st.pullToward(p, 1.0 + 3.0, 1.0 + 4.0, h)
        assertEquals(minOf(2 * force * h * w0, vLimit) * 0.6, st.vx[p], 1e-9 * v)
        // Частица обычной массы под той же силой за подшаг упирается в потолок скорости тяги.
        val light = w.freeParticle(w.addFree(1.0, 3.0, T_RADIUS))
        st.pullToward(light, 1.0 + 3.0, 3.0 + 4.0, h)
        assertEquals("потолок скорости тяги", vLimit, hypot(st.vx[light], st.vy[light]), 1e-9 * vLimit)
        assertEquals("направление — к цели", 0.6 * vLimit, st.vx[light], 1e-9 * vLimit)
        // Близкая цель: желаемая скорость — расстояние на DRAG_TAU, и сила не насыщена.
        val near = w.freeParticle(w.addFree(1.0, 5.0, T_RADIUS))
        val d = 0.01 * T_LINK
        st.pullToward(near, 1.0 + d, 5.0, h)
        assertEquals(d / DRAG_TAU, st.vx[near], 1e-12 * v); assertEquals(0.0, st.vy[near], 0.0)
        // Цель в самой частице и закреплённая частица — тяга ничего не делает.
        st.vx[near] = 0.0
        st.pullToward(near, st.px[near], st.py[near], h)
        assertEquals(0.0, st.vx[near], 0.0)
        st.invMass[near] = 0.0
        st.pullToward(near, 9.0, 9.0, h)
        assertEquals(0.0, st.vx[near], 0.0)
    }

    @Test fun `тяга за кость - импульс прикладывается к твёрдому телу, поза кости не нарушается`() {
        val w = gameWorld().vacuum()
        val def = patch(5, 2, bone = { _, _ -> true })
        val first = w.bodyFirst(w.addBody(def))
        val st = w.state
        val ids = IntArray(def.count) { first + it }
        val force = st.DRAG_ACCEL * st.linkScale / (DT * DT)
        var mass = 0.0; var cx = 0.0; var cy = 0.0
        for (i in ids) { val m = 1.0 / st.invMass[i]; mass += m; cx += m * st.px[i]; cy += m * st.py[i] }
        cx /= mass; cy /= mass
        val grab = ids[0]
        st.pullToward(grab, st.px[grab] - 5.0, st.py[grab] + 2.0, h)
        // Суммарный импульс кости — ровно импульс руки: по величине force·h.
        var jx = 0.0; var jy = 0.0; var l = 0.0
        for (i in ids) { val m = 1.0 / st.invMass[i]; jx += m * st.vx[i]; jy += m * st.vy[i]; l += m * ((st.px[i] - cx) * st.vy[i] - (st.py[i] - cy) * st.vx[i]) }
        assertEquals("импульс за подшаг — сила руки на шаг", force * h, hypot(jx, jy), 1e-9 * force * h)
        // Момент импульса — плечо точки захвата на импульс руки.
        assertEquals("момент импульса", (st.px[grab] - cx) * jy - (st.py[grab] - cy) * jx, l, 1e-9 * abs(l))
        // Поле скоростей — движение твёрдого тела: v = V + ω × r у каждой клетки.
        var inertia = 0.0
        for (i in ids) inertia += ((st.px[i] - cx).let { it * it } + (st.py[i] - cy).let { it * it }) / st.invMass[i]
        val omega = l / inertia
        for (i in ids) {
            assertEquals("клетка $i, vx", jx / mass - omega * (st.py[i] - cy), st.vx[i], 1e-9 * v)
            assertEquals("клетка $i, vy", jy / mass + omega * (st.px[i] - cx), st.vy[i], 1e-9 * v)
        }
        assertTrue("кость закрутило: рука тянет за край", abs(omega) > 0.0)
    }

    @Test fun `тяга через мир - схваченная частица едет к цели и не быстрее потолка тяги`() {
        val w = gameWorld().vacuum()
        val p = w.freeParticle(w.addFree(1.0, 1.0, T_RADIUS))
        val st = w.state
        val vLimit = st.DRAG_SPEED_LIMIT * MAX_SPEED_CELLS_PER_TICK * st.linkScale / DT
        w.grab(p); w.dragTo(1.0 + 40 * T_LINK, 1.0)
        assertEquals(p, w.grabbed)
        var worst = 0.0
        repeat(30) { w.step(); worst = maxOf(worst, hypot(st.vx[p], st.vy[p])) }
        assertTrue("частица поехала к цели: ${(st.px[p] - 1.0) / T_LINK} связи", st.px[p] > 1.0 + 5 * T_LINK)
        assertTrue("скорость не выше потолка тяги: ${worst / vLimit}", worst <= vLimit * (1 + 1e-9))
        assertEquals("вбок не увело", 1.0, st.py[p], 1e-12)
        w.release()
        assertEquals(-1, w.grabbed)
        val vx = st.vx[p]
        repeat(5) { w.step() }
        assertEquals("отпущенная летит по инерции", vx, st.vx[p], 1e-12 * v)
    }

    // --- хватка и пружина к месту ---

    @Test fun `хватка умножает скорость на один минус g каждый подшаг и ставит позицию по ней`() {
        val w = gameWorld().vacuum()
        val p = w.freeParticle(w.addFree(1.0, 1.0, T_RADIUS))
        val free = w.freeParticle(w.addFree(1.0, 2.0, T_RADIUS))
        val st = w.state
        w.setGrip(p, 0.25f)
        for (i in intArrayOf(p, free)) { st.vx[i] = v; st.vy[i] = -2 * v; st.px[i] += v * h; st.py[i] -= 2 * v * h }
        st.applyGrip(h)
        assertEquals(0.75 * v, st.vx[p], 1e-15); assertEquals(-1.5 * v, st.vy[p], 1e-15)
        assertEquals("позиция — от начала подшага по погашенной скорости", 1.0 + 0.75 * v * h, st.px[p], 1e-15)
        assertEquals(1.0 - 1.5 * v * h, st.py[p], 1e-15)
        assertEquals("частицу без хватки стадия не трогает", v, st.vx[free], 0.0); assertEquals(1.0 + v * h, st.px[free], 0.0)
        assertEquals(1, st.gripCountNow())
        // Закреплённая частица с хваткой не двигается и скорости не получает.
        st.invMass[p] = 0.0; st.vx[p] = v
        st.applyGrip(h)
        assertEquals(v, st.vx[p], 0.0)
        w.setGrip(p, 0f)
        assertEquals("хватку сняли — опор в мире нет", 0, st.gripCountNow())
    }

    @Test fun `пружина к месту убирает долю смещения по формуле XPBD`() {
        val compliance = 4.0e-5
        for (mass in doubleArrayOf(1.0, 4.0)) {
            val w = gameWorld().vacuum()
            val p = w.freeParticle(w.addFree(1.0, 1.0, T_RADIUS, mass = mass))
            val st = w.state
            w.setHome(p, 1.0, 1.0, compliance)
            // Частицу сдвинули от места на подшаге: prev — место, позиция — смещённая.
            val offset = 0.2 * T_LINK
            st.px[p] = 1.0 + offset; st.vx[p] = offset / h
            st.applyGrip(h)
            val wInv = st.invMass[p]
            val share = wInv / (wInv + compliance / (h * h))
            assertEquals("масса $mass: позиция", 1.0 + offset * (1 - share), st.px[p], 1e-15)
            assertEquals("масса $mass: скорость", offset * (1 - share) / h, st.vx[p], 1e-9)
            assertEquals(1, st.gripCountNow())
        }
    }
    @Test fun `запас среды у каждого тела свой - движение одного тела не трогает другое`() {
        val w = gameWorld().apply { state.dbgIsoDragOff = true }
        val a = w.bodyFirst(w.addBody(chain(2)))
        val b = w.bodyFirst(w.addBody(chain(2, x0 = 1.0 + 30 * T_LINK)))
        val st = w.state
        assertTrue(st.organismOf[a] != st.organismOf[b])
        st.vy[a] = v; st.vy[a + 1] = v
        repeat(5) { st.applyNormalDrag(DT) }
        assertTrue("первое тело тормозится о свою среду", st.vy[a] < v)
        assertTrue("его среда увлечена", st.flowVY[st.organismOf[a]] > 0.0)
        assertEquals("второе тело стоит", 0.0, st.vy[b], 0.0); assertEquals(0.0, st.vy[b + 1], 0.0)
        assertEquals("среда второго тела стоит", 0.0, st.flowVY[st.organismOf[b]], 0.0)
    }
}
