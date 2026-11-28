package io.github.some_example_name.physics

import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class ContactRegressionTest {
    @Test(timeout = 5000)
    fun longSweepGrowsGridDuringInsertionAndKeepsBucketOrder() {
        val grid = SweptContactGrid()
        grid.begin(2)
        for (k in 0L until 20000L) grid.insert(k, k.toInt())
        for (k in 0L until 20000L) grid.insert(k, k.toInt() + 20000)
        for (k in 0L until 20000L) {
            val first = grid.first(k)
            assertEquals(k.toInt(), grid.ids[first])
            assertEquals(k.toInt() + 20000, grid.ids[grid.next[first]])
            assertEquals(-1, grid.next[grid.next[first]])
        }
        grid.begin(2)
        assertEquals(-1, grid.first(100L))
        grid.insert(100L, 7)
        assertEquals(7, grid.ids[grid.first(100L)])
    }

    @Test fun anchoredGridMatchesReferenceThroughGrowthAndCellDeletion() {
        // Случайные перекладки на тесной области ключей: ячейки то пустеют (deleteCell со
        // сдвигом цепочки), то заводятся снова, таблица растёт. Сверка с простой картой.
        val n = 3000
        val grid = AnchoredContactGrid(n)
        val ref = HashMap<Long, MutableList<Int>>()
        val foot = arrayOfNulls<LongArray>(n)
        val rnd = java.util.Random(7)
        repeat(40000) {
            val v = rnd.nextInt(n)
            foot[v]?.let { keys -> for (k in keys) ref[k]!!.remove(v); grid.remove(v) }
            val keys = LongArray(1 + rnd.nextInt(3)) { (rnd.nextInt(60).toLong() shl 32) or rnd.nextInt(60).toLong() }.distinct().toLongArray()
            for (k in keys) { grid.insert(v, k); ref.getOrPut(k) { mutableListOf() }.add(v) }
            foot[v] = keys
        }
        for (x in 0L until 60L) for (y in 0L until 60L) {
            val k = (x shl 32) or y
            val got = ArrayList<Int>()
            var e = grid.first(k)
            while (e >= 0) { got.add(grid.ids[e]); e = grid.next[e] }
            assertEquals((ref[k] ?: emptyList<Int>()).sorted(), got.sorted())
        }
        assertEquals(ref.values.sumOf { it.size }, grid.live)
    }

    private fun pair(): BoundaryContacts = BoundaryContacts.build(
        2, IntArray(0), IntArray(0), 0, IntArray(0), IntArray(0), 0, IntArray(0),
        floatArrayOf(0.5f, 0.5f), 1.0f, 0.4f, 0.1f, 0.0f,
        floatArrayOf(-2f, 2f), floatArrayOf(0f, 0f), 1.0f, 0.2f,
        booleanArrayOf(true, true),
    )

    @Test fun ccdStopsCrossingEvenWhenTravelIsLessThanContactDiameter() {
        val ct = pair()
        val px = floatArrayOf(0.2f, -0.2f); val py = FloatArray(2)
        ct.prepare(px, py, floatArrayOf(-0.25f, 0.25f), py.copyOf(),
            floatArrayOf(1.0f, -1.0f), FloatArray(2), floatArrayOf(1.0f, 1.0f))
        assertTrue("Центры поменялись местами", px[0] < px[1])
        assertEquals(0.0f, px[0] + px[1], F_POS)
    }

    @Test fun ccdWorkArraysResetAfterPassesWithoutCrossings() {
        val ct = pair()
        val y = FloatArray(2); val v = FloatArray(2); val mass = floatArrayOf(1.0f, 1.0f)
        fun crossing(): FloatArray {
            val x = floatArrayOf(0.2f, -0.2f)
            ct.prepare(x, y, floatArrayOf(-0.25f, 0.25f), y, v, v, mass)
            assertTrue(x[0] < x[1])
            return x
        }
        val first = crossing()
        repeat(3) {
            val x = floatArrayOf(-0.5f, 0.5f)
            ct.prepare(x, y, x.copyOf(), y, v, v, mass)
            assertArrayEquals(floatArrayOf(-0.5f, 0.5f), x, 0.0f)
        }
        assertArrayEquals(first, crossing(), 0.0f)
    }

    @Test fun refreshFindsNewContactAndPreservesExistingMultiplier() {
        val ct = pair()
        val px = floatArrayOf(-0.6f, 0.6f); val py = FloatArray(2)
        val vx = FloatArray(2); val vy = FloatArray(2); val mass = floatArrayOf(1.0f, 1.0f)
        ct.prepare(px, py, px.copyOf(), py.copyOf(), vx, vy, mass)
        assertEquals(0, ct.contactCount)
        px[0] = -0.4f; px[1] = 0.4f
        ct.prepare(px, py, px.copyOf(), py.copyOf(), vx, vy, mass, continuing = true)
        assertEquals(1, ct.contactCount)
        ct.solveContacts(px, py, vx, vy, mass, 0.01f)
        val lambda = ct.lambdaOf(0, 1)
        assertTrue(lambda > 0)
        ct.prepare(px, py, px.copyOf(), py.copyOf(), vx, vy, mass, continuing = true)
        assertEquals(lambda, ct.lambdaOf(0, 1), 0.0f)
    }

    @Test fun restPairCacheFollowsReconfigurationAndLegacyMode() {
        val empty = IntArray(0)
        val restX = floatArrayOf(-0.4f, 0.4f, 2f); val restY = FloatArray(3)
        val isolated = BooleanArray(3) { true }
        val ever = BoundaryContacts.linkedPairs(intArrayOf(0, 0), intArrayOf(1, 2), 2)
        val ct = BoundaryContacts.build(3, empty, empty, 0, empty, empty, 0, empty,
            FloatArray(3) { 0.5f }, 1.0f, 0.4f, 0.1f, 0.0f,
            restX, restY, 1.0f, 0.2f, isolated, ever)
        fun prepare(): Int {
            val x = floatArrayOf(-0.45f, 0.45f, 3.0f); val y = FloatArray(3)
            ct.prepare(x, y, x.copyOf(), y, FloatArray(3), FloatArray(3), FloatArray(3) { 1.0f })
            return ct.contactCount
        }
        // Бывшие соседи 0–1 упираются на 0.8, хотя сумма радиусов равна 1.
        assertEquals(0, prepare())
        val legacy = ct.abLegacy
        try {
            ct.abLegacy = true
            assertEquals(1, prepare())
        } finally { ct.abLegacy = legacy }
        assertEquals(0, prepare())
        // Тот же слот покоя теперь принадлежит паре 0–2. Кэш кандидата 0–1
        // обязан сброситься, иначе он ошибочно возьмёт чужое расстояние 0.8.
        ct.reconfigure(empty, empty, 0, empty, empty, 0, empty,
            floatArrayOf(-0.4f, 2f, 0.4f), restY, isolated, null, null, ever)
        assertEquals(1, prepare())
        ct.reconfigure(empty, empty, 0, empty, empty, 0, empty,
            restX, restY, isolated, null, null, ever)
        assertEquals(0, prepare())
    }

    @Test fun rigidContactBatchPreservesShapeAndLinearImpulse() {
        val batch = RigidContactBatch(SPIN_MIN_ARM * T_LINK)
        val x = floatArrayOf(-1.0f, 1.0f); val y = FloatArray(2)
        batch.begin(1)
        batch.add(0, -1.0f, 0.0f, 0.02f, -0.1f)
        batch.add(0, 1.0f, 0.0f, 0.04f, 0.1f)
        batch.apply(arrayOf(intArrayOf(0, 1)), floatArrayOf(2.0f), floatArrayOf(2.0f),
            floatArrayOf(0.0f), floatArrayOf(0.0f), x, y, floatArrayOf(1.0f, 1.0f), x, y, true)
        val dx = x[1] - x[0]; val dy = y[1] - y[0]
        assertEquals(2.0f, sqrt(dx * dx + dy * dy), F_POS)
        assertEquals(0.06f, x.sum(), F_POS)
        assertEquals(0.0f, y.sum(), F_POS)
    }

    @Test fun cappedCorrectionStoresOnlyActuallyAppliedLambda() {
        val ct = pair()
        val x = floatArrayOf(-0.1f, 0.1f); val y = FloatArray(2)
        val vx = FloatArray(2); val vy = FloatArray(2); val mass = floatArrayOf(1.0f, 1.0f)
        ct.prepare(x, y, x.copyOf(), y.copyOf(), vx, vy, mass)
        ct.solveContacts(x, y, vx, vy, mass, 0.01f)
        assertEquals((x[1] - x[0] - 0.2f) / 2.0f, ct.lambdaOf(0, 1), F_POS)
    }
}
