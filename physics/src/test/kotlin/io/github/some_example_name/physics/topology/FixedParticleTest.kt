package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Неподвижная частица (PhysicsWorld.addFixed): не сдвигается, не гибнет, касание её не будит. */
class FixedParticleTest {
    /** Масштаб мира игры: связь 0.6 и радиус 0.5 клетки игры при 0.048 единицы физики на клетку. */
    private val r = 0.024f
    private fun world() = PhysicsWorld(PhysicsConfig(), workers = null,
        scale = WorldScale(meanLinkLength = 0.0288f, meanCellArea = r * r)).apply { state.quiet = true }

    @Test fun stopsMoverAndStaysInPlace() {
        val w = world()
        val wall = w.freeParticle(w.addFixed(1.0f, 1.0f, r))
        val ball = w.freeParticle(w.addFree(1.0f - 4 * r, 1.0f, r, vx = 1.0f))
        repeat(120) { w.step() }
        assertEquals(1.0f, w.x[wall], 0.0f); assertEquals(1.0f, w.y[wall], 0.0f)
        assertFalse(w.state.cellDead[wall])
        assertFalse(w.state.cellDead[ball])
        assertTrue("частица прошла сквозь неподвижную: x = ${w.x[ball]}", w.x[ball] < 1.0f - r)
    }

    @Test fun deepOverlapKillsOnlyPartner() {
        val w = world()
        w.deepKillOn = true
        val wall = w.freeParticle(w.addFixed(1.0f, 1.0f, r))
        val ball = w.freeParticle(w.addFree(1.0f + 0.1f * r, 1.0f, r))
        w.step()
        assertTrue("партнёр должен погибнуть", w.state.cellDead[ball])
        assertFalse(w.state.cellDead[wall])
        w.kill(wall)
        assertFalse("неподвижная бессмертна и для клиента", w.state.cellDead[wall])
        assertEquals(1.0f, w.x[wall], 0.0f)
    }

    @Test fun touchDoesNotWakeIt() {
        val w = world()
        w.sleepOn = true
        val wall = w.freeParticle(w.addFixed(1.0f, 1.0f, r))
        repeat(SLEEP_TICKS + 5) { w.step() }
        assertTrue(w.isAsleep(wall))
        w.addFree(1.0f - 4 * r, 1.0f, r, vx = 1.0f)
        repeat(60) { w.step(); assertTrue("касание разбудило неподвижную", w.isAsleep(wall)) }
        assertEquals(1.0f, w.x[wall], 0.0f)
    }
}
