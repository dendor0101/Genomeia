package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoneWorldBoundsTest {
    private fun world() = PhysicsWorld(scale = WorldScale(1f, 0.0625f)).apply {
        state.quiet = true
        addBody(BodyDefinition(
            floatArrayOf(2f, 3f, 3f), floatArrayOf(2f, 2f, 3f), floatArrayOf(0.25f, 0.25f, 0.5f),
            BooleanArray(3) { true }, BooleanArray(3), intArrayOf(0, 1, 0), intArrayOf(1, 2, 2),
            intArrayOf(0), intArrayOf(1), intArrayOf(2), floatArrayOf(1f),
            arrayOf(intArrayOf(0, 1, 2)), emptyArray(), 1f,
        ))
        setBounds(0.0f, 0.0f, 6.0f, 6.0f)
    }

    @Test fun hitWallsAndCornersAsOneBone() {
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            val w = world()
            // Вершина с радиусом у самой стенки: уже предсказание первого подшага выходит наружу.
            fun offset(direction: Int) = when (direction) { -1 -> -1.75f; 1 -> 2.5f; else -> 0.0f }
            for (i in 0..2) {
                w.place(i, w.x[i] + offset(dx), w.y[i] + offset(dy))
                w.setVelocity(i, dx * 24.0f, dy * 24.0f)
            }
            repeat(16) {
                w.step()
                for (i in 0..2) {
                    val r = w.state.radius[i]
                    assertTrue(w.x[i] in r..(6.0f - r) && w.y[i] in r..(6.0f - r))
                }
                // Кость твёрдая с точностью float: шаг float под единицей — 6·10⁻⁸, и за тик из 16 подшагов длина
                // стороны уходит до двух таких шагов (0.9999999; при шести подшагах — до одного). Допуск — четыре шага.
                assertEquals(1.0f, kotlin.math.hypot(w.x[1]-w.x[0], w.y[1]-w.y[0]), 2.4e-7f)
                assertEquals(1.0f, kotlin.math.hypot(w.x[2]-w.x[1], w.y[2]-w.y[1]), 2.4e-7f)
            }
        }
    }

    @Test fun integrationQueuesOnlyHitsAndDeduplicatesTheBone() {
        val w = world()
        val second = w.addBody(w.state.body)
        val s = w.state
        val h = DT / SUBSTEPS
        s.integrateRange(0, s.n, h)
        assertEquals(0, s.wallHits.size)
        val first = w.bodyFirst(second)
        val hitBone = s.boneOf[first]
        assertTrue(hitBone >= 0 && hitBone != s.boneOf[0])
        // Скорость — ширина рамки за подшаг: кость выходит за стенку при любом числе подшагов (прежние 1e3 единицы в
        // секунду — это 5.6 единицы за подшаг из шести, но всего 2.1 за подшаг из шестнадцати: до стенки не доставало).
        for (i in first until first + w.bodyCount(second)) w.setVelocity(i, 6.0f / h, 6.0f / h)
        s.integrateRange(0, s.n, h)
        var visits = 0
        s.wallHits.drain { b -> assertEquals(hitBone, b); visits++ }
        assertEquals(1, visits)
        assertEquals(0, s.wallHits.size)
    }

    @Test fun boxTooNarrowStillContainsTheParticleDisks() {
        val w = world()
        w.setBounds(2.0f, 0.0f, 3.0f, 6.0f)
        w.state.clampToBounds()
        for (i in 0..2) {
            val r = w.state.radius[i]
            assertTrue(w.x[i] in (2.0f + r)..(3.0f - r))
            assertEquals(w.state.body.y[i].toFloat(), w.y[i], 0.0f)
        }
    }

    @Test fun finalContactTranslatesOnceAndPreservesTangentialVelocity() {
        val w = world()
        for (i in 0..2) {
            w.place(i, w.x[i] - 3.0f, w.y[i])
            w.setVelocity(i, -5.0f, 2.0f + i)
        }
        w.state.clampToBounds()
        assertEquals(0.25f, w.x[0], 1e-12f)
        assertEquals(1.25f, w.x[1], 1e-12f)
        assertEquals(1.25f, w.x[2], 1e-12f)
        for (i in 0..2) {
            assertEquals(0.0f, w.vx[i], 1e-12f)
            assertEquals(2.0f + i, w.vy[i], 0.0f)
        }
    }

    @Test fun inactiveMemberIsClampedSeparately() {
        val w = world()
        w.state.boneOf[2] = -1
        w.place(2, 100.0f, 3.0f)
        w.place(0, -0.75f, 2.0f)
        w.state.clampToBounds()
        assertEquals(0.25f, w.x[0], 0.0f)
        assertEquals(4.0f, w.x[1], 0.0f)
        assertEquals(5.5f, w.x[2], 0.0f)
    }
}
