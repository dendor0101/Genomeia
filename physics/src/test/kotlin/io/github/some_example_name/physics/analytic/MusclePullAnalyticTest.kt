package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * МЫШЦА НЕ УБИВАЕТ СОБСТВЕННОЕ ТЕЛО (MUSCLE_MAX_PULL, 17.11.2026).
 *
 * Сцена — как стенд muscle/rect30x4-mAll/squeeze: червь 30×4 в среде, все клетки — мышца, команда
 * «сжаться до предела» скачком. До потолка тяги половины тела набирали ход к середине и сминали её:
 * 35 клеток из 120 гибли по правилу «центр в чужом радиусе», ни одна связь при этом не рвалась.
 */
class MusclePullAnalyticTest {
    private class Squeeze(val dead: Int, val torn: Int, val shortest: Double)

    private fun squeeze(cols: Int, rows: Int): Squeeze {
        val w = gameWorld().also { it.sleepOn = false }
        val def = patch(cols, rows)
        val first = w.bodyFirst(w.addBody(def))
        val st = w.state
        val left = first; val right = first + cols - 1          // концы нижнего ряда
        val rest = w.distance(left, right)
        repeat(5) { w.step() }
        for (i in first until first + def.count) w.setCellScale(i, 0.5f)
        var torn = 0; var shortest = 1.0
        repeat(130) {
            w.step()
            torn += w.edgeDeathCount
            if (!st.cellDead[left] && !st.cellDead[right]) shortest = minOf(shortest, w.distance(left, right) / rest)
        }
        return Squeeze(st.deadCount, torn, shortest)
    }

    @Test fun `червь 30 на 4, целиком мышца, сжимается до предела - ни гибели, ни разрывов`() {
        val s = squeeze(30, 4)
        assertEquals("погибло клеток", 0, s.dead)
        assertEquals("порвано связей", 0, s.torn)
        // Мышца при этом работает: тело сократилось хотя бы на треть (команда — вдвое).
        assertTrue("длина тела к исходной: ${s.shortest}", s.shortest < 0.67)
    }

    @Test fun `короткий червь сжимается так же, как до потолка тяги`() {
        // 12×3 успевает за командой: гибели не было и раньше, а сократиться он обязан почти до команды.
        val s = squeeze(12, 3)
        assertEquals("погибло клеток", 0, s.dead)
        assertEquals("порвано связей", 0, s.torn)
        assertTrue("длина тела к исходной: ${s.shortest}", s.shortest < 0.6)
    }
}
