package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * КОНТАКТ ДВУХ ЧАСТИЦ В ПУСТОТЕ: законы удара, которые не зависят от устройства решателя.
 *
 *  - Импульс пары сохраняется: контакт — внутренняя сила пары.
 *  - Отскок: после лобового удара частицы расходятся с долей CONTACT_RESTITUTION скорости сближения
 *    (у двух подвижных при глубоком перекрытии в подшаге касания — больше, см. известное отклонение
 *    ниже; о неподвижную — ровно заданная доля при любом перекрытии).
 *  - Энергия при ударе не растёт.
 *  - Трение: касательная скорость при скользящем ударе меняется не больше, чем на CONTACT_FRICTION
 *    от изменения нормальной (конус трения).
 *  - Неподвижная частица (рельеф) импульса не берёт и не сдвигается.
 *  - Частицы, поставленные внахлёст, расходятся на расстояние касания.
 */
class ContactAnalyticTest {
    /** Радиус касания свободной частицы — 0.6 её радиуса. */
    private val contact = T_RADIUS * CONTACT_SCALE
    private val speed = linksPerTick(0.6)

    private fun pair(vx1: Double, vy1: Double = 0.0, offset: Double = 0.0, r1: Double = T_RADIUS, r2: Double = T_RADIUS): Triple<PhysicsWorld, Int, Int> {
        val w = gameWorld().vacuum()
        val gap = 6 * T_LINK
        val a = w.freeParticle(w.addFree(1.0, 1.0 + offset, r1, vx1, vy1))
        val b = w.freeParticle(w.addFree(1.0 + gap, 1.0, r2))
        return Triple(w, a, b)
    }

    @Test fun `радиус касания свободной частицы - 0,6 её радиуса`() {
        val (w, a, b) = pair(speed)
        w.step()
        val c = w.state.contacts!!
        assertEquals(contact, c.contactRadiusOf(a), 1e-12); assertEquals(contact, c.contactRadiusOf(b), 1e-12)
    }

    @Test fun `лобовой удар равных частиц - импульс сохраняется, энергия не растёт`() {
        val (w, a, b) = pair(speed)
        val p0 = w.momentum(); val e0 = w.kinetic()
        repeat(60) { w.step() }
        val p1 = w.momentum()
        assertEquals("импульс вдоль удара", p0[0], p1[0], 1e-9 * abs(p0[0]))
        assertEquals("импульс поперёк удара", 0.0, p1[1], 1e-9 * abs(p0[0]))
        assertTrue("частицы разошлись", w.distance(a, b) > 2 * contact)
        val va = w.vx[a]; val vb = w.vx[b]
        assertTrue("после удара вторая частица уходит быстрее первой: $va, $vb", vb > va)
        assertTrue("энергия при ударе не растёт: было $e0, стало ${w.kinetic()}", w.kinetic() <= e0 * (1 + 1e-9))
    }

    /**
     * Лобовой удар с заданной ФАЗОЙ: в подшаге, где частицы впервые перекрылись, перекрытие равно
     * доле [phase] пути налетающей частицы за подшаг. Возвращает долю отскока — скорость расхождения
     * к скорости сближения. [fixed] — вторая частица неподвижна (рельеф).
     */
    private fun bounce(phase: Double, fixed: Boolean = false, r2: Double = T_RADIUS): Double {
        val w = gameWorld().vacuum()
        val path = speed * DT / SUBSTEPS                       // путь за подшаг
        val touch = (T_RADIUS + r2) * CONTACT_SCALE
        // После 20 подшагов до касания остаётся (1 − phase) пути, после 21-го перекрытие — phase пути.
        val gap = touch + path * (21 - phase)
        val a = w.freeParticle(w.addFree(1.0, 1.0, T_RADIUS, speed, 0.0))
        val b = w.freeParticle(if (fixed) w.addFixed(1.0 + gap, 1.0, r2) else w.addFree(1.0 + gap, 1.0, r2))
        repeat(30) { w.step() }
        assertTrue("частицы разошлись", w.distance(a, b) > touch)
        return (w.vx[b] - w.vx[a]) / speed
    }

    /** Доля перекрытия, которую мягкий контакт убирает за подшаг: ω² / (1 + ω²), при ω = 2 это 0.8. */
    private fun contactShare(): Double {
        val omega = gameWorld().also { it.addFree(1.0, 1.0, T_RADIUS); it.step() }.state.contacts!!.omegaPair
        return omega * omega / (1 + omega * omega)
    }

    @Test fun `отскок равен заданной доле скорости сближения, пока перекрытие в подшаге касания неглубокое`() {
        for (phase in doubleArrayOf(0.05, 0.2, 0.35, 0.5)) {
            assertEquals("равные частицы, фаза $phase", CONTACT_RESTITUTION, bounce(phase), 1e-9)
            assertEquals("о неподвижную, фаза $phase", CONTACT_RESTITUTION, bounce(phase, fixed = true), 1e-9)
            assertEquals("о лёгкую, фаза $phase", CONTACT_RESTITUTION, bounce(phase, r2 = T_RADIUS / 2), 1e-9)
        }
    }

    /**
     * ИЗВЕСТНОЕ ОТКЛОНЕНИЕ (находка юнит-тестов 16.11.2026): доля отскока зависит от того, в какой
     * момент подшага частицы коснулись.
     *
     * Мягкий контакт (CT_OMEGA_PAIR = 2) убирает за подшаг долю k = 0.8 перекрытия, а не всё. Отскок
     * в подшаге касания ставит скорость расхождения ровно e·v. На следующем подшаге остаток
     * перекрытия (1 − k)·φ·v·h ещё не закрыт, если он больше пути расхождения e·v·h, — и контакт
     * выталкивает его позиционно, а восстановление скоростей превращает сдвиг в добавочную скорость.
     * Итог: e_вышло = e + k · max(0, (1 − k)·φ − e), от 0.10 до 0.18 при e = 0.1.
     *
     * Энергия при этом не растёт (доля меньше единицы), но отскок выходит до 1.8 раза сильнее
     * заданного. Тест закрепляет закон как есть; если контакт поправят, заменить на строгий тест выше.
     *
     * Только у двух подвижных частиц. Удар о неподвижную с 17.11.2026 считает жёсткое касание
     * (BoundaryContacts.cFixed), и там отскок точный — тест ниже.
     */
    @Test fun `глубокое перекрытие в подшаге касания завышает отскок подвижных - не больше чем до 0,18`() {
        val k = contactShare()
        assertEquals("доля перекрытия за подшаг", 0.8, k, 1e-12)
        var worst = 0.0
        for (phase in doubleArrayOf(0.6, 0.75, 0.9, 0.99)) {
            val expected = CONTACT_RESTITUTION + k * ((1 - k) * phase - CONTACT_RESTITUTION)
            val out = bounce(phase)
            assertEquals("фаза $phase", expected, out, 1e-9)
            worst = maxOf(worst, out)
        }
        assertTrue("отскок завышен не больше чем до ${CONTACT_RESTITUTION + k * (1 - k - CONTACT_RESTITUTION)}: $worst",
            worst <= CONTACT_RESTITUTION + k * (1 - k - CONTACT_RESTITUTION) + 1e-9)
    }

    /**
     * Жёсткое касание с неподвижным (17.11.2026) снимает перекрытие в подшаге касания почти целиком,
     * остатка на следующий подшаг не остаётся, а скорость расхождения ограничена положенной
     * («рельеф не выстреливает»). До этого здесь было то же завышение, что у подвижных: до 0.18.
     */
    @Test fun `о неподвижную частицу отскок равен заданной доле при любом перекрытии в подшаге касания`() {
        for (phase in doubleArrayOf(0.6, 0.75, 0.9, 0.99)) {
            assertEquals("фаза $phase", CONTACT_RESTITUTION, bounce(phase, fixed = true), 1e-9)
        }
    }

    @Test fun `удар частиц разной массы - импульс сохраняется, лёгкая отлетает быстрее`() {
        // Масса — квадрат радиуса: вторая частица вчетверо легче.
        val (w, a, b) = pair(speed, r2 = T_RADIUS / 2)
        val p0 = w.momentum()
        assertEquals("масса идёт как квадрат радиуса", 4.0, w.state.restInvMass[b] / w.state.restInvMass[a], 1e-9)
        repeat(60) { w.step() }
        val p1 = w.momentum()
        assertEquals(p0[0], p1[0], 1e-9 * abs(p0[0])); assertEquals(0.0, p1[1], 1e-9 * abs(p0[0]))
        assertTrue("лёгкая частица получила скорость больше половины налетевшей: ${w.vx[b] / speed}", w.vx[b] > 0.5 * speed)
        assertTrue("тяжёлая продолжает вперёд: ${w.vx[a] / speed}", w.vx[a] > 0.0)
    }

    @Test fun `удар о неподвижную частицу - отскок назад, неподвижная стоит`() {
        val w = gameWorld().vacuum()
        val a = w.freeParticle(w.addFree(1.0, 1.0, T_RADIUS, speed, 0.0))
        val wall = w.freeParticle(w.addFixed(1.0 + 6 * T_LINK, 1.0, T_RADIUS))
        val wx = w.x[wall]; val wy = w.y[wall]
        repeat(60) { w.step() }
        assertEquals("неподвижная частица не сдвинулась", wx, w.x[wall], 0.0); assertEquals(wy, w.y[wall], 0.0)
        assertTrue("частица отскочила назад: ${w.vx[a]}", w.vx[a] < 0.0)
        assertTrue("отскок слабее удара: ${-w.vx[a] / speed}", -w.vx[a] < 0.2 * speed)
        assertEquals("вбок частицу не увело", 0.0, w.vy[a], 1e-9 * speed)
    }

    @Test fun `скользящий удар - трение в пределах конуса`() {
        // Удар со сдвигом в полтора радиуса касания: нормаль наклонена, есть и нормальная, и касательная часть.
        val (w, a, b) = pair(speed, offset = 1.2 * contact)
        val p0 = w.momentum()
        repeat(60) { w.step() }
        val p1 = w.momentum()
        assertEquals(p0[0], p1[0], 1e-9 * abs(p0[0])); assertEquals(p0[1], p1[1], 1e-9 * abs(p0[0]))
        // Импульс, полученный второй частицей, — вдоль линии удара плюс трение не больше CONTACT_FRICTION от него.
        val jx = w.vx[b]; val jy = w.vy[b]
        assertTrue("вторая частица получила толчок", hypot(jx, jy) > 0.05 * speed)
        // Нормаль в момент касания: центры на расстоянии суммы радиусов касания, сдвиг по Y задан.
        val ny = -1.2 * contact / (2 * contact); val nx = Math.sqrt(1 - ny * ny)
        val normal = jx * nx + jy * ny
        val tangent = abs(-jx * ny + jy * nx)
        assertTrue("толчок вдоль нормали: $normal", normal > 0.0)
        assertTrue("касательная часть толчка $tangent в пределах конуса трения ${CONTACT_FRICTION * normal}", tangent <= (CONTACT_FRICTION + 0.1) * normal)
    }

    @Test fun `частицы внахлёст расходятся на расстояние касания и останавливаются`() {
        val w = gameWorld().vacuum()
        val a = w.freeParticle(w.addFree(1.0, 1.0, T_RADIUS))
        val b = w.freeParticle(w.addFree(1.0 + 1.2 * contact, 1.0, T_RADIUS))   // перекрытие 40% диаметра касания
        repeat(120) { w.step() }
        assertTrue("частицы разошлись до касания: ${w.distance(a, b) / (2 * contact)}", w.distance(a, b) >= 2 * contact * 0.999)
        assertEquals("расталкивание симметрично: импульс пары нулевой", 0.0, w.momentum()[0], 1e-9 * linksPerTick(1.0))
    }

    @Test fun `частицы, пролетающие мимо, друг друга не трогают`() {
        val (w, a, b) = pair(speed, offset = 2.5 * contact)
        repeat(60) { w.step() }
        assertEquals(speed, w.vx[a], 1e-12 * speed); assertEquals(0.0, w.vy[a], 0.0)
        assertEquals(0.0, w.vx[b], 0.0); assertEquals(0.0, w.vy[b], 0.0)
    }
}
