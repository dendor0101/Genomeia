package io.github.some_example_name.physics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * ЭТАЛОННЫЙ РЕШАТЕЛЬ ПРОВЕРЯЕТСЯ САМ — задачами с известным ответом, без оглядки на быстрый.
 * Иначе сверка быстрого с эталоном сравнивала бы две неизвестные. Импульс и момент импульса тел,
 * ни на что не опёртых, здесь обязаны сохраняться до ошибки округления. Сами стадии положений
 * момент импульса теряют — неявный шаг XPBD, порядка (сила × скорость × подшаг²): 1.6e-6 за 40 тиков
 * у свободного мягкого тела, 4.2e-5 за 30 тиков у кости после удара. Потерянное возвращает учёт
 * импульса за тик (ReferenceSolver.cancelMomentum) — тот же закон, что у быстрого решателя (TickMomentum).
 */
class ReferenceSolverTest {
    private val v = linksPerTick(1.0f)

    private fun world(): PhysicsWorld = gameWorld().vacuum().also { it.sleepOn = false }

    private fun PhysicsWorld.run(ticks: Int) { repeat(ticks) { stepReference() } }

    /** Угловая скорость частиц [range] вокруг их центра масс, радиан за тик. */
    private fun spin(w: PhysicsWorld, range: IntRange): Float {
        val st = w.state
        var m = 0.0f; var cx = 0.0f; var cy = 0.0f; var vx = 0.0f; var vy = 0.0f
        for (i in range) { val mi = 1 / st.restInvMass[i]; m += mi; cx += mi * st.px[i]; cy += mi * st.py[i]; vx += mi * st.vx[i]; vy += mi * st.vy[i] }
        cx /= m; cy /= m; vx /= m; vy /= m
        var l = 0.0f; var inertia = 0.0f
        for (i in range) {
            val mi = 1 / st.restInvMass[i]
            val rx = st.px[i] - cx; val ry = st.py[i] - cy
            l += mi * (rx * (st.vy[i] - vy) - ry * (st.vx[i] - vx)); inertia += mi * (rx * rx + ry * ry)
        }
        return l / inertia * DT
    }

    private fun body(w: PhysicsWorld, cols: Int, rows: Int, x: Float, y: Float, bone: Boolean = false): IntRange {
        val def = patch(cols, rows, x0 = x, y0 = y, bone = { _, _ -> bone })
        val first = w.bodyFirst(w.addBody(def))
        return first until first + def.count
    }

    @Test fun `свободное мягкое тело летит по прямой и сохраняет импульс и момент импульса`() {
        val w = world()
        val b = body(w, 6, 5, 1.0f, 1.0f)
        // Скорость центра плюс вращение и сжатие: внутренние ограничения работают весь полёт.
        val st = w.state
        var cx = 0.0f; var cy = 0.0f
        for (i in b) { cx += st.px[i]; cy += st.py[i] }
        cx /= b.count(); cy /= b.count()
        for (i in b) w.setVelocity(i, 0.7f * v - 0.4f * (st.py[i] - cy) / DT * 0.05f - 0.3f * v * (st.px[i] - cx) / T_LINK * 0.1f,
            0.2f * v + 0.4f * (st.px[i] - cx) / DT * 0.05f)
        val before = w.momentum()
        w.run(40)
        val after = w.momentum()
        assertEquals("импульс x", before[0], after[0], F_VEL * abs(before[0]))
        assertEquals("импульс y", before[1], after[1], F_VEL * abs(before[0]))
        // Замер 16.11.2026: расхождение меньше 1e-14 (без учёта импульса за тик уходило 1.6e-6 за 40 тиков).
        assertEquals("момент импульса", before[2], after[2], F_VEL * abs(before[2]))
        var nx = 0.0f; var ny = 0.0f
        for (i in b) { nx += st.px[i]; ny += st.py[i] }
        nx /= b.count(); ny /= b.count()
        // Центр масс прошёл ровно скорость × время.
        assertEquals("путь x", before[0] / before[3] * 40 * DT, nx - cx, F_VEL)
        assertEquals("путь y", before[1] / before[3] * 40 * DT, ny - cy, F_VEL)
    }

    @Test fun `лобовой удар двух частиц - отскок ровно по закону при любой фазе касания`() {
        // У быстрого решателя доля отскока плавает от 0.10 до 0.18 в зависимости от того, в какой точке
        // подшага частицы коснулись (LinkAnalyticTest). У эталона касание жёсткое, и доля — ровно закон.
        for (gap in floatArrayOf(0.31f, 0.5f, 0.77f, 1.13f, 1.9f)) {
            val w = world()
            val a = w.freeParticle(w.addFree(1.0f, 1.0f, T_RADIUS, 0.6f * v, 0.0f))
            val reach = 2 * w.state.contacts!!.contactRadiusOf(a)
            val b = w.freeParticle(w.addFree(1.0f + reach + gap * T_LINK, 1.0f, T_RADIUS, -0.6f * v, 0.0f))
            val before = w.momentum()
            var touched = false
            repeat(20) {
                w.stepReference()
                if (w.vx[a] < 0.0f) touched = true
                assertTrue("зазор $gap: круги не перекрыты", w.distance(a, b) >= reach - F_POS)
            }
            assertTrue("зазор $gap: частицы столкнулись", touched)
            assertEquals("зазор $gap: доля отскока", CONTACT_RESTITUTION, (w.vx[b] - w.vx[a]) / (1.2f * v), 2 * F_VEL)   // во float до 2.2e-4 (22.11)
            assertEquals("зазор $gap: импульс", before[0], w.momentum()[0], F_POS)
        }
    }

    @Test fun `частица о неподвижную частицу отскакивает с той же долей`() {
        val w = world()
        val wall = w.freeParticle(w.addFixed(1.0f, 1.0f, T_RADIUS))
        val a = w.freeParticle(w.addFree(1.0f + 3 * T_LINK, 1.0f, T_RADIUS, -0.9f * v, 0.0f))
        w.run(20)
        assertEquals(CONTACT_RESTITUTION * 0.9f * v, w.vx[a], F_VEL)
        assertEquals("неподвижная стоит", 1.0f, w.x[wall], 0.0f)
    }

    @Test fun `удар частицы в край кости - импульс и момент системы сохраняются, кость остаётся твёрдой`() {
        val w = world()
        val bone = body(w, 7, 2, 1.0f, 1.0f, bone = true)
        val st = w.state
        val rest = Array(bone.count()) { a -> FloatArray(bone.count()) { b -> w.distance(bone.first + a, bone.first + b) } }
        // Частица летит сверху ровно в правую верхнюю клетку бруска: удар не по центру, одно касание с
        // отвесной нормалью. Ответ — удар точки о твёрдое тело: обратная масса кости в точке удара
        // 1/M + плечо²/I, а доля отскока — CONTACT_RESTITUTION.
        val corner = bone.first + 13
        val ball = w.freeParticle(w.addFree(st.px[corner], st.py[corner] + 4 * T_LINK, T_RADIUS, 0.0f, -0.8f * v))
        var mass = 0.0f; var cx = 0.0f; var cy = 0.0f
        for (i in bone) { val m = 1 / st.restInvMass[i]; mass += m; cx += m * st.px[i]; cy += m * st.py[i] }
        cx /= mass; cy /= mass
        var inertia = 0.0f
        for (i in bone) inertia += ((st.px[i] - cx) * (st.px[i] - cx) + (st.py[i] - cy) * (st.py[i] - cy)) / st.restInvMass[i]
        val arm = st.px[corner] - cx
        val wBone = 1 / mass + arm * arm / inertia; val wBall = st.restInvMass[ball]
        val before = w.momentum()
        w.run(30)
        val after = w.momentum()
        assertTrue("кость закрутилась", abs(spin(w, bone)) > 1e-4f)
        assertEquals("скорость частицы после удара", -0.8f * v + (1 + CONTACT_RESTITUTION) * 0.8f * v * wBall / (wBall + wBone), w.vy[ball], F_VEL * v)
        // Импульс — до округления. Сами стадии его чуть вносят: кость каждый подшаг заново ставится по
        // позе, и ошибка в последнем знаке координаты, делённая на подшаг, даёт скорость порядка 1e-12
        // на клетку на подшаг (7e-10 импульса за тик у покоящейся кости из 14 клеток). Набежавшее
        // снимает учёт импульса за тик: замер 16.11.2026 — остаток 1.3e-15.
        assertEquals("импульс x", before[0], after[0], F_VEL * abs(before[1]))
        assertEquals("импульс y", before[1], after[1], F_VEL * abs(before[1]))
        // Вращение кости стадии положений гасят на (угол поворота за подшаг)² / 3 за подшаг: клетки идут
        // по касательной, а поза возвращает их на окружность — 4.2e-5 момента за 30 тиков. Учёт импульса
        // за тик возвращает и это: замер 16.11.2026 — остаток 1.4e-14.
        assertEquals("момент импульса", before[2], after[2], F_VEL * abs(before[2]))
        var worst = 0.0f
        for (a in 0 until bone.count()) for (b in 0 until bone.count()) worst = maxOf(worst, abs(w.distance(bone.first + a, bone.first + b) - rest[a][b]))
        assertEquals("искажение кости, связей", 0.0f, worst / T_LINK, F_VEL)
    }

    /** Лобовой удар двух одинаковых брусков [rows] рядов: закрутка каждого в долях скорости удара. */
    private fun headOn(bone: Boolean, rows: Int, speed: Float): FloatArray {
        val w = world()
        val a = body(w, 8, rows, 1.0f, 1.0f, bone)
        val b = body(w, 8, rows, 1.0f + 11 * T_LINK, 1.0f, bone)
        for (i in a) w.setVelocity(i, speed * v, 0.0f)
        w.run(90)
        assertTrue("второе тело поехало", w.vx[b.first] > 0.0f)
        val reach = hypot(8.0f, rows.toFloat()) / 2 * T_LINK
        return floatArrayOf(spin(w, a) * reach / DT / (speed * v), spin(w, b) * reach / DT / (speed * v))
    }

    @Test fun `лобовой удар зеркальной пары тел их не закручивает - ни кости, ни ткань`() {
        // Брусок в 7 рядов зеркален относительно своей средней линии, и пара таких брусков на одной линии —
        // тоже: любая закрутка ломала бы это зеркало, а её знак выбирал бы порядок клеток в памяти. Замер
        // 16.11.2026: кости 1e-5 скорости удара, ткань 6e-4. У быстрого решателя ткань на 2 связях за
        // тик крутится на 2.4% и 3.7% — это и есть перекос порядка обхода связей.
        for (bone in listOf(true, false)) for (speed in floatArrayOf(0.6f, 2.0f)) {
            val spins = headOn(bone, 7, speed)
            // Во float ткань закручивается до 3.9e-3 (22.11): зеркало около x = 1 округляется несимметрично, и удар
            // усиливает разницу; в double — 6e-4.
            for (k in 0..1) assertEquals("кость=$bone, скорость $speed: закрутка тела ${k + 1}", 0.0f, spins[k], if (bone) 1e-4f else 5e-3f)
        }
    }

    @Test fun `лобовой удар пары с зубчатыми торцами крутит оба тела одинаково`() {
        // Брусок в 8 рядов не зеркален: его торцы — зубцы шестиугольной решётки, и у пары брусков они
        // сцепляются наискось. Касаний семь, четыре толкают первый брусок вверх и три вниз: закрутка
        // здесь законна, но у обоих тел обязана быть одной и той же — пара переходит в себя при
        // повороте на пол-оборота. Сама величина закрутки костей — не закон: у твёрдых тел с семью
        // одновременными касаниями ответ зависит от того, как решатель делит удар между ними (замер:
        // от −0.2% до −1.4% скорости удара при разном числе проходов по касаниям).
        for (bone in listOf(true, false)) for (speed in floatArrayOf(0.6f, 2.0f)) {
            val spins = headOn(bone, 8, speed)
            // Во float кости с семью касаниями разом закручиваются по-разному на 0.009 скорости удара (0.025 и 0.034,
            // 22.11): деление удара между касаниями плохо обусловлено, и шум float его меняет; в double — в 2e-3.
            assertEquals("кость=$bone, скорость $speed: тела закручены одинаково", spins[0], spins[1], 0.02f)
            assertTrue("кость=$bone, скорость $speed: закрутка в пределах десятой скорости удара, ${spins[0]}", abs(spins[0]) < 0.1f)
        }
    }

    @Test fun `сетка находит те же пары касания, что и полный перебор`() {
        val rnd = Random(7)
        for (round in 0 until 6) {
            val w = world()
            // Частицы россыпью с разными радиусами и с неподвижными среди них; часть перекрыта.
            val count = 300 + 100 * round
            val side = sqrt(count.toFloat()) * T_LINK * (0.6f + 0.2f * round)
            for (k in 0 until count) {
                val x = 1.0f + rnd.nextDouble().toFloat() * side; val y = 1.0f + rnd.nextDouble().toFloat() * side
                val r = T_RADIUS * (0.5f + rnd.nextDouble().toFloat())
                if (k % 7 == 0) w.addFixed(x, y, r) else w.addFree(x, y, r)
            }
            // До шага: после него эталон перекрытий не оставляет, и сверять было бы нечего.
            val solver = ReferenceSolver(w.state)
            val all = solver.contactPairs(grid = false); val grid = solver.contactPairs(grid = true)
            assertTrue("раунд $round: пары есть", all.size > 20)
            assertArrayEquals("раунд $round", all, grid)
        }
    }

    @Test fun `связь контура, сжатая до порога или перетянутая за порог, рвётся`() {
        for (ratio in floatArrayOf(0.3f, 1.6f)) {
            val w = world()
            val first = w.bodyFirst(w.addBody(chain(2)))
            w.place(first + 1, w.x[first] + ratio * T_LINK, w.y[first])
            w.stepReference()
            assertEquals("длина $ratio длины покоя: связь порвана", 1, w.edgeDeathCount)
        }
        // В пределах — цела, а перетянутая до 1.3 возвращена к наибольшей длине 1.05.
        val w = world()
        val first = w.bodyFirst(w.addBody(chain(2)))
        w.place(first + 1, w.x[first] + 1.3f * T_LINK, w.y[first])
        w.stepReference()
        assertEquals(0, w.edgeDeathCount)
        assertTrue("связь не длиннее предела", w.distance(first, first + 1) <= LINK_MAX_STRETCH * w.state.conRest[0] + F_POS)
    }

    @Test fun `торможение свободной частицы о среду - ровно по закону, и у быстрого решателя то же`() {
        // У частицы нет контура, поэтому из среды на неё действуют двое: плата за разгон своего запаса
        // среды (FLOW_MASS × FLOW_ENTRAIN × тик — запас в первый тик стоит) и общее торможение.
        val keep = (1.0f - FLOW_MASS * FLOW_ENTRAIN * DT) * (1.0f - MEDIUM_DRAG * DT)
        for (reference in listOf(true, false)) {
            val w = gameWorld().also { it.sleepOn = false }
            val a = w.freeParticle(w.addFree(1.0f, 1.0f, T_RADIUS, 0.5f * v, -0.25f * v))
            if (reference) w.stepReference() else w.step()
            assertEquals("эталон=$reference", 0.5f * v * keep, w.vx[a], F_VEL * v)
            assertEquals("эталон=$reference", -0.25f * v * keep, w.vy[a], F_POS)
        }
    }

    @Test fun `мышца сжимает связь до своей команды`() {
        val w = world()
        val first = w.bodyFirst(w.addBody(chain(2)))
        w.setCellScale(first, 0.7f); w.setCellScale(first + 1, 0.7f)
        w.run(60)
        assertEquals(0.7f * w.state.conRest[0], w.distance(first, first + 1), 2e-3f * T_LINK)
    }

    @Test fun `хватка гасит за тик ту же долю скорости, что у игры`() {
        // Частица стоит у начала координат, а не у x = 1, как остальные: за тик хватка оставляет от скорости проценты
        // (0.8¹⁶ = 2.8%), и у единицы шаг float в позиции, делённый на подшаг эталона (1/1920 с), — уже 2·10⁻⁴ единицы в
        // секунду на каждом из 64 подшагов, то есть несколько процентов остатка. У нуля float в сто раз мельче.
        for (grip in floatArrayOf(0.2f, 0.5f, 1f)) {
            val w = world()
            val a = w.freeParticle(w.addFree(0.01f, 0.01f, T_RADIUS, 0.5f * v, 0.0f))
            w.setGrip(a, grip)
            w.stepReference()
            assertEquals("хватка $grip", 0.5f * v * StrictMath.pow((1.0f - grip).toDouble(), (SUBSTEPS.toFloat()).toDouble()).toFloat(), w.vx[a], F_VEL * v)
        }
    }

    @Test fun `на спокойной сцене эталон и быстрый решатель приходят в одно место`() {
        // Мягкое тело с толчком и вращением в среде, без касаний: всё, чем решатели отличаются, —
        // подшаг. Центр масс обязан сойтись до сотых связи, поворот — до сотых радиана.
        fun fly(reference: Boolean): FloatArray {
            val w = gameWorld().also { it.sleepOn = false }
            val b = body(w, 7, 6, 1.0f, 1.0f)
            val st = w.state
            var cx = 0.0f; var cy = 0.0f
            for (i in b) { cx += st.px[i]; cy += st.py[i] }
            cx /= b.count(); cy /= b.count()
            for (i in b) w.setVelocity(i, 1.5f * v - 0.03f * (st.py[i] - cy) / DT, 0.5f * v + 0.03f * (st.px[i] - cx) / DT)
            repeat(60) { if (reference) w.stepReference() else w.step() }
            var nx = 0.0f; var ny = 0.0f
            for (i in b) { nx += st.px[i]; ny += st.py[i] }
            nx /= b.count(); ny /= b.count()
            val far = b.last
            return floatArrayOf((nx - cx) / T_LINK, (ny - cy) / T_LINK, kotlin.math.atan2(st.py[far] - ny, st.px[far] - nx))
        }
        val fast = fly(false); val slow = fly(true)
        assertTrue("тело улетело", hypot(fast[0], fast[1]) > 20.0f)
        assertEquals("центр x, связей", slow[0], fast[0], 0.05f)
        assertEquals("центр y, связей", slow[1], fast[1], 0.05f)
        assertEquals("поворот, радиан", slow[2], fast[2], 0.02f)
    }
    @Test fun `зажатая мышца не раскручивает тело - и на шаге игры тоже`() {
        // Тело целиком из мышцы сжато вдвое и так стоит. Внешнего момента сил нет (среда только тормозит),
        // значит, вращению взяться неоткуда. Без учёта импульса за тик (cancelMomentum) эталон
        // на 6 подшагах раскручивал такое тело на 0.15 радиана за 30 тиков: поправки ограничений
        // сохраняют момент лишь до ошибки шага по времени, а дрожащая сжатая ткань копит её в одну сторону.
        for (substeps in intArrayOf(SUBSTEPS, REFERENCE_SUBSTEPS)) {
            val w = gameWorld().also { it.sleepOn = false }
            val def = patch(12, 3, muscle = { _, _ -> true })
            val first = w.bodyFirst(w.addBody(def))
            val b = first until first + def.count
            val st = w.state
            fun heading(): Float {
                var cx = 0.0f; var cy = 0.0f
                for (i in b) { cx += st.px[i]; cy += st.py[i] }
                return kotlin.math.atan2(st.py[first] - cy / def.count, st.px[first] - cx / def.count)
            }
            repeat(10) { w.stepReference(substeps) }
            for (i in b) w.setCellScale(i, 0.5f)
            repeat(40) { w.stepReference(substeps) }
            assertTrue("подшагов $substeps: мышца сжала тело", w.distance(first, first + 11) < 0.7f * 11 * T_LINK)
            val start = heading()
            repeat(120) { w.stepReference(substeps) }
            assertEquals("подшагов $substeps: тело не повернулось, радиан", start, heading(), 0.02f)
            assertEquals("подшагов $substeps: закрутки нет, радиан за тик", 0.0f, spin(w, b), 5e-4f)   // во float до 2.5e-4 на 64 подшагах (22.11)
        }
    }

    @Test fun `тело отскакивает от рамки мира целым`() {
        // Рамка мира — опора: тело, упёршееся в неё, в этом тике из учёта импульса выходит. Иначе учёт
        // возвращает телу импульс, отнятый стенкой, и стенка его раздавливает — у быстрого решателя в этой
        // же сцене гибнут 22 клетки из 56 (WorldFrameAnalyticTest).
        for (substeps in intArrayOf(SUBSTEPS, REFERENCE_SUBSTEPS)) {
            val w = world()
            val b = body(w, 8, 7, 1.0f, 1.0f)
            val st = w.state
            w.setBounds(0.5f, 0.5f, 1.0f + 11 * T_LINK, 2.0f)
            for (i in b) w.setVelocity(i, 2 * v, 0.0f)
            repeat(12) { w.stepReference(substeps) }
            assertEquals("подшагов $substeps: погибло клеток", 0, st.deadCount)
            val back = b.sumOf { st.vx[it].toDouble() }.toFloat() / b.count() / v
            assertTrue("подшагов $substeps: тело отскочило от стенки, связей за тик: $back", back < -0.5f && back > -2.0f)
        }
    }

    @Test fun `старое перекрытие расталкивает частицы одинаково при любом числе подшагов`() {
        // Две частицы поставлены внахлёст на десятую связи и стоят. Закон: раздвинуть до упора и дать
        // скорость «перекрытие за подшаг игры» — десятая связи на каждый подшаг тика (0.6 связи за тик при шести
        // подшагах, 1.6 при шестнадцати). До правки 16.11.2026 выходило «перекрытие за свой подшаг»: при шести
        // подшагах игры — 0.6 на 6 подшагах, 1.2 на 12, 6.4 на 64 — энергия из шага.
        // Шаги — игры и мельче: на шаге грубее игрового закон не действует (скорость выходит «за свой подшаг»).
        for (substeps in intArrayOf(SUBSTEPS, 2 * SUBSTEPS, maxOf(REFERENCE_SUBSTEPS, 4 * SUBSTEPS), maxOf(128, 8 * SUBSTEPS))) {
            val w = world()
            val a = w.freeParticle(w.addFree(1.0f, 1.0f, T_RADIUS))
            val b = w.freeParticle(w.addFree(1.0f + 10 * T_LINK, 1.0f, T_RADIUS))
            val rest = w.state.contacts!!.contactDistanceOf(a, b)
            w.place(b, 1.0f + rest - 0.1f * T_LINK, 1.0f)
            w.stepReference(substeps)
            assertTrue("подшагов $substeps: круги не перекрыты", w.distance(a, b) >= rest - F_POS)
            assertEquals("подшагов $substeps: скорость расхождения, связей за тик", 0.1f * SUBSTEPS, (w.vx[b] - w.vx[a]) / v, F_VEL)
            assertEquals("подшагов $substeps: импульс пары", 0.0f, w.momentum()[0], F_POS)
        }
    }

    @Test fun `старое перекрытие не мешает отскоку и не тормозит расходящихся`() {
        // Пара внахлёст выходит из касания со скоростью расхождения — большей из двух: положенной ей и так
        // (доля отскока, если сближалась; прежняя скорость, если расходилась) и скорости выталкивания
        // («перекрытие за подшаг игры»). И на шаге игры, и на мелком — одной и той же.
        // Тройки: нахлёст в связях, скорость расхождения до касания и после него, связей за тик.
        // Нахлёсты заданы через скорость выталкивания («нахлёст за подшаг игры»): 0.6 и 0.06 связи за тик при любом
        // числе подшагов — при шести это прежние десятая и сотая связи.
        val deep = 0.6f / SUBSTEPS; val shallow = 0.06f / SUBSTEPS
        val cases = arrayOf(
            floatArrayOf(deep, -1.5f, 0.6f),                              // сближались, отскок 0.15 слабее выталкивания 0.6
            floatArrayOf(shallow, -2.0f, CONTACT_RESTITUTION * 2.0f),     // сближались, отскок 0.2 сильнее выталкивания 0.06
            floatArrayOf(deep, 0.2f, 0.6f),                               // расходились медленнее выталкивания
            floatArrayOf(deep, 3.0f, 3.0f))                               // расходились быстрее — скорость прежняя
        for (substeps in intArrayOf(SUBSTEPS, REFERENCE_SUBSTEPS)) for ((overlap, speed, expected) in cases) {
            val w = world()
            val a = w.freeParticle(w.addFree(1.0f, 1.0f, T_RADIUS, -speed / 2 * v, 0.0f))
            val b = w.freeParticle(w.addFree(1.0f + 10 * T_LINK, 1.0f, T_RADIUS, speed / 2 * v, 0.0f))
            val rest = w.state.contacts!!.contactDistanceOf(a, b)
            w.place(b, 1.0f + rest - overlap * T_LINK, 1.0f)
            w.stepReference(substeps)
            assertEquals("подшагов $substeps, нахлёст $overlap, было $speed: частицы целы", 0, w.state.deadCount)
            assertEquals("подшагов $substeps, нахлёст $overlap, было $speed: скорость расхождения, связей за тик",
                expected, (w.vx[b] - w.vx[a]) / v, F_VEL)
        }
    }

    @Test fun `тела, поставленные внахлёст, раздвигаются и не рвутся - и на мелком шаге тоже`() {
        // Сцена каталога «касание в покое». До правки 16.11.2026 эталон на 64 подшагах расталкивал два
        // лоскута на 1.10 связи за тик (на шаге игры — 0.10) и рвал тела геномов в 58 таких сценах из 141.
        val speeds = FloatArray(2)
        for ((k, substeps) in intArrayOf(SUBSTEPS, REFERENCE_SUBSTEPS).withIndex()) {
            val w = world()
            val a = body(w, 6, 5, 1.0f, 1.0f)
            val b = body(w, 6, 5, 1.0f + 12 * T_LINK, 1.0f)
            val st = w.state
            // Ряды двух лоскутов стоят на одной высоте: правый придвинут, между крайними клетками рядов — нахлёст в десятую связи.
            val rest = st.contacts!!.contactDistanceOf(a.first + 5, b.first)
            val shift = (st.px[b.first] - st.px[a.first + 5]) - (rest - 0.1f * T_LINK)
            for (i in b) w.place(i, st.px[i] - shift, st.py[i])
            repeat(40) { w.stepReference(substeps) }
            assertEquals("подшагов $substeps: порвано связей", 0, st.tornTotal)
            assertEquals("подшагов $substeps: погибло клеток", 0, st.deadCount)
            assertTrue("подшагов $substeps: тела разошлись", st.px[b.first] - st.px[a.first + 5] > rest)
            speeds[k] = (b.sumOf { st.vx[it].toDouble() }.toFloat() / b.count() - a.sumOf { st.vx[it].toDouble() }.toFloat() / a.count()) / v
        }
        // Замер 16.11.2026, шесть подшагов игры: 0.103 связи за тик на шаге игры и 0.143 на 64 подшагах (ткань на
        // мелком шаге упруже и после первого толчка касается ещё раз). У игры 0.102. Скорость выталкивания —
        // «нахлёст за подшаг игры», то есть растёт с числом подшагов: на 16 подшагах (24.11) — 0.275, ровно в 16/6
        // раза больше. Поэтому ожидание — 0.0172 связи за тик на каждый подшаг тика.
        println("внахлёст: расходятся на %.3f связи за тик на шаге игры и %.3f на мелком".format(speeds[0], speeds[1]))
        assertEquals("шаг игры: скорость расхождения тел, связей за тик", 0.0172f * SUBSTEPS, speeds[0], 0.0017f * SUBSTEPS)
        assertTrue("мелкий шаг: скорость расхождения ${speeds[1]} — того же порядка, что на шаге игры (${speeds[0]})",
            speeds[1] > 0.78f * speeds[0] && speeds[1] < 1.95f * speeds[0])
    }

    @Test fun `удар быстрее своего шага эталон отмечает`() {
        // Две частицы навстречу, каждая на связь за подшаг игры: за подшаг игры пара сближается на две дистанции
        // касания. Обрезки по времени удара у эталона нет, и такой удар он проскакивает (замер 16.11.2026:
        // частицы проходят друг сквозь друга, энергия растёт на 32%). Он обязан это отметить: сверка стендов
        // такой прогон не судит. На шаге вдесятеро мельче тот же удар — обычный отскок, и отмечать нечего.
        // При шести подшагах это были 6 связей за тик и 64 подшага. При шестнадцати «связь за подшаг» — 16 связей
        // за тик, вдвое выше потолка скорости, поэтому потолок здесь снят (dbgNoClamp): законный удар шаг из 16
        // подшагов уже не проскакивает (на потолке путь за подшаг — полсвязи), но отметка эталону по-прежнему
        // нужна на грубом шаге, и проверяется она на ударе быстрее шага.
        val each = SUBSTEPS.toFloat()
        val fine = maxOf(REFERENCE_SUBSTEPS, 10 * SUBSTEPS)
        for ((substeps, missed) in listOf(SUBSTEPS to true, fine to false)) for (gap in floatArrayOf(0.3f, 0.9f)) {
            val w = world()
            w.state.dbgNoClamp = true
            val a = w.freeParticle(w.addFree(1.0f, 1.0f, T_RADIUS, each * v, 0.0f))
            val b = w.freeParticle(w.addFree(1.0f + 10 * T_LINK, 1.0f, T_RADIUS, -each * v, 0.0f))
            val rest = w.state.contacts!!.contactDistanceOf(a, b)
            w.place(b, 1.0f + rest + gap * T_LINK, 1.0f)
            repeat(3) { w.stepReference(substeps) }
            assertEquals("подшагов $substeps, зазор $gap: удар проскочен", missed, w.reference!!.missedHits > 0)
            if (!missed) assertEquals("подшагов $substeps, зазор $gap: отскок по закону", CONTACT_RESTITUTION, (w.vx[b] - w.vx[a]) / (2 * each * v), F_VEL)
        }
        // Удар, который шаг игры берёт (по 2 связи за тик навстречу), и пара, уже стоявшая внахлёст, — не отмечаются.
        for (speed in floatArrayOf(2.0f, 0.0f)) {
            val w = world()
            val a = w.freeParticle(w.addFree(1.0f, 1.0f, T_RADIUS, speed * v, 0.0f))
            val b = w.freeParticle(w.addFree(1.0f + 10 * T_LINK, 1.0f, T_RADIUS, -speed * v, 0.0f))
            val rest = w.state.contacts!!.contactDistanceOf(a, b)
            w.place(b, 1.0f + rest + (if (speed > 0) 0.9f else -0.7f) * T_LINK, 1.0f)
            repeat(6) { w.stepReference(SUBSTEPS) }
            assertEquals("скорость $speed: проскоченных ударов", 0, w.reference!!.missedHits)
        }
    }

    @Test fun `потолок скорости срезает лишнее и считает срабатывания`() {
        // Счётчик читает правило стендов «потолок скорости молчит». Пока эталон его не вёл, правило для
        // эталона загореться не могло, и строка сверки «кто нарушает правила» о потолке была пустой.
        val w = world()
        val a = w.freeParticle(w.addFree(1.0f, 1.0f, T_RADIUS))
        // Масштаб связи мир узнаёт с первой частицей: потолок считается после неё.
        val cap = MAX_SPEED_CELLS_PER_TICK * w.state.linkScale / DT
        assertEquals("потолок — 8 связей за тик", 8.0f * v, cap, 1e-6f * v)
        w.setVelocity(a, 0.5f * cap, 0.0f)
        w.stepReference(SUBSTEPS)
        assertEquals("ниже потолка счётчик молчит", 0, w.state.speedCapHits)
        assertEquals("скорость ниже потолка не тронута", 0.5f * cap, w.vx[a], F_VEL * cap)
        w.setVelocity(a, 3 * cap, 0.0f)
        w.stepReference(SUBSTEPS)
        assertTrue("выше потолка счётчик растёт", w.state.speedCapHits > 0)
        assertEquals("скорость срезана до потолка", cap, w.vx[a], F_VEL * cap)
    }
}
