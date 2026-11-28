package io.github.some_example_name.physics.lab.stands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Самопроверка правил, появившихся 15.11: дрожь, допуск повреждений долей и повреждения по
 * сценарию. Как и в RuleMonitorTest, нарушение ставится прямо в состояние мира: проверяется
 * монитор, а не физика.
 */
class RuleMonitorDamageTest {
    private val k = PHYSICS_PER_GAME

    private class Stage(val w: StandWorld, val m: RuleMonitor) {
        fun tick(count: Int = 1, spoil: (Int) -> Unit = {}) { repeat(count) { w.step(); spoil(it); m.afterTick() } }
        fun failed(rule: Rule): Boolean = m.results[rule]!!.failed
    }

    private fun stage(limits: Limits = Limits.INTACT, ticks: Int = 400, setup: StandWorld.() -> Unit): Stage {
        val w = StandWorld()
        w.setup()
        val m = RuleMonitor(w, limits, ticks)
        m.begin()
        return Stage(w, m)
    }

    @Test
    fun `частица, которая каждый тик прыгает туда-сюда, поймана как дрожащая`() {
        lateinit var b: StandBody
        val s = stage { b = body(Shapes.square(6), 0.0, 0.0); noSleep() }
        s.tick(3)
        assertFalse(s.failed(Rule.JITTER))
        val p = b.particle(0)
        val x0 = s.w.world.x[p]; val y0 = s.w.world.y[p]
        // Сдвиг на 0.03 связи то вправо, то влево — втрое больше порога заметности.
        val step = 0.03 * PHYSICS_LINK
        s.tick(JITTER_TICKS + 12) { t -> s.w.world.place(p, (x0 + if (t % 2 == 0) step else -step).toFloat(), y0) }
        assertTrue("дрожь частицы не поймана; самая долгая серия ${s.m.jitterLongest}", s.failed(Rule.JITTER))
        assertTrue(s.m.jitterTicks > 0)
    }

    @Test
    fun `ровное движение и медленное колебание дрожью не считаются`() {
        lateinit var b: StandBody
        val s = stage { b = body(Shapes.square(6), 0.0, 0.0); noSleep() }
        s.tick(2)
        val p = b.particle(0)
        val x0 = s.w.world.x[p]; val y0 = s.w.world.y[p]
        // Частицу ведут в одну сторону, потом качают с периодом в тридцать тиков — как ткань после удара.
        s.tick(40) { t -> s.w.world.place(p, (x0 + 0.02 * PHYSICS_LINK * t).toFloat(), y0) }
        s.tick(120) { t -> s.w.world.place(p, (x0 + 0.4 * PHYSICS_LINK * Math.sin(2 * Math.PI * t / 30.0)).toFloat(), y0) }
        assertFalse("ровный ход или плавное колебание приняты за дрожь; серия ${s.m.jitterLongest}", s.failed(Rule.JITTER))
    }

    @Test
    fun `дрожь мельче порога заметности не считается`() {
        lateinit var b: StandBody
        val s = stage { b = body(Shapes.square(6), 0.0, 0.0); noSleep() }
        s.tick(2)
        val p = b.particle(0)
        val x0 = s.w.world.x[p]; val y0 = s.w.world.y[p]
        val step = 0.2 * JITTER_STEP * PHYSICS_LINK
        s.tick(60) { t -> s.w.world.place(p, (x0 + if (t % 2 == 0) step else -step).toFloat(), y0) }
        assertFalse(s.failed(Rule.JITTER))
    }

    @Test
    fun `допуск удара считается долей связей и клеток сцены`() {
        val shape = Shapes.square(10)
        // Удар на двух связях за тик: порваться может 4% связей, погибнуть — 2% клеток.
        val limits = Limits.hit(2.0)
        assertEquals(0.04, limits.tornShare, 1e-9)
        lateinit var b: StandBody
        val s = stage(limits) { b = body(shape, 0.0, 0.0) }
        s.tick(2)
        assertEquals((0.04 * shape.linkCount).toInt(), s.m.allowedTorn)
        assertEquals((0.02 * shape.count).toInt(), s.m.allowedDead)
        // Гибнет одна угловая клетка: это в допуске.
        val corner = shape.extreme(1.0, 1.0)
        s.tick { if (it == 0) s.w.world.kill(b.particle(corner)) }
        s.tick(3)
        assertFalse("одна погибшая клетка из ${shape.count} — в допуске ${s.m.allowedDead}", s.failed(Rule.DEATHS))
        assertFalse("порвано ${s.m.torn} при допуске ${s.m.allowedTorn}", s.failed(Rule.TEARS))
        // Гибнет целый ряд: допуск превышен.
        val row = edgeCells(shape, 0.0, -1.0)
        s.tick { if (it == 0) for (c in row) s.w.world.kill(b.particle(c)) }
        s.tick(3)
        assertTrue("гибель ряда клеток сверх допуска не поймана", s.failed(Rule.DEATHS))
        assertTrue("разрывы сверх допуска не пойманы: порвано ${s.m.torn} при допуске ${s.m.allowedTorn}", s.failed(Rule.TEARS))
    }

    @Test
    fun `скорость удара задаёт допуск - ниже одной связи за тик ничего, от шести без ограничения`() {
        assertTrue(Limits.hit(0.6) === Limits.INTACT)
        assertEquals(0.0, Limits.damageShare(1.0), 0.0)
        assertEquals(0.04, Limits.damageShare(2.0), 1e-9)
        assertEquals(0.36, Limits.damageShare(4.0), 1e-9)
        assertEquals(1.0, Limits.damageShare(6.0), 0.0)
        assertTrue(Limits.hit(8.0) === Limits.CRASH)
        assertTrue("допуск обязан расти со скоростью", (10..60).all { Limits.damageShare(it / 10.0) <= Limits.damageShare((it + 1) / 10.0) })
    }

    @Test
    fun `разрез и гибель по сценарию телу в вину не ставятся`() {
        val shape = Shapes.rect(12, 4)
        lateinit var b: StandBody
        val s = stage { b = body(shape, 0.0, 0.0) }
        s.tick(2)
        val left = sideCells(shape, -1.0, 0.0).toHashSet()
        var cutLinks = 0
        s.tick { if (it == 0) cutLinks = s.w.cut(b) { a, c -> (a in left) != (c in left) } }
        s.tick(5)
        assertTrue("разрез ничего не разрезал", cutLinks > 0)
        assertEquals("тело после разреза должно стать двумя кусками", 2, s.w.pieces(b))
        assertEquals("разрез по сценарию посчитан как повреждение", 0, s.m.torn)
        assertFalse(s.failed(Rule.TEARS))
        val strip = edgeCells(shape, 1.0, 0.0)
        s.tick { if (it == 0) s.w.killCells(b, strip) }
        s.tick(5)
        assertFalse("гибель по сценарию посчитана как гибель от физики", s.failed(Rule.DEATHS))
        assertEquals("связи погибших по сценарию клеток посчитаны как разрывы", 0, s.m.torn)
        // А настоящая гибель после этого по-прежнему ловится.
        s.tick { if (it == 0) s.w.world.kill(b.particle(shape.extreme(-1.0, -1.0))) }
        s.tick(3)
        assertTrue("гибель клетки не по сценарию не поймана", s.failed(Rule.DEATHS))
    }

    @Test
    fun `зажим держит клетки и ведёт их, куда велит сценарий`() {
        val shape = Shapes.rect(10, 4)
        val w = StandWorld()
        val b = w.body(shape, 0.0, 0.0)
        val cells = edgeCells(shape, 1.0, 0.0)
        val x0 = cells.map { w.gameX(b.particle(it)) }
        val c = w.clamp(b, cells)
        repeat(30) { t -> c.moveTo(0.01 * (t + 1), 0.0); w.step() }
        for ((i, cell) in cells.withIndex()) assertEquals("клетка зажима $cell не там, куда её вели", x0[i] + 0.30, w.gameX(b.particle(cell)), 1e-3)
        c.release()
        repeat(5) { w.step() }
        assertEquals(0f, w.world.grip(b.particle(cells[0])), 0f)
    }

    @Test
    fun `присоска с полной хваткой стоит под толчком`() {
        val shape = Shapes.square(6)
        val w = StandWorld()
        val b = w.body(shape, 0.0, 0.0)
        val cell = shape.extreme(-1.0, 0.0)
        val p = b.particle(cell)
        w.grip(b, cell, 1f)
        val x0 = w.world.x[p]; val y0 = w.world.y[p]
        w.kick(b, 1.0, 0.0)
        repeat(10) { w.step() }
        // Тело толкнули на связь за тик, а клетка с хваткой 1 ушла меньше чем на сотую связи.
        assertTrue("присоска ушла на ${Math.hypot((w.world.x[p] - x0).toDouble(), (w.world.y[p] - y0).toDouble()) / PHYSICS_LINK} связи",
            Math.hypot((w.world.x[p] - x0).toDouble(), (w.world.y[p] - y0).toDouble()) / PHYSICS_LINK < 0.01)
        assertTrue(k > 0)
    }
}
