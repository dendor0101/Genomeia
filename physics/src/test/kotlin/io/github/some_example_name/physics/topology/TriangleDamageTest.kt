package io.github.some_example_name.physics

import org.junit.Assert.*
import org.junit.Test

class TriangleDamageTest {
    private fun square(): BodyState {
        val body = BodyDefinition(
            floatArrayOf(0f, 1f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 1f),
            FloatArray(4) { 0.5f }, BooleanArray(4), BooleanArray(4),
            intArrayOf(0, 1, 0, 1, 2), intArrayOf(1, 2, 2, 3, 3),
            intArrayOf(0, 1), intArrayOf(1, 3), intArrayOf(2, 2),
            floatArrayOf(1f, 1f), emptyArray(), emptyArray(), 1f,
        )
        return BodyState().also {
            it.quiet = true
            it.initialize(body)
            it.resetState()
            it.tearingOn = true
        }
    }

    private fun assertTopology(s: BodyState) {
        val use = IntArray(s.baked.edgeCount)
        for (t in s.baked.triAlive.indices) if (s.baked.triAlive[t])
            for (k in 0..2) {
                val e = s.baked.triEdge[3 * t + k]
                if (e >= 0) use[e]++
            }
        assertArrayEquals(use, s.baked.triUse)
        assertEquals(s.baked.triAlive.count { it }, s.baked.liveTriangleCount)
        assertEquals(use.indices.count { use[it] == 0 && !s.baked.edgeDead[it] }, s.baked.bareEdges)
        val expected = (0 until s.conCount).filter {
            !s.conDead[it] && !s.baked.edgeDead[s.conEdge[it]] && use[s.conEdge[it]] <= 1
        }
        val list = s.boundaryLinkLimits
        assertEquals(expected, list.ids.copyOf(list.count).sorted())
    }

    @Test fun removesOnEleventhConsecutiveTickAndOnlyRemovesTheTriangle() {
        val s = square()
        s.py[0] = 2.0
        val x = s.px.copyOf(); val y = s.py.copyOf()
        val vx = s.vx.copyOf(); val vy = s.vy.copyOf()
        repeat(10) { s.removePersistentInversions() }
        assertTrue(s.baked.triAlive[0])
        assertEquals(10, s.baked.invertedTicks[0].toInt())
        s.removePersistentInversions()
        assertFalse(s.baked.triAlive[0])
        assertTrue(s.baked.triAlive[1])
        // Новый край доступен решателю сразу, до упаковки и перестройки контактов.
        assertTopology(s)
        val shared = s.baked.edgeOf(1, 2)
        assertTrue(s.conBoundary[s.conOfEdge[shared]])
        s.rebuildAfterTear()
        assertEquals(1, s.triCount)
        assertEquals(5, s.conCount)
        assertEquals(5, s.lnkCount)
        assertEquals(5, s.boundCount)
        assertEquals(0, s.deadCount)
        assertEquals(0, s.tornTotal)
        assertFalse(s.baked.edgeDead.any { it })
        assertArrayEquals(x, s.px, 0.0); assertArrayEquals(y, s.py, 0.0)
        assertArrayEquals(vx, s.vx, 0.0); assertArrayEquals(vy, s.vy, 0.0)
        assertTopology(s)
    }

    @Test fun recoveryAndDisabledTearingRestartTheConsecutiveTimer() {
        val s = square()
        s.py[0] = 2.0
        repeat(8) { s.removePersistentInversions() }
        s.py[0] = 1.0 // Нулевая площадь уже не отрицательная: серия прерывается.
        s.removePersistentInversions()
        assertEquals(0, s.baked.invertedTicks[0].toInt())
        s.py[0] = 2.0
        repeat(10) { s.removePersistentInversions() }
        assertTrue(s.baked.triAlive[0])
        s.tearingOn = false
        repeat(20) { s.removePersistentInversions() }
        assertEquals(0, s.baked.invertedTicks[0].toInt())
        assertTrue(s.baked.triAlive[0])
        s.tearingOn = true
        repeat(10) { s.removePersistentInversions() }
        assertTrue(s.baked.triAlive[0])
        s.removePersistentInversions()
        assertFalse(s.baked.triAlive[0])
    }

    @Test fun compactionKeepsOtherTimersAndResetRestoresEverything() {
        val s = square()
        s.py[0] = 2.0
        repeat(4) { s.removePersistentInversions() }
        s.px[3] = -1.0
        repeat(7) { s.removePersistentInversions() }
        s.rebuildAfterTear()
        assertArrayEquals(intArrayOf(1), s.triId.copyOf(s.triCount))
        assertEquals(7, s.baked.invertedTicks[1].toInt())
        repeat(3) { s.removePersistentInversions() }
        assertTrue(s.baked.triAlive[1])
        s.removePersistentInversions()
        s.rebuildAfterTear()
        assertEquals(0, s.triCount)
        assertTopology(s)
        s.resetState()
        assertEquals(2, s.triCount)
        assertEquals(2, s.baked.liveTriangleCount)
        assertTrue(s.baked.invertedTicks.all { it.toInt() == 0 })
        assertEquals(4, s.boundCount)
        assertTopology(s)
    }

    @Test fun triangleAndEdgeDeathsDoNotDoubleDecrementAdjacency() {
        val s = square()
        s.killTriangleNow(0)
        s.killTriangleNow(0)
        assertTopology(s)
        s.killEdgeNow(s.baked.edgeOf(1, 2))
        assertTopology(s)
        s.rebuildAfterTear()
        assertEquals(0, s.triCount)
        assertEquals(4, s.conCount)
        assertEquals(1, s.tornTotal)
        assertTopology(s)
    }
}
