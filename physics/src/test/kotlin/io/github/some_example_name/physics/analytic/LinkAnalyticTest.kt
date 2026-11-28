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
    private fun solve(px: FloatArray, py: FloatArray, w: FloatArray, rest: Float, alpha: Float, dead: Boolean = false) =
        solveDistanceConstraint(0, 0, px, py, w, intArrayOf(0), intArrayOf(1), floatArrayOf(rest), intArrayOf(-1),
            booleanArrayOf(dead), FloatArray(0), alpha)

    @Test fun `жёсткая связь за один проход встаёт на длину покоя`() {
        val px = floatArrayOf(0.0f, 1.5f); val py = floatArrayOf(0.0f, 0.0f)
        solve(px, py, floatArrayOf(1.0f, 1.0f), 1.0f, 0.0f)
        assertEquals(0.25f, px[0], 1e-15f); assertEquals(1.25f, px[1], 1e-15f)
        assertEquals(0.0f, py[0], 0.0f); assertEquals(0.0f, py[1], 0.0f)
    }

    @Test fun `сжатая связь расталкивает концы так же, как растянутая стягивает`() {
        val px = floatArrayOf(0.0f, 0.5f); val py = floatArrayOf(0.0f, 0.0f)
        solve(px, py, floatArrayOf(1.0f, 1.0f), 1.0f, 0.0f)
        assertEquals(-0.25f, px[0], 1e-15f); assertEquals(0.75f, px[1], 1e-15f)
    }

    @Test fun `поправка идёт вдоль связи при любом её наклоне`() {
        val px = floatArrayOf(0.0f, 3.0f); val py = floatArrayOf(0.0f, 4.0f)   // длина 5, покой 2.5
        solve(px, py, floatArrayOf(1.0f, 1.0f), 2.5f, 0.0f)
        assertEquals(0.75f, px[0], 1e-15f); assertEquals(1.0f, py[0], 1e-15f)
        assertEquals(2.25f, px[1], 1e-15f); assertEquals(3.0f, py[1], 1e-15f)
        assertEquals(2.5f, sqrt((px[1] - px[0]) * (px[1] - px[0]) + (py[1] - py[0]) * (py[1] - py[0])), 1e-14f)
    }

    @Test fun `поправка делится по обратным массам, и центр масс пары стоит на месте`() {
        val px = floatArrayOf(0.0f, 1.5f); val py = floatArrayOf(0.0f, 0.0f)
        val w = floatArrayOf(1.0f, 3.0f)   // массы 1 и 1/3
        solve(px, py, w, 1.0f, 0.0f)
        assertEquals(0.125f, px[0], 1e-15f); assertEquals(1.5f - 0.375f, px[1], 1e-15f)
        // Σ m·Δx = 0.
        assertEquals(0.0f, (px[0] - 0.0f) / w[0] + (px[1] - 1.5f) / w[1], 1e-15f)
    }

    @Test fun `закреплённый конец не двигается, свободный берёт всю поправку`() {
        val px = floatArrayOf(0.0f, 1.5f); val py = floatArrayOf(0.0f, 0.0f)
        solve(px, py, floatArrayOf(0.0f, 2.0f), 1.0f, 0.0f)
        assertEquals(0.0f, px[0], 0.0f); assertEquals(1.0f, px[1], 1e-15f)
    }

    @Test fun `оба конца закреплены - связь ничего не делает`() {
        val px = floatArrayOf(0.0f, 1.5f); val py = floatArrayOf(0.0f, 0.0f)
        solve(px, py, floatArrayOf(0.0f, 0.0f), 1.0f, 0.0f)
        assertEquals(0.0f, px[0], 0.0f); assertEquals(1.5f, px[1], 0.0f)
    }

    @Test fun `податливая связь убирает за проход долю w на w плюс альфа`() {
        val px = floatArrayOf(0.0f, 1.5f); val py = floatArrayOf(0.0f, 0.0f)
        val alpha = 3.0f   // при w = 2 убирается 2/5 растяжения
        solve(px, py, floatArrayOf(1.0f, 1.0f), 1.0f, alpha)
        assertEquals(0.5f * (1.0f - 2.0f / 5.0f), (px[1] - px[0]) - 1.0f, F_POS)
    }

    @Test fun `порванная связь не тянет`() {
        val px = floatArrayOf(0.0f, 1.5f); val py = floatArrayOf(0.0f, 0.0f)
        solve(px, py, floatArrayOf(1.0f, 1.0f), 1.0f, 0.0f, dead = true)
        assertEquals(0.0f, px[0], 0.0f); assertEquals(1.5f, px[1], 0.0f)
    }

    @Test fun `связь нулевой длины не даёт NaN`() {
        val px = floatArrayOf(1.0f, 1.0f); val py = floatArrayOf(2.0f, 2.0f)
        solve(px, py, floatArrayOf(1.0f, 1.0f), 1.0f, 0.0f)
        assertTrue(px.all { it.isFinite() } && py.all { it.isFinite() })
    }

    /**
     * Частица с обратной массой [w] на связи с закреплённой точкой, под постоянной силой [force]:
     * подшаг — скорость от силы, положение от скорости, проход связи, скорость по смещению (и
     * гашение, чтобы колебания улеглись). Возвращает равновесное растяжение после прохода связи.
     */
    private fun stretchUnder(force: Float, w: Float, h: Float): Float {
        val rest = 1.0f
        val alpha = SOFT_COMPLIANCE / (h * h)
        val px = floatArrayOf(0.0f, rest); val py = floatArrayOf(0.0f, 0.0f)
        var v = 0.0f
        repeat(20000) {
            val prev = px[1]
            v += force * w * h
            px[1] += v * h
            solve(px, py, floatArrayOf(0.0f, w), rest, alpha)
            v = (px[1] - prev) / h * 0.9f
        }
        return px[1] - rest
    }

    @Test fun `закон Гука - растяжение под постоянной силой равно силе на податливость, при любом шаге`() {
        // Во float шаг DT/64 уже не годится: приращение позиции за подшаг под силой — пара шагов float у x = 1, и
        // равновесие уходит на треть (22.11: 5.3e-5 вместо 8e-5). Игра идёт шагом DT/6.
        for (h in floatArrayOf(DT / 6, DT / 16)) for (force in floatArrayOf(1.0f, 25.0f)) for (w in floatArrayOf(1.0f, 4.0f)) {
            val stretch = stretchUnder(force, w, h)
            assertEquals("h = $h, F = $force, w = $w", force * SOFT_COMPLIANCE, stretch, F_VEL * force * SOFT_COMPLIANCE + F_POS)
        }
    }

    /** Свободные колебания двух масс на связи: растяжение по подшагам, начиная с единичного. */
    private fun oscillate(w1: Float, w2: Float, h: Float, steps: Int): FloatArray {
        val rest = 1.0f
        val alpha = SOFT_COMPLIANCE / (h * h)
        val px = floatArrayOf(0.0f, rest + 1e-3f); val py = floatArrayOf(0.0f, 0.0f)
        var v0 = 0.0f; var v1 = 0.0f
        val out = FloatArray(steps + 1)
        out[0] = px[1] - px[0] - rest
        for (s in 1..steps) {
            val p0 = px[0]; val p1 = px[1]
            px[0] += v0 * h; px[1] += v1 * h
            solve(px, py, floatArrayOf(w1, w2), rest, alpha)
            v0 = (px[0] - p0) / h; v1 = (px[1] - p1) / h
            out[s] = px[1] - px[0] - rest
        }
        return out
    }

    @Test fun `две массы на связи колеблются по своему разностному закону`() {
        val h = DT / 6; val w1 = 1.0f; val w2 = 0.5f
        val alphaStep = SOFT_COMPLIANCE / (h * h)
        val beta = alphaStep / (w1 + w2 + alphaStep)
        val u = oscillate(w1, w2, h, 40)
        // Первый подшаг — из покоя: u₁ = β·u₀; дальше u₊ = β (2u − u₋).
        assertEquals(beta * u[0], u[1], F_POS)
        for (s in 1 until 40) assertEquals("подшаг $s", beta * (2 * u[s] - u[s - 1]), u[s + 1], F_VEL * abs(u[0]))
    }

    /**
     * Период колебаний в секундах по переходам растяжения через ноль сверху вниз — по первым [count] переходам
     * (по умолчанию по всем). На шаге игры колебание затухает в β^(период/2) раз за период, и во float через пару
     * периодов тонет в шаге позиции около x = 1 (1.2e-7): переходы становятся шумом (22.11: по всем переходам за
     * 60 подшагов период выходил 9.19 подшага вместо 8.72). Там берутся первые два. Размах больше 0.001 брать
     * нельзя: на 2% работает закон гашения быстрого сближения, и период зависит от размаха.
     */
    private fun period(u: FloatArray, h: Float, count: Int = Int.MAX_VALUE): Float {
        val crossings = ArrayList<Float>()
        for (s in 1 until u.size) {
            if (u[s - 1] > 0.0f && u[s] <= 0.0f) crossings.add((s - 1 + u[s - 1] / (u[s - 1] - u[s])) * h)
            if (crossings.size == count) break
        }
        require(crossings.size >= minOf(count, 3)) { "колебаний не набралось: ${crossings.size}" }
        return (crossings.last() - crossings.first()) / (crossings.size - 1)
    }

    @Test fun `период двух масс сходится к двум пи на корень из податливости на сумму обратных масс`() {
        val w1 = 1.0f; val w2 = 1.0f
        val exact = 2 * kotlin.math.PI.toFloat() * sqrt(SOFT_COMPLIANCE / (w1 + w2))
        var before = Float.MAX_VALUE
        // Во float шаг мельче 1/48 периода не разрешает поправку связи: при малом h её доля w / (w + α/h²)
        // так мала, что поправка меньше шага float у позиции около 1, и сходимость упирается в шум (22.11:
        // замер: шаг 1/16 периода — 16%, 1/24 — 12%, 1/32 — 4%, 1/48 — 0.11%, 1/64 — снова 0.32%). Окно проверки — до 1/48,
        // и порог в конце — две десятых процента, а не одна: в double сходилось до 1/512. Шаг игры — около 1/9 периода
        // связи (тест ниже), до предела float там далеко.
        for (divide in intArrayOf(16, 24, 32, 48)) {
            val h = exact / divide
            val error = abs(period(oscillate(w1, w2, h, 12 * divide), h) / exact - 1.0f)
            println("период связи: шаг 1/$divide периода — ошибка $error")
            assertTrue("шаг вдвое меньше — ошибка периода не растёт: было $before, стало $error", error < before)
            before = error
        }
        assertTrue("на шаге в 1/48 периода ошибка меньше двух десятых процента: $before", before < 2e-3f)
    }

    @Test fun `на шаге игры период и затухание связи - по формуле разностного закона`() {
        // Шесть подшагов на тик: связь двух клеток единичной массы.
        val h = DT / 6; val w = 2.0f
        val alphaStep = SOFT_COMPLIANCE / (h * h)
        val beta = alphaStep / (w + alphaStep)
        val u = oscillate(1.0f, 1.0f, h, 60)
        val steps = period(u, h, count = 2) / h
        assertEquals("период в подшагах", 2 * kotlin.math.PI.toFloat() / acos(sqrt(beta)), steps, 0.05f * steps)
        // За период амплитуда падает в β^(период/2) раз: решатель сам гасит колебание связи.
        var peak = 0.0f
        val from = steps.toInt()
        for (s in from..minOf(u.size - 1, 2 * from)) peak = maxOf(peak, abs(u[s]))
        val expected = abs(u[0]) * StrictMath.pow((beta).toDouble(), (steps / 2).toDouble()).toFloat()
        assertTrue("амплитуда через период: вышло $peak, по формуле около $expected", peak < 3 * expected && peak > expected / 3)
    }

    // --- мышца: длина покоя и податливость по команде клетки ---

    private val inner = booleanArrayOf(false)
    private val rim = booleanArrayOf(true)

    @Test fun `сокращённая мышца тянет к длине покоя, умноженной на команду`() {
        // Команда 0.5: цель — половина длины покоя. Отставание 0.06 длины покоя — тянет на всё отставание.
        assertEquals(-(0.56f - 0.5f) / 2.0f, muscleLinkCorrection(0.56f, 1.0f, 0.5f, 2.0f, 0.0f, inner, 0), 1e-15f)
        assertEquals(0.0f, muscleLinkCorrection(0.5f, 1.0f, 0.5f, 2.0f, 0.0f, inner, 0), 1e-15f)
        // Отставание больше MUSCLE_MAX_PULL длины покоя: тянет как связь, растянутая на эту долю, и не сильнее.
        assertEquals(-MUSCLE_MAX_PULL / 2.0f, muscleLinkCorrection(1.0f, 1.0f, 0.5f, 2.0f, 0.0f, inner, 0), 1e-15f)
        assertEquals(-MUSCLE_MAX_PULL / 2.0f, muscleLinkCorrection(0.7f, 1.0f, 0.5f, 2.0f, 0.0f, inner, 0), 1e-15f)
        // Сжатая глубже цели расталкивается без потолка.
        assertEquals(0.3f / 2.0f, muscleLinkCorrection(0.2f, 1.0f, 0.5f, 2.0f, 0.0f, inner, 0), 1e-15f)
        // Команда 1 — обычная связь.
        assertEquals(-(1.2f - 1.0f) / (2.0f + 3.0f), muscleLinkCorrection(1.2f, 1.0f, 1.0f, 2.0f, 3.0f, inner, 0), 1e-15f)
    }

    @Test fun `расслабленная мышца не толкает и вчетверо податливее на растяжение`() {
        // Команда 1.5 — полное расслабление. В покое поправки нет: расслабленная мышца сама тело не раздвигает.
        assertEquals(0.0f, relaxedLinkCorrection(1.0f, 1.0f, 1.5f, 2.0f, 3.0f, inner, 0), 0.0f)
        // Сжатие держит как обычная связь.
        assertEquals(0.1f / (2.0f + 3.0f), relaxedLinkCorrection(0.9f, 1.0f, 1.5f, 2.0f, 3.0f, inner, 0), F_POS)
        // Растяжение в пределах 5%: податливость вчетверо выше.
        assertEquals(-(0.04f) / (2.0f + 4 * 3.0f), relaxedLinkCorrection(1.04f, 1.0f, 1.5f, 2.0f, 3.0f, inner, 0), F_POS)
        assertEquals(4 * 3.0f, tensileCompliance(3.0f, 1.5f), 1e-15f)
        assertEquals(2.5f * 3.0f, tensileCompliance(3.0f, 1.25f), 1e-15f)
        assertEquals(3.0f, tensileCompliance(3.0f, 1.0f), 1e-15f)
    }

    @Test fun `предел длины - 105 процентов, у расслабленной мышцы - до 125`() {
        assertEquals(LINK_MAX_STRETCH, linkMaxLength(1.0f, 1.0f), 0.0f)
        assertEquals(LINK_MAX_STRETCH, linkMaxLength(1.0f, 0.5f), 0.0f)
        assertEquals(1.25f, linkMaxLength(1.0f, 1.5f), 1e-15f)
        assertEquals((LINK_MAX_STRETCH + 1.25f) / 2, linkMaxLength(1.0f, 1.25f), 1e-15f)
        assertEquals(2 * LINK_MAX_STRETCH, linkMaxLength(2.0f, 1.0f), 1e-15f)
    }

    @Test fun `расслабленная внутренняя связь за пределом возвращается на предел, а у контура - нет`() {
        // Длина 1.4 при пределе 1.25: внутренняя связь жёстко возвращается на предел (поправка по сумме обратных масс).
        val back = relaxedLinkCorrection(1.4f, 1.0f, 1.5f, 2.0f, 3.0f, inner, 0)
        assertEquals((1.25f - 1.4f) / 2.0f, back, 1e-15f)
        // У связи контура предел решает следующая стадия: здесь только упругая поправка.
        val elastic = relaxedLinkCorrection(1.4f, 1.0f, 1.5f, 2.0f, 3.0f, rim, 0)
        assertEquals(-(0.4f) / (2.0f + 4 * 3.0f), elastic, F_POS)
    }
}
