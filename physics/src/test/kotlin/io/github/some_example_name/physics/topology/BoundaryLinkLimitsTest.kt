package io.github.some_example_name.physics

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.random.asKotlinRandom

class BoundaryLinkLimitsTest {
    private fun grid(duplicate: Boolean = false): BodyState {
        val width = 5
        val triangles = ArrayList<IntArray>()
        val edges = linkedSetOf<Pair<Int, Int>>()
        fun tri(a: Int, b: Int, c: Int) {
            triangles.add(intArrayOf(a, b, c))
            for ((i, j) in arrayOf(a to b, b to c, c to a))
                edges.add(minOf(i, j) to maxOf(i, j))
        }
        for (y in 0 until width - 1) for (x in 0 until width - 1) {
            val a = y * width + x
            tri(a, a + 1, a + width)
            tri(a + 1, a + width + 1, a + width)
        }
        val links = edges.toMutableList()
        if (duplicate) links.add(links.first())
        val n = width * width
        val body = BodyDefinition(FloatArray(n) { (it % width).toFloat() },
            FloatArray(n) { (it / width).toFloat() }, FloatArray(n) { 0.5f },
            BooleanArray(n), BooleanArray(n), links.map { it.first }.toIntArray(),
            links.map { it.second }.toIntArray(), triangles.map { it[0] }.toIntArray(),
            triangles.map { it[1] }.toIntArray(), triangles.map { it[2] }.toIntArray(),
            FloatArray(triangles.size) { 1f }, emptyArray(), emptyArray(), 1f)
        return BodyState().also { it.quiet = true; it.initialize(body); it.resetState() }
    }

    private fun assertCurrent(s: BodyState) {
        val list = s.boundaryLinkLimits
        val actual = list.ids.copyOf(list.count).sorted()
        val expected = (0 until s.conCount).filter {
            !s.conDead[it] && !s.baked.edgeDead[s.conEdge[it]] && s.baked.triUse[s.conEdge[it]] <= 1
        }
        assertEquals(expected, actual)
        assertEquals(actual.size, actual.distinct().size)
        for (c in 0 until s.conCount) assertEquals(c in expected, s.conBoundary[c])
    }

    @Test fun currentContourIncludesHolesDuplicateLinksAndBareEdges() {
        val s = grid(duplicate = true)
        assertCurrent(s)
        val initial = s.boundaryLinkLimits.ids.copyOf(s.boundaryLinkLimits.count)
        val inner = (0 until s.conCount).first { !s.conBoundary[it] }
        s.killEdgeNow(s.conEdge[inner])
        // Проверяем ДО refreshSurface: список не должен ждать следующего прохода.
        assertCurrent(s)
        assertTrue(s.boundaryLinkLimits.ids.take(s.boundaryLinkLimits.count).any { it !in initial })
        val random = Random(917L)
        val order = (0 until s.baked.edgeCount).shuffled(random.asKotlinRandom())
        for (e in order) {
            s.killEdgeNow(e)
            assertCurrent(s)
        }
        assertEquals(0, s.boundaryLinkLimits.count)
        s.refreshSurface(); s.rebuildAfterTear()
        assertCurrent(s)
        s.resetState()
        assertCurrent(s)
        assertArrayEquals(initial, s.boundaryLinkLimits.ids.copyOf(s.boundaryLinkLimits.count))
    }

    @Test fun deathAndConstraintCompactionKeepCurrentIndices() {
        val s = grid()
        s.cellDead[12] = true
        s.killIncidentEdges(12)
        assertCurrent(s)
        s.refreshSurface()
        s.rebuildAfterTear()
        assertCurrent(s)
        s.killEdgeNow(s.conEdge[0])
        assertCurrent(s)
        s.resetState()
        assertCurrent(s)
    }

    @Test fun interiorStillHasElasticityButNoHardLengthOrCrushLimit() {
        for (restScale in doubleArrayOf(0.25, 4.0)) {
            val s = grid()
            val inner = (0 until s.conCount).first { !s.conBoundary[it] }
            s.conRest[inner] *= restScale
            s.tearingOn = true
            val beforeX = s.px.copyOf(); val beforeY = s.py.copyOf()
            s.solveLinkMaxLength()
            assertArrayEquals(beforeX, s.px, 0.0)
            assertArrayEquals(beforeY, s.py, 0.0)
            assertFalse(s.conDead.any { it })
            assertFalse(s.crushedCell.any { it })
            s.solveConstraints(1.0 / 480.0)
            assertFalse(beforeX.contentEquals(s.px) && beforeY.contentEquals(s.py))
        }
    }

    @Test fun tearsExposeAndCheckNewBoundaryWithinTheSamePass() {
        val s = grid(duplicate = true)
        s.tearingOn = true
        s.px.fill(0.0); s.py.fill(0.0)
        s.solveLinkMaxLength()
        assertTrue((0 until s.conCount).all { s.conDead[it] })
        assertEquals(0, s.boundaryLinkLimits.count)
        assertCurrent(s)
    }

    @Test fun removingVisitedAndFutureEntriesDoesNotSkipAppendedConstraints() {
        val list = BoundaryLinkLimits()
        list.reset(8)
        for (c in 0..3) list.add(c)
        list.beginPass()
        val visited = ArrayList<Int>()
        var cursor = 0
        while (cursor < list.count) {
            val c = list.ids[cursor++]
            if (c < 0) continue
            visited.add(c)
            if (c == 1) {
                list.remove(0); list.remove(1); list.remove(3)
                list.add(4); list.add(5); list.add(4)
            }
        }
        list.endPass()
        assertEquals(listOf(0, 1, 2, 4, 5), visited)
        assertArrayEquals(intArrayOf(2, 4, 5), list.ids.copyOf(list.count))
        list.remove(2); list.add(6)
        assertArrayEquals(intArrayOf(5, 4, 6), list.ids.copyOf(list.count))
    }
}
