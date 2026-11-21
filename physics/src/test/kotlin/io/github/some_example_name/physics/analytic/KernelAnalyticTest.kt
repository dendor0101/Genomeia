package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * ЯДРА РЕШАТЕЛЯ В ТОМ ВИДЕ, В КАКОМ ИХ ЗОВЁТ ШАГ МИРА: связи, перепонки, изгиб, площадь и предел
 * длины — по блокам раскладки и последовательно по всему миру. Ответ у каждого теста выписан
 * заново из определения ограничения, а не взят у решателя:
 *
 *   связь      Δдлины = −(длина − цель) · w / (w + α/h²), концы делят её по обратным массам;
 *   цель       длина покоя × команда мышцы (кластера или клетки, не больше 1);
 *   мягкость   податливость × множитель связи; расслабленная мышца (команда 1.5) под растяжением
 *              вчетверо податливее;
 *   перепонка  односторонняя: только расталкивает сошедшихся ближе позы покоя;
 *   изгиб      двусторонний, по умолчанию выключен;
 *   предел     связь контура длиннее 105% покоя возвращается на предел, длиннее предела на запас
 *              разрыва — рвётся, короче 35% — рвётся как раздавленная.
 *
 * Длина покоя и обратные массы берутся из самого мира: описание тела хранится во float, и номинал
 * (0.0288, масса 1) отличается от рабочих чисел решателя в девятом знаке.
 */
class KernelAnalyticTest {
    private val h = DT / SUBSTEPS

    private fun each(kernels: List<Kernel>, body: (Kernel) -> Unit) {
        for (kernel in kernels) try { body(kernel) } catch (e: AssertionError) { throw AssertionError("ядро $kernel: ${e.message}", e) }
    }

    /** Мир с одной цепочкой; возвращает мир и номер первой частицы. */
    private fun chainWorld(count: Int): Pair<PhysicsWorld, Int> {
        val w = gameWorld().vacuum()
        return w to w.bodyFirst(w.addBody(chain(count)))
    }

    private fun BodyState.setX(i: Int, x: Double) { px[i] = x; prevX[i] = x }

    // --- связи ---

    /** Итог одного прохода ядра по паре клеток: длина до и после, длина покоя связи, состояние и первая частица. */
    private class Solved(val before: Double, val after: Double, val rest: Double, val st: BodyState, val first: Int)

    /** Пара, растянутая до [stretch] длины покоя, с обратными массами [wi], [wj]. */
    private fun solved(kernel: Kernel, stretch: Double, wi: Double = 1.0, wj: Double = 1.0, step: Double = h,
                       prepare: (BodyState, Int) -> Unit = { _, _ -> }): Solved {
        val (w, first) = chainWorld(2)
        val st = w.state
        st.invMass[first] = wi; st.invMass[first + 1] = wj
        st.setX(first + 1, st.px[first] + T_LINK * stretch)
        val c = st.linkBetween(first, first + 1)
        prepare(st, c)
        val before = st.px[first + 1] - st.px[first]
        val com = if (wi > 0 && wj > 0) st.px[first] / wi + st.px[first + 1] / wj else 0.0
        kernel.run(st, step)
        if (wi > 0 && wj > 0) assertEquals("центр масс пары стоит", com, st.px[first] / wi + st.px[first + 1] / wj, 1e-12)
        assertEquals("поперёк связи ничего не сдвинулось", st.py[first], st.py[first + 1], 0.0)
        return Solved(before, st.px[first + 1] - st.px[first], st.conRest[c], st, first)
    }

    private fun expected(len: Double, target: Double, w: Double, alpha: Double) = len - (len - target) * w / (w + alpha)

    /** Нарушение, которое видит ядро связи: под командой сокращения — не больше MUSCLE_MAX_PULL длины покоя. */
    private fun violation(len: Double, rest: Double, command: Double) =
        if (command < 1.0) minOf(len - rest * command, MUSCLE_MAX_PULL * rest) else len - rest * command

    /** То же, что [expected], для связи под командой [command]. */
    private fun expectedMuscle(len: Double, rest: Double, command: Double, w: Double, alpha: Double) =
        len - violation(len, rest, command) * w / (w + alpha)

    @Test fun `связь убирает за проход долю w на w плюс альфа - при любом шаге и любых массах`() = each(LINK_KERNELS) { kernel ->
        for (step in doubleArrayOf(DT / 4, DT / 6, DT / 16)) for ((wi, wj) in listOf(1.0 to 1.0, 1.0 to 4.0, 0.25 to 2.0, 0.0 to 1.0)) {
            for (stretch in doubleArrayOf(1.03, 0.9)) {
                val s = solved(kernel, stretch, wi, wj, step)
                assertEquals("шаг $step, массы $wi/$wj, растяжение $stretch",
                    expected(s.before, s.rest, wi + wj, SOFT_COMPLIANCE / (step * step)), s.after, 1e-15)
            }
        }
    }

    @Test fun `оба конца закреплены или связь порвана - ядро связи ничего не делает`() = each(LINK_KERNELS) { kernel ->
        val pinned = solved(kernel, 1.04, 0.0, 0.0)
        assertEquals(pinned.before, pinned.after, 0.0)
        val dead = solved(kernel, 1.04) { st, c -> st.conDead[c] = true }
        assertEquals(dead.before, dead.after, 0.0)
    }

    @Test fun `совпавшие концы связи не дают NaN`() = each(LINK_KERNELS) { kernel ->
        val s = solved(kernel, 0.0)
        assertEquals(0.0, s.after, 0.0)
        assertFalse(s.st.px[s.first].isNaN() || s.st.py[s.first].isNaN())
    }

    @Test fun `команда мышцы клетки - сокращённая тянет к укороченной длине покоя`() = each(LINK_KERNELS) { kernel ->
        val alpha = SOFT_COMPLIANCE / (h * h)
        // Отставание от команды меньше MUSCLE_MAX_PULL длины покоя: тянет на всё отставание.
        for (command in floatArrayOf(0.95f, 0.92f, 1f)) {
            val s = solved(kernel, 1.0) { st, c -> st.cellScalesOn = true; st.conCellScale[c] = command }
            assertEquals("команда $command", expected(s.before, s.rest * command, 2.0, alpha), s.after, 1e-15)
        }
        // Связь уже короче цели: мышца её расталкивает, как обычная связь, без потолка.
        val pushed = solved(kernel, 0.4) { st, c -> st.cellScalesOn = true; st.conCellScale[c] = 0.5f }
        assertEquals("сжата глубже команды", expected(pushed.before, pushed.rest * 0.5f, 2.0, alpha), pushed.after, 1e-15)
    }

    @Test fun `мышца тянет отстающую ткань не сильнее связи, растянутой на десятую длины покоя`() = each(LINK_KERNELS) { kernel ->
        val alpha = SOFT_COMPLIANCE / (h * h)
        assertEquals(0.1, MUSCLE_MAX_PULL, 0.0)
        for (command in floatArrayOf(0.5f, 0.8f, 0.89f)) for (stretch in doubleArrayOf(1.0, 1.04)) {
            val s = solved(kernel, stretch) { st, c -> st.cellScalesOn = true; st.conCellScale[c] = command }
            // Тяга та же, что у обычной связи, растянутой на десятую: от команды и от длины не зависит.
            assertEquals("команда $command, длина $stretch", s.before - MUSCLE_MAX_PULL * s.rest * 2.0 / (2.0 + alpha), s.after, 1e-15)
            assertEquals(expectedMuscle(s.before, s.rest, command.toDouble(), 2.0, alpha), s.after, 1e-15)
        }
        // Обычная связь (команда 1) потолка не знает: растянутая на 30% тянет на все 30%.
        val plain = solved(kernel, 1.3)
        assertEquals("обычная связь", expected(plain.before, plain.rest, 2.0, alpha), plain.after, 1e-15)
    }

    @Test fun `расслабленная мышца под растяжением вчетверо податливее, на сжатие - обычная`() = each(LINK_KERNELS) { kernel ->
        val alpha = SOFT_COMPLIANCE / (h * h)
        val relaxed: (BodyState, Int) -> Unit = { st, c -> st.cellScalesOn = true; st.conCellScale[c] = 1.5f }
        val pulled = solved(kernel, 1.03, prepare = relaxed)
        assertEquals("растянутая", expected(pulled.before, pulled.rest, 2.0, 4 * alpha), pulled.after, 1e-15)
        val pushed = solved(kernel, 0.95, prepare = relaxed)
        assertEquals("сжатая", expected(pushed.before, pushed.rest, 2.0, alpha), pushed.after, 1e-15)
        // Половина расслабления (команда 1.25): податливость 1 + 3·0.5 = 2.5 обычной.
        val half = solved(kernel, 1.03) { st, c -> st.cellScalesOn = true; st.conCellScale[c] = 1.25f }
        assertEquals("наполовину расслабленная", expected(half.before, half.rest, 2.0, 2.5 * alpha), half.after, 1e-15)
    }

    @Test fun `мягкость ткани умножает податливость связи`() = each(LINK_KERNELS) { kernel ->
        val alpha = SOFT_COMPLIANCE / (h * h)
        for (soft in floatArrayOf(1f, 2.5f, 0.4f)) {
            val s = solved(kernel, 1.03) { st, c -> st.softnessOn = true; st.conSoftness[c] = soft }
            assertEquals("мягкость $soft", expected(s.before, s.rest, 2.0, alpha * soft), s.after, 1e-15)
            // Мягкость вместе с командой мышцы: цель короче, податливость та же.
            val both = solved(kernel, 1.0) { st, c -> st.softnessOn = true; st.conSoftness[c] = soft; st.conCellScale[c] = 0.7f }
            assertEquals("мягкость $soft с командой 0.7", expectedMuscle(both.before, both.rest, 0.7f.toDouble(), 2.0, alpha * soft), both.after, 1e-15)
        }
    }

    @Test fun `мышца-кластер - связь между её клетками тянется к длине покоя на множитель кластера`() = each(LINK_KERNELS) { kernel ->
        val alpha = SOFT_COMPLIANCE / (h * h)
        for (command in floatArrayOf(1f, 0.8f)) {
            val w = gameWorld().vacuum()
            val first = w.bodyFirst(w.addBody(patch(3, 1, muscle = { _, _ -> true })))
            val st = w.state
            val c = st.linkBetween(first, first + 1)
            assertTrue("связь двух мышечных клеток принадлежит мышце", st.conMuscle[c] >= 0)
            if (st.muscleScaleOf.size <= st.conMuscle[c]) st.muscleScaleOf = DoubleArray(st.conMuscle[c] + 1) { 1.0 }
            st.muscleScaleOf[st.conMuscle[c]] = 0.75
            // С командой клетки поверх кластера множители перемножаются.
            if (command != 1f) { st.cellScalesOn = true; st.conCellScale[c] = command }
            // Вторую связь выключаем, чтобы ответ остался ответом одной связи.
            st.conDead[st.linkBetween(first + 1, first + 2)] = true
            st.invMass[first] = 1.0; st.invMass[first + 1] = 1.0
            val len = st.px[first + 1] - st.px[first]
            kernel.run(st, h)
            assertEquals("команда клетки $command", expectedMuscle(len, st.conRest[c], 0.75 * command, 2.0, alpha), st.px[first + 1] - st.px[first], 1e-15)
        }
    }

    @Test fun `цепочка решается по очереди - направление обхода меняет ответ предсказуемо`() = each(LINK_KERNELS) { kernel ->
        val alpha = SOFT_COMPLIANCE / (h * h)
        for (backwards in booleanArrayOf(false, true)) {
            val (w, first) = chainWorld(3)
            val st = w.state
            for (k in 0 until 3) st.invMass[first + k] = 1.0
            st.setX(first + 1, st.px[first] + 1.05 * T_LINK); st.setX(first + 2, st.px[first + 1] + 0.98 * T_LINK)
            val x = DoubleArray(3) { st.px[first + it] }
            st.sweepBackwards = backwards
            kernel.run(st, h)
            // Тот же обход заново: связь k соединяет частицы k и k+1.
            for (k in if (backwards) intArrayOf(1, 0) else intArrayOf(0, 1)) {
                val len = x[k + 1] - x[k]
                val move = (len - st.conRest[st.linkBetween(first + k, first + k + 1)]) / (2.0 + alpha)
                x[k] += move; x[k + 1] -= move
            }
            for (k in 0 until 3) assertEquals("обход назад=$backwards, частица $k", x[k], st.px[first + k], 1e-15)
        }
    }

    // --- перепонки ---

    @Test fun `перепонка односторонняя - расталкивает сошедшихся, разошедшихся не держит`() = each(FLAP_KERNELS) { kernel ->
        val alpha = FLAP_COMPLIANCE / (h * h)
        /** Концы цепочки на [scale] длины покоя перепонки; возвращает длину после ядра и ожидание. */
        fun run(scale: Double, wi: Double = 1.0, wj: Double = 1.0): Pair<Double, Double> {
            val (w, first) = chainWorld(3)
            val st = w.state
            assertEquals("у средней клетки цепочки одна перепонка — между её соседями", 1, st.flapCount)
            val rest = st.flapRest[0]
            assertEquals(2 * T_LINK, rest, 1e-6)
            st.invMass[first] = wi; st.invMass[first + 2] = wj
            st.setX(first + 2, st.px[first] + rest * scale)
            val middle = st.px[first + 1]
            val before = st.px[first + 2] - st.px[first]
            kernel.run(st, h)
            assertEquals("среднюю клетку перепонка не трогает", middle, st.px[first + 1], 0.0)
            // Сошедшиеся концы расталкиваются по формуле связи, остальное не трогается.
            val want = if (before < rest && wi + wj > 0.0) expected(before, rest, wi + wj, alpha) else before
            return (st.px[first + 2] - st.px[first]) to want
        }
        run(1.0).let { (after, want) -> assertEquals("в позе покоя", want, after, 0.0) }
        run(1.2).let { (after, want) -> assertEquals("разошедшиеся концы перепонка не стягивает", want, after, 0.0) }
        for ((wi, wj) in listOf(1.0 to 1.0, 1.0 to 3.0, 0.0 to 1.0))
            run(0.8, wi, wj).let { (after, want) -> assertEquals("сошедшиеся, массы $wi/$wj", want, after, 1e-15); assertTrue(after > 1.6 * T_LINK) }
        run(0.8, 0.0, 0.0).let { (after, want) -> assertEquals("оба конца закреплены", want, after, 0.0) }
    }

    @Test fun `перепонка делит поправку по обратным массам и идёт вдоль линии концов`() = each(FLAP_KERNELS) { kernel ->
        val (w, first) = chainWorld(3)
        val st = w.state
        st.invMass[first] = 1.0; st.invMass[first + 2] = 3.0
        // Конец загнули вверх и к началу: линия концов наклонена.
        st.px[first + 2] = st.px[first] + 0.9 * T_LINK; st.py[first + 2] = st.py[first] + 0.7 * T_LINK
        val ax = st.px[first]; val ay = st.py[first]; val bx = st.px[first + 2]; val by = st.py[first + 2]
        kernel.run(st, h)
        val dax = st.px[first] - ax; val day = st.py[first] - ay; val dbx = st.px[first + 2] - bx; val dby = st.py[first + 2] - by
        assertEquals("поправки концов противоположны и идут как 1 к 3 (x)", -3 * dax, dbx, 1e-15)
        assertEquals("поправки концов противоположны и идут как 1 к 3 (y)", -3 * day, dby, 1e-15)
        assertEquals("поправка вдоль линии концов", 0.0, (dbx * (by - ay) - dby * (bx - ax)) / (T_LINK * T_LINK), 1e-12)
        assertTrue("концы расходятся", hypot(st.px[first + 2] - st.px[first], st.py[first + 2] - st.py[first]) > hypot(bx - ax, by - ay))
    }

    // --- изгиб ---

    @Test fun `изгиб по умолчанию выключен, включённый держит расстояние между соседями в обе стороны`() = each(BEND_KERNELS) { kernel ->
        /** Соседи средней клетки на [scale] расстояния покоя; возвращает расстояние после ядра и ожидание. */
        fun run(level: Int, scale: Double, wi: Double = 1.0, wj: Double = 1.0): Pair<Double, Double> {
            val (w, first) = chainWorld(3)
            val st = w.state
            assertEquals("у средней клетки цепочки одно ограничение изгиба", 1, st.bendCount)
            st.bendLevel = level
            st.invMass[first] = wi; st.invMass[first + 2] = wj
            val rest = st.bendRest[0]
            st.setX(first + 2, st.px[first] + rest * scale)
            val before = st.px[first + 2] - st.px[first]
            kernel.run(st, h)
            val want = if (level > 0 && wi + wj > 0.0) expected(before, rest, wi + wj, BEND_LEVELS[level] / (h * h)) else before
            return (st.px[first + 2] - st.px[first]) to want
        }
        run(0, 0.8).let { (after, want) -> assertEquals("уровень 0 — изгиб выключен", want, after, 0.0) }
        for (level in 1 until BEND_LEVELS.size) {
            run(level, 0.8).let { (after, want) -> assertEquals("уровень $level, сошлись", want, after, 1e-15); assertTrue(after > 1.6 * T_LINK) }
            run(level, 1.1).let { (after, want) -> assertEquals("уровень $level, разошлись", want, after, 1e-15); assertTrue(after < 2.2 * T_LINK) }
            run(level, 0.8, 1.0, 4.0).let { (after, want) -> assertEquals("уровень $level, массы 1 и 4", want, after, 1e-15) }
        }
        run(3, 0.8, 0.0, 0.0).let { (after, want) -> assertEquals("оба конца закреплены", want, after, 0.0) }
    }

    // --- площадь: то, чего нет в AreaAnalyticTest ---

    private fun triangle(): Pair<PhysicsWorld, IntArray> {
        val w = gameWorld().vacuum()
        w.addBody(patch(2, 2))
        val st = w.state
        // Лоскут 2×2 — два треугольника; второй выключаем, чтобы ответ остался ответом одного.
        assertEquals(2, st.triCount)
        st.baked.triAlive[st.triId[1]] = false
        return w to intArrayOf(st.triA[0], st.triB[0], st.triC[0])
    }

    private fun area2(st: BodyState, ids: IntArray): Double =
        (st.px[ids[1]] - st.px[ids[0]]) * (st.py[ids[2]] - st.py[ids[0]]) - (st.py[ids[1]] - st.py[ids[0]]) * (st.px[ids[2]] - st.px[ids[0]])

    /** Масштаб вокруг вершины 0: площадь меняется в [scale]² раз, форма прежняя. */
    private fun scaleTriangle(st: BodyState, ids: IntArray, scale: Double) {
        for (v in 1..2) {
            st.px[ids[v]] = st.px[ids[0]] + (st.px[ids[v]] - st.px[ids[0]]) * scale
            st.py[ids[v]] = st.py[ids[0]] + (st.py[ids[v]] - st.py[ids[0]]) * scale
        }
    }

    /**
     * Один проход ядра площади против ответа по определению: множитель λ = −C / (Σ w|g|² + α),
     * вершина сдвигается на λ·w·g, где g — градиент удвоенной площади по её координатам.
     * Возвращает наибольшее расхождение позиций и длину поправки (для проверки, что потолок не задет).
     */
    private fun areaStep(kernel: Kernel, st: BodyState, ids: IntArray, restArea2: Double, compliance: Double): Pair<Double, Double> {
        val gx = DoubleArray(3); val gy = DoubleArray(3)
        var denom = 0.0
        for (v in 0 until 3) {
            val a = ids[(v + 1) % 3]; val b = ids[(v + 2) % 3]
            gx[v] = st.py[a] - st.py[b]; gy[v] = st.px[b] - st.px[a]
            denom += st.invMass[ids[v]] * (gx[v] * gx[v] + gy[v] * gy[v])
        }
        val lambda = -(area2(st, ids) - restArea2) / (denom + compliance)
        val ex = DoubleArray(3) { st.px[ids[it]] + st.invMass[ids[it]] * lambda * gx[it] }
        val ey = DoubleArray(3) { st.py[ids[it]] + st.invMass[ids[it]] * lambda * gy[it] }
        kernel.run(st, h)
        var worst = 0.0
        for (v in 0 until 3) worst = maxOf(worst, abs(st.px[ids[v]] - ex[v]), abs(st.py[ids[v]] - ey[v]))
        return worst to abs(lambda) * sqrt(denom)
    }

    @Test fun `сжатый треугольник - податливость падает как квадрат доли площади`() = each(AREA_KERNELS) { kernel ->
        val alpha = AREA_COMPLIANCE / (h * h)
        for (scale in doubleArrayOf(0.97, 0.9, 0.8)) {
            val (w, ids) = triangle()
            val st = w.state
            val rest = st.triRestArea2[0].toDouble()
            scaleTriangle(st, ids, scale)
            val q = area2(st, ids) / rest
            val (error, step) = areaStep(kernel, st, ids, rest, alpha * q * q)
            assertTrue("поправка не упёрлась в потолок: $step", step < AREA_MAX_STEP * st.linkScale)
            assertEquals("масштаб $scale", 0.0, error, 1e-15)
        }
    }

    @Test fun `раздутый треугольник - податливость обычная, поправка по формуле`() = each(AREA_KERNELS) { kernel ->
        val alpha = AREA_COMPLIANCE / (h * h)
        for ((scale, wLight) in listOf(1.02 to 1.0, 1.05 to 1.0, 1.03 to 3.0)) {
            val (w, ids) = triangle()
            val st = w.state
            st.invMass[ids[1]] = wLight
            scaleTriangle(st, ids, scale)
            val (error, step) = areaStep(kernel, st, ids, st.triRestArea2[0].toDouble(), alpha)
            assertTrue("поправка не упёрлась в потолок: $step", step < AREA_MAX_STEP * st.linkScale)
            assertEquals("масштаб $scale, обратная масса вершины $wLight", 0.0, error, 1e-15)
        }
    }

    @Test fun `мягкость ткани умножает податливость площади`() = each(AREA_KERNELS) { kernel ->
        val alpha = AREA_COMPLIANCE / (h * h)
        for (soft in floatArrayOf(2.5f, 0.5f)) {
            val (w, ids) = triangle()
            val st = w.state
            st.softnessOn = true; st.triSoftness[0] = soft
            scaleTriangle(st, ids, 1.03)
            val (error, _) = areaStep(kernel, st, ids, st.triRestArea2[0].toDouble(), alpha * soft)
            assertEquals("мягкость $soft", 0.0, error, 1e-15)
        }
    }

    @Test fun `команда мышцы клетки - площадь покоя едет как квадрат команды, расслабленная вчетверо податливее`() = each(AREA_KERNELS) { kernel ->
        val alpha = AREA_COMPLIANCE / (h * h)
        // Сокращённая: цель — площадь покоя × команда². Треугольник в позе покоя теперь «раздут».
        run {
            val (w, ids) = triangle()
            val st = w.state
            st.cellScalesOn = true; st.triCellScale[0] = 0.9f
            val command = 0.9f.toDouble()
            val (error, _) = areaStep(kernel, st, ids, st.triRestArea2[0].toDouble() * command * command, alpha)
            assertEquals("сокращённая", 0.0, error, 1e-15)
        }
        // Глубокая команда: площадь тянет не сильнее удвоенной доли MUSCLE_MAX_PULL площади покоя —
        // так, будто цель на эту долю меньше нынешней площади.
        run {
            val (w, ids) = triangle()
            val st = w.state
            st.cellScalesOn = true; st.triCellScale[0] = 0.5f
            val (error, _) = areaStep(kernel, st, ids, area2(st, ids) - 2.0 * MUSCLE_MAX_PULL * st.triRestArea2[0], alpha)
            assertEquals("сокращённая вдвое — потолок тяги", 0.0, error, 1e-15)
        }
        // Расслабленная и раздутая: податливость ×4, площадь покоя прежняя.
        run {
            val (w, ids) = triangle()
            val st = w.state
            st.cellScalesOn = true; st.triCellScale[0] = 1.5f
            scaleTriangle(st, ids, 1.03)
            val (error, _) = areaStep(kernel, st, ids, st.triRestArea2[0].toDouble(), 4 * alpha)
            assertEquals("расслабленная раздутая", 0.0, error, 1e-15)
        }
        // Расслабленная и сжатая: податливость обычная (со спадом по доле площади).
        run {
            val (w, ids) = triangle()
            val st = w.state
            st.cellScalesOn = true; st.triCellScale[0] = 1.5f
            scaleTriangle(st, ids, 0.95)
            val rest = st.triRestArea2[0].toDouble()
            val q = area2(st, ids) / rest
            val (error, _) = areaStep(kernel, st, ids, rest, alpha * q * q)
            assertEquals("расслабленная сжатая", 0.0, error, 1e-15)
        }
    }

    @Test fun `вывернутый треугольник решается жёстко - податливость ноль`() = each(AREA_KERNELS) { kernel ->
        val (w, ids) = triangle()
        val st = w.state
        // Вершину 2 перенесли через основание на малую глубину: площадь отрицательна.
        val x0 = st.px[ids[0]]; val y0 = st.py[ids[0]]; val x1 = st.px[ids[1]]; val y1 = st.py[ids[1]]
        val len = hypot(x1 - x0, y1 - y0)
        val nx = -(y1 - y0) / len; val ny = (x1 - x0) / len      // нормаль к основанию в сторону вершины 2
        val height = (st.px[ids[2]] - x0) * nx + (st.py[ids[2]] - y0) * ny
        st.px[ids[2]] -= nx * (height + 0.02 * T_LINK); st.py[ids[2]] -= ny * (height + 0.02 * T_LINK)
        val before = area2(st, ids)
        assertTrue(before < 0.0)
        // Жёсткая поправка до площади покоя длиннее потолка AREA_MAX_STEP: длина поправки — ровно потолок.
        val x = DoubleArray(3) { st.px[ids[it]] }; val y = DoubleArray(3) { st.py[ids[it]] }
        kernel.run(st, h)
        var sum = 0.0
        for (v in 0 until 3) sum += ((st.px[ids[v]] - x[v]).let { it * it } + (st.py[ids[v]] - y[v]).let { it * it }) / st.invMass[ids[v]]
        assertEquals(AREA_MAX_STEP * st.linkScale, sqrt(sum), 1e-9 * T_LINK)
        assertTrue("площадь пошла к положительной: было $before, стало ${area2(st, ids)}", area2(st, ids) > before)
    }

    // --- предел длины связи контура ---

    /** Пара клеток, раздвинутая до [stretch] длины покоя; возвращает состояние, связь и первую частицу. */
    private fun limitWorld(stretch: Double, config: Map<String, String> = emptyMap()): Triple<BodyState, Int, Int> {
        val w = gameWorld(config).vacuum()
        val first = w.bodyFirst(w.addBody(chain(2)))
        val st = w.state
        st.invMass[first] = 1.0; st.invMass[first + 1] = 1.0
        st.setX(first + 1, st.px[first] + T_LINK * stretch)
        return Triple(st, st.linkBetween(first, first + 1), first)
    }

    private fun BodyState.length(first: Int): Double = px[first + 1] - px[first]

    @Test fun `связь контура в пределах 105 процентов предел не трогает`() = each(LIMIT_KERNELS) { kernel ->
        for (stretch in doubleArrayOf(0.5, 1.0, 1.049)) {
            val (st, c, first) = limitWorld(stretch)
            val hits = st.linkCapHits
            val before = st.length(first)
            kernel.run(st, h)
            assertEquals("растяжение $stretch", before, st.length(first), 0.0)
            assertFalse(st.conDead[c]); assertEquals(hits, st.linkCapHits)
        }
    }

    @Test fun `связь длиннее предела возвращается ровно на предел, концы делят поправку по обратным массам`() = each(LIMIT_KERNELS) { kernel ->
        for ((wi, wj) in listOf(1.0 to 1.0, 1.0 to 3.0, 0.0 to 1.0)) {
            val (st, c, first) = limitWorld(1.12)
            st.invMass[first] = wi; st.invMass[first + 1] = wj
            val a = st.px[first]; val b = st.px[first + 1]
            val hits = st.linkCapHits
            kernel.run(st, h)
            assertEquals("массы $wi/$wj: длина — предел", LINK_MAX_STRETCH * st.conRest[c], st.length(first), 1e-15)
            assertEquals("массы $wi/$wj: доли концов", (st.px[first] - a) * wj, -(st.px[first + 1] - b) * wi, 1e-15)
            assertFalse("связь цела", st.conDead[c])
            assertTrue("срабатывание предела сосчитано", st.linkCapHits > hits)
        }
    }

    @Test fun `поправка предела за проход не больше потолка`() = each(LIMIT_KERNELS) { kernel ->
        // Разрывы выключены: связь, растянутая вдвое, возвращается не сразу, а шагами по LINK_MAX_STEP связи.
        val (st, c, first) = limitWorld(2.0)
        st.tearingOn = false
        val before = st.length(first)
        kernel.run(st, h)
        assertFalse(st.conDead[c])
        // Блочное ядро «у порога» зовёт предел дважды — два шага.
        val steps = if (kernel.name == "блочное у порога") 2 else 1
        assertEquals(before - steps * st.LINK_MAX_STEP * st.linkScale, st.length(first), 1e-15)
    }

    @Test fun `связь, растянутая за запас разрыва, рвётся, а без разрывов только помечается`() = each(LIMIT_KERNELS) { kernel ->
        val over = LINK_MAX_STRETCH + LINK_TEAR_STRAIN
        run {
            val (st, c, _) = limitWorld(over + 0.02)
            val torn = st.dbgTornPull
            kernel.run(st, h)
            assertTrue("связь порвана", st.conDead[c]); assertTrue(st.linkTorn[c]); assertTrue(st.tearsPending)
            assertEquals(torn + 1, st.dbgTornPull)
        }
        run {
            val (st, c, _) = limitWorld(over - 0.02)
            kernel.run(st, h)
            assertFalse("до запаса разрыва связь цела", st.conDead[c]); assertFalse(st.linkTorn[c])
        }
        run {
            val (st, c, _) = limitWorld(over + 0.02)
            st.tearingOn = false
            kernel.run(st, h)
            assertFalse("разрывы выключены — связь жива", st.conDead[c]); assertTrue("но помечена порванной", st.linkTorn[c])
        }
    }

    @Test fun `раздавленная связь рвётся и помечает обе клетки, без разрывов - нет`() = each(LIMIT_KERNELS) { kernel ->
        run {
            val (st, c, first) = limitWorld(LINK_CRUSH_RATIO - 0.02)
            val crushed = st.dbgTornCrush
            kernel.run(st, h)
            assertTrue("связь раздавлена", st.conDead[c]); assertTrue(st.linkTorn[c])
            assertTrue(st.crushedCell[first]); assertTrue(st.crushedCell[first + 1])
            assertEquals(crushed + 1, st.dbgTornCrush)
        }
        run {
            val (st, c, first) = limitWorld(LINK_CRUSH_RATIO + 0.02)
            kernel.run(st, h)
            assertFalse("чуть длиннее порога — цела", st.conDead[c]); assertFalse(st.crushedCell[first])
        }
        run {
            val (st, c, _) = limitWorld(LINK_CRUSH_RATIO - 0.02)
            st.tearingOn = false
            kernel.run(st, h)
            assertFalse("разрывы выключены — раздавить нельзя", st.conDead[c])
        }
    }

    @Test fun `у сокращённой мышцы порог раздавливания считается от укороченной длины, у расслабленной предел - 125 процентов`() = each(LIMIT_KERNELS) { kernel ->
        // Команда 0.5: длина покоя вдвое короче, раздавлена — короче 0.35 от неё.
        run {
            val (st, c, _) = limitWorld(0.5 * LINK_CRUSH_RATIO + 0.02)
            st.cellScalesOn = true; st.conCellScale[c] = 0.5f
            kernel.run(st, h)
            assertFalse("сокращённая мышца на 0.195 длины покоя не раздавлена", st.conDead[c])
        }
        run {
            val (st, c, _) = limitWorld(0.5 * LINK_CRUSH_RATIO - 0.02)
            st.cellScalesOn = true; st.conCellScale[c] = 0.5f
            kernel.run(st, h)
            assertTrue("а на 0.155 — раздавлена", st.conDead[c])
        }
        // Команда 1.5: предел длины 125% покоя.
        run {
            val (st, c, first) = limitWorld(1.2)
            st.cellScalesOn = true; st.conCellScale[c] = 1.5f
            val before = st.length(first)
            kernel.run(st, h)
            assertEquals("расслабленная на 120% — в пределах", before, st.length(first), 0.0)
            assertFalse(st.conDead[c])
        }
        run {
            val (st, c, first) = limitWorld(1.32)
            st.cellScalesOn = true; st.conCellScale[c] = 1.5f
            kernel.run(st, h)
            assertEquals("расслабленная на 132% возвращается на 125%", 1.25 * st.conRest[c], st.length(first), 1e-15)
        }
    }

    @Test fun `сухожилие рвётся позже обычной связи, закреплённую с двух сторон предел не трогает`() = each(LIMIT_KERNELS) { kernel ->
        val over = LINK_MAX_STRETCH + LINK_TEAR_STRAIN
        run {
            val (st, c, _) = limitWorld(over + 0.1)
            st.conTendon[c] = true
            kernel.run(st, h)
            assertFalse("сухожилие держит ${over + 0.1} длины покоя", st.conDead[c])
        }
        run {
            val (st, c, _) = limitWorld(LINK_MAX_STRETCH + LINK_TEAR_STRAIN * TENDON_TEAR_FACTOR + 0.02)
            st.conTendon[c] = true
            kernel.run(st, h)
            assertTrue("за своим запасом рвётся и сухожилие", st.conDead[c])
        }
        run {
            val (st, c, first) = limitWorld(1.3)
            st.invMass[first] = 0.0; st.invMass[first + 1] = 0.0
            val before = st.length(first)
            kernel.run(st, h)
            assertEquals("оба конца закреплены", before, st.length(first), 0.0); assertFalse(st.conDead[c])
        }
    }

    @Test fun `хрупкая мембрана рвётся раньше ткани, когда порог задан`() = each(LIMIT_KERNELS) { kernel ->
        // RB_MEMBRANE: связь контура рвётся, как только вышла за предел больше чем на эту долю покоя.
        val (st, c, _) = limitWorld(LINK_MAX_STRETCH + 0.2, mapOf("RB_MEMBRANE" to "0.1"))
        val count = st.membraneTearCount
        kernel.run(st, h)
        assertTrue("мембрана порвана", st.conDead[c]); assertEquals(count + 1, st.membraneTearCount)
        val (soft, c2, _) = limitWorld(LINK_MAX_STRETCH + 0.05, mapOf("RB_MEMBRANE" to "0.1"))
        kernel.run(soft, h)
        assertFalse("в пределах порога мембрана цела", soft.conDead[c2])
    }

    @Test fun `дорастающую связь предел не трогает`() = each(LIMIT_KERNELS) { kernel ->
        val (st, c, first) = limitWorld(2.5)
        if (st.conGrowTarget.size <= c) st.conGrowTarget = st.conGrowTarget.copyOf(c + 1)
        st.conGrowTarget[c] = st.conRest[c]
        val before = st.length(first)
        kernel.run(st, h)
        assertEquals(before, st.length(first), 0.0); assertFalse(st.conDead[c])
    }

    // --- наклонные связи и все виды ядра связей ---

    /** Вид ядра связей: какие множители включены. Блочный обход выбирает по ним разные ядра. */
    private class LinkMode(val name: String, val cells: Float?, val soft: Float?) {
        fun apply(st: BodyState) {
            if (cells != null) { st.cellScalesOn = true; java.util.Arrays.fill(st.conCellScale, cells) }
            if (soft != null) { st.softnessOn = true; java.util.Arrays.fill(st.conSoftness, soft) }
        }
        val target: Double get() = minOf(1.0, (cells ?: 1f).toDouble())
        val softness: Double get() = (soft ?: 1f).toDouble()
    }
    private val linkModes = listOf(LinkMode("обычное", null, null), LinkMode("команда клетки", 0.8f, null),
        LinkMode("мягкость", null, 2.5f), LinkMode("команда и мягкость", 0.8f, 2.5f))

    @Test fun `наклонная связь - поправка вдоль связи, во всех видах ядра`() = each(LINK_KERNELS) { kernel ->
        val alpha = SOFT_COMPLIANCE / (h * h)
        for (mode in linkModes) for (angle in doubleArrayOf(0.6, 2.2, -1.3)) {
            val (w, first) = chainWorld(2)
            val st = w.state
            st.invMass[first] = 1.0; st.invMass[first + 1] = 2.0
            mode.apply(st)
            val c = st.linkBetween(first, first + 1)
            val len = 1.04 * st.conRest[c]
            val nx = kotlin.math.cos(angle); val ny = kotlin.math.sin(angle)
            st.px[first + 1] = st.px[first] + len * nx; st.py[first + 1] = st.py[first] + len * ny
            val ax = st.px[first]; val ay = st.py[first]; val bx = st.px[first + 1]; val by = st.py[first + 1]
            kernel.run(st, h)
            val shrink = violation(len, st.conRest[c], mode.target.toDouble()) / (3.0 + alpha * mode.softness)
            val what = "${mode.name}, угол $angle"
            assertEquals("$what: начало, x", ax + shrink * nx, st.px[first], 1e-15); assertEquals("$what: начало, y", ay + shrink * ny, st.py[first], 1e-15)
            assertEquals("$what: конец, x", bx - 2 * shrink * nx, st.px[first + 1], 1e-15); assertEquals("$what: конец, y", by - 2 * shrink * ny, st.py[first + 1], 1e-15)
        }
    }

    @Test fun `цепочка по очереди в обе стороны - во всех видах ядра`() = each(LINK_KERNELS) { kernel ->
        val alpha = SOFT_COMPLIANCE / (h * h)
        for (mode in linkModes) for (backwards in booleanArrayOf(false, true)) {
            val (w, first) = chainWorld(4)
            val st = w.state
            for (k in 0 until 4) st.invMass[first + k] = 1.0
            mode.apply(st)
            st.setX(first + 1, st.px[first] + 1.04 * T_LINK); st.setX(first + 2, st.px[first + 1] + 0.97 * T_LINK); st.setX(first + 3, st.px[first + 2] + 1.02 * T_LINK)
            val x = DoubleArray(4) { st.px[first + it] }
            st.sweepBackwards = backwards
            kernel.run(st, h)
            for (k in if (backwards) intArrayOf(2, 1, 0) else intArrayOf(0, 1, 2)) {
                val rest = st.conRest[st.linkBetween(first + k, first + k + 1)]
                val move = violation(x[k + 1] - x[k], rest, mode.target.toDouble()) / (2.0 + alpha * mode.softness)
                x[k] += move; x[k + 1] -= move
            }
            for (k in 0 until 4) assertEquals("${mode.name}, обход назад=$backwards, частица $k", x[k], st.px[first + k], 1e-15)
        }
    }

    @Test fun `наклонные изгиб и предел длины - поправка вдоль линии концов`() {
        each(BEND_KERNELS) { kernel ->
            val (w, first) = chainWorld(3)
            val st = w.state
            st.bendLevel = 4
            st.invMass[first] = 1.0; st.invMass[first + 2] = 2.0
            val rest = st.bendRest[0]
            val len = 0.85 * rest
            st.px[first + 2] = st.px[first] + len * 0.6; st.py[first + 2] = st.py[first] + len * 0.8
            val ax = st.px[first]; val ay = st.py[first]; val bx = st.px[first + 2]; val by = st.py[first + 2]
            kernel.run(st, h)
            val grow = (rest - len) / (3.0 + BEND_LEVELS[4] / (h * h))
            assertEquals(ax - grow * 0.6, st.px[first], 1e-15); assertEquals(ay - grow * 0.8, st.py[first], 1e-15)
            assertEquals(bx + 2 * grow * 0.6, st.px[first + 2], 1e-15); assertEquals(by + 2 * grow * 0.8, st.py[first + 2], 1e-15)
        }
        each(LIMIT_KERNELS) { kernel ->
            val (st, c, first) = limitWorld(1.0)
            st.invMass[first] = 1.0; st.invMass[first + 1] = 2.0
            val len = 1.1 * st.conRest[c]
            st.px[first + 1] = st.px[first] + len * 0.6; st.py[first + 1] = st.py[first] - len * 0.8
            val ax = st.px[first]; val ay = st.py[first]; val bx = st.px[first + 1]; val by = st.py[first + 1]
            kernel.run(st, h)
            val shrink = (len - LINK_MAX_STRETCH * st.conRest[c]) / 3.0
            assertEquals(ax + shrink * 0.6, st.px[first], 1e-15); assertEquals(ay - shrink * 0.8, st.py[first], 1e-15)
            assertEquals(bx - 2 * shrink * 0.6, st.px[first + 1], 1e-15); assertEquals(by + 2 * shrink * 0.8, st.py[first + 1], 1e-15)
        }
    }

    @Test fun `список связей у порога - ранний проход блока собирает тех, кто близок к пределу или раздавливанию`() {
        fun near(stretch: Double, tearing: Boolean = true): Int {
            val (st, _, _) = limitWorld(stretch)
            st.tearingOn = tearing
            var count = 0
            st.eachBlock { b, l -> st.solveBlockLimits(b, l); count += l.nearCount[b] }
            return count
        }
        // Порог сверху: длиннее 97% предела (1.05 · 0.97 = 1.0185 длины покоя).
        assertEquals("в позе покоя — не у порога", 0, near(1.0))
        assertEquals("чуть ниже порога", 0, near(1.016))
        assertEquals("чуть выше порога", 1, near(1.021))
        // Порог снизу: короче полутора порогов раздавливания (0.35 · 1.5 = 0.525), только при разрывах.
        assertEquals("сжатая до 0.54 — не у порога", 0, near(0.54))
        assertEquals("сжатая до 0.51 — у порога", 1, near(0.51))
        assertEquals("без разрывов порога снизу нет", 0, near(0.51, tearing = false))
    }

    @Test fun `предел длины не двигает костный конец связи - всю поправку берёт мягкая клетка`() = each(LIMIT_KERNELS) { kernel ->
        // Лоскут 4×2: левые два столбца — кость (4 клетки), правые — мягкая ткань.
        val w = gameWorld().vacuum()
        val first = w.bodyFirst(w.addBody(patch(4, 2, bone = { c, _ -> c <= 1 })))
        val st = w.state
        val boneCell = first + 1; val soft = first + 2          // связь контура между костью и тканью, нижний ряд
        assertTrue(w.isBone(boneCell)); assertFalse(w.isBone(soft))
        val c = st.linkBetween(boneCell, soft)
        assertTrue("связь кость–ткань на контуре", st.conBoundary[c])
        // Мягкую клетку оттянули: связь длиннее предела.
        st.px[soft] += 0.2 * T_LINK
        val boneX = st.px[boneCell]; val boneY = st.py[boneCell]
        kernel.run(st, h)
        assertEquals("клетка кости не сдвинулась (x)", boneX, st.px[boneCell], 0.0)
        assertEquals("клетка кости не сдвинулась (y)", boneY, st.py[boneCell], 0.0)
        assertEquals("связь вернулась на предел", LINK_MAX_STRETCH * st.conRest[c], w.distance(boneCell, soft), 1e-12)
        // С выключенной жёсткостью костей поправка снова делится пополам.
        val w2 = gameWorld().vacuum()
        val f2 = w2.bodyFirst(w2.addBody(patch(4, 2, bone = { c2, _ -> c2 <= 1 })))
        val s2 = w2.state
        s2.bonesRigid = false
        s2.px[f2 + 2] += 0.2 * T_LINK
        val x2 = s2.px[f2 + 1]
        kernel.run(s2, h)
        assertTrue("без жёстких костей клетку тянет за связью", s2.px[f2 + 1] > x2)
    }

    @Test fun `даст ли нарушение предела событие - то же решение, что у самого предела`() {

        // limitViolationIsEvent — проба без записи для параллельного прохода: ответ обязан совпасть с делом.
        for ((stretch, tearing) in listOf(1.2 to true, 1.6 to true, 1.6 to false, 0.2 to true, 1.02 to true)) {
            val (st, c, first) = limitWorld(stretch)
            st.tearingOn = tearing
            val len2 = st.length(first).let { it * it }
            val crushed = tearing && sqrt(len2) < LINK_CRUSH_RATIO * st.conRest[c]
            val x = st.px[first]; val x1 = st.px[first + 1]
            val predicted = st.limitViolationIsEvent(c, len2, crushed, 1.0)
            assertEquals("проба ничего не пишет", x, st.px[first], 0.0); assertEquals(x1, st.px[first + 1], 0.0)
            assertFalse(st.linkTorn[c])
            if (stretch > LINK_MAX_STRETCH || crushed) st.solveLinkLimitViolation(c, len2, crushed, 1.0)
            assertEquals("растяжение $stretch, разрывы $tearing", predicted, st.linkTorn[c])
        }
    }
}
