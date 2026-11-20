package io.github.some_example_name.lwjgl3.mcp

import org.junit.Assert.*
import org.junit.Test

class ForwardDistanceTest {
    @Test
    fun sidewaysStepsCancelInPositionButCountAgainstForwardEfficiency() {
        val t = ForwardDistance(1.0, 0.0, 4.0, 4, 2, 0.0, .85)
        assertEquals(0.0, t.forwardEfficiency, 0.0)
        repeat(2) {
            t.record(1.0, 3.0)
            t.record(1.0, -3.0)
        }
        assertEquals(4.0, t.net, 0.0)
        assertEquals(0.0, t.lateral, 0.0)
        assertEquals(1.0 / kotlin.math.sqrt(10.0), t.forwardEfficiency, 1e-12)
        assertFalse(t.sustained)
    }

    @Test
    fun reachingTheGoalThenReturningIsNotForwardWalking() {
        val t = ForwardDistance(1.0, 0.0, 4.0, 8, 2, .1)
        repeat(4) { t.record(1.0, 0.0) }
        repeat(4) { t.record(-1.0, 0.0) }
        assertEquals(4, t.reachedAt)
        assertEquals(0.0, t.net, 0.0)
        assertEquals(8.0, t.path, 0.0)
        assertEquals(4.0, t.maxRetreat, 0.0)
        assertFalse(t.sustained)
    }

    @Test
    fun reversalInsidePositiveWindowsStillCounts() {
        val t = ForwardDistance(0.0, 1.0, 2.0, 4, 2, .1)
        repeat(2) {
            t.record(0.0, 2.0)
            t.record(0.0, -1.0)
        }
        assertEquals(listOf(1.0, 1.0), t.segments)
        assertFalse(t.sustained)
    }

    @Test
    fun fixedHeadingSeparatesLateralTravelFromForwardTravel() {
        val t = ForwardDistance(0.0, 1.0, 5.0, 5, 2, 0.0)
        repeat(5) { t.record(3.0, 1.0) }
        assertEquals(listOf(2.0, 2.0, 1.0), t.segments)
        assertEquals(15.0, t.maxLateral, 0.0)
        assertTrue(t.sustained)
    }
}
