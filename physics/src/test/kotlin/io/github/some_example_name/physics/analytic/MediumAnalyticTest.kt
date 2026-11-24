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
    private val v = linksPerTick(1.0f)

    private fun chainWorld(count: Int, vacuum: Boolean = true): Pair<PhysicsWorld, Int> {
        val w = gameWorld().also { if (vacuum) it.vacuum() }
        return w to w.bodyFirst(w.addBody(chain(count)))
    }

    // --- интегрирование ---

    @Test fun `интегрирование - позиция идёт на скорость за шаг, прежняя запоминается, закреплённая стоит`() {
        val (w, first) = chainWorld(3)
        val st = w.state
        st.vx[first] = 0.7f * v; st.vy[first] = -0.3f * v
        st.vx[first + 1] = 5 * v; st.invMass[first + 1] = 0.0f
        val x = FloatArray(3) { st.px[first + it] }; val y = FloatArray(3) { st.py[first + it] }
        st.integrate(h)
        assertEquals(x[0] + 0.7f * v * h, st.px[first], 1e-15f); assertEquals(y[0] - 0.3f * v * h, st.py[first], 1e-15f)
        assertEquals(x[0], st.prevX[first], 0.0f); assertEquals(y[0], st.prevY[first], 0.0f)
        assertEquals("закреплённая частица стоит", x[1], st.px[first + 1], 0.0f); assertEquals(x[1], st.prevX[first + 1], 0.0f)
        assertEquals("покоящаяся остаётся на месте", x[2], st.px[first + 2], 0.0f)
        assertEquals("тяжести нет: скорость прежняя", -0.3f * v, st.vy[first], 0.0f)
        assertFalse(st.inContact[first])
    }

    @Test fun `восстановление скоростей - скорость равна смещению за подшаг на шаг`() {
        val (w, first) = chainWorld(2)
        val st = w.state
        st.prevX[first] = st.px[first] - 0.002f; st.prevY[first] = st.py[first] + 0.001f
        st.invMass[first + 1] = 0.0f; st.vx[first + 1] = 3.0f
        st.updateVelocities(h)
        assertEquals(0.002f / h, st.vx[first], F_VEL); assertEquals(-0.001f / h, st.vy[first], F_VEL)
        assertEquals("у закреплённой скорость обнуляется", 0.0f, st.vx[first + 1], 0.0f)
    }

    // --- вязкость ---

    /** Пара клеток под углом [angle] со скоростями концов; возвращает состояние и первую частицу. */
    private fun pairAt(angle: Float, wi: Float = 1.0f, wj: Float = 1.0f): Pair<BodyState, Int> {
        val (w, first) = chainWorld(2)
        val st = w.state
        st.invMass[first] = wi; st.invMass[first + 1] = wj
        val len = st.px[first + 1] - st.px[first]
        st.px[first + 1] = st.px[first] + len * cos(angle); st.py[first + 1] = st.py[first] + len * sin(angle)
        return st to first
    }

    @Test fun `вязкость гасит долю скорости сближения концов связи и не трогает движение поперёк и целиком`() {
        val k = minOf(VISCOSITY * DT, 0.5f)
        for (angle in floatArrayOf(0.0f, 0.7f, 2.4f)) for ((wi, wj) in listOf(1.0f to 1.0f, 1.0f to 4.0f, 0.0f to 1.0f)) {
            val (st, a) = pairAt(angle, wi, wj)
            val b = a + 1
            val nx = cos(angle); val ny = sin(angle)
            // Общий снос, расхождение вдоль связи и вращение поперёк неё.
            val along = 0.6f * v; val across = 0.25f * v; val driftX = 0.3f * v; val driftY = -0.2f * v
            st.vx[a] = driftX; st.vy[a] = driftY
            st.vx[b] = driftX + along * nx - across * ny; st.vy[b] = driftY + along * ny + across * nx
            val px0 = if (wi > 0) st.vx[a] / wi + st.vx[b] / wj else 0.0f
            st.applyViscosity(DT)
            val rvx = st.vx[b] - st.vx[a]; val rvy = st.vy[b] - st.vy[a]
            assertEquals("угол $angle, массы $wi/$wj: вдоль связи", along * (1 - k), rvx * nx + rvy * ny, F_VEL * v)
            assertEquals("угол $angle, массы $wi/$wj: поперёк связи", across, -rvx * ny + rvy * nx, F_VEL * v)
            if (wi > 0) assertEquals("импульс пары", px0, st.vx[a] / wi + st.vx[b] / wj, F_VEL * v)
            else { assertEquals("закреплённый конец стоит", driftX, st.vx[a], 0.0f); assertEquals(driftY, st.vy[a], 0.0f) }
        }
        assertEquals("доля за тик", 1.0f / 3, k, F_POS)
    }

    @Test fun `вязкость не трогает порванную связь и связь с совпавшими концами, а доля не больше половины`() {
        val (st, a) = pairAt(0.0f)
        st.vx[a + 1] = v
        st.conDead[st.linkBetween(a, a + 1)] = true
        st.applyViscosity(DT)
        assertEquals(v, st.vx[a + 1], 0.0f); assertEquals(0.0f, st.vx[a], 0.0f)

        val (zero, z) = pairAt(0.0f)
        zero.px[z + 1] = zero.px[z]; zero.vx[z + 1] = v
        zero.applyViscosity(DT)
        assertEquals(v, zero.vx[z + 1], 0.0f)

        // Шаг в секунду: VISCOSITY·h = 10, доля обрезается на 0.5 — концы сходятся к общей скорости, но не дальше.
        val (big, c) = pairAt(0.0f)
        big.vx[c + 1] = v
        big.applyViscosity(1.0f)
        assertEquals(0.5f * v, big.vx[c + 1] - big.vx[c], F_VEL * v)
        // Само ядро на одну связь — тот же ответ.
        val (one, d) = pairAt(0.0f)
        one.vx[d + 1] = v
        one.viscousLink(one.linkBetween(d, d + 1), 0.25f)
        assertEquals(0.75f * v, one.vx[d + 1] - one.vx[d], F_VEL * v)
    }

    // --- сопротивление контура ---

    @Test fun `ребро контура тормозит только движение поперёк себя`() {
        for (angle in floatArrayOf(0.0f, 1.1f)) {
            val (st, a) = pairAt(angle)
            val b = a + 1
            assertEquals("у пары клеток одно ребро контура", 1, st.boundCount)
            val len = hypot(st.px[b] - st.px[a], st.py[b] - st.py[a])
            val tx = cos(angle); val ty = sin(angle); val nx = -ty; val ny = tx
            val across = 0.5f * v; val along = 0.8f * v
            for (i in intArrayOf(a, b)) { st.vx[i] = across * nx + along * tx; st.vy[i] = across * ny + along * ty }
            st.normalDragEdge(0, DT)
            val k = minOf((NORMAL_DRAG + NORMAL_DRAG_QUADRATIC * across) * len * DT, 0.5f)
            for (i in intArrayOf(a, b)) {
                assertEquals("угол $angle: поперёк ребра", across * (1 - k), st.vx[i] * nx + st.vy[i] * ny, F_VEL * v)
                assertEquals("угол $angle: вдоль ребра", along, st.vx[i] * tx + st.vy[i] * ty, F_VEL * v)
            }
            assertTrue("сопротивление не упёрлось в потолок доли: $k", k < 0.5f)
        }
    }

    @Test fun `ребро тормозится относительно увлечённой среды, а доля торможения не больше половины`() {
        val (st, a) = pairAt(0.0f)
        val b = a + 1
        val len = st.px[b] - st.px[a]
        val o = st.organismOf[a]
        // Среда уже едет вместе с ребром: сопротивления нет.
        st.vy[a] = 0.5f * v; st.vy[b] = 0.5f * v; st.flowVY[o] = 0.5f * v
        st.normalDragEdge(0, DT)
        assertEquals(0.5f * v, st.vy[a], 0.0f); assertEquals(0.5f * v, st.vy[b], 0.0f)
        // Среда едет навстречу: тормозит разность скоростей.
        st.flowVY[o] = -0.5f * v
        st.normalDragEdge(0, DT)
        val k = minOf((NORMAL_DRAG + NORMAL_DRAG_QUADRATIC * v) * len * DT, 0.5f)
        assertEquals(0.5f * v - v * k, st.vy[a], F_VEL * v)
        // Огромный шаг: доля обрезается на половине, скорость через ноль не перелетает.
        st.flowVY[o] = 0.0f; st.vy[a] = v; st.vy[b] = v
        st.normalDragEdge(0, 100.0f)
        assertEquals(0.5f * v, st.vy[a], F_VEL * v); assertEquals(0.5f * v, st.vy[b], F_VEL * v)
        // Вращение ребра вокруг середины (середина стоит) сопротивления не даёт.
        st.vy[a] = -v; st.vy[b] = v
        st.normalDragEdge(0, DT)
        assertEquals(-v, st.vy[a], 0.0f); assertEquals(v, st.vy[b], 0.0f)
    }

    @Test fun `сопротивление контура за тик - запас среды и ребро вместе, по разностному закону`() {
        val w = gameWorld().apply { state.dbgIsoDragOff = true }
        val a = w.bodyFirst(w.addBody(chain(2)))
        val b = a + 1
        val st = w.state
        val len = st.px[b] - st.px[a]
        val kf = minOf(FLOW_ENTRAIN * DT, 0.9f / (1.0f + FLOW_MASS)); val kd = minOf(FLOW_DECAY * DT, 1.0f)
        st.vy[a] = 0.5f * v; st.vy[b] = 0.5f * v
        st.applyNormalDrag(DT)
        // Запас догоняет центр масс, частицы платят за разгон, затем ребро тормозится о запас.
        val gain = 0.5f * v * kf
        val flow = gain * (1 - kd)
        val paid = 0.5f * v - FLOW_MASS * gain
        val vn = paid - flow
        val k = minOf((NORMAL_DRAG + NORMAL_DRAG_QUADRATIC * abs(vn)) * len * DT, 0.5f)
        assertEquals("запас среды", flow, st.flowVY[st.organismOf[a]], F_VEL * v)
        assertEquals(paid - vn * k, st.vy[a], F_VEL * v); assertEquals(paid - vn * k, st.vy[b], F_VEL * v)
        assertEquals("вдоль ребра ничего не появилось", 0.0f, st.vx[a], 0.0f)
        // С выключенным сопротивлением стадия не делает ничего.
        st.dbgDragOff = true
        val before = st.vy[a]
        st.applyNormalDrag(DT)
        assertEquals(before, st.vy[a], 0.0f)
    }

    // --- изотропная среда и потолок скорости ---

    @Test fun `изотропная среда отдельной стадией - тот же закон и тот же множитель частицы`() {
        val w = gameWorld()
        val p = w.freeParticle(w.addFree(1.0f, 1.0f, T_RADIUS, v, -0.5f * v))
        val q = w.freeParticle(w.addFree(2.0f, 1.0f, T_RADIUS, v, 0.0f, drag = 2.0f))
        val st = w.state
        st.applyMediumDrag(DT)
        val keep = 1.0f - MEDIUM_DRAG * DT
        assertEquals(v * keep, st.vx[p], 1e-15f); assertEquals(-0.5f * v * keep, st.vy[p], 1e-15f)
        assertEquals(v * (1.0f - 2 * MEDIUM_DRAG * DT), st.vx[q], 1e-15f)
        assertEquals("доля не уходит ниже нуля", 0.0f, st.mediumKeep(1000.0f, 1.0f), 0.0f)
        assertEquals(1.0f - MEDIUM_DRAG * 0.5f * 3, st.mediumKeep(0.5f, 3.0f), 1e-15f)
        st.dbgIsoDragOff = true
        val before = st.vx[p]
        st.applyMediumDrag(DT)
        assertEquals("выключенная среда не тормозит", before, st.vx[p], 0.0f)
    }

    @Test fun `потолок скорости сохраняет направление и не трогает тех, кто медленнее`() {
        val w = gameWorld().vacuum()
        val fast = w.freeParticle(w.addFree(1.0f, 1.0f, T_RADIUS))
        val slow = w.freeParticle(w.addFree(2.0f, 1.0f, T_RADIUS))
        val edge = w.freeParticle(w.addFree(3.0f, 1.0f, T_RADIUS))
        val st = w.state
        val cap = MAX_SPEED_CELLS_PER_TICK * st.linkScale / DT
        st.vx[fast] = 3 * cap * 0.6f; st.vy[fast] = -3 * cap * 0.8f
        st.vx[slow] = 0.5f * cap; st.vy[slow] = 0.5f * cap
        st.vx[edge] = cap
        val hits = st.speedCapHits
        st.peakSpeed2 = 0.0f
        st.clampSpeed()
        assertEquals(cap * 0.6f, st.vx[fast], F_VEL * cap); assertEquals(-cap * 0.8f, st.vy[fast], F_VEL * cap)
        assertEquals(0.5f * cap, st.vx[slow], 0.0f); assertEquals(0.5f * cap, st.vy[slow], 0.0f)
        assertEquals("ровно на потолке — не нарушение", cap, st.vx[edge], 0.0f)
        assertEquals("срезана одна частица", hits + 1, st.speedCapHits)
        assertEquals("пик скорости запомнен до среза", 9 * cap * cap, st.peakSpeed2, F_VEL * cap * cap)
        // Выключенный потолок не режет.
        st.dbgNoClamp = true
        st.vx[slow] = 5 * cap
        st.clampSpeed()
        assertEquals(5 * cap, st.vx[slow], 0.0f)
    }

    // --- рамка мира ---

    @Test fun `рамка мира - частица не выходит дальше своего радиуса от края, скорость наружу гасится`() {
        val w = gameWorld().vacuum()
        w.setBounds(0.5f, 0.5f, 2.0f, 1.5f)
        val st = w.state
        val out = w.freeParticle(w.addFree(1.0f, 1.0f, T_RADIUS))
        val inside = w.freeParticle(w.addFree(1.2f, 1.0f, T_RADIUS))
        val r = st.radius[out]
        // За левый нижний угол, со скоростью наружу по x и внутрь по y.
        st.px[out] = 0.4f; st.py[out] = 0.3f; st.vx[out] = -v; st.vy[out] = 0.5f * v
        st.vx[inside] = -v
        st.clampToBounds()
        assertEquals(0.5f + r, st.px[out], 0.0f); assertEquals(0.5f + r, st.py[out], 0.0f)
        assertEquals("скорость наружу погашена", 0.0f, st.vx[out], 0.0f)
        assertEquals("скорость внутрь оставлена", 0.5f * v, st.vy[out], 0.0f)
        assertEquals("частицу внутри рамка не трогает", 1.2f, st.px[inside], 0.0f); assertEquals(-v, st.vx[inside], 0.0f)
        // За правый верхний угол.
        st.px[out] = 2.5f; st.py[out] = 1.9f; st.vx[out] = v; st.vy[out] = -0.5f * v
        st.clampToBounds()
        assertEquals(2.0f - r, st.px[out], 0.0f); assertEquals(1.5f - r, st.py[out], 0.0f)
        assertEquals(0.0f, st.vx[out], 0.0f); assertEquals(-0.5f * v, st.vy[out], 0.0f)
        // Ровно на допустимой границе — не нарушение.
        st.px[out] = 0.5f + r; st.py[out] = 1.0f; st.vx[out] = -v
        st.clampToBounds()
        assertEquals(-v, st.vx[out], 0.0f)
    }

    @Test fun `интегрирование у рамки - частица упирается в край в тот же подшаг`() {
        val w = gameWorld().vacuum()
        w.setBounds(0.5f, 0.5f, 2.0f, 1.5f)
        val st = w.state
        val p = w.freeParticle(w.addFree(0.6f, 0.6f, T_RADIUS))
        val r = st.radius[p]
        st.vx[p] = -1000 * v; st.vy[p] = -1000 * v
        st.integrate(h)
        assertEquals(0.5f + r, st.px[p], 0.0f); assertEquals(0.5f + r, st.py[p], 0.0f)
        st.px[p] = 1.9f; st.py[p] = 1.4f; st.vx[p] = 1000 * v; st.vy[p] = 1000 * v
        st.integrate(h)
        assertEquals(2.0f - r, st.px[p], 0.0f); assertEquals(1.5f - r, st.py[p], 0.0f)
        // Без рамки та же частица улетает свободно.
        w.clearBounds()
        st.integrate(h)
        assertTrue(st.px[p] > 2.0f)
    }

    // --- тяга мышью ---

    @Test fun `тяга одиночной частицы - импульс за подшаг не больше силы руки, у цели скорость падает`() {
        val w = gameWorld().vacuum()
        // Тяжёлая частица (масса 4): сила руки насыщена, а до потолка скорости ещё далеко.
        val p = w.freeParticle(w.addFree(1.0f, 1.0f, T_RADIUS, mass = 4.0f))
        val st = w.state
        val force = st.DRAG_ACCEL * st.linkScale / (DT * DT)
        val vLimit = st.DRAG_SPEED_LIMIT * MAX_SPEED_CELLS_PER_TICK * st.linkScale / DT
        // Далёкая цель по диагонали: скорость растёт на force·h·w вдоль направления к цели.
        st.pullToward(p, 1.0f + 3.0f, 1.0f + 4.0f, h)
        val w0 = st.invMass[p]
        assertTrue("сила насыщена раньше потолка скорости", force * h * w0 < vLimit)
        assertEquals(force * h * w0 * 0.6f, st.vx[p], F_VEL * v); assertEquals(force * h * w0 * 0.8f, st.vy[p], F_VEL * v)
        // Второй подшаг: ещё столько же.
        st.pullToward(p, 1.0f + 3.0f, 1.0f + 4.0f, h)
        assertEquals(minOf(2 * force * h * w0, vLimit) * 0.6f, st.vx[p], F_VEL * v)
        // Частица обычной массы под той же силой упирается в потолок скорости тяги. За один подшаг — пока подшагов не
        // больше 16 (сила руки даёт ей 128 / N связи за тик, потолок — 8); при 20 подшагах (24.11) — за два.
        val light = w.freeParticle(w.addFree(1.0f, 3.0f, T_RADIUS))
        val toCap = kotlin.math.ceil(vLimit / (force * h * st.invMass[light])).toInt()
        repeat(toCap) { st.pullToward(light, 1.0f + 3.0f, 3.0f + 4.0f, h) }
        assertEquals("потолок скорости тяги", vLimit, hypot(st.vx[light], st.vy[light]), F_VEL * vLimit)
        assertEquals("направление — к цели", 0.6f * vLimit, st.vx[light], F_VEL * vLimit)
        // Близкая цель: желаемая скорость — расстояние на DRAG_TAU, и сила не насыщена.
        val near = w.freeParticle(w.addFree(1.0f, 5.0f, T_RADIUS))
        val d = 0.01f * T_LINK
        st.pullToward(near, 1.0f + d, 5.0f, h)
        assertEquals(d / DRAG_TAU, st.vx[near], F_VEL * v); assertEquals(0.0f, st.vy[near], 0.0f)
        // Цель в самой частице и закреплённая частица — тяга ничего не делает.
        st.vx[near] = 0.0f
        st.pullToward(near, st.px[near], st.py[near], h)
        assertEquals(0.0f, st.vx[near], 0.0f)
        st.invMass[near] = 0.0f
        st.pullToward(near, 9.0f, 9.0f, h)
        assertEquals(0.0f, st.vx[near], 0.0f)
    }

    @Test fun `тяга за кость - импульс прикладывается к твёрдому телу, поза кости не нарушается`() {
        val w = gameWorld().vacuum()
        val def = patch(5, 2, bone = { _, _ -> true })
        val first = w.bodyFirst(w.addBody(def))
        val st = w.state
        val ids = IntArray(def.count) { first + it }
        val force = st.DRAG_ACCEL * st.linkScale / (DT * DT)
        var mass = 0.0f; var cx = 0.0f; var cy = 0.0f
        for (i in ids) { val m = 1.0f / st.invMass[i]; mass += m; cx += m * st.px[i]; cy += m * st.py[i] }
        cx /= mass; cy /= mass
        val grab = ids[0]
        st.pullToward(grab, st.px[grab] - 5.0f, st.py[grab] + 2.0f, h)
        // Суммарный импульс кости — ровно импульс руки: по величине force·h.
        var jx = 0.0f; var jy = 0.0f; var l = 0.0f
        for (i in ids) { val m = 1.0f / st.invMass[i]; jx += m * st.vx[i]; jy += m * st.vy[i]; l += m * ((st.px[i] - cx) * st.vy[i] - (st.py[i] - cy) * st.vx[i]) }
        assertEquals("импульс за подшаг — сила руки на шаг", force * h, hypot(jx, jy), F_VEL * force * h)
        // Момент импульса — плечо точки захвата на импульс руки.
        assertEquals("момент импульса", (st.px[grab] - cx) * jy - (st.py[grab] - cy) * jx, l, F_VEL * abs(l))
        // Поле скоростей — движение твёрдого тела: v = V + ω × r у каждой клетки.
        var inertia = 0.0f
        for (i in ids) inertia += ((st.px[i] - cx).let { it * it } + (st.py[i] - cy).let { it * it }) / st.invMass[i]
        val omega = l / inertia
        for (i in ids) {
            assertEquals("клетка $i, vx", jx / mass - omega * (st.py[i] - cy), st.vx[i], F_VEL * v)
            assertEquals("клетка $i, vy", jy / mass + omega * (st.px[i] - cx), st.vy[i], F_VEL * v)
        }
        assertTrue("кость закрутило: рука тянет за край", abs(omega) > 0.0f)
    }

    @Test fun `тяга через мир - схваченная частица едет к цели и не быстрее потолка тяги`() {
        val w = gameWorld().vacuum()
        val p = w.freeParticle(w.addFree(1.0f, 1.0f, T_RADIUS))
        val st = w.state
        val vLimit = st.DRAG_SPEED_LIMIT * MAX_SPEED_CELLS_PER_TICK * st.linkScale / DT
        w.grab(p); w.dragTo(1.0f + 40 * T_LINK, 1.0f)
        assertEquals(p, w.grabbed)
        var worst = 0.0f
        repeat(30) { w.step(); worst = maxOf(worst, hypot(st.vx[p], st.vy[p])) }
        assertTrue("частица поехала к цели: ${(st.px[p] - 1.0f) / T_LINK} связи", st.px[p] > 1.0f + 5 * T_LINK)
        assertTrue("скорость не выше потолка тяги: ${worst / vLimit}", worst <= vLimit * (1 + 2 * F_VEL))
        assertEquals("вбок не увело", 1.0f, st.py[p], F_POS)
        w.release()
        assertEquals(-1, w.grabbed)
        val vx = st.vx[p]
        repeat(5) { w.step() }
        assertEquals("отпущенная летит по инерции", vx, st.vx[p], F_VEL * v)
    }

    // --- хватка и пружина к месту ---

    @Test fun `хватка умножает скорость на один минус g каждый подшаг и ставит позицию по ней`() {
        val w = gameWorld().vacuum()
        val p = w.freeParticle(w.addFree(1.0f, 1.0f, T_RADIUS))
        val free = w.freeParticle(w.addFree(1.0f, 2.0f, T_RADIUS))
        val st = w.state
        w.setGrip(p, 0.25f)
        for (i in intArrayOf(p, free)) { st.vx[i] = v; st.vy[i] = -2 * v; st.px[i] += v * h; st.py[i] -= 2 * v * h }
        st.applyGrip(h)
        assertEquals(0.75f * v, st.vx[p], 1e-15f); assertEquals(-1.5f * v, st.vy[p], 1e-15f)
        assertEquals("позиция — от начала подшага по погашенной скорости", 1.0f + 0.75f * v * h, st.px[p], 1e-15f)
        assertEquals(1.0f - 1.5f * v * h, st.py[p], 1e-15f)
        assertEquals("частицу без хватки стадия не трогает", v, st.vx[free], 0.0f); assertEquals(1.0f + v * h, st.px[free], 0.0f)
        assertEquals(1, st.gripCountNow())
        // Закреплённая частица с хваткой не двигается и скорости не получает.
        st.invMass[p] = 0.0f; st.vx[p] = v
        st.applyGrip(h)
        assertEquals(v, st.vx[p], 0.0f)
        w.setGrip(p, 0f)
        assertEquals("хватку сняли — опор в мире нет", 0, st.gripCountNow())
    }

    @Test fun `пружина к месту убирает долю смещения по формуле XPBD`() {
        val compliance = 4.0e-5f
        for (mass in floatArrayOf(1.0f, 4.0f)) {
            val w = gameWorld().vacuum()
            val p = w.freeParticle(w.addFree(1.0f, 1.0f, T_RADIUS, mass = mass))
            val st = w.state
            w.setHome(p, 1.0f, 1.0f, compliance)
            // Частицу сдвинули от места на подшаге: prev — место, позиция — смещённая.
            val offset = 0.2f * T_LINK
            st.px[p] = 1.0f + offset; st.vx[p] = offset / h
            st.applyGrip(h)
            val wInv = st.invMass[p]
            val share = wInv / (wInv + compliance / (h * h))
            assertEquals("масса $mass: позиция", 1.0f + offset * (1 - share), st.px[p], 1e-15f)
            assertEquals("масса $mass: скорость", offset * (1 - share) / h, st.vx[p], F_VEL)
            assertEquals(1, st.gripCountNow())
        }
    }
    @Test fun `запас среды у каждого тела свой - движение одного тела не трогает другое`() {
        val w = gameWorld().apply { state.dbgIsoDragOff = true }
        val a = w.bodyFirst(w.addBody(chain(2)))
        val b = w.bodyFirst(w.addBody(chain(2, x0 = 1.0f + 30 * T_LINK)))
        val st = w.state
        assertTrue(st.organismOf[a] != st.organismOf[b])
        st.vy[a] = v; st.vy[a + 1] = v
        repeat(5) { st.applyNormalDrag(DT) }
        assertTrue("первое тело тормозится о свою среду", st.vy[a] < v)
        assertTrue("его среда увлечена", st.flowVY[st.organismOf[a]] > 0.0f)
        assertEquals("второе тело стоит", 0.0f, st.vy[b], 0.0f); assertEquals(0.0f, st.vy[b + 1], 0.0f)
        assertEquals("среда второго тела стоит", 0.0f, st.flowVY[st.organismOf[b]], 0.0f)
    }
}
