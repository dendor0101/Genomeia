package io.github.some_example_name.physics.lab.stands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * САМОПРОВЕРКА ПРАВИЛА «СЦЕНА НЕ ВХОЛОСТУЮ».
 *
 * Сцена, в которой не случилось то, ради чего она поставлена, обязана покраснеть этим правилом —
 * как бы зелены ни были остальные. И наоборот: сцена, где встреча состоялась (в том числе
 * внутри одного тика, с гибелью налетевшей частицы), пустой считаться не должна.
 */
class PremiseTest {
    private fun run(ticks: Int, limits: Limits = Limits.INTACT, setup: StandWorld.() -> Unit): StandRun =
        StandRun(Scenario("t/premise", "тест", "условие сцены", "", "", ticks, limits, setup = setup)).runAll()

    private fun StandRun.vacant(): Boolean = monitor.results[Rule.PREMISE]!!.failed
    private fun StandRun.why(): String = monitor.results[Rule.PREMISE]!!.text

    @Test
    fun `два тела, которые разлетелись не встретившись, - сцена вхолостую`() {
        val r = run(settle(0.6)) {
            val a = body(Shapes.square(6), -6.0, 0.0); body(Shapes.square(6), 6.0, 0.0)
            kick(a, -0.6, 0.0)   // прочь от соседа
        }
        assertTrue("сцена без встречи обязана покраснеть как пустая", r.vacant())
        assertFalse("пустая сцена не считается прошедшей", r.passed)
        assertTrue(r.why(), r.why().contains("не случилось"))
        // Остальные правила при этом зелёные: именно так пустая сцена и пряталась.
        assertEquals(listOf(Rule.PREMISE), r.monitor.results.values.filter { it.failed }.map { it.rule })
    }

    @Test
    fun `два тела, которые столкнулись, - сцена не вхолостую`() {
        val r = run(settle(0.6)) { duel(Shapes.square(6), Shapes.square(6), 0.6) }
        assertFalse("встреча состоялась: ${r.why()}", r.vacant())
    }

    @Test
    fun `сцена без единого воздействия - покой, а не пустая сцена`() {
        val r = run(150) { body(Shapes.square(6), 0.0, 0.0) }
        assertFalse(r.vacant())
        assertTrue("покой объявляется сам", r.w.idleReason != null)
    }

    @Test
    fun `пуля, погибшая в тик удара, считается долетевшей`() {
        // 7.5 связи за тик: частица долетает, бьёт и гибнет внутри одного тика.
        val r = run(120, Limits.CRASH) {
            val s = Shapes.square(8).boneAll()
            body(s, 0.0, 0.0)
            ball(-(s.reach + START_GAP + 1.0), 0.0, 7.5, 0.0)
        }
        assertFalse("пуля долетела до тела: ${r.why()}", r.vacant())
    }

    @Test
    fun `быстрый встречный удар виден, даже если на границах тика тела врозь`() {
        val r = run(CRASH_TICKS, Limits.CRASH) { duelBoth(Shapes.disc(3), Shapes.disc(3), SPEED_CAP) }
        assertFalse("тела на потолке скорости встретились: ${r.why()}", r.vacant())
    }

    @Test
    fun `тела, поставленные рядом, не должны касаться - касание ловится`() {
        val shape = Shapes.square(6)
        val clean = run(100) {
            val half = separation(shape, shape, 0.8) / 2
            mustNotTouch(body(shape, -half, 0.0), body(shape, half, 0.0))
        }
        assertFalse("тела рядом не касались: ${clean.why()}", clean.vacant())
        val touched = run(100) {
            val half = separation(shape, shape, 0.55) / 2   // с перекрытием кругов касания
            mustNotTouch(body(shape, -half, 0.0), body(shape, half, 0.0))
        }
        assertTrue("касание тел, поставленных «рядом», обязано быть поймано", touched.vacant())
        assertTrue(touched.why(), touched.why().contains("по постановке не должно"))
    }

    @Test
    fun `тело у стены - дошло или не дошло`() {
        val wall = terrainLayouts().first { it.id == "wall" }
        val shape = Shapes.square(6)
        fun scene(speed: Double) = run(settle(0.6)) {
            place(wall, shape.halfWidth + GAME_RADIUS + START_GAP + 2.0)
            val b = body(shape, 0.0, 0.0)
            kick(b, speed, 0.0)
        }
        assertFalse("тело, летящее в стену, до неё дошло", scene(0.6).vacant())
        assertTrue("тело, летящее от стены, до неё не дошло — сцена пустая", scene(-0.6).vacant())
    }

    @Test
    fun `рост двух тел рядом - встретились или нет`() {
        val disc = Shapes.disc(4)
        val center = disc.nearest(0.0, 0.0)
        fun scene(apart: Double) = run(SLOW.ticks(disc, center) + 200, Limits.DRIVEN.copy(sleep = false, shape = false)) {
            growing(disc, -apart / 2, 0.0, seed = center, period = SLOW.period, batch = SLOW.batch)
            growing(disc, apart / 2, 0.0, seed = center, period = SLOW.period, batch = SLOW.batch)
        }
        assertFalse("круги радиуса 4 в трёх единицах друг от друга встречаются", scene(3.0).vacant())
        assertTrue("круги в двадцати единицах друг от друга не встречаются — сцена пустая", scene(20.0).vacant())
    }
}
