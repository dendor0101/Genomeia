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

    private fun pair(): BoundaryContacts = BoundaryContacts.build(
        2, IntArray(0), IntArray(0), 0, IntArray(0), IntArray(0), 0, IntArray(0),
        doubleArrayOf(0.5, 0.5), 1.0, 0.4, 0.1, 0.0,
        floatArrayOf(-2f, 2f), floatArrayOf(0f, 0f), 1.0, 0.2,
        booleanArrayOf(true, true),
    )

    @Test fun ccdStopsCrossingEvenWhenTravelIsLessThanContactDiameter() {
        val ct = pair()
        val px = doubleArrayOf(0.2, -0.2); val py = DoubleArray(2)
        ct.prepare(px, py, doubleArrayOf(-0.25, 0.25), py.copyOf(),
            doubleArrayOf(1.0, -1.0), DoubleArray(2), doubleArrayOf(1.0, 1.0))
        assertTrue("Центры поменялись местами", px[0] < px[1])
        assertEquals(0.0, px[0] + px[1], 1e-14)
    }

    @Test fun ccdWorkArraysResetAfterPassesWithoutCrossings() {
        val ct = pair()
        val y = DoubleArray(2); val v = DoubleArray(2); val mass = doubleArrayOf(1.0, 1.0)
        fun crossing(): DoubleArray {
            val x = doubleArrayOf(0.2, -0.2)
            ct.prepare(x, y, doubleArrayOf(-0.25, 0.25), y, v, v, mass)
            assertTrue(x[0] < x[1])
            return x
        }
        val first = crossing()
        repeat(3) {
            val x = doubleArrayOf(-0.5, 0.5)
            ct.prepare(x, y, x.copyOf(), y, v, v, mass)
            assertArrayEquals(doubleArrayOf(-0.5, 0.5), x, 0.0)
        }
        assertArrayEquals(first, crossing(), 0.0)
    }

    @Test fun refreshFindsNewContactAndPreservesExistingMultiplier() {
        val ct = pair()
        val px = doubleArrayOf(-0.6, 0.6); val py = DoubleArray(2)
        val vx = DoubleArray(2); val vy = DoubleArray(2); val mass = doubleArrayOf(1.0, 1.0)
        ct.prepare(px, py, px.copyOf(), py.copyOf(), vx, vy, mass)
        assertEquals(0, ct.contactCount)
        px[0] = -0.4; px[1] = 0.4
        ct.prepare(px, py, px.copyOf(), py.copyOf(), vx, vy, mass, continuing = true)
        assertEquals(1, ct.contactCount)
        ct.solveContacts(px, py, vx, vy, mass, 0.01)
        val lambda = ct.lambdaOf(0, 1)
        assertTrue(lambda > 0)
        ct.prepare(px, py, px.copyOf(), py.copyOf(), vx, vy, mass, continuing = true)
        assertEquals(lambda, ct.lambdaOf(0, 1), 0.0)
    }

    @Test fun restPairCacheFollowsReconfigurationAndLegacyMode() {
        val empty = IntArray(0)
        val restX = floatArrayOf(-0.4f, 0.4f, 2f); val restY = FloatArray(3)
        val isolated = BooleanArray(3) { true }
        val ever = BoundaryContacts.linkedPairs(intArrayOf(0, 0), intArrayOf(1, 2), 2)
        val ct = BoundaryContacts.build(3, empty, empty, 0, empty, empty, 0, empty,
            DoubleArray(3) { 0.5 }, 1.0, 0.4, 0.1, 0.0,
            restX, restY, 1.0, 0.2, isolated, ever)
        fun prepare(): Int {
            val x = doubleArrayOf(-0.45, 0.45, 3.0); val y = DoubleArray(3)
            ct.prepare(x, y, x.copyOf(), y, DoubleArray(3), DoubleArray(3), DoubleArray(3) { 1.0 })
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
        val batch = RigidContactBatch()
        val x = doubleArrayOf(-1.0, 1.0); val y = DoubleArray(2)
        batch.begin(1)
        batch.add(0, -1.0, 0.0, 0.02, -0.1)
        batch.add(0, 1.0, 0.0, 0.04, 0.1)
        batch.apply(arrayOf(intArrayOf(0, 1)), doubleArrayOf(2.0), doubleArrayOf(2.0),
            doubleArrayOf(0.0), doubleArrayOf(0.0), x, y, doubleArrayOf(1.0, 1.0), x, y, true)
        val dx = x[1] - x[0]; val dy = y[1] - y[0]
        assertEquals(2.0, sqrt(dx * dx + dy * dy), 1e-14)
        assertEquals(0.06, x.sum(), 1e-14)
        assertEquals(0.0, y.sum(), 1e-14)
    }

    @Test fun cappedCorrectionStoresOnlyActuallyAppliedLambda() {
        val ct = pair()
        val x = doubleArrayOf(-0.1, 0.1); val y = DoubleArray(2)
        val vx = DoubleArray(2); val vy = DoubleArray(2); val mass = doubleArrayOf(1.0, 1.0)
        ct.prepare(x, y, x.copyOf(), y.copyOf(), vx, vy, mass)
        ct.solveContacts(x, y, vx, vy, mass, 0.01)
        assertEquals((x[1] - x[0] - 0.2) / 2.0, ct.lambdaOf(0, 1), 1e-14)
    }
}
