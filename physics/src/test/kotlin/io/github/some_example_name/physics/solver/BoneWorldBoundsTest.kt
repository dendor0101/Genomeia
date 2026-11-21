package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoneWorldBoundsTest {
    private fun world() = PhysicsWorld(scale = WorldScale(1f, 0.0625)).apply {
        state.quiet = true
        addBody(BodyDefinition(
            floatArrayOf(2f, 3f, 3f), floatArrayOf(2f, 2f, 3f), floatArrayOf(0.25f, 0.25f, 0.5f),
            BooleanArray(3) { true }, BooleanArray(3), intArrayOf(0, 1, 0), intArrayOf(1, 2, 2),
            intArrayOf(0), intArrayOf(1), intArrayOf(2), floatArrayOf(1f),
            arrayOf(intArrayOf(0, 1, 2)), emptyArray(), 1f,
        ))
        setBounds(0.0, 0.0, 6.0, 6.0)
    }

    @Test fun hitWallsAndCornersAsOneBone() {
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            val w = world()
            // Вершина с радиусом у самой стенки: уже предсказание первого подшага выходит наружу.
            fun offset(direction: Int) = when (direction) { -1 -> -1.75; 1 -> 2.5; else -> 0.0 }
            for (i in 0..2) {
                w.place(i, w.x[i] + offset(dx), w.y[i] + offset(dy))
                w.setVelocity(i, dx * 24.0, dy * 24.0)
            }
            repeat(16) {
                w.step()
                for (i in 0..2) {
                    val r = w.state.radius[i]
                    assertTrue(w.x[i] in r..(6.0 - r) && w.y[i] in r..(6.0 - r))
                }
                assertEquals(1.0, Math.hypot(w.x[1]-w.x[0], w.y[1]-w.y[0]), 1e-7)
                assertEquals(1.0, Math.hypot(w.x[2]-w.x[1], w.y[2]-w.y[1]), 1e-7)
            }
        }
    }

    @Test fun integrationQueuesOnlyHitsAndDeduplicatesTheBone() {
        val w = world()
        val second = w.addBody(w.state.body)
        val s = w.state
        s.integrateRange(0, s.n, DT / SUBSTEPS)
        assertEquals(0, s.wallHits.size)
        val first = w.bodyFirst(second)
        val hitBone = s.boneOf[first]
        assertTrue(hitBone >= 0 && hitBone != s.boneOf[0])
        for (i in first until first + w.bodyCount(second)) w.setVelocity(i, 1e3, 1e3)
        s.integrateRange(0, s.n, DT / SUBSTEPS)
        var visits = 0
        s.wallHits.drain { b -> assertEquals(hitBone, b); visits++ }
        assertEquals(1, visits)
        assertEquals(0, s.wallHits.size)
    }

    @Test fun boxTooNarrowStillContainsTheParticleDisks() {
        val w = world()
        w.setBounds(2.0, 0.0, 3.0, 6.0)
        w.state.clampToBounds()
        for (i in 0..2) {
            val r = w.state.radius[i]
            assertTrue(w.x[i] in (2.0 + r)..(3.0 - r))
            assertEquals(w.state.body.y[i].toDouble(), w.y[i], 0.0)
        }
    }

    @Test fun finalContactTranslatesOnceAndPreservesTangentialVelocity() {
        val w = world()
        for (i in 0..2) {
            w.place(i, w.x[i] - 3.0, w.y[i])
            w.setVelocity(i, -5.0, 2.0 + i)
        }
        w.state.clampToBounds()
        assertEquals(0.25, w.x[0], 1e-12)
        assertEquals(1.25, w.x[1], 1e-12)
        assertEquals(1.25, w.x[2], 1e-12)
        for (i in 0..2) {
            assertEquals(0.0, w.vx[i], 1e-12)
            assertEquals(2.0 + i, w.vy[i], 0.0)
        }
    }

    @Test fun inactiveMemberIsClampedSeparately() {
        val w = world()
        w.state.boneOf[2] = -1
        w.place(2, 100.0, 3.0)
        w.place(0, -0.75, 2.0)
        w.state.clampToBounds()
        assertEquals(0.25, w.x[0], 0.0)
        assertEquals(4.0, w.x[1], 0.0)
        assertEquals(5.5, w.x[2], 0.0)
    }
}
