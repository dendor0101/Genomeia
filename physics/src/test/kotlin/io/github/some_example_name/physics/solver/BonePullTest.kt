package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BonePullTest {
    @Test fun pullingOneEndMovesAndTurnsOnlyTheActiveBone() {
        val body = BodyDefinition(
            floatArrayOf(0f, 1f, 2f, 3f), FloatArray(4), FloatArray(4) { 0.5f },
            BooleanArray(4) { true }, BooleanArray(4), intArrayOf(0, 1, 2), intArrayOf(1, 2, 3),
            IntArray(0), IntArray(0), IntArray(0), FloatArray(0),
            arrayOf(intArrayOf(0, 1, 2, 3)), emptyArray(), 1f,
        )
        val world = PhysicsWorld(scale = WorldScale(1f, 0.25f))
        val id = world.addBody(body, BooleanArray(4) { true }, booleanArrayOf(true, true, true, false))
        val first = world.bodyFirst(id)
        world.pullToward(first + 2, world.x[first + 2], 20.0f)
        val vy = world.state.vy
        assertTrue(vy[first + 1] > 0.0f)
        assertTrue(vy[first + 2] > vy[first + 1])
        assertEquals(vy[first + 1] - vy[first], vy[first + 2] - vy[first + 1], 1e-12f)
        assertEquals(0.0f, vy[first + 3], 0.0f)
    }
}
