package io.github.some_example_name.physics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * КЛИН: БЫСТРЫЙ УДАР ПРИНИМАЕТ КЛИН ТКАНИ ЗА КЛЕТКОЙ КАСАНИЯ (ImpactWedge, 18.11.2026).
 *
 * Каждый тест проверяет одно свойство закона и падает, если это свойство убрать: медленное касание
 * клин не трогает; импульс удара сохраняется; правило гибели клин не ослабляет; память удара — часть
 * состояния мира и переживает снимок; ради чего всё — тело на сильном ударе цело. Сравнение «без
 * клина» — тот же мир с переключателем CT_WEDGE=0.
 */
class ImpactWedgeAnalyticTest {
    private fun world(wedge: Boolean): PhysicsWorld =
        gameWorld(if (wedge) emptyMap() else mapOf("CT_WEDGE" to "0")).vacuum().also { it.sleepOn = false }

    /** Два лоскута [cols]×[rows] лоб в лоб: левый летит вправо со скоростью [left], правый влево — [right] (связей за тик). */
    private fun ram(w: PhysicsWorld, cols: Int, rows: Int, left: Double, right: Double, gapLinks: Double = 2.0): IntRange {
        val a = w.bodyFirst(w.addBody(patch(cols, rows)))
        val b = w.bodyFirst(w.addBody(patch(cols, rows, x0 = 1.0 + (cols - 0.5 + gapLinks) * T_LINK)))
        val n = cols * rows
        for (i in a until a + n) w.setVelocity(i, linksPerTick(left), 0.0)
        for (i in b until b + n) w.setVelocity(i, -linksPerTick(right), 0.0)
        return a until b + n
    }

    @Test fun `медленное касание клин не трогает - мир побитово тот же, что без клина`() {
        // Лоскут налетает на лоскут в покое со скоростью 0.2 связи за тик — ниже порога удара IMPACT_FROM.
        // Если бы клин работал и тут (порог ноль), положения разошлись бы уже на тике касания.
        val runs = listOf(true, false).map { wedge ->
            val w = world(wedge)
            val bodies = ram(w, 6, 5, 0.2, 0.0, gapLinks = 0.5)
            val second = bodies.first + 30
            var touched = 0
            repeat(40) {
                w.step()
                val ct = w.state.contacts!!
                for (c in 0 until ct.contactCount) if ((ct.contactI(c) < second) != (ct.contactJ(c) < second)) touched++
            }
            assertTrue("лоскуты касались: касаний за прогон $touched", touched > 0)
            w.x.copyOf(w.particleCount)
        }
        assertArrayEquals("положения с клином и без", runs[1], runs[0], 0.0)
    }

    @Test fun `удар клиньев сохраняет импульс и не прибавляет энергии`() {
        // Лоскуты 8×7 сходятся на 0.6 + 0.6 связи за тик: удар (выше порога), но без гибели. Пустота — внешних
        // сил нет: суммарный импульс обязан сохраниться, кинетическая энергия — убыть (удар клиньев неупругий).
        val w = world(true)
        ram(w, 8, 7, 0.6, 0.6, gapLinks = 1.0)
        val p0 = w.momentum(); val e0 = w.kinetic()
        var hot = 0
        repeat(30) {
            w.step()
            hot = maxOf(hot, w.state.impactSpeed.count { it > IMPACT_FROM })
        }
        val p1 = w.momentum(); val e1 = w.kinetic()
        val scale = linksPerTick(0.6) * p0[3]
        assertTrue("клин работал: клеток с памятью удара $hot", hot > 0)
        assertEquals("погибло клеток", 0, w.state.deadCount)
        assertEquals("импульс по x, доля импульса одного тела", 0.0, (p1[0] - p0[0]) / scale, 1e-9)
        assertEquals("импульс по y, доля импульса одного тела", 0.0, (p1[1] - p0[1]) / scale, 1e-9)
        assertTrue("энергия не выросла: было $e0, стало $e1", e1 <= e0)
    }

    @Test fun `клин не отменяет гибель - центр в чужом радиусе судится до его толчков`() {
        // Две цепочки по три клетки налетают торцами на 2.1 + 2.1 связи за тик. За первый подшаг касания
        // передние клетки сходятся с 1.16 до 0.46 связи: глубже порога гибели (радиус касания 0.525 связи) и
        // мельче ядра обрезки по времени удара (0.42 связи), то есть гибель судит проход касаний. Клин тем же
        // подшагом раздвинул бы их на расстояние касания — суди он гибель после своих толчков, обе выжили бы.
        val dead = listOf(true, false).map { wedge ->
            val w = world(wedge)
            val a = w.bodyFirst(w.addBody(chain(3)))
            val front = a + 2
            val b = w.bodyFirst(w.addBody(chain(3, x0 = 1.0 + (2 + 1.16) * T_LINK)))
            for (i in a until a + 3) w.setVelocity(i, linksPerTick(2.1), 0.0)
            for (i in b until b + 3) w.setVelocity(i, -linksPerTick(2.1), 0.0)
            w.step()
            if (wedge) assertTrue("клин работал: память удара передней клетки ${w.state.impactSpeed[front]}",
                w.state.impactSpeed[front] > IMPACT_FROM || w.state.cellDead[front])
            Pair(w.state.cellDead[front], w.state.cellDead[b])
        }
        assertEquals("без клина передние клетки гибнут (сцена проверяет то, что должна)", Pair(true, true), dead[1])
        assertEquals("с клином — тоже", Pair(true, true), dead[0])
    }

    @Test fun `память удара - состояние мира - переживает снимок`() {
        // Таран на 2 + 2 связи за тик; на третьем тике касания мир пишет снимок, из снимка читается второй
        // мир, дальше оба идут побитово одинаково. Третий мир — тот же снимок, но с обнулённой памятью
        // удара: он обязан разойтись, иначе тест не проверял бы, что память что-то решает.
        val w = world(true)
        ram(w, 8, 7, 2.0, 2.0)
        var t = 0
        while (w.state.impactSpeed.none { it > IMPACT_FROM } && t < 40) { w.step(); t++ }
        repeat(2) { w.step() }
        val bytes = ByteArrayOutputStream().also { w.write(DataOutputStream(it)) }.toByteArray()
        fun load(): PhysicsWorld = gameWorld().vacuum().also { it.sleepOn = false; it.read(DataInputStream(ByteArrayInputStream(bytes))) }
        val l = load()
        val forgot = load().also { java.util.Arrays.fill(it.state.impactSpeed, 0.0) }
        assertTrue("в снимке есть память удара", l.state.impactSpeed.any { it > IMPACT_FROM })
        repeat(10) { w.step(); l.step(); forgot.step() }
        val n = w.particleCount
        assertArrayEquals("x после снимка", w.x.copyOf(n), l.x.copyOf(n), 0.0)
        assertArrayEquals("y после снимка", w.y.copyOf(n), l.y.copyOf(n), 0.0)
        assertTrue("без памяти удара мир идёт иначе", !w.x.copyOf(n).contentEquals(forgot.x.copyOf(n)))
    }

    @Test fun `на таране клин бережёт ткань`() {
        // Ради чего клин: лоскуты 8×7 лоб в лоб на 2 + 2 связи за тик («таран» стендов: 4 связи за тик).
        // Замер 18.11.2026: с клином погибло 14 клеток и порвано 38 связей, без клина — 112 и 278.
        val result = listOf(true, false).map { wedge ->
            val w = world(wedge)
            ram(w, 8, 7, 2.0, 2.0)
            repeat(40) { w.step() }
            Pair(w.state.deadCount, w.state.tornTotal)
        }
        println("таран 8×7: с клином погибло ${result[0].first}, порвано ${result[0].second}; " +
            "без клина ${result[1].first} и ${result[1].second}")
        assertTrue("без клина тело повреждено (сцена проверяет то, что должна): ${result[1]}",
            result[1].first + result[1].second > 0)
        assertTrue("с клином повреждений меньше: ${result[0]} против ${result[1]}",
            result[0].first + result[0].second < result[1].first + result[1].second)
    }
}
