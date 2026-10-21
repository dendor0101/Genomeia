package io.github.some_example_name.physics

import org.junit.Assert.*
import org.junit.Test

class LocalComponentsTest {
    private fun star(): BodyState {
        val body = BodyDefinition(floatArrayOf(0f, -1f, 1f, 0f), floatArrayOf(0f, 0f, 0f, 1f),
            FloatArray(4) { 0.5f }, BooleanArray(4), BooleanArray(4),
            intArrayOf(0, 0, 0), intArrayOf(1, 2, 3), intArrayOf(), intArrayOf(), intArrayOf(),
            floatArrayOf(), emptyArray(), emptyArray(), 1f)
        return BodyState().also { it.quiet = true; it.initialize(body); it.resetState() }
    }

    @Test fun severalCutsBeforeSurfaceRefreshSeparateEveryFragment() {
        val s = star()
        s.flowVX[0] = 0.25; s.flowVY[0] = -0.5
        for (v in 1..3) s.killEdgeNow(s.baked.edgeOf(0, v))
        s.refreshSurface()
        assertEquals(4, s.organismOf.toSet().size)
        for (v in 0..3) {
            val o = s.organismOf[v]
            assertEquals(1, s.organismSize[o])
            assertEquals(1.0 / s.restInvMass[v], s.organismMass[o], 0.0)
            assertEquals(0.25, s.flowVX[o], 0.0)
            assertEquals(-0.5, s.flowVY[o], 0.0)
        }
        val before = s.organismOf.copyOf()
        s.rebuildAfterTear()
        assertArrayEquals(before, s.organismOf)
        s.resetState()
        assertEquals(1, s.organismOf.toSet().size)
    }

    @Test fun draggingDetachedParticleCannotChargeAnotherFragmentsMedium() {
        val s = star()
        for (v in 1..3) s.killEdgeNow(s.baked.edgeOf(0, v))
        s.refreshSurface()
        s.vx[1] = 1.0
        s.applyNormalDrag(DT / SUBSTEPS)
        assertTrue(s.flowVX[s.organismOf[1]] > 0.0)
        for (v in intArrayOf(0, 2, 3)) {
            assertEquals(0.0, s.vx[v], 0.0)
            assertEquals(0.0, s.vy[v], 0.0)
            assertEquals(0.0, s.flowVX[s.organismOf[v]], 0.0)
        }
    }

    @Test fun deathOfJunctionSeparatesAllLiveBranchesAndKeepsMass() {
        val s = star()
        s.cellDead[0] = true
        s.killIncidentEdges(0)
        s.refreshSurface()
        assertEquals(4, s.organismOf.toSet().size)
        assertEquals(0, s.organismSize[s.organismOf[0]])
        assertEquals(0.0, s.organismMass[s.organismOf[0]], 0.0)
        for (v in 1..3) {
            assertEquals(1, s.organismSize[s.organismOf[v]])
            assertEquals(1.0 / s.restInvMass[v], s.organismMass[s.organismOf[v]], 0.0)
        }
    }
}
