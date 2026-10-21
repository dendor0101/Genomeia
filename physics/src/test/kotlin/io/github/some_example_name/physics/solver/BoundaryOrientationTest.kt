package io.github.some_example_name.physics

import org.junit.Assert.*
import org.junit.Test

class BoundaryOrientationTest {
    private fun area(x: DoubleArray, y: DoubleArray): Double =
        (x[1] - x[0]) * (y[2] - y[0]) - (y[1] - y[0]) * (x[2] - x[0])

    private fun solve(x: DoubleArray, y: DoubleArray, w: DoubleArray, sign: Byte = 1) =
        solveBoundaryOrientation(1, intArrayOf(0), intArrayOf(1), intArrayOf(2),
            byteArrayOf(sign), x, y, w)

    @Test fun foldedContactFromPlayerRecordPreservesLinearAndAngularImpulse() {
        // Тик 1198: сразу после первого контакта. Порядок 121,141,123
        // положителен в позе покоя, но контакт вывернул этот треугольник.
        val x = doubleArrayOf(2.0601891724618615, 2.0611818623315123, 2.061253627409214)
        val y = doubleArrayOf(1.018354342582386, 1.0374675541834986, 0.9876163220531716)
        val beforeX = x.copyOf(); val beforeY = y.copyOf()
        val w = doubleArrayOf(1.0, 0.25, 2.0)
        assertTrue(area(x, y) < 0.0)
        solve(x, y, w)
        assertEquals(0.0, area(x, y), 1e-16)
        var dx = 0.0; var dy = 0.0; var torque = 0.0
        for (i in x.indices) {
            val ix = (x[i] - beforeX[i]) / w[i]; val iy = (y[i] - beforeY[i]) / w[i]
            dx += ix; dy += iy
            torque += (beforeX[i] - beforeX[0]) * iy - (beforeY[i] - beforeY[0]) * ix
        }
        assertEquals(0.0, dx, 2e-15); assertEquals(0.0, dy, 2e-15)
        assertEquals(0.0, torque, 1e-16)
    }

    @Test fun compressedButNotInvertedTriangleIsBitwiseUntouched() {
        val x = doubleArrayOf(0.0, 1.0, 0.5); val y = doubleArrayOf(0.0, 0.0, 1e-12)
        val oldX = x.copyOf(); val oldY = y.copyOf()
        solve(x, y, DoubleArray(3) { 1.0 })
        assertArrayEquals(oldX, x, 0.0); assertArrayEquals(oldY, y, 0.0)
        y[2] = 0.0
        solve(x, y, DoubleArray(3) { 1.0 })
        assertArrayEquals(oldX, x, 0.0); assertEquals(0.0, y[2], 0.0)
    }

    @Test fun oppositeWindingAndFixedVerticesAreHandled() {
        val x = doubleArrayOf(0.0, 1.0, 0.3); val y = doubleArrayOf(0.0, 0.0, 0.1)
        solve(x, y, doubleArrayOf(0.0, 0.0, 2.0), -1)
        assertArrayEquals(doubleArrayOf(0.0, 1.0, 0.3), x, 0.0)
        assertEquals(0.0, y[2], 1e-16)
        y[2] = -0.1
        solve(x, y, DoubleArray(3))
        assertEquals(-0.1, y[2], 0.0)
    }

    @Test fun bareEdgesHaveNoOrientationConstraint() {
        val x = doubleArrayOf(0.0, 1.0); val y = doubleArrayOf(0.0, 1.0)
        solveBoundaryOrientation(1, intArrayOf(0), intArrayOf(1), intArrayOf(-1),
            byteArrayOf(0), x, y, DoubleArray(2) { 1.0 })
        assertArrayEquals(doubleArrayOf(0.0, 1.0), x, 0.0)
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
}
