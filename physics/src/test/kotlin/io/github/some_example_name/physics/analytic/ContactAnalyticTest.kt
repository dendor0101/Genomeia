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
    private val speed = linksPerTick(0.6f)

    private fun pair(vx1: Float, vy1: Float = 0.0f, offset: Float = 0.0f, r1: Float = T_RADIUS, r2: Float = T_RADIUS): Triple<PhysicsWorld, Int, Int> {
        val w = gameWorld().vacuum()
        val gap = 6 * T_LINK
        val a = w.freeParticle(w.addFree(1.0f, 1.0f + offset, r1, vx1, vy1))
        val b = w.freeParticle(w.addFree(1.0f + gap, 1.0f, r2))
        return Triple(w, a, b)
    }

    @Test fun `радиус касания свободной частицы - 0,6 её радиуса`() {
        val (w, a, b) = pair(speed)
        w.step()
        val c = w.state.contacts!!
        assertEquals(contact, c.contactRadiusOf(a), F_POS); assertEquals(contact, c.contactRadiusOf(b), F_POS)
    }

    @Test fun `лобовой удар равных частиц - импульс сохраняется, энергия не растёт`() {
        val (w, a, b) = pair(speed)
        val p0 = w.momentum(); val e0 = w.kinetic()
        repeat(60) { w.step() }
        val p1 = w.momentum()
        assertEquals("импульс вдоль удара", p0[0], p1[0], F_VEL * abs(p0[0]))
        assertEquals("импульс поперёк удара", 0.0f, p1[1], F_VEL * abs(p0[0]))
        assertTrue("частицы разошлись", w.distance(a, b) > 2 * contact)
        val va = w.vx[a]; val vb = w.vx[b]
        assertTrue("после удара вторая частица уходит быстрее первой: $va, $vb", vb > va)
        assertTrue("энергия при ударе не растёт: было $e0, стало ${w.kinetic()}", w.kinetic() <= e0 * (1 + 2 * F_VEL))
    }

    /**
     * Лобовой удар с заданной ФАЗОЙ: в подшаге, где частицы впервые перекрылись, перекрытие равно
     * доле [phase] пути налетающей частицы за подшаг. Возвращает долю отскока — скорость расхождения
     * к скорости сближения. [fixed] — вторая частица неподвижна (рельеф).
     */
    private fun bounce(phase: Float, fixed: Boolean = false, r2: Float = T_RADIUS): Float {
        val w = gameWorld().vacuum()
        val path = speed * DT / SUBSTEPS                       // путь за подшаг
        val touch = (T_RADIUS + r2) * CONTACT_SCALE
        // После 20 подшагов до касания остаётся (1 − phase) пути, после 21-го перекрытие — phase пути.
        val gap = touch + path * (21 - phase)
        val a = w.freeParticle(w.addFree(1.0f, 1.0f, T_RADIUS, speed, 0.0f))
        val b = w.freeParticle(if (fixed) w.addFixed(1.0f + gap, 1.0f, r2) else w.addFree(1.0f + gap, 1.0f, r2))
        repeat(30) { w.step() }
        assertTrue("частицы разошлись", w.distance(a, b) > touch)
        return (w.vx[b] - w.vx[a]) / speed
    }

    /** Доля перекрытия, которую мягкий контакт убирает за подшаг: ω² / (1 + ω²), при ω = 2 это 0.8. */
    private fun contactShare(): Float {
        val omega = gameWorld().also { it.addFree(1.0f, 1.0f, T_RADIUS); it.step() }.state.contacts!!.omegaPair
        return omega * omega / (1 + omega * omega)
    }

    @Test fun `отскок равен заданной доле скорости сближения, пока перекрытие в подшаге касания неглубокое`() {
        // Граница «неглубокого» — фаза 0.5: дальше остаток перекрытия (1 − k)·φ больше пути расхождения e (тест
        // ниже). Сама граница в список не входит: путь за подшаг — тысячная единицы, фаза во float плывёт на ~10⁻³,
        // и на 16 подшагах граничная фаза давала уже 0.10022 (24.11). Берём 0.45 — с запасом на этот шум.
        for (phase in floatArrayOf(0.05f, 0.2f, 0.35f, 0.45f)) {
            assertEquals("равные частицы, фаза $phase", CONTACT_RESTITUTION, bounce(phase), F_VEL)
            assertEquals("о неподвижную, фаза $phase", CONTACT_RESTITUTION, bounce(phase, fixed = true), F_VEL)
            assertEquals("о лёгкую, фаза $phase", CONTACT_RESTITUTION, bounce(phase, r2 = T_RADIUS / 2), F_VEL)
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
        assertEquals("доля перекрытия за подшаг", 0.8f, k, F_POS)
        var worst = 0.0f
        for (phase in floatArrayOf(0.6f, 0.75f, 0.9f, 0.99f)) {
            val expected = CONTACT_RESTITUTION + k * ((1 - k) * phase - CONTACT_RESTITUTION)
            val out = bounce(phase)
            assertEquals("фаза $phase", expected, out, F_VEL)
            worst = maxOf(worst, out)
        }
        assertTrue("отскок завышен не больше чем до ${CONTACT_RESTITUTION + k * (1 - k - CONTACT_RESTITUTION)}: $worst",
            worst <= CONTACT_RESTITUTION + k * (1 - k - CONTACT_RESTITUTION) + 1e-9f)
    }

    /**
     * Жёсткое касание с неподвижным (17.11.2026) снимает перекрытие в подшаге касания почти целиком,
     * остатка на следующий подшаг не остаётся, а скорость расхождения ограничена положенной
     * («рельеф не выстреливает»). До этого здесь было то же завышение, что у подвижных: до 0.18.
     */
    @Test fun `о неподвижную частицу отскок равен заданной доле при любом перекрытии в подшаге касания`() {
        for (phase in floatArrayOf(0.6f, 0.75f, 0.9f, 0.99f)) {
            assertEquals("фаза $phase", CONTACT_RESTITUTION, bounce(phase, fixed = true), F_VEL)
        }
    }

    @Test fun `удар частиц разной массы - импульс сохраняется, лёгкая отлетает быстрее`() {
        // Масса — квадрат радиуса: вторая частица вчетверо легче.
        val (w, a, b) = pair(speed, r2 = T_RADIUS / 2)
        val p0 = w.momentum()
        assertEquals("масса идёт как квадрат радиуса", 4.0f, w.state.restInvMass[b] / w.state.restInvMass[a], F_VEL)
        repeat(60) { w.step() }
        val p1 = w.momentum()
        assertEquals(p0[0], p1[0], F_VEL * abs(p0[0])); assertEquals(0.0f, p1[1], F_VEL * abs(p0[0]))
        assertTrue("лёгкая частица получила скорость больше половины налетевшей: ${w.vx[b] / speed}", w.vx[b] > 0.5f * speed)
        assertTrue("тяжёлая продолжает вперёд: ${w.vx[a] / speed}", w.vx[a] > 0.0f)
    }

    @Test fun `удар о неподвижную частицу - отскок назад, неподвижная стоит`() {
        val w = gameWorld().vacuum()
        val a = w.freeParticle(w.addFree(1.0f, 1.0f, T_RADIUS, speed, 0.0f))
        val wall = w.freeParticle(w.addFixed(1.0f + 6 * T_LINK, 1.0f, T_RADIUS))
        val wx = w.x[wall]; val wy = w.y[wall]
        repeat(60) { w.step() }
        assertEquals("неподвижная частица не сдвинулась", wx, w.x[wall], 0.0f); assertEquals(wy, w.y[wall], 0.0f)
        assertTrue("частица отскочила назад: ${w.vx[a]}", w.vx[a] < 0.0f)
        assertTrue("отскок слабее удара: ${-w.vx[a] / speed}", -w.vx[a] < 0.2f * speed)
        assertEquals("вбок частицу не увело", 0.0f, w.vy[a], F_VEL * speed)
    }

    @Test fun `скользящий удар - трение в пределах конуса`() {
        // Удар со сдвигом в полтора радиуса касания: нормаль наклонена, есть и нормальная, и касательная часть.
        val (w, a, b) = pair(speed, offset = 1.2f * contact)
        val p0 = w.momentum()
        repeat(60) { w.step() }
        val p1 = w.momentum()
        assertEquals(p0[0], p1[0], F_VEL * abs(p0[0])); assertEquals(p0[1], p1[1], F_VEL * abs(p0[0]))
        // Импульс, полученный второй частицей, — вдоль линии удара плюс трение не больше CONTACT_FRICTION от него.
        val jx = w.vx[b]; val jy = w.vy[b]
        assertTrue("вторая частица получила толчок", hypot(jx, jy) > 0.05f * speed)
        // Нормаль в момент касания: центры на расстоянии суммы радиусов касания, сдвиг по Y задан.
        val ny = -1.2f * contact / (2 * contact); val nx = kotlin.math.sqrt(1 - ny * ny)
        val normal = jx * nx + jy * ny
        val tangent = abs(-jx * ny + jy * nx)
        assertTrue("толчок вдоль нормали: $normal", normal > 0.0f)
        assertTrue("касательная часть толчка $tangent в пределах конуса трения ${CONTACT_FRICTION * normal}", tangent <= (CONTACT_FRICTION + 0.1f) * normal)
    }

    @Test fun `частицы внахлёст расходятся на расстояние касания и останавливаются`() {
        val w = gameWorld().vacuum()
        val a = w.freeParticle(w.addFree(1.0f, 1.0f, T_RADIUS))
        val b = w.freeParticle(w.addFree(1.0f + 1.2f * contact, 1.0f, T_RADIUS))   // перекрытие 40% диаметра касания
        repeat(120) { w.step() }
        assertTrue("частицы разошлись до касания: ${w.distance(a, b) / (2 * contact)}", w.distance(a, b) >= 2 * contact * 0.999f)
        assertEquals("расталкивание симметрично: импульс пары нулевой", 0.0f, w.momentum()[0], F_VEL * linksPerTick(1.0f))
    }

    @Test fun `частицы, пролетающие мимо, друг друга не трогают`() {
        val (w, a, b) = pair(speed, offset = 2.5f * contact)
        repeat(60) { w.step() }
        assertEquals(speed, w.vx[a], F_VEL * speed); assertEquals(0.0f, w.vy[a], 0.0f)
        assertEquals(0.0f, w.vx[b], 0.0f); assertEquals(0.0f, w.vy[b], 0.0f)
    }
}
