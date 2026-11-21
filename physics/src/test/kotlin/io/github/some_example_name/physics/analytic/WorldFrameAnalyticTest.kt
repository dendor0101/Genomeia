package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * РАМКА МИРА — ОПОРА, А НЕ ПРЕСС. Тело, влетевшее в стенку рамки, обязано отскочить или остановиться
 * о неё и остаться целым: стенка отнимает у тела импульс, и возвращать его некому.
 *
 * Сцена одна: мягкий лоскут 8×7 в пустоте летит в правую стенку на 2 связи за тик (четверть потолка
 * скорости), до стенки три связи. Ту же сцену эталонный решатель проходит в ReferenceSolverTest.
 */
class WorldFrameAnalyticTest {
    private class Ram(val dead: Int, val back: Double)

    /** [momentum] — включён ли учёт импульса за тик (TickMomentum). Итог — после 12 тиков. */
    private fun ram(momentum: Boolean): Ram {
        val w = gameWorld().vacuum().also { it.sleepOn = false }
        val def = patch(8, 7)
        val first = w.bodyFirst(w.addBody(def))
        val st = w.state
        st.cancelSpinOn = momentum
        w.setBounds(0.5, 0.5, 1.0 + 11 * T_LINK, 2.0)
        for (i in first until first + def.count) w.setVelocity(i, linksPerTick(2.0), 0.0)
        repeat(12) { w.step() }
        var vx = 0.0
        for (i in first until first + def.count) vx += st.vx[i]
        return Ram(st.deadCount, vx / def.count / linksPerTick(1.0))
    }

    // ПОЧИНЕНО 17.11.2026 (TickMomentum.block). До починки учёт импульса за тик не считал рамку опорой
    // (опорой для него был только пол, inContact) и в конце тика возвращал телу импульс, отнятый
    // стенкой. Тело вдавливалось в стенку собственным импульсом снова и снова: ширина 7.5 → 2.5 связи
    // за три тика, гибли 22 клетки из 56 (docs/physics/reference.md, находка 8).
    @Test fun `тело отскакивает от рамки мира целым`() {
        val r = ram(momentum = true)
        assertEquals("погибло клеток", 0, r.dead)
        assertTrue("тело отскочило от стенки, связей за тик: ${r.back}", r.back < -0.5 && r.back > -2.0)
    }

    @Test fun `без учёта импульса тело отскакивает от рамки мира целым`() {
        // Сама рамка работает: держит стенку и не убивает. Давит тело не она, а учёт импульса поверх неё.
        val r = ram(momentum = false)
        assertEquals("погибло клеток", 0, r.dead)
        // Замер 16.11.2026: −1.04 связи за тик при ударе на 2.
        assertTrue("тело отскочило от стенки, связей за тик: ${r.back}", r.back < -0.5 && r.back > -2.0)
    }
}
