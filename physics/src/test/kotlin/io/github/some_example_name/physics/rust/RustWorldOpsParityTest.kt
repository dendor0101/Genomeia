package io.github.some_example_name.physics

import io.github.some_example_name.physics.rust.RustPhysics
import io.github.some_example_name.physics.rust.RustWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * СВЕРКА ОПЕРАЦИЙ МИРА (RUST_PORT.md, веха 4): дописывание тел, рост из зиготы (рождение клеток, деление, кость по
 * клетке), пул свободных частиц и неподвижные частицы, удаление тел и уплотнение мира. Касания включены.
 */
class RustWorldOpsParityTest {
    @Before
    fun needLibrary() {
        assumeTrue("нет библиотеки физики Rust: ${RustPhysics.loadError}", RustPhysics.available)
    }

    private val link = RustScenes.LINK

    private fun body(x0: Float, y0: Float, cols: Int = 8, rows: Int = 6, bone: Boolean = false) =
        RustScenes.lattice(cols, rows, x0 = x0, y0 = y0,
            muscles = listOf(IntArray(cols) { cols * 2 + it }),
            bones = if (bone) listOf(intArrayOf(cols * (rows - 2) + 1, cols * (rows - 2) + 2,
                cols * (rows - 1) + 1, cols * (rows - 1) + 2)) else emptyList())

    private fun both(p: RustScenes.Pair, f: (PhysicsWorld) -> Int, g: (RustWorld) -> Int): Int {
        val a = f(p.k); val b = g(p.r)
        assertEquals("ответ операции разный", a, b)
        return a
    }

    @Test
    fun appendedBodiesCollideTheSame() {
        RustScenes.Pair(contacts = true).use { p ->
            val defs = listOf(body(1f, 1f, bone = true), body(1f + 9 * link, 1.02f), body(1f + 4 * link, 1f + 7 * link))
            for (d in defs) both(p, { it.addBody(d) }, { it.addBody(d) })
            p.check("три тела")
            for (c in 0 until 48) { p.k.setVelocity(48 + c, -1.5f, 0.2f); p.r.setVelocity(48 + c, -1.5f, 0.2f) }
            for (c in 0 until 48) { p.k.setVelocity(96 + c, 0.1f, -1.2f); p.r.setVelocity(96 + c, 0.1f, -1.2f) }
            var touched = 0
            for (t in 0 until 150) {
                val v = if ((t / 10) % 2 == 0) 1f else 0f
                for (m in 0 until p.k.muscleCount) { p.k.setMuscleTarget(m, v); p.r.setMuscleTarget(m, v) }
                p.step("дописанные тела")
                touched = maxOf(touched, p.k.state.contacts!!.contactCount)
            }
            assertTrue("тела не столкнулись", touched > 0)
        }
    }

    @Test
    fun growthFromZygoteMatches() {
        val def = body(1f, 1f, cols = 7, rows = 5, bone = true)
        RustScenes.Pair(contacts = true).use { p ->
            val born = BooleanArray(def.count).also { it[17] = true }
            val id = both(p, { it.addBody(def, born) }, { it.addBody(def, born) })
            val first = p.k.bodyFirst(id)
            assertEquals(first, p.r.bodyFirst(id))
            p.check("зигота")
            // Растём кольцами от зиготы: делением от соседа-родителя и рождением на месте.
            val order = (0 until def.count).sortedBy { (def.x[it] - def.x[17]).let { d -> d * d } + (def.y[it] - def.y[17]).let { d -> d * d } }
            var next = 1
            for (t in 0 until 220) {
                if (t % 3 == 0 && next < order.size) {
                    val cell = first + order[next]
                    if (next % 2 == 0) { p.k.activate(cell); p.r.activate(cell) }
                    else {
                        val parent = first + order[next - 1]
                        p.k.activateFromParent(cell, parent); p.r.activateFromParent(cell, parent)
                    }
                    next++
                }
                if (t == 150) for (c in def.boneClusters[0]) {
                    val a = p.k.setBone(first + c); val b = p.r.setBone(first + c)
                    assertEquals("setBone($c)", a, b)
                }
                p.step("рост из зиготы")
            }
            assertTrue("тело не выросло", (0 until def.count).count { !p.k.isUnborn(first + it) } > def.count / 2)
        }
    }

    @Test
    fun freeParticlesAndWallsMatch() {
        RustScenes.Pair(contacts = true).use { p ->
            both(p, { it.addBody(body(1f, 1f)) }, { it.addBody(body(1f, 1f)) })
            val ids = ArrayList<Int>()
            for (k in 0 until 300) {
                val x = 1.3f + (k % 20) * 0.6f * link; val y = 0.95f + (k / 20) * 0.6f * link
                ids += both(p, { it.addFree(x, y, 0.3f * link, -0.2f, 0.05f) }, { it.addFree(x, y, 0.3f * link, -0.2f, 0.05f) })
            }
            for (k in 0 until 40) {
                val x = 0.95f; val y = 0.9f + k * 0.5f * link
                both(p, { it.addFixed(x, y, 0.4f * link) }, { it.addFixed(x, y, 0.4f * link) })
            }
            p.check("газ и стенка")
            var touched = 0
            for (t in 0 until 120) {
                if (t % 25 == 10) for (q in 0 until 15) {
                    val id = ids.removeAt(ids.size - 1)
                    p.k.removeFree(id); p.r.removeFree(id)
                }
                if (t == 60) for (q in 0 until 20) {
                    val id = both(p, { it.addFree(1.5f, 1.2f + q * link, 0.3f * link) }, { it.addFree(1.5f, 1.2f + q * link, 0.3f * link) })
                    ids += id
                }
                p.step("газ, стенка, убывание и добавка")
                touched = maxOf(touched, p.k.state.contacts!!.contactCount)
            }
            assertTrue("частицы газа ни с чем не столкнулись: $touched", touched > 20)
        }
    }

    @Test
    fun removeBodyAndCompactionMatch() {
        RustScenes.Pair(contacts = true).use { p ->
            val defs = listOf(body(1f, 1f), body(1f + 10 * link, 1f), body(1f, 1f + 8 * link), body(1f + 10 * link, 1f + 8 * link))
            for (d in defs) both(p, { it.addBody(d) }, { it.addBody(d) })
            for (t in 0 until 40) p.step("до удаления")
            p.k.removeBody(1); p.r.removeBody(1)
            p.check("удалено тело 1 (ещё без уплотнения)")
            for (t in 0 until 20) p.step("после удаления")
            p.k.removeBody(2); p.r.removeBody(2)
            assertTrue("уплотнение не случилось", p.k.compactions > 0)
            assertEquals(p.k.compactions, p.r.compactions)
            p.check("уплотнение")
            for (b in listOf(0, 3)) assertEquals(p.k.bodyFirst(b), p.r.bodyFirst(b))
            for (t in 0 until 60) p.step("после уплотнения")
            p.k.compact(); p.r.compact()
            p.check("явное уплотнение")
            for (t in 0 until 30) p.step("после явного")
        }
    }

    @Test
    fun snapshotRoundTripsBothWays() {
        RustScenes.Pair(contacts = true).use { p ->
            val key = p.k.materialKey()
            for (d in listOf(body(1f, 1f, bone = true), body(1f + 9 * link, 1.02f))) both(p, { it.addBody(d) }, { it.addBody(d) })
            both(p, { it.addFree(1.2f, 0.9f, 0.3f * link) }, { it.addFree(1.2f, 0.9f, 0.3f * link) })
            for (c in 0 until 48) { p.k.setVelocity(48 + c, -1f, 0f); p.r.setVelocity(48 + c, -1f, 0f) }
            for (t in 0 until 50) p.step("до снимка")
            // Запись в обоих мирах: байты одинаковые, и после записи миры идут дальше вровень.
            val kb = java.io.ByteArrayOutputStream().also { p.k.write(java.io.DataOutputStream(it)) }.toByteArray()
            val rb = p.r.write(key)
            org.junit.Assert.assertArrayEquals("снимки Kotlin и Rust различаются", kb, rb)
            p.check("после записи")
            for (t in 0 until 30) p.step("после записи")
            // Снимок Kotlin → новый мир Rust; снимок Rust → новый мир Kotlin. Запись уплотняет мир, поэтому пишут оба
            // мира ровно по разу.
            val bytes = java.io.ByteArrayOutputStream().also { p.k.write(java.io.DataOutputStream(it)) }.toByteArray()
            val rustBytes = p.r.write(key)
            org.junit.Assert.assertArrayEquals("второй снимок Kotlin и Rust различается", bytes, rustBytes)
            RustScenes.Pair(contacts = true).use { q ->
                q.r.read(bytes, key)
                q.k.read(java.io.DataInputStream(rustBytes.inputStream()))
                q.check("прочитанные снимки")
                for (t in 0 until 40) { p.step("исходные"); q.step("из снимков") }
                // Исходный мир и мир из снимка сравниваются тем, что входит в снимок: счётчики событий (версия
                // структуры, срабатывания пределов) в него не входят и законно разные.
                val original = java.io.ByteArrayOutputStream().also { p.k.write(java.io.DataOutputStream(it)) }.toByteArray()
                org.junit.Assert.assertArrayEquals("мир Rust из снимка разошёлся с исходным", original, q.r.write(key))
            }
        }
    }

    /** Уплотнение посреди роста и разрывов: временные связи, нерождённые клетки, рваная ткань, сон. */
    @Test
    fun compactionWithGrowthAndTearsMatches() {
        RustScenes.Pair(contacts = true).use { p ->
            val grown = body(1f, 1f, cols = 7, rows = 5, bone = true)
            val born = BooleanArray(grown.count) { it % 3 != 0 }
            both(p, { it.addBody(grown, born) }, { it.addBody(grown, born) })
            val other = both(p, { it.addBody(body(1f + 9 * link, 1.02f)) }, { it.addBody(body(1f + 9 * link, 1.02f)) })
            val third = both(p, { it.addBody(body(1f, 1f + 8 * link)) }, { it.addBody(body(1f, 1f + 8 * link)) })
            val first0 = p.k.bodyFirst(1)
            for (t in 0 until 90) {
                if (t == 5) for (c in 0 until 6) {
                    val i = p.k.bodyFirst(other) + c
                    p.k.setVelocity(i, -6f, 1f); p.r.setVelocity(i, -6f, 1f); p.k.wake(i); p.r.wake(i)
                }
                if (t % 4 == 0) for (k in 0 until grown.count) if (!born[k] && p.k.isUnborn(first0 + k)) {
                    p.k.activate(first0 + k); p.r.activate(first0 + k); break
                }
                if (t == 40) { p.k.removeBody(third); p.r.removeBody(third) }
                if (t == 50) { p.k.compact(); p.r.compact() }
                p.step("рост, разрывы, уплотнение")
            }
            assertTrue("уплотнение не случилось", p.k.compactions > 0)
        }
    }
}
