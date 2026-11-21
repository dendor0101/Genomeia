package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * СВЯЗЬ: ядро расстояния против ответов, выведенных на бумаге.
 *
 * Ядро — один проход XPBD на подшаг: поправка длины −(len − rest) / (w + α̃), где w — сумма
 * обратных масс концов, α̃ = α / h² — податливость связи, приведённая к шагу. Отсюда:
 *  - при α = 0 связь за проход встаёт ровно на длину покоя;
 *  - поправка делится между концами по обратным массам, центр масс пары не сдвигается;
 *  - под постоянной силой F равновесное растяжение после прохода равно F · α — закон Гука,
 *    причём ровно, при любом шаге;
 *  - две массы на связи колеблются по закону u₊ = β (2u − u₋), β = α̃ / (w + α̃): период
 *    2π / arccos √β подшагов и затухание √β за подшаг; при h → 0 это частота √(w / α).
 */
class LinkAnalyticTest {
    private fun solve(px: DoubleArray, py: DoubleArray, w: DoubleArray, rest: Double, alpha: Double, dead: Boolean = false) =
        solveDistanceConstraint(0, 0, px, py, w, intArrayOf(0), intArrayOf(1), doubleArrayOf(rest), intArrayOf(-1),
            booleanArrayOf(dead), DoubleArray(0), alpha)

    @Test fun `жёсткая связь за один проход встаёт на длину покоя`() {
        val px = doubleArrayOf(0.0, 1.5); val py = doubleArrayOf(0.0, 0.0)
        solve(px, py, doubleArrayOf(1.0, 1.0), 1.0, 0.0)
        assertEquals(0.25, px[0], 1e-15); assertEquals(1.25, px[1], 1e-15)
        assertEquals(0.0, py[0], 0.0); assertEquals(0.0, py[1], 0.0)
    }

    @Test fun `сжатая связь расталкивает концы так же, как растянутая стягивает`() {
        val px = doubleArrayOf(0.0, 0.5); val py = doubleArrayOf(0.0, 0.0)
        solve(px, py, doubleArrayOf(1.0, 1.0), 1.0, 0.0)
        assertEquals(-0.25, px[0], 1e-15); assertEquals(0.75, px[1], 1e-15)
    }

    @Test fun `поправка идёт вдоль связи при любом её наклоне`() {
        val px = doubleArrayOf(0.0, 3.0); val py = doubleArrayOf(0.0, 4.0)   // длина 5, покой 2.5
        solve(px, py, doubleArrayOf(1.0, 1.0), 2.5, 0.0)
        assertEquals(0.75, px[0], 1e-15); assertEquals(1.0, py[0], 1e-15)
        assertEquals(2.25, px[1], 1e-15); assertEquals(3.0, py[1], 1e-15)
        assertEquals(2.5, sqrt((px[1] - px[0]) * (px[1] - px[0]) + (py[1] - py[0]) * (py[1] - py[0])), 1e-14)
    }

    @Test fun `поправка делится по обратным массам, и центр масс пары стоит на месте`() {
        val px = doubleArrayOf(0.0, 1.5); val py = doubleArrayOf(0.0, 0.0)
        val w = doubleArrayOf(1.0, 3.0)   // массы 1 и 1/3
        solve(px, py, w, 1.0, 0.0)
        assertEquals(0.125, px[0], 1e-15); assertEquals(1.5 - 0.375, px[1], 1e-15)
        // Σ m·Δx = 0.
        assertEquals(0.0, (px[0] - 0.0) / w[0] + (px[1] - 1.5) / w[1], 1e-15)
    }

    @Test fun `закреплённый конец не двигается, свободный берёт всю поправку`() {
        val px = doubleArrayOf(0.0, 1.5); val py = doubleArrayOf(0.0, 0.0)
        solve(px, py, doubleArrayOf(0.0, 2.0), 1.0, 0.0)
        assertEquals(0.0, px[0], 0.0); assertEquals(1.0, px[1], 1e-15)
    }

    @Test fun `оба конца закреплены - связь ничего не делает`() {
        val px = doubleArrayOf(0.0, 1.5); val py = doubleArrayOf(0.0, 0.0)
        solve(px, py, doubleArrayOf(0.0, 0.0), 1.0, 0.0)
        assertEquals(0.0, px[0], 0.0); assertEquals(1.5, px[1], 0.0)
    }

    @Test fun `податливая связь убирает за проход долю w на w плюс альфа`() {
        val px = doubleArrayOf(0.0, 1.5); val py = doubleArrayOf(0.0, 0.0)
        val alpha = 3.0   // при w = 2 убирается 2/5 растяжения
        solve(px, py, doubleArrayOf(1.0, 1.0), 1.0, alpha)
        assertEquals(0.5 * (1.0 - 2.0 / 5.0), (px[1] - px[0]) - 1.0, 1e-15)
    }

    @Test fun `порванная связь не тянет`() {
        val px = doubleArrayOf(0.0, 1.5); val py = doubleArrayOf(0.0, 0.0)
        solve(px, py, doubleArrayOf(1.0, 1.0), 1.0, 0.0, dead = true)
        assertEquals(0.0, px[0], 0.0); assertEquals(1.5, px[1], 0.0)
    }

    @Test fun `связь нулевой длины не даёт NaN`() {
        val px = doubleArrayOf(1.0, 1.0); val py = doubleArrayOf(2.0, 2.0)
        solve(px, py, doubleArrayOf(1.0, 1.0), 1.0, 0.0)
        assertTrue(px.all { it.isFinite() } && py.all { it.isFinite() })
    }

    /**
     * Частица с обратной массой [w] на связи с закреплённой точкой, под постоянной силой [force]:
     * подшаг — скорость от силы, положение от скорости, проход связи, скорость по смещению (и
     * гашение, чтобы колебания улеглись). Возвращает равновесное растяжение после прохода связи.
     */
    private fun stretchUnder(force: Double, w: Double, h: Double): Double {
        val rest = 1.0
        val alpha = SOFT_COMPLIANCE / (h * h)
        val px = doubleArrayOf(0.0, rest); val py = doubleArrayOf(0.0, 0.0)
        var v = 0.0
        repeat(20000) {
            val prev = px[1]
            v += force * w * h
            px[1] += v * h
            solve(px, py, doubleArrayOf(0.0, w), rest, alpha)
            v = (px[1] - prev) / h * 0.9
        }
        return px[1] - rest
    }

    @Test fun `закон Гука - растяжение под постоянной силой равно силе на податливость, при любом шаге`() {
        for (h in doubleArrayOf(DT / 6, DT / 16, DT / 64)) for (force in doubleArrayOf(1.0, 25.0)) for (w in doubleArrayOf(1.0, 4.0)) {
            val stretch = stretchUnder(force, w, h)
            assertEquals("h = $h, F = $force, w = $w", force * SOFT_COMPLIANCE, stretch, 1e-9 * force * SOFT_COMPLIANCE + 1e-15)
        }
    }

    /** Свободные колебания двух масс на связи: растяжение по подшагам, начиная с единичного. */
    private fun oscillate(w1: Double, w2: Double, h: Double, steps: Int): DoubleArray {
        val rest = 1.0
        val alpha = SOFT_COMPLIANCE / (h * h)
        val px = doubleArrayOf(0.0, rest + 1e-3); val py = doubleArrayOf(0.0, 0.0)
        var v0 = 0.0; var v1 = 0.0
        val out = DoubleArray(steps + 1)
        out[0] = px[1] - px[0] - rest
        for (s in 1..steps) {
            val p0 = px[0]; val p1 = px[1]
            px[0] += v0 * h; px[1] += v1 * h
            solve(px, py, doubleArrayOf(w1, w2), rest, alpha)
            v0 = (px[0] - p0) / h; v1 = (px[1] - p1) / h
            out[s] = px[1] - px[0] - rest
        }
        return out
    }

    @Test fun `две массы на связи колеблются по своему разностному закону`() {
        val h = DT / 6; val w1 = 1.0; val w2 = 0.5
        val alphaStep = SOFT_COMPLIANCE / (h * h)
        val beta = alphaStep / (w1 + w2 + alphaStep)
        val u = oscillate(w1, w2, h, 40)
        // Первый подшаг — из покоя: u₁ = β·u₀; дальше u₊ = β (2u − u₋).
        assertEquals(beta * u[0], u[1], 1e-15)
        for (s in 1 until 40) assertEquals("подшаг $s", beta * (2 * u[s] - u[s - 1]), u[s + 1], 1e-12 * abs(u[0]))
    }

    /** Период колебаний в секундах по переходам растяжения через ноль сверху вниз. */
    private fun period(u: DoubleArray, h: Double): Double {
        val crossings = ArrayList<Double>()
        for (s in 1 until u.size) if (u[s - 1] > 0.0 && u[s] <= 0.0) crossings.add((s - 1 + u[s - 1] / (u[s - 1] - u[s])) * h)
        require(crossings.size >= 3) { "колебаний не набралось: ${crossings.size}" }
        return (crossings.last() - crossings.first()) / (crossings.size - 1)
    }

    @Test fun `период двух масс сходится к двум пи на корень из податливости на сумму обратных масс`() {
        val w1 = 1.0; val w2 = 1.0
        val exact = 2 * Math.PI * sqrt(SOFT_COMPLIANCE / (w1 + w2))
        var before = Double.MAX_VALUE
        for (divide in intArrayOf(64, 128, 256, 512)) {
            val h = exact / divide
            val error = abs(period(oscillate(w1, w2, h, 12 * divide), h) / exact - 1.0)
            assertTrue("шаг вдвое меньше — ошибка периода не растёт: было $before, стало $error", error < before)
            before = error
        }
        assertTrue("на шаге в 1/512 периода ошибка меньше десятой доли процента: $before", before < 1e-3)
    }

    @Test fun `на шаге игры период и затухание связи - по формуле разностного закона`() {
        // Шесть подшагов на тик: связь двух клеток единичной массы.
        val h = DT / 6; val w = 2.0
        val alphaStep = SOFT_COMPLIANCE / (h * h)
        val beta = alphaStep / (w + alphaStep)
        val u = oscillate(1.0, 1.0, h, 60)
        val steps = period(u, h) / h
        assertEquals("период в подшагах", 2 * Math.PI / acos(sqrt(beta)), steps, 0.05 * steps)
        // За период амплитуда падает в β^(период/2) раз: решатель сам гасит колебание связи.
        var peak = 0.0
        val from = steps.toInt()
        for (s in from..minOf(u.size - 1, 2 * from)) peak = maxOf(peak, abs(u[s]))
        val expected = abs(u[0]) * Math.pow(beta, steps / 2)
        assertTrue("амплитуда через период: вышло $peak, по формуле около $expected", peak < 3 * expected && peak > expected / 3)
    }

    // --- мышца: длина покоя и податливость по команде клетки ---

    private val inner = booleanArrayOf(false)
    private val rim = booleanArrayOf(true)

    @Test fun `сокращённая мышца тянет к длине покоя, умноженной на команду`() {
        // Команда 0.5: цель — половина длины покоя. Отставание 0.06 длины покоя — тянет на всё отставание.
        assertEquals(-(0.56 - 0.5) / 2.0, muscleLinkCorrection(0.56, 1.0, 0.5, 2.0, 0.0, inner, 0), 1e-15)
        assertEquals(0.0, muscleLinkCorrection(0.5, 1.0, 0.5, 2.0, 0.0, inner, 0), 1e-15)
        // Отставание больше MUSCLE_MAX_PULL длины покоя: тянет как связь, растянутая на эту долю, и не сильнее.
        assertEquals(-MUSCLE_MAX_PULL / 2.0, muscleLinkCorrection(1.0, 1.0, 0.5, 2.0, 0.0, inner, 0), 1e-15)
        assertEquals(-MUSCLE_MAX_PULL / 2.0, muscleLinkCorrection(0.7, 1.0, 0.5, 2.0, 0.0, inner, 0), 1e-15)
        // Сжатая глубже цели расталкивается без потолка.
        assertEquals(0.3 / 2.0, muscleLinkCorrection(0.2, 1.0, 0.5, 2.0, 0.0, inner, 0), 1e-15)
        // Команда 1 — обычная связь.
        assertEquals(-(1.2 - 1.0) / (2.0 + 3.0), muscleLinkCorrection(1.2, 1.0, 1.0, 2.0, 3.0, inner, 0), 1e-15)
    }

    @Test fun `расслабленная мышца не толкает и вчетверо податливее на растяжение`() {
        // Команда 1.5 — полное расслабление. В покое поправки нет: расслабленная мышца сама тело не раздвигает.
        assertEquals(0.0, relaxedLinkCorrection(1.0, 1.0, 1.5, 2.0, 3.0, inner, 0), 0.0)
        // Сжатие держит как обычная связь.
        assertEquals(0.1 / (2.0 + 3.0), relaxedLinkCorrection(0.9, 1.0, 1.5, 2.0, 3.0, inner, 0), 1e-15)
        // Растяжение в пределах 5%: податливость вчетверо выше.
        assertEquals(-(0.04) / (2.0 + 4 * 3.0), relaxedLinkCorrection(1.04, 1.0, 1.5, 2.0, 3.0, inner, 0), 1e-15)
        assertEquals(4 * 3.0, tensileCompliance(3.0, 1.5), 1e-15)
        assertEquals(2.5 * 3.0, tensileCompliance(3.0, 1.25), 1e-15)
        assertEquals(3.0, tensileCompliance(3.0, 1.0), 1e-15)
    }

    @Test fun `предел длины - 105 процентов, у расслабленной мышцы - до 125`() {
        assertEquals(LINK_MAX_STRETCH, linkMaxLength(1.0, 1.0), 0.0)
        assertEquals(LINK_MAX_STRETCH, linkMaxLength(1.0, 0.5), 0.0)
        assertEquals(1.25, linkMaxLength(1.0, 1.5), 1e-15)
        assertEquals((LINK_MAX_STRETCH + 1.25) / 2, linkMaxLength(1.0, 1.25), 1e-15)
        assertEquals(2 * LINK_MAX_STRETCH, linkMaxLength(2.0, 1.0), 1e-15)
    }

    @Test fun `расслабленная внутренняя связь за пределом возвращается на предел, а у контура - нет`() {
        // Длина 1.4 при пределе 1.25: внутренняя связь жёстко возвращается на предел (поправка по сумме обратных масс).
        val back = relaxedLinkCorrection(1.4, 1.0, 1.5, 2.0, 3.0, inner, 0)
        assertEquals((1.25 - 1.4) / 2.0, back, 1e-15)
        // У связи контура предел решает следующая стадия: здесь только упругая поправка.
        val elastic = relaxedLinkCorrection(1.4, 1.0, 1.5, 2.0, 3.0, rim, 0)
        assertEquals(-(0.4) / (2.0 + 4 * 3.0), elastic, 1e-15)
    }
}
