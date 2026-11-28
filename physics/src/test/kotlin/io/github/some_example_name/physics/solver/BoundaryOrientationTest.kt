package io.github.some_example_name.physics

import org.junit.Assert.*
import org.junit.Test

class BoundaryOrientationTest {
    private fun area(x: FloatArray, y: FloatArray): Float =
        (x[1] - x[0]) * (y[2] - y[0]) - (y[1] - y[0]) * (x[2] - x[0])

    private fun solve(x: FloatArray, y: FloatArray, w: FloatArray, sign: Byte = 1) =
        solveBoundaryOrientation(1, intArrayOf(0), intArrayOf(1), intArrayOf(2),
            byteArrayOf(sign), x, y, w)

    @Test fun foldedContactFromPlayerRecordPreservesLinearAndAngularImpulse() {
        // Тик 1198: сразу после первого контакта. Порядок 121,141,123
        // положителен в позе покоя, но контакт вывернул этот треугольник.
        val x = floatArrayOf(2.0601891724618615f, 2.0611818623315123f, 2.061253627409214f)
        val y = floatArrayOf(1.018354342582386f, 1.0374675541834986f, 0.9876163220531716f)
        val beforeX = x.copyOf(); val beforeY = y.copyOf()
        val w = floatArrayOf(1.0f, 0.25f, 2.0f)
        assertTrue(area(x, y) < 0.0f)
        solve(x, y, w)
        assertEquals(0.0f, area(x, y), F_POS)
        var dx = 0.0f; var dy = 0.0f; var torque = 0.0f
        for (i in x.indices) {
            val ix = (x[i] - beforeX[i]) / w[i]; val iy = (y[i] - beforeY[i]) / w[i]
            dx += ix; dy += iy
            torque += (beforeX[i] - beforeX[0]) * iy - (beforeY[i] - beforeY[0]) * ix
        }
        assertEquals(0.0f, dx, F_POS); assertEquals(0.0f, dy, F_POS)
        assertEquals(0.0f, torque, F_POS)
    }

    @Test fun compressedButNotInvertedTriangleIsBitwiseUntouched() {
        val x = floatArrayOf(0.0f, 1.0f, 0.5f); val y = floatArrayOf(0.0f, 0.0f, 1e-12f)
        val oldX = x.copyOf(); val oldY = y.copyOf()
        solve(x, y, FloatArray(3) { 1.0f })
        assertArrayEquals(oldX, x, 0.0f); assertArrayEquals(oldY, y, 0.0f)
        y[2] = 0.0f
        solve(x, y, FloatArray(3) { 1.0f })
        assertArrayEquals(oldX, x, 0.0f); assertEquals(0.0f, y[2], 0.0f)
    }

    @Test fun oppositeWindingAndFixedVerticesAreHandled() {
        val x = floatArrayOf(0.0f, 1.0f, 0.3f); val y = floatArrayOf(0.0f, 0.0f, 0.1f)
        solve(x, y, floatArrayOf(0.0f, 0.0f, 2.0f), -1)
        assertArrayEquals(floatArrayOf(0.0f, 1.0f, 0.3f), x, 0.0f)
        assertEquals(0.0f, y[2], F_POS)
        y[2] = -0.1f
        solve(x, y, FloatArray(3))
        assertEquals(-0.1f, y[2], 0.0f)
    }

    @Test fun bareEdgesHaveNoOrientationConstraint() {
        val x = floatArrayOf(0.0f, 1.0f); val y = floatArrayOf(0.0f, 1.0f)
        solveBoundaryOrientation(1, intArrayOf(0), intArrayOf(1), intArrayOf(-1),
            byteArrayOf(0), x, y, FloatArray(2) { 1.0f })
        assertArrayEquals(floatArrayOf(0.0f, 1.0f), x, 0.0f)
    }

    @Test fun orientationFollowsIncrementalBoundarySwapsNewEdgesAndReset() {
        val definition = BodyDefinition(
            floatArrayOf(0f, 1f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 1f),
            FloatArray(4) { 0.5f }, BooleanArray(4), BooleanArray(4),
            intArrayOf(0, 1, 0, 1, 2), intArrayOf(1, 2, 2, 3, 3),
            intArrayOf(0, 1), intArrayOf(1, 3), intArrayOf(2, 2),
            floatArrayOf(1f, 1f), emptyArray(), emptyArray(), 1f,
        )
        val s = BodyState().also { it.quiet = true; it.initialize(definition); it.resetState() }
        fun verify() {
            val boundary = s.localBoundary
            for (k in 0 until boundary.count) {
                val a = boundary.a[k]; val b = boundary.b[k]; val c = boundary.opposite[k]
                val expected = if (c < 0) 0 else {
                    val rest = (definition.x[b] - definition.x[a]) * (definition.y[c] - definition.y[a]) -
                        (definition.y[b] - definition.y[a]) * (definition.x[c] - definition.x[a])
                    if (rest > 0f) 1 else -1
                }
                assertEquals(expected, boundary.orientation[k].toInt())
            }
        }
        verify()
        s.killEdgeNow(s.baked.edgeOf(0, 1)); s.refreshSurface(); verify()
        s.killEdgeNow(s.baked.edgeOf(1, 3)); s.refreshSurface(); verify()
        s.resetState(); verify()
    }
    @Test fun boneVerticesDoNotMoveWhileASoftVertexCanTakeTheCorrection() {
        // Вывернутый треугольник на стыке кости и ткани: вершины 0 и 1 — кость, вершина 2 — мягкая клетка.
        val x = floatArrayOf(0.0f, 1.0f, 0.4f); val y = floatArrayOf(0.0f, 0.0f, -0.2f)
        val w = floatArrayOf(1.0f, 1.0f, 1.0f)
        val bone = intArrayOf(0, 0, -1)
        solveBoundaryOrientation(1, intArrayOf(0), intArrayOf(1), intArrayOf(2), byteArrayOf(1), x, y, w, bone)
        assertArrayEquals("клетки кости стоят", floatArrayOf(0.0f, 1.0f), x.copyOf(2), 0.0f)
        assertArrayEquals(floatArrayOf(0.0f, 0.0f), y.copyOf(2), 0.0f)
        assertEquals("треугольник расправлен мягкой вершиной", 0.0f, area(x, y), F_POS)
        assertEquals("мягкая вершина вернулась на линию кости", 0.0f, y[2], F_POS)
        // Мягкая вершина закреплена — расправлять приходится кости, как раньше.
        val x2 = floatArrayOf(0.0f, 1.0f, 0.4f); val y2 = floatArrayOf(0.0f, 0.0f, -0.2f)
        solveBoundaryOrientation(1, intArrayOf(0), intArrayOf(1), intArrayOf(2), byteArrayOf(1), x2, y2, floatArrayOf(1.0f, 1.0f, 0.0f), bone)
        assertEquals(-0.2f, y2[2], 0.0f)
        assertEquals(0.0f, area(x2, y2), F_POS)
        assertTrue("кость сдвинулась к закреплённой клетке", y2[0] < 0.0f && y2[1] < 0.0f)
        // Все три вершины — кость: поправка делится как у обычной ткани.
        val x3 = floatArrayOf(0.0f, 1.0f, 0.4f); val y3 = floatArrayOf(0.0f, 0.0f, -0.2f)
        val x4 = x3.copyOf(); val y4 = y3.copyOf()
        solveBoundaryOrientation(1, intArrayOf(0), intArrayOf(1), intArrayOf(2), byteArrayOf(1), x3, y3, w, intArrayOf(0, 0, 1))
        solve(x4, y4, w)
        assertArrayEquals(x4, x3, 0.0f); assertArrayEquals(y4, y3, 0.0f)
    }
}
