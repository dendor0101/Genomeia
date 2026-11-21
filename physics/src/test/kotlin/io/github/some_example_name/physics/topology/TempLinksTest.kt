package io.github.some_example_name.physics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.sqrt

/** Временные связи роста (topology/TempLinks.kt, находка 32). */
class TempLinksTest {
    /** Масштаб мира игры: связь 0.6 и радиус 0.5 клетки игры при 0.048 единицы физики на клетку. */
    private val link = 0.0288
    private val r = 0.024
    private fun world() = PhysicsWorld(PhysicsConfig(emptyMap()), workers = null,
        scale = WorldScale(meanLinkLength = link.toFloat(), meanCellArea = r * r)).apply { state.quiet = true }

    /**
     * Три клетки в ряд: 0 — 1 — 2, связи 0–1 и 1–2. Связь 0–2 проходит прямо по клетке 1 — во взрослом теле она
     * «поперёк граней» и временная: снимает её рождение клетки 1.
     */
    private fun row(temp: Boolean): BodyDefinition {
        val def = BodyDefinition(
            floatArrayOf(1f, (1 + link).toFloat(), (1 + 2 * link).toFloat()), floatArrayOf(1f, 1f, 1f),
            FloatArray(3) { r.toFloat() }, BooleanArray(3), BooleanArray(3),
            intArrayOf(0, 1), intArrayOf(1, 2), IntArray(0), IntArray(0), IntArray(0), FloatArray(0),
            emptyArray(), emptyArray(), 1f,
        )
        return if (temp) def.withTempLinks(intArrayOf(0), intArrayOf(2)) else def
    }

    private fun dist(w: PhysicsWorld, a: Int, b: Int): Double {
        val st = w.state
        val dx = st.px[a] - st.px[b]; val dy = st.py[a] - st.py[b]
        return sqrt(dx * dx + dy * dy)
    }

    @Test fun blockersAreCellsLyingOnTheLinkAndCrossingLinks() {
        // Клетка 1 лежит на связи 0–2; связь 3–4 пересекает её крест-накрест; связь 5–6 стоит в стороне.
        val x = floatArrayOf(0f, 1f, 2f, 1.5f, 1.5f, 0f, 2f)
        val y = floatArrayOf(0f, 0.01f, 0f, -1f, 1f, 3f, 3f)
        val la = intArrayOf(0, 3, 5, 0); val lb = intArrayOf(2, 4, 6, 1)
        val b = crossingBlockers(x, y, la, lb, intArrayOf(0, 2))
        // Пары: (1, −1) — клетка на связи, (3, 4) — пересекающая связь.
        assertArrayEquals(intArrayOf(1, -1, 3, 4), b[0])
        assertArrayEquals(IntArray(0), b[1])
    }

    /**
     * Ромб: 0 и 1 — концы временной связи по горизонтали, 2 и 3 — снизу и сверху, связь 2–3 пересекает её посередине.
     * Связь снимается не при рождении первого конца пересекающей связи, а когда оживает она сама — рождён второй конец.
     */
    @Test fun crossingLinkRetiresTempLinkOnlyWhenItIsAlive() {
        val def = BodyDefinition(
            floatArrayOf(1f, (1 + 2 * link).toFloat(), (1 + link).toFloat(), (1 + link).toFloat()),
            floatArrayOf(1f, 1f, (1 - link).toFloat(), (1 + link).toFloat()),
            FloatArray(4) { r.toFloat() }, BooleanArray(4), BooleanArray(4),
            intArrayOf(0, 2, 0, 3, 2), intArrayOf(2, 1, 3, 1, 3), IntArray(0), IntArray(0), IntArray(0), FloatArray(0),
            emptyArray(), emptyArray(), 1f,
        ).withTempLinks(intArrayOf(0), intArrayOf(1))
        assertTrue(def.hasTempLinks)
        val w = world()
        val first = w.bodyFirst(w.addBody(def, booleanArrayOf(true, true, false, false)))
        val st = w.state
        val e = st.baked.edgeOf(first, first + 1)
        assertFalse("концы рождены, на связь ничего не легло — она жива", st.baked.edgeDead[e])
        w.activate(first + 2)
        w.step()
        assertFalse("родился один конец пересекающей связи — её самой ещё нет, временная жива", st.baked.edgeDead[e])
        w.activate(first + 3)
        assertTrue("ожила пересекающая связь — временная снята", st.baked.edgeDead[e])
        w.step()
        assertEquals(0, st.tornTotal)
    }

    /** Сетка — только ускорение: на случайных телах ответ тот же, что у перебора всех клеток и связей. */
    @Test fun gridAnswersLikeBruteForce() {
        val rnd = java.util.Random(7)
        repeat(40) { round ->
            val n = 5 + rnd.nextInt(60)
            val x = FloatArray(n) { rnd.nextFloat() * 6f }; val y = FloatArray(n) { rnd.nextFloat() * (if (round % 3 == 0) 0.5f else 6f) }
            val m = n + rnd.nextInt(2 * n)
            val la = IntArray(m); val lb = IntArray(m)
            for (k in 0 until m) { la[k] = rnd.nextInt(n); lb[k] = (la[k] + 1 + rnd.nextInt(n - 1)) % n }
            val temp = IntArray(m / 3) { rnd.nextInt(m) }
            val fast = crossingBlockers(x, y, la, lb, temp)
            for (q in temp.indices) assertArrayEquals("тело $round, связь ${temp[q]}", bruteBlockers(x, y, la, lb, temp[q]), fast[q])
        }
    }

    /** Перебор: каждая клетка и каждая связь против связи [k] — по тем же правилам, что crossingBlockers. */
    private fun bruteBlockers(x: FloatArray, y: FloatArray, la: IntArray, lb: IntArray, k: Int): IntArray {
        val a = la[k]; val b = lb[k]
        val ax = x[a].toDouble(); val ay = y[a].toDouble()
        val dx = x[b] - ax; val dy = y[b] - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-24) return IntArray(0)
        val pairs = sortedSetOf<Long>()
        val n = x.size
        for (o in 0 until n) {
            if (o == a || o == b) continue
            val ox = x[o] - ax; val oy = y[o] - ay
            val u = (ox * dx + oy * dy) / len2
            if (u < 0.1 || u > 0.9) continue
            val px = ox - u * dx; val py = oy - u * dy
            if (px * px + py * py < len2 / 16.0) pairs.add(o.toLong() * (n + 1))
        }
        fun orient(p: Int, q: Int, r: Int) = (x[q] - x[p]).toDouble() * (y[r] - y[p]).toDouble() - (y[q] - y[p]).toDouble() * (x[r] - x[p]).toDouble()
        for (j in la.indices) {
            if (j == k) continue
            val u = la[j]; val v = lb[j]
            if (u == a || u == b || v == a || v == b) continue
            val o1 = orient(a, b, u); val o2 = orient(a, b, v); val o3 = orient(u, v, a); val o4 = orient(u, v, b)
            if (((o1 > 0 && o2 < 0) || (o1 < 0 && o2 > 0)) && ((o3 > 0 && o4 < 0) || (o3 < 0 && o4 > 0)))
                pairs.add(minOf(u, v).toLong() * (n + 1) + maxOf(u, v) + 1)
        }
        val out = IntArray(2 * pairs.size); var s = 0
        for (key in pairs) { out[s++] = (key / (n + 1)).toInt(); out[s++] = (key % (n + 1)).toInt() - 1 }
        return out
    }

    @Test fun linkThatNothingCrossesIsNotTemporary() {
        val def = row(temp = false)
        // Связь 0–1 повторно: на неё никто не ложится — дописывать её временной некому снимать.
        val same = def.withTempLinks(intArrayOf(0), intArrayOf(1))
        assertFalse(same.hasTempLinks)
        val temp = row(temp = true)
        assertTrue(temp.hasTempLinks)
        assertEquals(3, temp.linkCount)
        assertTrue(temp.isTempLink(2))
    }

    @Test fun adultBodyIsBitwiseTheSameAsWithoutTempLinks() {
        val a = world(); a.addBody(row(temp = true))
        val b = world(); b.addBody(row(temp = false))
        assertFalse("у взрослого тела временных связей нет", a.state.body.hasTempLinks)
        for (w in listOf(a, b)) w.setVelocity(2, 0.4, 0.1)
        repeat(60) { a.step(); b.step() }
        assertArrayEquals(b.state.px, a.state.px, 0.0)
        assertArrayEquals(b.state.py, a.state.py, 0.0)
    }

    @Test fun tempLinkHoldsEndsUntilTheCellOnItIsBornAndThenGoesQuietly() {
        fun grown(temp: Boolean): Pair<PhysicsWorld, Int> {
            val w = world()
            val first = w.bodyFirst(w.addBody(row(temp), booleanArrayOf(true, false, true)))
            w.setVelocity(first + 2, 0.3, 0.0)
            repeat(40) { w.step() }
            return w to first
        }
        val (held, f1) = grown(temp = true)
        val (loose, f2) = grown(temp = false)
        val start = 2 * link
        println("концы через 40 тиков: с временной связью ${"%.3f".format(dist(held, f1, f1 + 2) / start)}, без неё ${"%.3f".format(dist(loose, f2, f2 + 2) / start)} длины покоя")
        assertTrue("временная связь держит концы", dist(held, f1, f1 + 2) < 1.2 * start)
        assertTrue("без неё концы расходятся", dist(loose, f2, f2 + 2) > 1.5 * start)

        // Рождается клетка, которая ложится на связь: связь снята — не разрыв и не событие для клиента.
        val st = held.state
        val e = st.baked.edgeOf(f1, f1 + 2)
        assertTrue(e >= 0 && !st.baked.edgeDead[e])
        val torn = st.tornTotal
        held.activate(f1 + 1)
        assertTrue("связь снята", st.baked.edgeDead[e])
        held.step()
        assertEquals("снятие — не разрыв", torn, st.tornTotal)
        for (k in 0 until held.edgeDeathCount) {
            val pair = setOf(held.edgeDeathA(k), held.edgeDeathB(k))
            assertFalse("снятие не уходит клиенту как гибель связи", pair == setOf(f1, f1 + 2))
        }
        assertFalse(st.baked.edgeDead[st.baked.edgeOf(f1, f1 + 1)])
        assertFalse(st.baked.edgeDead[st.baked.edgeOf(f1 + 1, f1 + 2)])
    }

    @Test fun cellOnTheLinkBornFirstMeansTheLinkIsNeverBorn() {
        val w = world()
        val first = w.bodyFirst(w.addBody(row(temp = true), booleanArrayOf(true, false, false)))
        w.activate(first + 1)
        w.activate(first + 2)
        w.step()
        val e = w.state.baked.edgeOf(first, first + 2)
        assertTrue("связь, на которую клетка легла раньше её концов, не рождается", e < 0 || w.state.baked.edgeDead[e])
        assertEquals(0, w.state.tornTotal)
    }

    /**
     * Мост: клетка 2 ложится на временную связь 0–1, но связана только с 1 — без связи 0–1 клетка 0 оторвалась бы от тела.
     * Связь ждёт; рождается клетка 3, связанная с 0 и с 2, — обходной путь есть, связь снята.
     */
    @Test fun bridgeWaitsForDetourAndThenGoes() {
        val def = BodyDefinition(
            floatArrayOf(1f, (1 + 2 * link).toFloat(), (1 + link).toFloat(), (1 + 0.5 * link).toFloat()),
            floatArrayOf(1f, 1f, (1 + 0.01 * link).toFloat(), (1 + 0.8 * link).toFloat()),
            FloatArray(4) { r.toFloat() }, BooleanArray(4), BooleanArray(4),
            intArrayOf(1, 0, 3), intArrayOf(2, 3, 2), IntArray(0), IntArray(0), IntArray(0), FloatArray(0),
            emptyArray(), emptyArray(), 1f,
        ).withTempLinks(intArrayOf(0), intArrayOf(1))
        assertTrue(def.hasTempLinks)
        val w = world()
        val first = w.bodyFirst(w.addBody(def, booleanArrayOf(true, true, false, false)))
        val st = w.state
        val e = st.baked.edgeOf(first, first + 1)
        w.activate(first + 2)
        assertFalse("клетка 0 держится только этой связью — снимать нельзя", st.baked.edgeDead[e])
        w.step()
        w.activate(first + 3)
        assertTrue("обходной путь 0–3–2–1 есть — связь снята", st.baked.edgeDead[e])
        w.step()
        assertEquals(0, st.tornTotal)
        assertEquals("тело одним куском", 1, st.organismOf.slice(first until first + 4).distinct().size)
    }

    @Test fun snapshotMidGrowthContinuesBitwise() {
        val a = world()
        val first = a.bodyFirst(a.addBody(row(temp = true), booleanArrayOf(true, false, true)))
        a.setVelocity(first + 2, 0.3, 0.05)
        repeat(20) { a.step() }
        val bytes = ByteArrayOutputStream().also { a.write(DataOutputStream(it)) }.toByteArray()
        val b = world()
        b.read(DataInputStream(ByteArrayInputStream(bytes)))
        assertTrue(b.state.body.hasTempLinks)
        repeat(20) { a.step(); b.step() }
        assertArrayEquals("x из снимка", a.state.px, b.state.px, 0.0)
        a.activate(first + 1); b.activate(first + 1)
        repeat(20) { a.step(); b.step() }
        assertArrayEquals("x после снятия", a.state.px, b.state.px, 0.0)
        assertArrayEquals("y после снятия", a.state.py, b.state.py, 0.0)
        assertEquals(a.state.tornTotal, b.state.tornTotal)
    }
}
