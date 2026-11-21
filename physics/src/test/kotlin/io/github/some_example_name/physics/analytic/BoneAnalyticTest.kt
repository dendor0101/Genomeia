package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * КОСТЬ: проекция на позу покоя (shape matching) против того, что обязано быть по определению.
 *
 *  - Кость в своей позе, перенесённая и повёрнутая как угодно, поправки не получает: поза покоя
 *    задаёт форму, а не место.
 *  - Сдвинутая клетка возвращается, и после проекции кость снова твёрдая: все расстояния между её
 *    клетками — как в позе покоя.
 *  - Проекция — внутренняя сила: центр масс кости она не двигает.
 *  - Поправка за подшаг ограничена потолком BONE_MAX_STEP и ужимается вся целиком, одним множителем.
 */
class BoneAnalyticTest {
    /** Костный брусок [cols] × [rows] клеток; возвращает мир и номера его частиц. */
    private fun bar(cols: Int = 5, rows: Int = 2): Pair<PhysicsWorld, IntArray> {
        val w = gameWorld().vacuum()
        val first = w.bodyFirst(w.addBody(patch(cols, rows, bone = { _, _ -> true })))
        return w to IntArray(cols * rows) { first + it }
    }

    private fun restDistances(w: PhysicsWorld, ids: IntArray): Array<DoubleArray> =
        Array(ids.size) { a -> DoubleArray(ids.size) { b -> w.distance(ids[a], ids[b]) } }

    private fun worstShapeError(w: PhysicsWorld, ids: IntArray, rest: Array<DoubleArray>): Double {
        var worst = 0.0
        for (a in ids.indices) for (b in a + 1 until ids.size) worst = maxOf(worst, Math.abs(w.distance(ids[a], ids[b]) - rest[a][b]))
        return worst
    }

    @Test fun `описание даёт одну кость на весь брусок`() {
        val (w, ids) = bar()
        assertEquals(1, w.state.rigidBones.size)
        assertEquals(ids.size, w.state.rigidBones[0].size)
        for (i in ids) { assertEquals(0, w.state.boneOf[i]); assertTrue(w.isBone(i)) }
    }

    @Test fun `кость в своей позе поправки не получает`() {
        val (w, ids) = bar()
        val st = w.state
        val x = DoubleArray(ids.size) { st.px[ids[it]] }; val y = DoubleArray(ids.size) { st.py[ids[it]] }
        st.projectBone(0)
        // Поза покоя посчитана заранее во float, отсюда остаток порядка 1e-7 единицы (см. BoneSolver.kt).
        for (k in ids.indices) {
            assertEquals(x[k], st.px[ids[k]], 5e-7); assertEquals(y[k], st.py[ids[k]], 5e-7)
        }
    }

    @Test fun `перенос и поворот позы покоя дают нулевую поправку`() {
        for (angle in doubleArrayOf(0.3, 1.5707963267948966, 2.5, -1.1)) {
            val (w, ids) = bar()
            val st = w.state
            val cx = ids.sumOf { st.px[it] } / ids.size; val cy = ids.sumOf { st.py[it] } / ids.size
            val cs = cos(angle); val sn = sin(angle)
            for (i in ids) {
                val rx = st.px[i] - cx; val ry = st.py[i] - cy
                st.px[i] = cx + 7.5 + rx * cs - ry * sn; st.py[i] = cy - 3.25 + rx * sn + ry * cs
            }
            val x = DoubleArray(ids.size) { st.px[ids[it]] }; val y = DoubleArray(ids.size) { st.py[ids[it]] }
            st.projectBone(0)
            for (k in ids.indices) {
                assertEquals("угол $angle, клетка $k, x", x[k], st.px[ids[k]], 5e-7)
                assertEquals("угол $angle, клетка $k, y", y[k], st.py[ids[k]], 5e-7)
            }
        }
    }

    @Test fun `сдвинутая клетка возвращается - кость после проекции снова твёрдая`() {
        val (w, ids) = bar()
        val st = w.state
        val rest = restDistances(w, ids)
        st.px[ids[3]] += 0.1 * T_LINK; st.py[ids[3]] -= 0.07 * T_LINK
        assertTrue(worstShapeError(w, ids, rest) > 0.05 * T_LINK)
        st.projectBone(0)
        assertEquals("после проекции расстояния между клетками кости — как в позе покоя", 0.0, worstShapeError(w, ids, rest), 1e-6 * T_LINK)
    }

    @Test fun `проекция не двигает центр масс кости`() {
        val (w, ids) = bar()
        val st = w.state
        for ((k, i) in ids.withIndex()) { st.px[i] += 0.02 * T_LINK * sin(1.7 * k); st.py[i] += 0.03 * T_LINK * cos(0.9 * k) }
        val cx = ids.sumOf { st.px[it] } / ids.size; val cy = ids.sumOf { st.py[it] } / ids.size
        st.projectBone(0)
        assertEquals(cx, ids.sumOf { st.px[it] } / ids.size, 1e-13)
        assertEquals(cy, ids.sumOf { st.py[it] } / ids.size, 1e-13)
    }

    @Test fun `проекция не двигает центр масс и при неравных весах клеток, и когда одна выпала из кости`() {
        val (w, ids) = bar()
        val st = w.state
        // Веса клеток разные, поза покоя под них не отцентрована: сумма поправок обязана обнулиться явно.
        for ((k, i) in ids.withIndex()) st.matchWeight[i] = 1.0 + 0.5 * (k % 3)
        st.matchWeight[ids[7]] = 0.0
        for ((k, i) in ids.withIndex()) { st.px[i] += 0.03 * T_LINK * cos(2.1 * k); st.py[i] -= 0.02 * T_LINK * sin(1.3 * k) }
        fun centre(): Pair<Double, Double> {
            var sx = 0.0; var sy = 0.0; var sw = 0.0
            for (i in ids) { val m = st.matchWeight[i]; sx += m * st.px[i]; sy += m * st.py[i]; sw += m }
            return sx / sw to sy / sw
        }
        val (cx, cy) = centre()
        val outX = st.px[ids[7]]
        st.projectBone(0)
        val (nx, ny) = centre()
        assertEquals(cx, nx, 1e-13); assertEquals(cy, ny, 1e-13)
        assertEquals("выпавшую клетку проекция не трогает", outX, st.px[ids[7]], 0.0)
    }

    @Test fun `кость из одних нерождённых клеток не проецируется, а совпавшие в точку клетки не дают NaN`() {
        val (w, ids) = bar()
        val st = w.state
        for (i in ids) st.matchWeight[i] = 0.0
        st.px[ids[2]] += 0.5 * T_LINK
        val x = DoubleArray(ids.size) { st.px[ids[it]] }
        val hits = st.boneCapHits
        st.projectBone(0)
        for (k in ids.indices) assertEquals(x[k], st.px[ids[k]], 0.0)
        assertEquals(hits, st.boneCapHits)

        val (w2, ids2) = bar()
        val s2 = w2.state
        for (i in ids2) { s2.px[i] = 1.0; s2.py[i] = 1.0 }
        s2.projectBone(0)
        for (i in ids2) { assertEquals(1.0, s2.px[i], 0.0); assertEquals(1.0, s2.py[i], 0.0) }
    }

    @Test fun `отражённая кость возвращается поворотом, а не остаётся вывернутой`() {
        val (w, ids) = bar()
        val st = w.state
        val rest = restDistances(w, ids)
        // Зеркало по вертикали вокруг средней линии бруска: лучший поворот для него — на 180 градусов или ноль,
        // и за несколько проекций кость снова твёрдая и той же ориентации обхода.
        val cy = ids.sumOf { st.py[it] } / ids.size
        for (i in ids) st.py[i] = 2 * cy - st.py[i]
        repeat(40) { st.projectBone(0) }
        assertEquals(0.0, worstShapeError(w, ids, rest), 1e-6 * T_LINK)
        val a = ids[0]; val b = ids[1]; val c = ids[5]
        val area = (st.px[b] - st.px[a]) * (st.py[c] - st.py[a]) - (st.py[b] - st.py[a]) * (st.px[c] - st.px[a])
        assertTrue("ориентация обхода клеток — как в позе покоя: $area", area > 0.0)
    }

    @Test fun `поправка кости за подшаг не больше потолка и ужимается целиком`() {
        val (w, ids) = bar()
        val st = w.state
        val before = Array(ids.size) { doubleArrayOf(st.px[ids[it]], st.py[ids[it]]) }
        // Клетку унесли на три связи: без потолка проекция вернула бы её почти целиком за один раз.
        st.px[ids[0]] += 3.0 * T_LINK
        val moved = doubleArrayOf(st.px[ids[0]], st.py[ids[0]])
        val hits = st.boneCapHits
        st.projectBone(0)
        assertEquals("потолок обязан сработать", hits + 1, st.boneCapHits)
        // Потолок считается от масштаба связи мира, а он хранится во float.
        val cap = BONE_MAX_STEP * st.linkScale
        var worst = 0.0
        for ((k, i) in ids.withIndex()) {
            val from = if (k == 0) moved else before[k]
            worst = maxOf(worst, hypot(st.px[i] - from[0], st.py[i] - from[1]))
        }
        assertEquals("самая большая поправка — ровно потолок", cap, worst, 1e-9 * cap)
    }

    @Test fun `клетка без веса в кости не участвует - нерождённая, ещё не включённая`() {
        val (w, ids) = bar()
        val st = w.state
        val rest = restDistances(w, ids)
        val out = ids[4]
        st.matchWeight[out] = 0.0
        st.px[out] += 2.0 * T_LINK
        val x = st.px[out]; val y = st.py[out]
        st.projectBone(0)
        assertEquals("клетку без веса проекция не трогает", x, st.px[out], 0.0); assertEquals(y, st.py[out], 0.0)
        val others = ids.filter { it != out }.toIntArray()
        val restOthers = Array(others.size) { a -> DoubleArray(others.size) { b -> rest[ids.indexOf(others[a])][ids.indexOf(others[b])] } }
        assertEquals("остальные клетки она за собой не тянет", 0.0, worstShapeError(w, others, restOthers), 1e-6 * T_LINK)
    }

    @Test fun `костный брусок в полёте и вращении в пустоте остаётся твёрдым`() {
        val (w, ids) = bar(6, 3)
        val st = w.state
        val rest = restDistances(w, ids)
        val cx = ids.sumOf { st.px[it] } / ids.size; val cy = ids.sumOf { st.py[it] } / ids.size
        val omega = 0.05 / DT
        for (i in ids) w.setVelocity(i, linksPerTick(1.0) - omega * (st.py[i] - cy), omega * (st.px[i] - cx))
        w.wake(ids[0])
        val before = w.momentum()
        repeat(200) { w.step() }
        assertEquals("кость в свободном полёте не гнётся", 0.0, worstShapeError(w, ids, rest), 1e-4 * T_LINK)
        val after = w.momentum()
        assertEquals("импульс вдоль хода", before[0], after[0], 1e-9 * Math.abs(before[0]))
        assertEquals("импульс поперёк хода", before[1], after[1], 1e-9 * Math.abs(before[0]))
    }

    @Test fun `две кости одного тела - два отдельных твёрдых куска`() {
        // Два костных столбца по краям мягкого лоскута: между ними мягкая ткань.
        val w = gameWorld().vacuum()
        val first = w.bodyFirst(w.addBody(patch(7, 3, bone = { c, _ -> c <= 1 || c >= 5 })))
        val st = w.state
        assertEquals(2, st.rigidBones.size)
        val a = st.rigidBones[0]; val b = st.rigidBones[1]
        assertTrue(a.none { it in b.toHashSet() })
        // Сдвиг одной кости целиком другую не трогает.
        val bx = DoubleArray(b.size) { st.px[b[it]] }
        for (i in a) st.px[i] -= 0.3 * T_LINK
        st.projectBone(0); st.projectBone(1)
        for (k in b.indices) assertEquals(bx[k], st.px[b[k]], 5e-7)
        assertTrue(first >= 0)
    }
}
