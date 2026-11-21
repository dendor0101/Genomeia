package io.github.some_example_name.physics.lab.stands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * САМОПРОВЕРКА МОНИТОРА ПРАВИЛ: каждому правилу подстраивается нарушение, и оно обязано его
 * поймать, а чистая сцена обязана пройти все правила. Монитор — судья всех стендов; судья,
 * который молчит при нарушении, хуже отсутствия судьи, потому что зелёным сценам верят.
 *
 * Нарушение ставится прямо в состояние мира после шага (частица переносится, скорость
 * умножается): проверяется монитор, а не физика, и от свойств решателя тест не зависит.
 */
class RuleMonitorTest {
    private val k = PHYSICS_PER_GAME

    private class Stage(val w: StandWorld, val m: RuleMonitor) {
        /** Тик мира, затем порча состояния, затем взгляд монитора. */
        fun tick(count: Int = 1, spoil: () -> Unit = {}) { repeat(count) { w.step(); spoil(); m.afterTick() } }
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
    fun `чистая сцена проходит все правила`() {
        val run = StandRun(Scenario("t/rest", "тест", "покой", "квадрат стоит", "ничего не происходит", 150, Limits.INTACT) { body(Shapes.square(6), 0.0, 0.0) }).runAll()
        assertTrue("нарушены: ${run.monitor.failedRules}", run.passed)
        assertTrue("сцена покоя должна уснуть", run.monitor.sleepTick >= 0)
    }

    @Test
    fun `частица, перенесённая сквозь тело, поймана как прошедшая сквозь ткань`() {
        var ball = -1
        val s = stage { body(Shapes.square(6), 0.0, 0.0); ball = ball(-6.0, 0.1) }
        s.tick(3)
        assertFalse(s.failed(Rule.CROSS))
        val p = s.w.world.freeParticle(ball)
        s.tick { s.w.world.place(p, 6.0 * k, 0.1 * k) }
        assertTrue("перенос частицы через квадрат не пойман", s.failed(Rule.CROSS))
        assertFalse("частица снаружи тела не должна считаться сидящей в ткани", s.failed(Rule.FOREIGN))
    }

    @Test
    fun `частица мимо тела сквозь ткань не проходит`() {
        var ball = -1
        val s = stage { body(Shapes.square(6), 0.0, 0.0); ball = ball(-6.0, 5.0) }
        val p = s.w.world.freeParticle(ball)
        s.tick(3)
        s.tick { s.w.world.place(p, 6.0 * k, 5.0 * k) }
        assertFalse("частица пролетела выше тела — пересечений нет", s.failed(Rule.CROSS))
    }

    @Test
    fun `частица внутри чужого треугольника поймана`() {
        var ball = -1
        lateinit var b: StandBody
        val s = stage { b = body(Shapes.square(6), 0.0, 0.0); ball = ball(-6.0, 0.0) }
        s.tick(2)
        val t = b.shape.triangles
        val w = s.w
        val cx = (w.gameX(b.particle(t[0])) + w.gameX(b.particle(t[1])) + w.gameX(b.particle(t[2]))) / 3
        val cy = (w.gameY(b.particle(t[0])) + w.gameY(b.particle(t[1])) + w.gameY(b.particle(t[2]))) / 3
        s.tick { w.world.place(w.world.freeParticle(ball), cx * k, cy * k) }
        assertTrue("частица в середине треугольника тела не поймана", s.failed(Rule.FOREIGN))
    }

    @Test
    fun `частица в частице рельефа поймана`() {
        var ball = -1
        val s = stage { rock(0.0, 0.0); ball = ball(5.0, 0.0) }
        s.tick(2)
        assertFalse(s.failed(Rule.TERRAIN))
        s.tick { s.w.world.place(s.w.world.freeParticle(ball), 0.05 * k, 0.0) }
        assertTrue("частица в центре частицы рельефа не поймана", s.failed(Rule.TERRAIN))
    }

    @Test
    fun `частица в толще рельефа поймана`() {
        var ball = -1
        val s = stage { terrain(0.0, 0.0, 9, 9, jitter = false) { _, _ -> true }; ball = ball(-5.0, 0.0) }
        s.tick(2)
        // Середина плиты 9×9: вокруг только твёрдые клетки, частиц рельефа там нет.
        s.tick { s.w.world.place(s.w.world.freeParticle(ball), 4 * TERRAIN_STEP * k, 4 * TERRAIN_STEP * k) }
        assertTrue("частица в толще рельефа не поймана", s.failed(Rule.TERRAIN))
    }

    @Test
    fun `энергия из ниоткуда поймана`() {
        lateinit var b: StandBody
        val s = stage { b = body(Shapes.square(6), 0.0, 0.0); kick(b, 0.5, 0.0) }
        s.tick(3)
        assertFalse("честный толчок не должен нарушать правило энергии", s.failed(Rule.ENERGY))
        val st = s.w.world.state
        s.tick { for (c in 0 until b.count) { val p = b.particle(c); st.vx[p] *= 3.0; st.vy[p] *= 3.0 } }
        assertTrue("утроенная скорость тела не поймана", s.failed(Rule.ENERGY))
    }

    @Test
    fun `нечисло поймано`() {
        lateinit var b: StandBody
        val s = stage { b = body(Shapes.square(6), 0.0, 0.0) }
        s.tick(2)
        s.tick { s.w.world.state.px[b.particle(3)] = Double.NaN }
        assertTrue(s.failed(Rule.NUMBERS))
    }

    @Test
    fun `вывернутый треугольник пойман только когда держится дольше порога`() {
        lateinit var b: StandBody
        val s = stage(Limits.DRIVEN) { b = body(Shapes.square(6), 0.0, 0.0) }
        val t = b.shape.triangles
        val a = b.particle(t[0]); val c = b.particle(t[1]); val d = b.particle(t[2])
        val w = s.w.world
        // Клетка d отражается через сторону a–c каждый тик: треугольник вывернут, пока её держат.
        val flip = {
            val ex = w.x[c] - w.x[a]; val ey = w.y[c] - w.y[a]
            val px = w.x[d] - w.x[a]; val py = w.y[d] - w.y[a]
            val cross = ex * py - ey * px
            if (cross > 0.0) {
                val along = (px * ex + py * ey) / (ex * ex + ey * ey)
                val fx = w.x[a] + along * ex; val fy = w.y[a] + along * ey
                w.place(d, 2 * fx - w.x[d], 2 * fy - w.y[d])
            }
        }
        s.tick(INVERSION_TICKS, flip)
        assertFalse("короткий выворот — ещё не нарушение", s.failed(Rule.INVERSION))
        s.tick(3, flip)
        assertTrue("выворот дольше $INVERSION_TICKS тиков не пойман", s.failed(Rule.INVERSION))
    }

    @Test
    fun `смятая кость поймана`() {
        lateinit var b: StandBody
        val s = stage(Limits.DRIVEN) { b = body(Shapes.square(6).boneAll(), 0.0, 0.0) }
        s.tick(3)
        assertFalse("целая кость в покое не должна считаться смятой", s.failed(Rule.BONES))
        val w = s.w.world
        val p = b.particle(0)
        s.tick { w.place(p, w.x[p] - 1.5 * k, w.y[p] - 1.5 * k) }
        assertTrue("клетка кости, унесённая на полторы единицы, не поймана", s.failed(Rule.BONES))
    }

    @Test
    fun `гибель клетки поймана и считается`() {
        lateinit var b: StandBody
        val s = stage { b = body(Shapes.square(6), 0.0, 0.0) }
        s.tick(2)
        s.tick { s.w.world.kill(b.particle(7)) }
        assertTrue(s.failed(Rule.DEATHS))
        assertEquals(1, s.m.died)
    }

    @Test
    fun `разрыв связей пойман`() {
        lateinit var b: StandBody
        val s = stage { b = body(Shapes.square(6), 0.0, 0.0) }
        val w = s.w.world
        val p = b.particle(b.shape.extreme(1.0, 0.0))
        // Крайнюю клетку уводят от тела по связи за тик: связи растягиваются за предел и рвутся.
        val x0 = w.x[p]; val y0 = w.y[p]
        var step = 0
        s.tick(12) { step++; w.place(p, x0 + step * 0.6 * k, y0) }
        assertTrue("клетку оторвали от тела, а разрыв не пойман (порвано ${s.m.torn})", s.failed(Rule.TEARS))
    }

    @Test
    fun `сдвинутая спящая частица поймана`() {
        lateinit var b: StandBody
        val s = stage(ticks = 300) { b = body(Shapes.square(6), 0.0, 0.0) }
        s.tick(120)
        assertEquals("тело в покое должно уснуть за 120 тиков", 0, s.m.awake)
        val st = s.w.world.state
        val p = b.particle(5)
        s.tick { st.px[p] += 0.01 * k }
        assertTrue(s.failed(Rule.STILL))
    }

    @Test
    fun `сцена, которую трясут до конца, не успокоилась и не уснула`() {
        val run = StandRun(Scenario("t/shake", "тест", "тряска", "квадрат толкают каждый тик", "—", 200, Limits.DRIVEN) {
            val b = body(Shapes.square(6), 0.0, 0.0)
            during(0, 200) { t -> kick(b, if (t % 2 == 0) 0.5 else -0.5, 0.0) }
        }).runAll()
        assertTrue("тряска до последнего тика — «сцена успокоилась» обязана упасть", Rule.CALM in run.monitor.failedRules)
        assertTrue("тряска до последнего тика — «всё уснуло» обязана упасть", Rule.SLEEP in run.monitor.failedRules)
    }

    @Test
    fun `несбывшееся ожидание сцены поймано`() {
        val run = StandRun(Scenario("t/expect", "тест", "ожидание", "квадрат стоит", "—", 100, Limits.INTACT) {
            val b = body(Shapes.square(6), 0.0, 0.0)
            probe("скорость") { speedOf(b) }
            expectMaxAtLeast("тело разогналось", "скорость", 1, 100, 1.0)
        }).runAll()
        assertTrue(Rule.EXPECT in run.monitor.failedRules)
    }

    @Test
    fun `тело, оставшееся сжатым, не вернулось к форме`() {
        lateinit var b: StandBody
        val s = stage(Limits.DRIVEN, ticks = 50) { b = body(Shapes.square(6), 0.0, 0.0) }
        val w = s.w.world
        val p = b.particle(b.shape.extreme(1.0, 0.0))
        s.tick(49)
        // В последний тик крайняя клетка вдавлена внутрь на треть связи: связи короче исходных.
        s.tick { w.place(p, w.x[p] - 0.2 * k, w.y[p]) }
        s.m.finish()
        assertTrue("сжатая связь в конце сцены не поймана", s.failed(Rule.SHAPE))
    }

    @Test
    fun `повтор сцены в том же процессе побитово тот же`() {
        val scenario = Scenario("t/duel", "тест", "удар", "два квадрата", "—", 120, Limits.INTACT) { duel(Shapes.square(6), Shapes.square(6), 0.6) }
        val a = StandRun(scenario).runAll(); val b = StandRun(scenario).runAll()
        assertEquals(a.tick, b.tick)
        for (t in 0..a.tick) assertEquals("отпечаток состояния на тике $t", a.monitor.hashes[t], b.monitor.hashes[t])
    }
}
