package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** Временная связь между телами (PhysicsWorld.addJoint) и клетка без касаний (setContactless). */
class JointTest {
    /** Масштаб мира игры: связь 0.6 и радиус 0.5 клетки игры при 0.048 единицы физики на клетку. */
    private val r = 0.024f
    private fun world() = PhysicsWorld(PhysicsConfig(), workers = null,
        scale = WorldScale(meanLinkLength = 0.0288f, meanCellArea = r * r)).apply { state.quiet = true }

    /** Брошенная частица тянет за собой привязанную; импульс пары за тик тот же, что без связи. */
    @Test fun pullsPartnerAndKeepsMomentum() {
        val w = world()
        val a = w.freeParticle(w.addFree(1.0f, 1.0f, r))
        val b = w.freeParticle(w.addFree(1.0f + 2 * r, 1.0f, r))
        val joint = w.addJoint(a, b, 2 * r, SOFT_COMPLIANCE)
        w.setVelocity(a, -0.5f, 0.0f)
        val p0 = w.vx[a] + w.vx[b]
        w.step()
        val p1 = w.vx[a] + w.vx[b]
        assertTrue("партнёр не поехал: vx = ${w.vx[b]}", w.vx[b] < -0.05f)
        // Среда гасит обе частицы одинаково; связь импульс пары не меняет.
        val keep = 1.0f - MEDIUM_DRAG * DT
        assertEquals(p0 * keep, p1, 0.02f * kotlin.math.abs(p0))
        assertTrue(w.isJointLive(joint))
        assertEquals(2 * r, hypot(w.x[a] - w.x[b], w.y[a] - w.y[b]), 0.2f * r)
    }

    /** Удалённая частица уносит свою связь: следующая в том же слоте ни к чему не привязана. */
    @Test fun removedParticleDropsJoint() {
        val w = world()
        val ida = w.addFree(1.0f, 1.0f, r)
        val b = w.freeParticle(w.addFree(1.0f + 2 * r, 1.0f, r))
        val joint = w.addJoint(w.freeParticle(ida), b, 2 * r, SOFT_COMPLIANCE)
        w.removeFree(ida)
        assertFalse(w.isJointLive(joint))
        w.addFree(2.0f, 2.0f, r)
        repeat(5) { w.step() }
        assertEquals("частица в слоте удалённой утянула партнёра", 1.0f + 2 * r, w.x[b], 1e-3f)
    }

    /** Частица без касаний пропускает сквозь себя, обычная — нет. */
    @Test fun contactlessLetsThrough() {
        for (ghost in booleanArrayOf(false, true)) {
            val w = world()
            val wall = w.freeParticle(w.addFixed(1.0f, 1.0f, r))
            w.setContactless(wall, ghost)
            val ball = w.freeParticle(w.addFree(1.0f - 4 * r, 1.0f, r, vx = 1.0f))
            repeat(60) { w.step() }
            val passed = w.x[ball] > 1.0f + r
            assertEquals("без касаний = $ghost, x = ${w.x[ball]}", ghost, passed)
        }
    }

    /** Растянутая сверх предела длины и запаса до разрыва связь рвётся, как связь тела. */
    @Test fun tearsWhenStretched() {
        val w = world()
        val a = w.freeParticle(w.addFree(1.0f, 1.0f, r))
        val b = w.freeParticle(w.addFree(1.0f + 2 * r, 1.0f, r))
        val joint = w.addJoint(a, b, 2 * r, 2 * SOFT_COMPLIANCE)
        // Концы растаскивают на потолке скорости (8 связей за тик каждый): связь рвётся, когда её тянут быстрее, чем
        // она успевает возвращать, а это зависит от подшага и жёсткости. Прежние 3 единицы в секунду (3.5 связи за
        // тик) рвали мягкую связь при шести подшагах, а жёсткую при шестнадцати (24.11) она уже держит: растяжение
        // за подшаг — четверть длины покоя, до разрыва (полторы длины) не доходит.
        val pull = MAX_SPEED_CELLS_PER_TICK * 0.0288f / DT
        var torn = -1
        for (t in 0 until 120) {
            w.setVelocity(a, -pull, 0.0f); w.setVelocity(b, pull, 0.0f)
            w.step()
            if (!w.isJointLive(joint)) { torn = t; break }
        }
        assertTrue("связь не порвалась", torn >= 0)
    }

    /** Хватка: при 1 частицу не сдвигает ничто, при 0.95 — в разы меньше, чем без хватки; растёт монотонно. */
    @Test fun gripGrowsToAbsolute() {
        fun moved(grip: Float): Float {
            val w = world()
            val a = w.freeParticle(w.addFree(1.0f, 1.0f, r))
            w.setGrip(a, grip)
            w.addFree(1.0f - 4 * r, 1.0f, r, vx = 1.0f)
            repeat(30) { w.setVelocity(a, 0.2f, 0.0f); w.step() }
            return hypot(w.x[a] - 1.0f, w.y[a] - 1.0f)
        }
        val m = floatArrayOf(0f, 0.5f, 0.9f, 0.95f, 1f).map { moved(it) }
        assertEquals("хватка 1 сдвинулась", 0.0f, m[4], 1e-12f)
        for (k in 1 until m.size) assertTrue("трение не растёт: $m", m[k] < m[k - 1])
        assertTrue("0.95f сдвигается слишком легко: $m", m[3] < 0.1f * m[0])
    }
}
