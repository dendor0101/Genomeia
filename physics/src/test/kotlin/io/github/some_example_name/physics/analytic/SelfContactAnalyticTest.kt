package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * КАСАНИЕ КЛЕТОК ОДНОГО ОРГАНИЗМА НЕ ВЫСТРЕЛИВАЕТ (BoundaryContacts.cFixed, вид «свой», 17.11.2026).
 *
 * Разбор причин гибели по каталогу стендов (KillCauses): семь погибших клеток из десяти гибнут не о
 * чужое тело и не о рельеф, а о клетку СВОЕГО тела. Смятая ударом или мышцей ткань упирается сама в
 * себя; касание снимает перекрытие, и снятое становится скоростью «перекрытие / подшаг» — ткань
 * разбрасывает, она разбалтывается, и следующий заход сминает её глубже, до гибели. Положения у
 * таких пар решаются прежним мягким законом, а скорость после толчка приводится к положенной: доле
 * отскока, если пара сближалась, и прежней, если расходилась. Между клетками РАЗНЫХ тел закон прежний.
 */
class SelfContactAnalyticTest {
    @Test fun `у касания клеток одного тела свой вид, у касания клеток разных тел - обычный`() {
        // Червь 12×3, целиком мышца, сжимается до предела: связанные соседи сходятся на полсвязи, а клетки
        // контура через одну, между собой не связанные, упираются друг в друга. Рядом — две цепочки по
        // две клетки, разные тела; их каждый тик толкают друг к другу.
        val w = gameWorld().also { it.sleepOn = false }
        val def = patch(12, 3)
        val first = w.bodyFirst(w.addBody(def))
        val p = w.bodyFirst(w.addBody(chain(2, x0 = 2.0f, y0 = 1.0f + T_LINK)))
        val q = w.bodyFirst(w.addBody(chain(2, x0 = 2.0f, y0 = 1.0f - T_LINK)))
        repeat(5) { w.step() }
        for (i in first until first + def.count) w.setCellScale(i, 0.5f)
        val ct = w.state.contacts!!
        var own = 0; var ownOther = 0; var foreign = 0; var foreignOther = 0
        // Касания смотрим после КАЖДОГО подшага: список касаний — последнего подшага. Цепочки толкают раз в тик, они
        // касаются в одном-двух подшагах и отскакивают; пока подшагов было 16, касание попадало и в последний подшаг
        // тика, при 20 (24.11) — только в первые, и проверка по концу тика видела ноль касаний.
        w.state.dbgSubstepHook = {
            for (c in 0 until ct.contactCount) {
                val i = ct.contactI(c); val j = ct.contactJ(c)
                val inWorm = i in first until first + def.count && j in first until first + def.count
                val special = ct.contactIsOwn(c)
                if (inWorm) { if (special) own++ else ownOther++ }
                else if ((i < q) != (j < q)) { if (special || ct.contactIsFixed(c)) foreignOther++ else foreign++ }
            }
        }
        repeat(40) {
            for (k in 0..1) { w.setVelocity(p + k, 0.0f, -linksPerTick(0.5f)); w.setVelocity(q + k, 0.0f, linksPerTick(0.5f)) }
            w.step()
        }
        assertEquals("клетки целы", 0, w.state.deadCount)
        assertTrue("клетки червя касались друг друга: $own", own > 0)
        assertEquals("у касания клеток одного тела — свой вид", 0, ownOther)
        assertTrue("цепочки касались: $foreign", foreign > 0)
        assertEquals("у касания клеток разных тел — обычный вид", 0, foreignOther)
    }

    private class Run(val dead: Int, val torn: Int, val shortest: Float, val longest: Float)

    /** Червь [cols]×[rows], целиком мышца, в среде: шесть раз подряд сжимается до предела и растягивается, по 30 тиков. */
    private fun pumpWorm(cols: Int, rows: Int): Run {
        val w = gameWorld().also { it.sleepOn = false }
        val def = patch(cols, rows)
        val first = w.bodyFirst(w.addBody(def))
        val st = w.state
        val left = first; val right = first + cols - 1
        val rest = w.distance(left, right)
        repeat(5) { w.step() }
        var torn = 0; var shortest = 1.0f; var longest = 1.0f
        for (k in 0 until 6) {
            val scale = if (k % 2 == 0) 0.5f else 1.5f
            for (i in first until first + def.count) w.setCellScale(i, scale)
            repeat(30) {
                w.step()
                torn += w.edgeDeathCount
                if (!st.cellDead[left] && !st.cellDead[right]) {
                    val len = w.distance(left, right) / rest
                    shortest = minOf(shortest, len); longest = maxOf(longest, len)
                }
            }
        }
        return Run(st.deadCount, torn, shortest, longest)
    }

    @Test fun `мышечный червь 20 на 4 шесть раз сжимается и растягивается - ни гибели, ни разрывов`() {
        // Как стенд free/muscle/rect20x4-mAll/swapfast. Пока касание своих клеток выстреливало, здесь гибла
        // 21 клетка из 80 и рвалась 71 связь (на стенде — 25 и 81): сжатая ткань разбалтывалась и входила в себя.
        val r = pumpWorm(20, 4)
        println("червь 20×4: погибло ${r.dead}, порвано ${r.torn}, длина от ${r.shortest} до ${r.longest} исходной")
        assertEquals("погибло клеток", 0, r.dead)
        assertEquals("порвано связей", 0, r.torn)
        // Мышца при этом работает в обе стороны. Растяжение — не тяга мышцы (с 21.11 она только расслабляется), а выбег
        // отпущенного сжатого тела по расслабленной ткани: с мягкой тканью (шесть подшагов) тело вытягивалось за 1.2
        // исходной длины, с жёсткой (24.11) — до 1.17 при 16 подшагах и 1.16 при 20. Проверка — что фаза растяжения в
        // тесте есть.
        assertTrue("сжатие: длина тела к исходной ${r.shortest}", r.shortest < 0.75f)
        assertTrue("растяжение: длина тела к исходной ${r.longest}", r.longest > 1.1f)
    }
}
