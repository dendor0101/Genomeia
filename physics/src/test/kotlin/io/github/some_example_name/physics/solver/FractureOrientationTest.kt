package io.github.some_example_name.physics

import org.junit.Assert.*
import org.junit.Test

class FractureOrientationTest {
    private fun triangles(count: Int): BodyDefinition {
        val n = count * 3
        return BodyDefinition(
            FloatArray(n) { (it / 3) * 3f + if (it % 3 == 1) 1f else 0f },
            FloatArray(n) { if (it % 3 == 2) 1f else 0f },
            FloatArray(n) { 0.5f }, BooleanArray(n), BooleanArray(n),
            IntArray(n) { (it / 3) * 3 + it % 3 },
            IntArray(n) { (it / 3) * 3 + (it + 1) % 3 },
            IntArray(count) { it * 3 }, IntArray(count) { it * 3 + 1 }, IntArray(count) { it * 3 + 2 },
            FloatArray(count) { 1f }, emptyArray(), emptyArray(), 1f,
        )
    }

    @Test fun onlyLiveTrianglesIncidentToDeathsAreCorrectedAcrossWordBoundaries() {
        val body = triangles(130)
        val topology = BakedTopology(body)
        val solve = FractureOrientation(body, topology)
        val x = DoubleArray(body.count) { body.x[it].toDouble() }
        val y = DoubleArray(body.count) { -body.y[it].toDouble() }
        val originalX = x.copyOf(); val originalY = y.copyOf()
        val w = DoubleArray(body.count) { if (it % 3 == 2) 1.0 else 0.0 }
        // Дубликат треугольника, обратный порядок заявок, биты 63/64, уже мёртвый t=65.
        topology.kill(topology.edgeOf(195, 196))
        val ids = intArrayOf(387, 193, 189, 192, 195)
        solve.solve(ids, ids.size, x, y, w)
        for (t in 0 until body.triCount) {
            if (t == 63 || t == 64 || t == 129) assertEquals(0.0, y[3 * t + 2], 1e-15)
            else assertEquals(originalY[3 * t + 2], y[3 * t + 2], 0.0)
        }
        assertArrayEquals(originalX, x, 0.0)
        // Буферы не сохраняют заявки: новый проход не должен трогать старые треугольники.
        originalY.copyInto(y)
        solve.solve(intArrayOf(0), 1, x, y, w)
        assertEquals(0.0, y[2], 1e-15)
        for (t in 1 until body.triCount) assertEquals(originalY[3 * t + 2], y[3 * t + 2], 0.0)
    }

    @Test fun incidentTrianglesAreResolvedBeforeTheirEdgesDisappear() {
        val body = triangles(1)
        val topology = BakedTopology(body)
        val solve = FractureOrientation(body, topology)
        val x = doubleArrayOf(0.0, 1.0, 0.2)
        val y = doubleArrayOf(0.0, 0.0, -0.4)
        val w = doubleArrayOf(1.0, 0.25, 2.0)
        val oldX = x.copyOf(); val oldY = y.copyOf()
        solve.solve(intArrayOf(0, 1), 2, x, y, w)
        val area = (x[1] - x[0]) * (y[2] - y[0]) - (y[1] - y[0]) * (x[2] - x[0])
        assertEquals(0.0, area, 1e-15)
        var dx = 0.0; var dy = 0.0; var torque = 0.0
        for (i in x.indices) {
            val ix = (x[i] - oldX[i]) / w[i]; val iy = (y[i] - oldY[i]) / w[i]
            dx += ix; dy += iy; torque += oldX[i] * iy - oldY[i] * ix
        }
        assertEquals(0.0, dx, 1e-15); assertEquals(0.0, dy, 1e-15); assertEquals(0.0, torque, 1e-15)
        topology.kill(topology.edgeOf(0, 1))
        assertFalse(topology.triAlive[0])
    }

    @Test fun validTrianglesAndEmptyRequestsAreBitwiseUntouched() {
        val body = triangles(1); val topology = BakedTopology(body)
        val solve = FractureOrientation(body, topology)
        val x = doubleArrayOf(0.0, 1.0, 0.2); val y = doubleArrayOf(0.0, 0.0, 0.4)
        val oldX = x.copyOf(); val oldY = y.copyOf()
        solve.solve(intArrayOf(0), 1, x, y, DoubleArray(3) { 1.0 })
        solve.solve(intArrayOf(2), 0, x, y, DoubleArray(3) { 1.0 })
        assertArrayEquals(oldX, x, 0.0); assertArrayEquals(oldY, y, 0.0)
    }

    @Test fun sparseSelectionMatchesCanonicalOrderOnSharedVertices() {
        // Общие вершины делают порядок существенным: поправка одного треугольника
        // меняет следующий. Упаковка живых списков не участвует: ID исходные.
        val count = 132
        val body = BodyDefinition(
            FloatArray(count) { (it / 2).toFloat() }, FloatArray(count) { (it % 2).toFloat() },
            FloatArray(count) { 0.5f }, BooleanArray(count), BooleanArray(count),
            intArrayOf(), intArrayOf(), IntArray(130) { it },
            IntArray(130) { it + if (it % 2 == 0) 2 else 1 },
            IntArray(130) { it + if (it % 2 == 0) 1 else 2 },
            FloatArray(130) { 1f }, emptyArray(), emptyArray(), 1f,
        )
        val topology = BakedTopology(body)
        topology.triAlive[64] = false
        val solve = FractureOrientation(body, topology)
        val x = DoubleArray(count) { body.x[it].toDouble() }
        val y = DoubleArray(count) { -body.y[it].toDouble() }
        val expectedX = x.copyOf(); val expectedY = y.copyOf()
        val w = DoubleArray(count) { 1.0 / (1 + it % 3) }
        for (ids in arrayOf(intArrayOf(128, 66, 65, 0, 65), intArrayOf(1, 63, 129))) {
            val touched = BooleanArray(count)
            for (v in ids) touched[v] = true
            for (t in 0 until body.triCount) {
                if (!topology.triAlive[t]) continue
                val a = body.triA[t]; val b = body.triB[t]; val c = body.triC[t]
                if (!touched[a] && !touched[b] && !touched[c]) continue
                val area = (expectedX[b] - expectedX[a]) * (expectedY[c] - expectedY[a]) -
                    (expectedY[b] - expectedY[a]) * (expectedX[c] - expectedX[a])
                if (area < 0.0) projectBoundaryOrientation(a, b, c, area, expectedX, expectedY, w)
            }
            solve.solve(ids, ids.size, x, y, w)
            assertArrayEquals(expectedX, x, 0.0); assertArrayEquals(expectedY, y, 0.0)
        }
    }
}
