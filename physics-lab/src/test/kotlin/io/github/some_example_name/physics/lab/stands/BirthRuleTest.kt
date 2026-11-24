package io.github.some_example_name.physics.lab.stands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * САМОПРОВЕРКА ПРАВИЛА «КЛЕТКИ РОЖДАЮТСЯ НА СВОЁМ МЕСТЕ» (Rule.BIRTH, BirthLog): клетка, рождённая далеко от тела,
 * поймана, а честный рост — нет, и при этом правило действительно мерило рождения (пустая проверка не зелёная).
 */
class BirthRuleTest {
    @Test
    fun `клетка, рождённая в десяти связях от тела, поймана`() {
        val w = StandWorld()
        val shape = Shapes.square(4)
        val seed = shape.nearest(0.0, 0.0)
        // Рост сам не идёт до конца теста: рождение ставит сценарий.
        val g = w.growing(shape, 0.0, 0.0, seed = seed, start = 1000, period = 1000, batch = 1)
        val k = shape.adj[shape.adjStart[seed]]
        val m = RuleMonitor(w, GROW, 50)
        m.begin()
        w.at(0) {
            val st = w.world.state
            val z = g.particle(seed)
            w.world.activate(g.particle(k), (st.px[z] + 10 * GAME_LINK * PHYSICS_PER_GAME).toFloat(), st.py[z])
            w.births.note(w, g, k)
        }
        repeat(3) { w.step(); m.afterTick() }
        assertTrue("рождение в десяти связях от тела не поймано", m.results[Rule.BIRTH]!!.failed)
        assertTrue("зазор ${m.birthGapWorst}", m.birthGapWorst > 9.0)
        assertTrue("лишняя длина ${m.birthExcessWorst}", m.birthExcessWorst > 8.0)
    }

    @Test
    fun `честный рост квадрата правило проходит, и рождения измерены`() {
        val shape = Shapes.square(6)
        val seed = shape.nearest(0.0, 0.0)
        val run = StandRun(Scenario("t/grow", "тест", "рост", "квадрат растёт из середины", "растёт без нарушений",
            shape.count * 3 + 200, GROW) { growing(shape, 0.0, 0.0, seed = seed, period = 3, batch = 1) }).runAll()
        val m = run.monitor
        assertFalse("честный рост пойман как рождение не на месте: ${m.results[Rule.BIRTH]!!.text}", m.results[Rule.BIRTH]!!.failed)
        // Делением клетка рождается на десятой доле пути от родителя (DIVISION_START_FRACTION): зазор — около 0.1 связи.
        // Ноль значил бы, что рождения не мерились вовсе.
        assertTrue("правило не мерило рождения: худший зазор ${m.birthGapWorst}", m.birthGapWorst > 0.05)
        assertTrue("зазор ${m.birthGapWorst} при пороге $BIRTH_GAP", m.birthGapWorst <= BIRTH_GAP)
        assertTrue("лишняя длина ${m.birthExcessWorst} при пороге $BIRTH_EXCESS", m.birthExcessWorst <= BIRTH_EXCESS)
    }
}
