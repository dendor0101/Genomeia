package io.github.some_example_name.physics

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class DistanceSweepOrderTest {
    private fun state(): BodyState {
        val n = 25
        val a = ArrayList<Int>(); val b = ArrayList<Int>()
        for (i in 0 until n) {
            if (i % 5 < 4) { a.add(i); b.add(i + 1) }
            if (i < 20) { a.add(i); b.add(i + 5) }
        }
        val body = BodyDefinition(FloatArray(n) { (it % 5).toFloat() },
            FloatArray(n) { (it / 5).toFloat() }, FloatArray(n) { 0.5f },
            BooleanArray(n), BooleanArray(n) { it % 3 == 0 }, a.toIntArray(), b.toIntArray(),
            IntArray(0), IntArray(0), IntArray(0), FloatArray(0), emptyArray(),
            arrayOf(intArrayOf(0, 3, 6, 9, 12, 15, 18, 21, 24)), 1f)
        return BodyState().also { it.quiet = true; it.initialize(body); it.resetState() }
    }

    private fun same(a: BodyState, b: BodyState) {
        for (i in 0 until a.n) {
            assertEquals("x[$i]", a.px[i].toRawBits(), b.px[i].toRawBits())
            assertEquals("y[$i]", a.py[i].toRawBits(), b.py[i].toRawBits())
        }
    }

    @Test fun preservesBothSweepOrdersWithMassesMusclesAndZeroLengthEdges() {
        val a = state(); val b = state()
        a.distanceSweepOrder = null
        assertNotNull(b.distanceSweepOrder)
        val random = Random(129047L)
        repeat(250) { tick ->
            for (i in 0 until a.n) {
                val x = random.nextDouble() * 4.0; val y = random.nextDouble() * 4.0
                a.px[i] = x; b.px[i] = x; a.py[i] = y; b.py[i] = y
                val w = if (random.nextInt(4) == 0) 0.0 else random.nextDouble() + 0.1
                a.invMass[i] = w; b.invMass[i] = w
            }
            if (tick % 3 == 0) {
                a.px[0] = a.px[1]; b.px[0] = b.px[1]
                a.py[0] = a.py[1]; b.py[0] = b.py[1]
            }
            val scale = random.nextDouble() * 0.7 + 0.3
            a.muscleScaleOf.fill(scale); b.muscleScaleOf.fill(scale)
            repeat(4) { pass ->
                a.sweepBackwards = pass % 2 == 0; b.sweepBackwards = a.sweepBackwards
                a.solveConstraints(DT / SUBSTEPS); b.solveConstraints(DT / SUBSTEPS); same(a, b)
            }
        }
    }

    @Test fun directIndicesFallBackAfterCompactionAndReturnOnReset() {
        val a = state(); val b = state()
        a.distanceSweepOrder = null
        assertNotNull(b.distanceSweepOrder)
        repeat(3) { cycle ->
            a.resetState(); b.resetState()
            a.distanceSweepOrder = null
            for (edge in intArrayOf(cycle, cycle + 7, cycle + 13)) {
                a.killEdgeNow(edge); b.killEdgeNow(edge)
            }
            repeat(6) { pass ->
                if (pass == 2) { a.rebuildAfterTear(); b.rebuildAfterTear() }
                a.px[12] += 0.02; b.px[12] += 0.02
                a.sweepBackwards = pass % 2 == 0; b.sweepBackwards = a.sweepBackwards
                a.solveConstraints(DT / SUBSTEPS); b.solveConstraints(DT / SUBSTEPS); same(a, b)
            }
        }
    }
    @Test fun orderKeepsEveryVertexSequenceAcrossChunkBoundaries() {
        val n = 1500
        val a = IntArray(3000) { it % n }
        val b = IntArray(3000) { (it * 7 + 1) % n }
        val body = BodyDefinition(FloatArray(n), FloatArray(n), FloatArray(n) { 0.5f },
            BooleanArray(n), BooleanArray(n), a, b, IntArray(0), IntArray(0), IntArray(0),
            FloatArray(0), emptyArray(), emptyArray(), 1f)
        val s = BodyState().also { it.quiet = true; it.initialize(body) }
        // Повторные пары тоже имеют отдельные номера ограничений и зависимости.
        assertNotNull(s.distanceSweepOrder)
        checkOrder(s)
        // Проверяем крупный план на уникальных связях кольца и длинных хордах.
        val aa = IntArray(n * 2) { it % n }
        val bb = IntArray(n * 2) { (it + if (it < n) 1 else 37) % n }
        val unique = BodyDefinition(FloatArray(n), FloatArray(n), FloatArray(n) { 0.5f },
            BooleanArray(n), BooleanArray(n), aa, bb, IntArray(0), IntArray(0), IntArray(0),
            FloatArray(0), emptyArray(), emptyArray(), 1f)
        val u = BodyState().also { it.quiet = true; it.initialize(unique) }
        checkOrder(u)
    }

    private fun checkOrder(u: BodyState) {
        val n = u.n
        val plan = u.distanceSweepOrder!!
        for (reverse in booleanArrayOf(false, true)) {
            val order = if (reverse) plan.forward.reversedArray() else plan.forward
            assertEquals(u.conCount, order.size)
            val rank = IntArray(u.conCount)
            for (k in order.indices) rank[order[k]] = k
            val last = IntArray(n) { -1 }
            val original = if (reverse) u.conCount - 1 downTo 0 else 0 until u.conCount
            for (c in original) {
                val i = u.conA[c]; val j = u.conB[c]
                assertTrue("direction=$reverse c=$c first end", rank[c] > last[i])
                assertTrue("direction=$reverse c=$c second end", rank[c] > last[j])
                last[i] = rank[c]; last[j] = rank[c]
            }
        }
    }

}
