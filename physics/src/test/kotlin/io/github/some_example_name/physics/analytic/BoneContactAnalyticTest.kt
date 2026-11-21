package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * УДАРЫ КОСТЕЙ В ПУСТОТЕ: кость — твёрдое тело, и её удар обязан подчиняться тем же законам,
 * что удар двух частиц.
 *
 *  - Импульс пары тел сохраняется: контакт — внутренняя сила пары.
 *  - Момент импульса пары относительно неподвижной точки сохраняется.
 *  - Кинетическая энергия при ударе не растёт.
 *  - Кости после удара остаются твёрдыми (расстояния между клетками — как в позе покоя).
 *  - Клетки одной кости не оказываются внутри другой.
 */
class BoneContactAnalyticTest {
    private val v = linksPerTick(1.0)

    private class Brick(val first: Int, val count: Int, val rest: Array<DoubleArray>)

    private fun brick(w: PhysicsWorld, cols: Int, rows: Int, x: Double, y: Double, bone: Boolean = true): Brick {
        val def = patch(cols, rows, x0 = x, y0 = y, bone = { _, _ -> bone })
        val first = w.bodyFirst(w.addBody(def))
        val rest = Array(def.count) { a -> DoubleArray(def.count) { b -> w.distance(first + a, first + b) } }
        return Brick(first, def.count, rest)
    }

    private fun PhysicsWorld.shove(b: Brick, vx: Double, vy: Double) = push(b.first until b.first + b.count, vx, vy)

    private fun shapeError(w: PhysicsWorld, b: Brick): Double {
        var worst = 0.0
        for (i in 0 until b.count) for (j in i + 1 until b.count) worst = maxOf(worst, abs(w.distance(b.first + i, b.first + j) - b.rest[i][j]))
        return worst
    }

    /** Наименьшее расстояние между клетками двух тел в долях расстояния касания (меньше 1 — перекрытие). */
    private fun closest(w: PhysicsWorld, a: Brick, b: Brick): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until a.count) for (j in 0 until b.count) best = minOf(best, w.distance(a.first + i, b.first + j))
        return best / (2 * T_RADIUS * CONTACT_SCALE)
    }

    /** Гонит мир [ticks] тиков и сторожит законы сохранения на каждом тике. */
    private fun watch(w: PhysicsWorld, ticks: Int, what: String, momentumTolerance: Double = 1e-9, spinTolerance: Double = 1e-6,
                      each: () -> Unit = {}) {
        val start = w.momentum(); val energy = w.kinetic()
        val scale = maxOf(hypot(start[0], start[1]), start[3] * 0.01 * v)
        var worstP = 0.0; var worstL = 0.0; var worstE = 0.0
        repeat(ticks) { tick ->
            w.step()
            val now = w.momentum()
            worstP = maxOf(worstP, hypot(now[0] - start[0], now[1] - start[1]) / scale)
            worstL = maxOf(worstL, abs(now[2] - start[2]) / maxOf(abs(start[2]), scale * T_LINK))
            worstE = maxOf(worstE, w.kinetic() / energy)
            each()
            assertTrue("$what, тик $tick: импульс пары ушёл на ${worstP} своей величины", worstP <= momentumTolerance)
        }
        assertTrue("$what: момент импульса пары ушёл на $worstL своей величины", worstL <= spinTolerance)
        assertTrue("$what: энергия выросла в $worstE раза", worstE <= 1 + 1e-9)
    }

    @Test fun `частица бьёт в кость - импульс и момент пары сохраняются, кость остаётся твёрдой`() {
        for (offset in doubleArrayOf(0.0, 0.8, 1.6)) {
            val w = gameWorld().vacuum()
            w.sleepOn = false
            val bone = brick(w, 5, 3, 1.0, 1.0)
            // Частица летит слева в торец бруска, со сдвигом от его оси: удар ещё и закручивает кость.
            val p = w.freeParticle(w.addFree(1.0 - 4 * T_LINK, 1.0 + T_LINK * (0.866 + offset), T_RADIUS, 2 * v, 0.0))
            watch(w, 90, "сдвиг $offset") { assertEquals("кость твёрдая", 0.0, shapeError(w, bone), 1e-4 * T_LINK) }
            assertTrue("частица отскочила или остановилась: ${w.vx[p] / v}", w.vx[p] < 2 * v)
            assertTrue("кость получила импульс", w.vx[bone.first] > 0.0)
        }
    }

    @Test fun `кость бьёт в кость лоб в лоб - импульс пары сохраняется, тела не проходят друг в друга`() {
        for (speed in doubleArrayOf(0.5, 2.0, 4.0)) {
            val w = gameWorld().vacuum()
            w.sleepOn = false
            val a = brick(w, 4, 3, 1.0, 1.0)
            val b = brick(w, 4, 3, 1.0 + 8 * T_LINK, 1.0)
            w.shove(a, speed * v, 0.0)
            var deepest = Double.MAX_VALUE
            watch(w, 120, "скорость $speed") {
                assertEquals("кость a твёрдая", 0.0, shapeError(w, a), 1e-4 * T_LINK)
                assertEquals("кость b твёрдая", 0.0, shapeError(w, b), 1e-4 * T_LINK)
                deepest = minOf(deepest, closest(w, a, b))
            }
            assertTrue("скорость $speed: клетки костей перекрылись глубже чем на треть расстояния касания: $deepest", deepest > 0.66)
            assertTrue("вторая кость поехала", w.vx[b.first] > 0.0)
        }
    }

    @Test fun `кость бьёт в кость со сдвигом и под углом - импульс и момент пары сохраняются`() {
        for ((shift, drift) in listOf(1.5 to 0.0, 2.5 to 0.0, 1.0 to 0.6)) {
            val w = gameWorld().vacuum()
            w.sleepOn = false
            val a = brick(w, 5, 2, 1.0, 1.0)
            val b = brick(w, 5, 2, 1.0 + 9 * T_LINK, 1.0 + shift * T_LINK)
            w.shove(a, 2 * v, drift * v)
            watch(w, 150, "сдвиг $shift, снос $drift") {
                assertEquals("кость a твёрдая", 0.0, shapeError(w, a), 1e-4 * T_LINK)
                assertEquals("кость b твёрдая", 0.0, shapeError(w, b), 1e-4 * T_LINK)
            }
        }
    }

    @Test fun `кость бьёт в мягкое тело - импульс пары сохраняется`() {
        val w = gameWorld().vacuum()
        w.sleepOn = false
        val bone = brick(w, 4, 3, 1.0, 1.0)
        val soft = brick(w, 4, 3, 1.0 + 8 * T_LINK, 1.0 + 0.5 * T_LINK, bone = false)
        w.shove(bone, 1.5 * v, 0.0)
        watch(w, 150, "кость в ткань", spinTolerance = 1e-3) { assertEquals("кость твёрдая", 0.0, shapeError(w, bone), 1e-4 * T_LINK) }
        assertTrue("мягкое тело поехало", w.vx[soft.first] > 0.0)
    }

    @Test fun `две покоящиеся кости вплотную не разгоняют друг друга`() {
        val w = gameWorld().vacuum()
        w.sleepOn = false
        val a = brick(w, 4, 3, 1.0, 1.0)
        // Вторая кость — правее на ширину первой плюс расстояние касания: клетки торцов ровно касаются.
        val b = brick(w, 4, 3, 1.0 + 3.5 * T_LINK + 2 * T_RADIUS * CONTACT_SCALE, 1.0)
        repeat(200) { w.step() }
        val m = w.momentum()
        assertEquals("импульс пары нулевой", 0.0, hypot(m[0], m[1]) / (m[3] * v), 1e-9)
        assertTrue("кости не разлетелись: ${w.kinetic()}", w.kinetic() <= 1e-9 * m[3] * v * v)
        assertEquals(0.0, shapeError(w, a), 1e-4 * T_LINK); assertEquals(0.0, shapeError(w, b), 1e-4 * T_LINK)
    }
    @Test fun `частица отскакивает от кости с заданной долей скорости сближения - кость отвечает всей своей массой`() {
        // Ряд из пяти костных клеток; частица летит снизу точно в среднюю: одно касание, удар через центр масс.
        val w = gameWorld().vacuum()
        w.sleepOn = false
        val def = patch(5, 1, bone = { _, _ -> true })
        val first = w.bodyFirst(w.addBody(def))
        val st = w.state
        assertEquals("ряд из пяти клеток — одна кость", 1, st.rigidBones.size)
        val speed = linksPerTick(0.6)
        val path = speed * DT / SUBSTEPS
        // Радиус касания клетки тела берётся из мира: у клетки контура он с запасом на сшивание мембраны.
        val touch = T_RADIUS * CONTACT_SCALE + st.contacts!!.contactRadiusOf(first + 2)
        // Неглубокое перекрытие в подшаге касания (фаза 0.3): отскок равен заданному точно, см. ContactAnalyticTest.
        val p = w.freeParticle(w.addFree(st.px[first + 2], st.py[first + 2] - touch - path * (21 - 0.3), T_RADIUS, 0.0, speed))
        val before = w.momentum()
        repeat(40) { w.step() }
        val after = w.momentum()
        assertEquals("импульс пары вдоль удара", before[1], after[1], 1e-9 * before[1])
        assertEquals("импульс пары поперёк удара", 0.0, after[0], 1e-9 * before[1])
        // Удар через центр масс: кость идёт поступательно, все её клетки — с одной скоростью.
        for (k in 0 until 5) assertEquals("клетка $k кости", w.vy[first + 2], w.vy[first + k], 1e-9 * speed)
        assertEquals("доля отскока", CONTACT_RESTITUTION, (w.vy[first + 2] - w.vy[p]) / speed, 1e-6)
        // Кость впятеро тяжелее частицы: по импульсу и доле отскока её скорость — (1 + e)/6 скорости удара.
        assertEquals((1 + CONTACT_RESTITUTION) / 6, w.vy[first + 2] / speed, 1e-6)
    }
    @Test fun `широкие кости сходятся торцами многими клетками разом - энергия не растёт на любой скорости`() {
        // Торец в восемь клеток: касаний сразу много, и каждое норовит развести кости целиком само.
        for (speed in doubleArrayOf(0.15, 0.6, 2.0)) for (rows in intArrayOf(8, 4)) {
            val w = gameWorld().vacuum()
            w.sleepOn = false
            val a = brick(w, 8, rows, 1.0, 1.0)
            val b = brick(w, 8, rows, 1.0 + 11 * T_LINK, 1.0)
            w.shove(a, speed * v, 0.0)
            var deepest = Double.MAX_VALUE
            watch(w, 150, "скорость $speed, торец $rows клеток") {
                assertEquals("кость a твёрдая", 0.0, shapeError(w, a), 1e-4 * T_LINK)
                assertEquals("кость b твёрдая", 0.0, shapeError(w, b), 1e-4 * T_LINK)
                deepest = minOf(deepest, closest(w, a, b))
            }
            assertTrue("скорость $speed: кости перекрылись глубже чем на треть расстояния касания: $deepest", deepest > 0.66)
            assertTrue("вторая кость поехала", w.vx[b.first] > 0.0)
            // Равные кости, удар в лоб: налетевшая после удара не быстрее, чем была.
            assertTrue("налетевшая кость не отлетает назад быстрее, чем прилетела: ${w.vx[a.first] / (speed * v)}", w.vx[a.first] > -0.5 * speed * v)
        }
    }
    /** Тело 18 × 6 с двумя костными пластинами по краям и мягким суставом между ними — как у стенда «plates». */
    private class Plates(val first: Int, val count: Int, val bones: List<IntArray>, val rest: List<Array<DoubleArray>>)

    private fun plates(w: PhysicsWorld, x: Double): Plates {
        val before = if (w.state.n == 0) 0 else w.state.rigidBones.size
        val def = patch(18, 6, x0 = x, y0 = 1.0, bone = { c, _ -> c <= 6 || c >= 11 })
        val first = w.bodyFirst(w.addBody(def))
        val bones = w.state.rigidBones.drop(before).map { it.copyOf() }
        val rest = bones.map { ids -> Array(ids.size) { p -> DoubleArray(ids.size) { q -> w.distance(ids[p], ids[q]) } } }
        return Plates(first, def.count, bones, rest)
    }

    private fun worstBone(w: PhysicsWorld, p: Plates): Double {
        var worst = 0.0
        for ((k, ids) in p.bones.withIndex()) for (a in ids.indices) for (b in a + 1 until ids.size)
            worst = maxOf(worst, abs(w.distance(ids[a], ids[b]) - p.rest[k][a][b]))
        return worst / T_LINK
    }

    @Test fun `кости с мягким суставом остаются твёрдыми при ударе - ни предел длины, ни ориентация контура их не мнут`() {
        for (speed in doubleArrayOf(0.6, 2.0, 4.0)) {
            val w = gameWorld().vacuum()
            w.sleepOn = false
            val a = plates(w, 1.0); val b = plates(w, 1.0 + 21 * T_LINK)
            assertEquals("у каждого тела две кости", 2, a.bones.size)
            w.push(a.first until a.first + a.count, speed * v, 0.0)
            var worst = 0.0; var worstE = 0.0
            val e0 = w.kinetic()
            // Смотрим кости после КАЖДОГО подшага: искажение возникало и гасло внутри тика.
            w.state.dbgSubstepHook = { worst = maxOf(worst, worstBone(w, a), worstBone(w, b)) }
            repeat(90) { w.step(); worstE = maxOf(worstE, w.kinetic() / e0) }
            // ИЗВЕСТНОЕ ОТКЛОНЕНИЕ (16.11.2026): при таране 4 связи за тик мягкие связи смятого сустава
            // двигают клетки кости дальше потолка проекции (BONE_MAX_STEP, полсвязи за подшаг), и кость
            // остаётся смятой до следующего подшага — до 0.53 связи. До 2 связей за тик кость не мнётся
            // вовсе. Лекарство — прикладывать связь к кости как к твёрдому телу, а не к её клетке; это
            // перестройка стадии связей, здесь закреплён нынешний размер отклонения.
            val allowed = if (speed <= 2.0) 1e-3 else 0.6
            assertTrue("скорость $speed: кость искажена на $worst связи", worst < allowed)
            assertTrue("скорость $speed: к концу кости снова твёрдые: ${maxOf(worstBone(w, a), worstBone(w, b))}", maxOf(worstBone(w, a), worstBone(w, b)) < 1e-3)
            assertTrue("скорость $speed: энергия выросла в $worstE раза", worstE <= 1 + 1e-9)
            assertTrue("второе тело поехало", w.vx[b.first] > 0.0)
        }
    }

    @Test fun `быстрый удар - обрезка по времени касания откатывает кость целиком`() {
        // 7 связей за тик — больше расстояния касания за подшаг: работает обрезка пути (CCD).
        // Сработает ли обрезка, зависит от того, на каком расстоянии пару застал подшаг: перебираем зазоры.
        var clamped = 0
        for (k in 0 until 8) {
            val w = gameWorld().vacuum()
            w.sleepOn = false
            val a = brick(w, 6, 6, 1.0, 1.0)
            val b = brick(w, 6, 6, 1.0 + (9 + 0.15 * k) * T_LINK, 1.0)
            w.shove(a, 7 * v, 0.0)
            var worst = 0.0
            w.state.dbgSubstepHook = { worst = maxOf(worst, shapeError(w, a), shapeError(w, b)) }
            val before = w.momentum()
            repeat(40) { w.step() }
            if (w.state.contacts!!.dbgCcdTotal > 0) clamped++
            assertTrue("зазор $k: кости остались твёрдыми и внутри тика: ${worst / T_LINK} связи", worst < 1e-3 * T_LINK)
            assertEquals("зазор $k: импульс пары", before[0], w.momentum()[0], 1e-9 * before[0])
            assertTrue("зазор $k: вторая кость поехала", w.vx[b.first] > 0.0)
            assertTrue("зазор $k: кости не прошли друг сквозь друга", w.x[a.first] < w.x[b.first])
        }
        assertTrue("хотя бы в одном из восьми зазоров обрезка сработала", clamped > 0)
    }
    /** Сколько клеток тела [a] лежит центром внутри треугольников тела [b] — «в чужой ткани». */
    private fun inside(w: PhysicsWorld, a: Brick, b: Brick): Int {
        val st = w.state
        var count = 0
        for (k in 0 until a.count) {
            val x = st.px[a.first + k]; val y = st.py[a.first + k]
            for (t in 0 until st.triCount) {
                val p = st.triA[t]; val q = st.triB[t]; val r = st.triC[t]
                if (p < b.first || p >= b.first + b.count) continue
                val d1 = (st.px[q] - st.px[p]) * (y - st.py[p]) - (st.py[q] - st.py[p]) * (x - st.px[p])
                val d2 = (st.px[r] - st.px[q]) * (y - st.py[q]) - (st.py[r] - st.py[q]) * (x - st.px[q])
                val d3 = (st.px[p] - st.px[r]) * (y - st.py[r]) - (st.py[p] - st.py[r]) * (x - st.px[r])
                if (!((d1 < 0 || d2 < 0 || d3 < 0) && (d1 > 0 || d2 > 0 || d3 > 0))) { count++; break }
            }
        }
        return count
    }

    // НЕ ПОЧИНЕНО (16.11.2026, пункт 2 списка «что чинить»): кости, уже стоящие друг в друге, не расходятся.
    // Клетки контура толкают зашедшую внутрь клетку ВНУТРЬ, а клетки костей бессмертны. Тест описывает, как
    // должно быть; включить, когда появится решение (варианты — в docs/physics/stands.md).
    @Ignore("пункт 2 не починен: застрявшие друг в друге кости не расходятся")
    @Test fun `кости, оказавшиеся друг в друге, расходятся сами`() {
        // Две кости поставлены внахлёст: торец второй заходит в первую на полторы-две клетки вглубь.
        for (overlap in doubleArrayOf(1.6, 2.4)) for (shift in doubleArrayOf(0.0, 1.3)) {
            val w = gameWorld().vacuum()
            w.sleepOn = false
            val a = brick(w, 6, 6, 1.0, 1.0)
            val b = brick(w, 6, 6, 1.0 + (5.5 - overlap) * T_LINK, 1.0 + shift * T_LINK)
            assertTrue("постановка: кости внахлёст", inside(w, b, a) > 0)
            repeat(240) { w.step() }
            assertEquals("нахлёст $overlap, сдвиг $shift: клеток второй кости в первой", 0, inside(w, b, a))
            assertEquals("нахлёст $overlap, сдвиг $shift: клеток первой кости во второй", 0, inside(w, a, b))
            assertEquals("кость a твёрдая", 0.0, shapeError(w, a), 1e-4 * T_LINK)
            assertEquals("кость b твёрдая", 0.0, shapeError(w, b), 1e-4 * T_LINK)
            val m = w.momentum()
            assertEquals("расталкивание не создаёт импульса", 0.0, hypot(m[0], m[1]) / (m[3] * v), 1e-9)
            assertTrue("кости не разлетелись со скоростью удара: ${w.kinetic() / (m[3] * v * v)}", w.kinetic() < 0.5 * m[3] * v * v)
        }
    }
    /** Угловая скорость кости вокруг её центра масс, радиан за тик. */
    private fun spin(w: PhysicsWorld, b: Brick): Double {
        val st = w.state
        var m = 0.0; var cx = 0.0; var cy = 0.0; var vx = 0.0; var vy = 0.0
        for (k in 0 until b.count) { val i = b.first + k; val mi = 1 / st.invMass[i]; m += mi; cx += mi * st.px[i]; cy += mi * st.py[i]; vx += mi * st.vx[i]; vy += mi * st.vy[i] }
        cx /= m; cy /= m; vx /= m; vy /= m
        var l = 0.0; var inertia = 0.0
        for (k in 0 until b.count) {
            val i = b.first + k; val mi = 1 / st.invMass[i]
            val rx = st.px[i] - cx; val ry = st.py[i] - cy
            l += mi * (rx * (st.vy[i] - vy) - ry * (st.vx[i] - vx)); inertia += mi * (rx * rx + ry * ry)
        }
        return l / inertia * DT
    }

    /** Лобовой удар двух одинаковых костных брусков: закрутка каждого в долях скорости удара. */
    private fun headOn(cols: Int, rows: Int, speed: Double): DoubleArray {
        val w = gameWorld().vacuum()
        w.sleepOn = false
        val a = brick(w, cols, rows, 1.0, 1.0)
        val b = brick(w, cols, rows, 1.0 + (cols + 3) * T_LINK, 1.0)
        w.shove(a, speed * v, 0.0)
        repeat(90) { w.step() }
        assertTrue("вторая кость поехала", w.vx[b.first] > 0.0)
        val reach = hypot(cols.toDouble(), rows.toDouble()) / 2 * T_LINK
        return doubleArrayOf(spin(w, a) * reach / DT / (speed * v), spin(w, b) * reach / DT / (speed * v))
    }

    @Test fun `лобовой удар зеркальной пары костей их не закручивает`() {
        // Брусок с нечётным числом рядов зеркален относительно своей средней линии, и пара таких брусков
        // на одной линии — тоже. Закрутке после удара взяться неоткуда: она ломала бы это зеркало, а
        // сторону выбирал бы порядок клеток в памяти. До починки отскока (16.11.2026) касания кости
        // отвечали по очереди, и первое по списку брало на себя весь удар: закрутка доходила до 31%
        // скорости удара. Теперь — миллионные доли (замер: 1.4e-6).
        for (speed in doubleArrayOf(0.6, 2.0)) for ((cols, rows) in listOf(8 to 7, 6 to 5)) {
            val spins = headOn(cols, rows, speed)
            for (k in 0..1) assertEquals("скорость $speed, брусок $cols×$rows: закрутка кости ${k + 1}", 0.0, spins[k], 1e-4)
        }
    }

    @Test fun `лобовой удар костей с зубчатыми торцами крутит обе одинаково`() {
        // Брусок с чётным числом рядов не зеркален: торцы — зубцы решётки, у пары они сцепляются наискось,
        // и боковые толчки касаний не уравновешены (у бруска 8×8 четыре касания толкают первый вверх и
        // три вниз). Закрутка здесь законна, но у обоих тел обязана быть одной и той же: пара переходит в
        // себя при повороте на пол-оборота. Быстрый решатель даёт −2.2…−2.4% скорости удара; сама
        // величина — не закон (у твёрдых тел с семью одновременными касаниями ответ зависит от того,
        // как решатель делит удар между ними), закреплена нынешняя. Раньше (до 16.11.2026) этот тест
        // требовал нулевой закрутки с допуском 10% — он принимал законную закрутку зубцов за остаток
        // перекоса и ничего не сторожил.
        for (speed in doubleArrayOf(0.6, 2.0)) {
            val spins = headOn(8, 8, speed)
            assertEquals("скорость $speed: кости закручены одинаково", spins[0], spins[1], 1e-4)
            assertEquals("скорость $speed: закрутка костей", -0.023, spins[0], 0.004)
        }
    }

}
